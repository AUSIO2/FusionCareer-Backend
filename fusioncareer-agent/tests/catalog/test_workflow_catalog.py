"""WorkflowCatalog"""

import json
from pathlib import Path

import pytest

from app.catalog.catalog import DataClassCatalog
from app.catalog.workflow_catalog import WorkflowCatalog, WorkflowCatalogError
from app.core.registry import SkillRegistry
from app.engine.validator import WorkflowValidator
from app.runtime.paths import RuntimePaths


@pytest.fixture
def catalog_bundle(tmp_path: Path):
    paths = RuntimePaths(tmp_path / "runtime")
    paths.ensure_dirs()
    dc = DataClassCatalog(paths)
    dc.seed_if_empty()
    if not dc.list_all():
        dc.load_from_disk()
    registry = SkillRegistry()
    registry.auto_discover("app.skills.platform")
    registry.auto_discover("app.skills.business")
    validator = WorkflowValidator(registry, dc)
    wf_catalog = WorkflowCatalog(paths)
    wf_catalog.load_all()
    return wf_catalog, validator, paths


def test_list_builtin_preset(catalog_bundle):
    wf_catalog, _, _ = catalog_bundle
    names = {e["name"] for e in wf_catalog.list_entries()}
    assert "write_resume_profile" in names


def test_put_rejects_literals_when_strict(catalog_bundle):
    wf_catalog, validator, _ = catalog_bundle
    body = {
        "nodes": {
            "n1": {
                "skill": "insert_resume",
                "inputs": {
                    "user_id": {"value": 1},
                    "resume_data": {"value": {"personalIntro": "x"}},
                },
            }
        }
    }
    with pytest.raises(WorkflowCatalogError) as exc:
        wf_catalog.put("bad", body, validator)
    assert exc.value.code == "workflow_validation_failed"
    assert exc.value.errors


def test_put_migrated_preset(catalog_bundle):
    wf_catalog, validator, _ = catalog_bundle
    preset = Path(__file__).resolve().parents[2] / "app" / "presets" / "workflows" / "write_resume_profile.json"
    body = json.loads(preset.read_text(encoding="utf-8"))
    status = wf_catalog.put("write_resume_profile_test", body, validator)
    assert status == "created"


def test_delete_builtin_forbidden(catalog_bundle):
    wf_catalog, _, _ = catalog_bundle
    with pytest.raises(WorkflowCatalogError) as exc:
        wf_catalog.delete("write_resume_profile")
    assert exc.value.code == "workflow_builtin"


def test_runtime_overrides_builtin(catalog_bundle, tmp_path: Path):
    wf_catalog, validator, paths = catalog_bundle
    custom = {"name": "custom", "nodes": {}}
    # empty nodes will fail validation - use valid minimal with literals in put - actually put rejects literals
    # use a valid from-only workflow - need two nodes or single with only from impossible without source
    # skip override test with valid workflow file written directly
    path = paths.workflows / "write_resume_profile.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(
            {
                "name": "覆盖",
                "nodes": {
                    "n1": {
                        "skill": "insert_resume",
                        "inputs": {
                            "user_id": {"value": 1},
                            "resume_data": {"value": {"personalIntro": "覆盖"}},
                        },
                    }
                },
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    wf_catalog.load_all()
    assert wf_catalog.source("write_resume_profile") == "runtime"
    assert wf_catalog.get("write_resume_profile")["name"] == "覆盖"
