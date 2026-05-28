"""字符串拼接工作流端到端测试"""

from pathlib import Path

import pytest

from app.catalog.catalog import DataClassCatalog
from app.core.registry import SkillRegistry
from app.engine import WorkflowEngine
from app.runtime.paths import RuntimePaths


@pytest.fixture
def engine(tmp_path: Path):
    paths = RuntimePaths(tmp_path / "runtime")
    paths.ensure_dirs()
    catalog = DataClassCatalog(paths)
    catalog.seed_if_empty()
    if not catalog.list_all():
        catalog.load_from_disk()
    registry = SkillRegistry()
    registry.auto_discover("app.skills.platform")
    registry.auto_discover("app.skills.business")
    return WorkflowEngine(registry, catalog)


@pytest.mark.asyncio
async def test_string_concat_hello_world(engine: WorkflowEngine):
    workflow = {
        "name": "string_concat_demo",
        "nodes": {
            "in_hello": {
                "skill": "input_text",
                "inputs": {"text": {"value": "hello"}},
            },
            "in_world": {
                "skill": "input_text",
                "inputs": {"text": {"value": "world"}},
            },
            "concat": {
                "skill": "string_concat",
                "inputs": {
                    "left": {"from": "in_hello.text"},
                    "right": {"from": "in_world.text"},
                },
            },
        },
    }
    errors = engine.validate(workflow, allow_source_literals_only=True)
    assert errors == []

    outputs = await engine.run(workflow)
    assert outputs["concat.text"] == "helloworld"
