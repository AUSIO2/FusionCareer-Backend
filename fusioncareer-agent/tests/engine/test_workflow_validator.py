"""WorkflowValidator"""

import json
from pathlib import Path

import pytest

from app.catalog.catalog import DataClassCatalog
from app.core.registry import SkillRegistry
from app.engine.validator import WorkflowValidator
from app.runtime.paths import RuntimePaths


@pytest.fixture
def validator(tmp_path: Path):
    paths = RuntimePaths(tmp_path / "runtime")
    paths.ensure_dirs()
    catalog = DataClassCatalog(paths)
    catalog.seed_if_empty()
    if not catalog.list_all():
        catalog.load_from_disk()
    registry = SkillRegistry()
    registry.auto_discover("app.skills.platform")
    registry.auto_discover("app.skills.business")
    return WorkflowValidator(registry, catalog)


def test_reject_value_without_allow_literals(validator: WorkflowValidator):
    wf = {
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
    errors = validator.validate(wf, allow_source_literals_only=True)
    assert any("业务节点不允许 value" in e for e in errors)


def test_allow_literals(validator: WorkflowValidator):
    wf = {
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
    assert validator.validate(wf, allow_literals=True) == []


def test_from_type_must_match(validator: WorkflowValidator):
    wf = {
        "nodes": {
            "n1": {
                "skill": "insert_resume",
                "inputs": {
                    "user_id": {"value": 1},
                    "resume_data": {"value": {"personalIntro": "x"}},
                },
            },
            "n2": {
                "skill": "insert_user_profile",
                "inputs": {
                    "user_id": {"value": 1},
                    "profile_data": {"from": "n1.result"},
                },
            },
        }
    }
    errors = validator.validate(wf, allow_literals=True)
    assert any("类型不相等" in e for e in errors)


def test_from_wrong_output_slot(validator: WorkflowValidator):
    wf = {
        "nodes": {
            "n1": {
                "skill": "insert_resume",
                "inputs": {
                    "user_id": {"value": 1},
                    "resume_data": {"value": {"personalIntro": "x"}},
                },
            },
            "n2": {
                "skill": "insert_user_profile",
                "inputs": {
                    "user_id": {"value": 1},
                    "profile_data": {"from": "n1.typo"},
                },
            },
        }
    }
    errors = validator.validate(wf, allow_literals=True)
    assert any("应指向" in e for e in errors)


def test_write_resume_profile_preset_with_source_only(validator: WorkflowValidator):
    preset = Path(__file__).resolve().parents[2] / "app" / "presets" / "workflows" / "write_resume_profile.json"
    wf = json.loads(preset.read_text(encoding="utf-8"))
    assert validator.validate(wf, allow_source_literals_only=True) == []


def test_reject_business_value_under_source_only_mode(validator: WorkflowValidator):
    wf = {
        "nodes": {
            "input_uid": {
                "skill": "input_int",
                "inputs": {"int": {"value": 1}},
            },
            "n1": {
                "skill": "insert_resume",
                "inputs": {
                    "user_id": {"value": 99},
                    "resume_data": {"from": "input_rd.data"},
                },
            },
            "input_rd": {
                "skill": "input_resume_data",
                "inputs": {"data": {"value": {"personalIntro": "x"}}},
            },
        }
    }
    errors = validator.validate(wf, allow_source_literals_only=True)
    assert any("n1.user_id" in e and "业务节点" in e for e in errors)
