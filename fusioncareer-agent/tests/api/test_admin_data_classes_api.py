"""管理员数据类 HTTP API"""

from pathlib import Path

import pytest

pytest.importorskip("fastapi")
from fastapi.testclient import TestClient  # noqa: E402

from app.config import settings
from app.main import app
from app.runtime.paths import RuntimePaths
from app.catalog.catalog import DataClassCatalog
from app.catalog.ref_index import DataClassRefIndex


@pytest.fixture
def client(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    runtime = tmp_path / "runtime"
    monkeypatch.setattr(settings, "agent_runtime_dir", str(runtime))
    monkeypatch.setattr(settings, "agent_admin_token", "test-token")
    with TestClient(app) as c:
        yield c


def _headers():
    return {"X-Agent-Admin-Token": "test-token"}


def test_list_requires_token(client: TestClient):
    r = client.get("/api/admin/data-classes")
    assert r.status_code == 403


def test_list_and_get_data_class(client: TestClient):
    r = client.get("/api/admin/data-classes", headers=_headers())
    assert r.status_code == 200
    names = {item["name"] for item in r.json()["data_classes"]}
    assert "int" in names
    assert "api_result" in names

    r2 = client.get("/api/admin/data-classes/int", headers=_headers())
    assert r2.status_code == 200
    assert r2.json()["data_class"]["role"] == "IO"


def test_put_idempotent(client: TestClient):
    existing = client.get("/api/admin/data-classes/int", headers=_headers()).json()["data_class"]
    r = client.put(
        "/api/admin/data-classes/int",
        headers=_headers(),
        json={
            "role": existing["role"],
            "schema": existing["schema"],
        },
    )
    assert r.status_code == 200
    assert r.json()["status"] == "idempotent"
