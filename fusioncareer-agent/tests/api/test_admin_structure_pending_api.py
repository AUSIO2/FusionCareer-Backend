"""管理员手动清理待结构化文档 API。"""

import asyncio
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.api.routers import admin as admin_router
from app.config import settings
from app.main import app


@pytest.fixture
def client(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(settings, "agent_runtime_dir", str(tmp_path / "runtime"))
    monkeypatch.setattr(settings, "wechat_config_root", str(tmp_path / "wechat"))
    monkeypatch.setattr(settings, "agent_admin_token", "test-token")
    with TestClient(app) as readClient:
        yield readClient


def readHeaders() -> dict[str, str]:
    return {"X-Agent-Admin-Token": "test-token"}


def testManualDrainRequiresAdminToken(client: TestClient):
    assert client.post("/api/admin/wechat/structure-pending").status_code == 403


def testManualDrainRunsOnceAndReportsStatus(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
):
    readCalls = []

    async def readDrain(readPaths, readBackend):
        readCalls.append(readPaths.config_root)
        await asyncio.sleep(0.1)
        return {"articleCount": 3, "jobCount": 2, "failedCount": 0, "skippedCount": 1}

    monkeypatch.setattr(admin_router, "drainPendingArticles", readDrain)

    first = client.post("/api/admin/wechat/structure-pending", headers=readHeaders())
    second = client.post("/api/admin/wechat/structure-pending", headers=readHeaders())
    assert first.status_code == 202
    assert second.status_code == 202
    assert second.json()["status"] == "RUNNING"

    time.sleep(0.2)
    status = client.get("/api/admin/wechat/structure-pending", headers=readHeaders())
    assert status.status_code == 200
    assert status.json()["status"] == "COMPLETED"
    assert status.json()["result"]["articleCount"] == 3
    assert len(readCalls) == 1
