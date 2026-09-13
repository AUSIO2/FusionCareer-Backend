import asyncio

import pytest

from app.integrations.backend import BackendApiError, BackendClient
from app.config import settings


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
