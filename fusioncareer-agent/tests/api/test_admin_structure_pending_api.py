"""管理员手动清理待结构化文档 API。"""

import asyncio
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.api.routers import admin as admin_router
from app.config import settings
from app.main import app
from app.skills.business.crawlers.store import CrawlStore


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


def testRequeueRequiresExplicitScope(client: TestClient, tmp_path: Path):
    store = CrawlStore(admin_router._structure_paths().database_file)
    store.saveAccount("source", "Source", True)
    markdown = tmp_path / "job.md"
    markdown.write_text("# 招聘", encoding="utf-8")
    url = "https://example.test/quarantined"
    store.saveArticle("source", {"title": "招聘", "link": url, "create_time": 1}, markdown, "hash")
    with store.openDatabase() as database:
        database.execute(
            "UPDATE articles SET structure_status='QUARANTINED', structure_attempts=3 WHERE url=?",
            (url,),
        )

    empty = client.post(
        "/api/admin/wechat/structure-requeue", headers=readHeaders(), json={}
    )
    response = client.post(
        "/api/admin/wechat/structure-requeue", headers=readHeaders(), json={"urls": [url]}
    )

    assert empty.status_code == 422
    assert response.status_code == 200
    assert response.json() == {"requeued": 1}
    assert store.readStructureCounts()["PENDING"] == 1
