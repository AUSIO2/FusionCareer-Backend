"""wechat 预置 workflow 可通过校验。"""

from __future__ import annotations

from pathlib import Path

import pytest

from app.catalog.catalog import DataClassCatalog
from app.catalog.workflow_catalog import WorkflowCatalog
from app.core.registry import SkillRegistry
from app.engine.runner import WorkflowEngine
from app.engine.loop_runner import validate_loop, LoopControl
from app.runtime.paths import RuntimePaths


@pytest.fixture
def engine_bundle(tmp_path: Path):
    paths = RuntimePaths(tmp_path / "runtime")
    paths.ensure_dirs()
    catalog = DataClassCatalog(paths)
    catalog.seed_if_empty()
    reg = SkillRegistry()
    reg.auto_discover("app.skills.platform")
    reg.auto_discover("app.skills.business")
    wf_cat = WorkflowCatalog(paths)
    wf_cat.load_all()
    engine = WorkflowEngine(reg, catalog)
    return engine, reg, wf_cat


def test_wechat_daily_body_valid(engine_bundle):
    engine, reg, wf_cat = engine_bundle
    wf = wf_cat.get("wechat_daily_body")
    errors = engine.validate(wf, allow_source_literals_only=True)
    assert errors == []
    loop = LoopControl(
        judge_skill="wechat_judge_accounts",
        max_iterations=10,
        finalize_skill="wechat_finalize_daily",
        finalize_inputs={"paths": {"config_root": "/tmp"}},
    )
    assert validate_loop(reg, loop) == []
