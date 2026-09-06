from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app


@pytest.fixture
def client(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(settings, "agent_runtime_dir", str(tmp_path / "runtime"))
    monkeypatch.setattr(settings, "internal_service_token", "internal-test")

    async def readJobs(readText, readSourceUrl="", readSourceType="PLATFORM", readClient=None):
        return {
            "jobs": [{
                "sourceType": readSourceType,
                "sourceUrl": readSourceUrl,
                "companyName": "示例公司",
                "positionName": "编辑",
                "jobCategory": "ENTERPRISE",
                "jobSubCategory": "PRIVATE_ENTERPRISE",
                "recruitType": "OTHER",
                "status": "OFFLINE",
            }],
            "warnings": [],
        }

    monkeypatch.setattr("app.api.routers.internal.structureJobs", readJobs)
    with TestClient(app) as readClient:
        yield readClient


def readHeaders() -> dict[str, str]:
    return {"X-Internal-Token": "internal-test"}


def testProtectJob(client: TestClient):
    readResponse = client.post("/api/internal/job/structure", json={"text": "招聘编辑"})
    assert readResponse.status_code == 403


def testStructureJob(client: TestClient):
    readResponse = client.post(
        "/api/internal/job/structure",
        headers=readHeaders(),
        json={"text": "招聘编辑", "sourceUrl": "https://example.test/job"},
    )
    assert readResponse.status_code == 200
    assert readResponse.json()["jobs"][0]["status"] == "OFFLINE"
    assert readResponse.json()["jobs"][0]["sourceUrl"] == "https://example.test/job"


def testRejectJob(client: TestClient):
    readResponse = client.post(
        "/api/internal/job/structure", headers=readHeaders(), json={"text": ""}
    )
    assert readResponse.status_code == 422
