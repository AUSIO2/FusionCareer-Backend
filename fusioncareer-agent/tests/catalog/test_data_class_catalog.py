"""数据类目录与引用锁定"""

from pathlib import Path

import pytest

from app.catalog import DataClassCatalog, CatalogError, DataClassRecord, DataClassRole, DataClassRefIndex
from app.catalog.models import DataClassUpsertBody
from app.core.registry import SkillRegistry
from app.runtime.paths import RuntimePaths


@pytest.fixture
def runtime(tmp_path: Path):
    paths = RuntimePaths(tmp_path / "runtime")
    paths.ensure_dirs()
    catalog = DataClassCatalog(paths)
    catalog.seed_if_empty()
    if not catalog.list_all():
        catalog.load_from_disk()
    ref_index = DataClassRefIndex()
    return paths, catalog, ref_index


def _body(role: DataClassRole = DataClassRole.IO, schema: dict | None = None) -> DataClassUpsertBody:
    return DataClassUpsertBody(role=role, schema=schema or {"type": "string"})


def test_seed_loads_io_and_o(runtime):
    _paths, catalog, _ref = runtime
    assert catalog.has("int")
    assert catalog.get("int").role == DataClassRole.IO
    assert catalog.get("api_result").role == DataClassRole.O
    assert catalog.get("int").type_schema["type"] == "integer"


def test_upsert_idempotent(runtime):
    _paths, catalog, ref_index = runtime
    existing = catalog.get("int")
    body = DataClassUpsertBody(role=existing.role, schema=existing.type_schema)
    record, status = catalog.upsert("int", body, ref_index)
    assert status == "idempotent"
    assert record.name == "int"


def test_upsert_unlocked_overwrite(runtime):
    _paths, catalog, ref_index = runtime
    body = _body(schema={"type": "integer", "minimum": 0})
    _record, status = catalog.upsert("custom_int", body, ref_index)
    assert status == "created"
    body2 = _body(schema={"type": "integer", "maximum": 100})
    _record, status2 = catalog.upsert("custom_int", body2, ref_index)
    assert status2 == "updated"
    assert catalog.get("custom_int").type_schema.get("maximum") == 100


def test_locked_after_skill_reference(runtime):
    _paths, catalog, ref_index = runtime
    registry = SkillRegistry()
    registry.auto_discover("app.skills.platform")
    registry.auto_discover("app.skills.business")
    ref_index.rebuild_from_registry(registry)
    assert ref_index.is_locked("resume_data")

    with pytest.raises(CatalogError) as exc:
        catalog.upsert("resume_data", _body(), ref_index)
    assert exc.value.code == "data_class_locked"


def test_delete_locked_fails(runtime):
    _paths, catalog, ref_index = runtime
    ref_index.register_skill("fake", {"resume_data"})
    with pytest.raises(CatalogError) as exc:
        catalog.delete("resume_data", ref_index)
    assert exc.value.code == "data_class_locked"


def test_role_o_not_for_input(runtime):
    _paths, catalog, _ref = runtime
    with pytest.raises(CatalogError) as exc:
        catalog.assert_usable_as_input("api_result")
    assert exc.value.code == "role_not_input"


def test_forbid_any_name(runtime):
    _paths, catalog, ref_index = runtime
    with pytest.raises(CatalogError):
        catalog.upsert("any", _body(), ref_index)


def test_reject_old_input_output_format(tmp_path: Path):
    paths = RuntimePaths(tmp_path / "runtime")
    paths.ensure_dirs()
    paths.data_class_file("old").write_text(
        '{"name":"old","role":"IO","input":{"type":"string"},"output":{"type":"string"}}',
        encoding="utf-8",
    )
    catalog = DataClassCatalog(paths)
    with pytest.raises(ValueError, match="schema"):
        catalog.load_from_disk()


def test_refs_persisted(runtime):
    paths, catalog, ref_index = runtime
    ref_index.register_skill("x", {"int"})
    ref_index.save_to_disk(paths)
    ref_index2 = DataClassRefIndex()
    ref_index2.load_from_disk(paths)
    assert ref_index2.is_locked("int")
