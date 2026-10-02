import asyncio

from fastapi.testclient import TestClient

from app.api.routers import admin
from app.config import settings
from app.main import app


def test_qr_and_exports_are_available_but_sessions_and_other_workspaces_are_not(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "agent_runtime_dir", str(tmp_path))
    monkeypatch.setattr(settings, "agent_admin_token", "admin-test")
    workspace = tmp_path / "algorithm/login"
    workspace.mkdir(parents=True)
    (workspace / "weread-login.png").write_bytes(b"test-qr")
    (workspace / "weread_session.json").write_text('{"accessToken":"private"}')
    with TestClient(app) as client:
        headers = {"X-Agent-Admin-Token": "admin-test"}
        assert client.get("/api/admin/algorithm/login/artifact", params={"path": "weread-login.png"}).status_code == 403
        response = client.get("/api/admin/algorithm/login/artifact", headers=headers, params={"path": "weread-login.png"})
        assert response.content == b"test-qr"
        for path in ["weread_session.json", "../login/weread_session.json", "logs/llm_io/latest.json"]:
            assert client.get("/api/admin/algorithm/login/artifact", headers=headers, params={"path": path}).status_code == 403


def test_login_is_nonblocking_and_cancelled_at_shutdown(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "agent_runtime_dir", str(tmp_path))
    monkeypatch.setattr(settings, "agent_admin_token", "admin-test")
    cancelled = []
    async def login(operation, request):
        assert operation == "weread_login" and request["workspace"] == "login"
        try:
            await asyncio.sleep(300)
        except asyncio.CancelledError:
            cancelled.append(True)
            raise
    monkeypatch.setattr(admin, "run_algorithm", login)
    with TestClient(app) as client:
        headers = {"X-Agent-Admin-Token": "admin-test"}
        assert client.post("/api/admin/algorithm/login/weread-login", headers=headers).status_code == 202
        assert client.get("/api/admin/algorithm/login/weread-login", headers=headers).json()["status"] == "RUNNING"
    assert cancelled == [True]
