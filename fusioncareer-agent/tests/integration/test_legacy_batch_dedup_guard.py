"""Legacy batch entry points must not pre-commit article dedup history."""

import asyncio
import json

import pytest
from fastapi.testclient import TestClient

from app.algorithms import upstream
from app.config import settings
from app.main import app

# Explicit re-export registers the shared pytest fixture without duplicating it.
from tests.integration.test_upstream_workflows import service as service  # noqa: PLC0414


@pytest.mark.parametrize("native_enabled", [False, True])
@pytest.mark.asyncio
async def test_legacy_batch_always_injects_disabled_vendor_dedup(tmp_path, monkeypatch, native_enabled):
    captured = []

    class Process:
        returncode = 0

        async def communicate(self, payload):
            captured.append(json.loads(payload))
            return json.dumps({"result": {"positions": []}}).encode(), b""

    async def create_process(*args, **kwargs):
        return Process()

    monkeypatch.setattr(settings, "agent_runtime_dir", str(tmp_path))
    monkeypatch.setattr(settings, "article_dedup_enabled", native_enabled)
    monkeypatch.setattr(upstream.asyncio, "create_subprocess_exec", create_process)
    await upstream.run_algorithm("job_batch", {"workspace": "legacy", "documents": ["招聘新闻编辑"]})

    assert captured[0]["config"]["article_dedup_enabled"] is False
    assert captured[0]["request"]["documents"] == ["招聘新闻编辑"]


@pytest.mark.parametrize("workflow", ["algorithm_job_batch", "algorithm_job_pipeline"])
def test_failed_legacy_batch_retries_same_articles_without_history_suppression(service, workflow):
    calls, _uploads, state, runtime = service
    workspace = f"retry-{workflow}"
    request = {"overrides": {"input.json_obj": {
        "workspace": workspace,
        "documents": ["# 新闻记者与编辑招聘\n负责新闻采访、写作及内容生产。"],
    }}}
    state["invalid"] = True
    with TestClient(app) as client:
        first = client.post(
            f"/api/workflows/{workflow}/run",
            headers={"X-Agent-Admin-Token": "test-admin"},
            json=request,
        )
        assert first.status_code == 200, first.text
        assert first.json()["status"] == "failed", first.text
        assert len(calls) == 1
        assert not (runtime / f"algorithm/{workspace}/logs/article_minhash.json").exists()

        state["invalid"] = False
        second = client.post(
            f"/api/workflows/{workflow}/run",
            headers={"X-Agent-Admin-Token": "test-admin"},
            json=request,
        )
        assert second.status_code == 200, second.text
        assert second.json()["status"] == "completed", second.text
        assert len(calls) == 2

    root = runtime / f"algorithm/{workspace}"
    assert not (root / "logs/article_minhash.json").exists()
    exported = asyncio.run(upstream.run_algorithm("job_export_json", {"workspace": workspace}))
    assert len(exported["positions"]) == 2


def test_legacy_batch_caller_cannot_reenable_unsafe_vendor_dedup(service):
    with pytest.raises(ValueError, match="Unsupported algorithm config option"):
        asyncio.run(upstream.run_algorithm("job_batch", {
            "workspace": "unsafe-option",
            "documents": ["招聘新闻记者"],
            "config": {"article_dedup_enabled": True},
        }))
