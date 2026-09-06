import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app


READ_CONTRACT = Path(__file__).parents[1] / "fixtures" / "algorithm" / "resume_contract.json"


@pytest.fixture
def client(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(settings, "agent_runtime_dir", str(tmp_path / "runtime"))
    monkeypatch.setattr(settings, "internal_service_token", "internal-test")

    async def readFile(readBackend, readUserId, readFileId):
        return {"id": str(readFileId), "originalName": "resume.pdf"}, b"%PDF-test"

    async def readResume(readPath, readClient=None):
        assert readPath.is_file()
        return json.loads(READ_CONTRACT.read_text(encoding="utf-8"))

    monkeypatch.setattr("app.api.routers.internal.readResumeFile", readFile)
    monkeypatch.setattr("app.api.routers.internal.parseResume", readResume)
    with TestClient(app) as readClient:
        yield readClient


def readHeaders() -> dict[str, str]:
    return {"X-Internal-Token": "internal-test"}


def testParseResume(client: TestClient):
    readResponse = client.post(
        "/api/internal/resume/parse",
        headers=readHeaders(),
        json={"userId": "700000000000000001", "fileId": "700000000000000002"},
    )
    assert readResponse.status_code == 200
    assert readResponse.json()["profilePatch"]["gender"] == "FEMALE"


def testProtectResume(client: TestClient):
    readResponse = client.post(
        "/api/internal/resume/parse",
        json={"userId": "1", "fileId": "2"},
    )
    assert readResponse.status_code == 403


def testRejectResume(client: TestClient):
    readResponse = client.post(
        "/api/internal/resume/parse",
        headers=readHeaders(),
        json={"userId": "not-id", "fileId": "2"},
    )
    assert readResponse.status_code == 422
