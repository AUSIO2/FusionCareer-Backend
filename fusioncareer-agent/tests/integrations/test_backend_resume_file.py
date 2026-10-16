import asyncio

import httpx
import pytest

from app.config import settings
from app.integrations.backend import BackendApiError, BackendClient


def testRejectOwnedFile(monkeypatch: pytest.MonkeyPatch):
    readBackend = BackendClient()

    async def readFiles(readUserId):
        return [{"id": "10", "originalName": "owned.pdf"}]

    monkeypatch.setattr(readBackend, "list_resume_files", readFiles)
    with pytest.raises(BackendApiError):
        asyncio.run(readBackend.read_resume_file(1, 11))


def testSendInternalToken(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(settings, "internal_service_token", "internal-test")
    readBackend = BackendClient()

    async def readHeaders():
        readClient = await readBackend._ensure_client()
        try:
            return readClient.headers["X-Internal-Token"]
        finally:
            await readBackend.close()

    assert asyncio.run(readHeaders()) == "internal-test"


def testMergeJobPostEvidenceUsesPutAndFiltersResponseOnlyFields(monkeypatch: pytest.MonkeyPatch):
    readBackend = BackendClient()
    calls = []

    async def put(path, data):
        calls.append((path, data))
        return {"id": "1001", **data["incoming"], "updatedAt": "2026-10-16T08:00:01"}

    async def rejectPost(_path, _data):
        raise AssertionError("merge-evidence is a PUT endpoint, not POST")

    monkeypatch.setattr(readBackend, "_put", put)
    monkeypatch.setattr(readBackend, "_post", rejectPost)
    incoming = {
        "sourceUrl": "https://example.test/repost",
        "companyName": "慧策集团",
        "positionName": "培训管培生",
        "workCities": ["北京", "天津"],
        "applicationDeadline": "2026-10-30",
        "id": "response-only-id",
        "updatedAt": "response-only-version",
        "applicationCount": 9,
    }

    merged = asyncio.run(readBackend.merge_job_post_evidence(
        "1001", "2026-10-16T08:00:00", incoming,
    ))

    assert calls == [(
        "/internal/job-post/1001/merge-evidence",
        {
            "expectedUpdatedAt": "2026-10-16T08:00:00",
            "incoming": {
                "sourceUrl": "https://example.test/repost",
                "companyName": "慧策集团",
                "positionName": "培训管培生",
                "workCities": ["北京", "天津"],
                "applicationDeadline": "2026-10-30",
            },
        },
    )]
    assert merged["id"] == "1001"
    assert merged["updatedAt"] == "2026-10-16T08:00:01"


def testMergeJobPostEvidencePreservesStructuredConflictResponse():
    async def respond(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            409,
            json={"code": 409, "message": "工作城市存在冲突", "data": None},
        )

    async def request():
        readBackend = BackendClient()
        readBackend._client = httpx.AsyncClient(
            base_url="http://backend.test",
            transport=httpx.MockTransport(respond),
        )
        try:
            await readBackend.merge_job_post_evidence(
                "1001", "2026-10-16T08:00:00", {"workCity": "上海"},
            )
        finally:
            await readBackend.close()

    with pytest.raises(BackendApiError) as error:
        asyncio.run(request())
    assert error.value.code == 409
    assert error.value.http_status == 409
    assert error.value.message == "工作城市存在冲突"
