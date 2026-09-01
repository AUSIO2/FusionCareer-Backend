import asyncio

import pytest

from app.integrations.backend import BackendApiError, BackendClient


def testRejectOwnedFile(monkeypatch: pytest.MonkeyPatch):
    readBackend = BackendClient()

    async def readFiles(readUserId):
        return [{"id": "10", "originalName": "owned.pdf"}]

    monkeypatch.setattr(readBackend, "list_resume_files", readFiles)
    with pytest.raises(BackendApiError):
        asyncio.run(readBackend.read_resume_file(1, 11))
