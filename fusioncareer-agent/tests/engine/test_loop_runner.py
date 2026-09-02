"""loop_runner 校验与 finalize 字段。"""

from __future__ import annotations

from pathlib import Path

import pytest

from app.catalog.catalog import DataClassCatalog
from app.core.registry import SkillRegistry
from app.engine.loop_runner import LoopControl, validate_loop
from app.runtime.paths import RuntimePaths


@pytest.fixture
def registry(tmp_path: Path) -> SkillRegistry:
    paths = RuntimePaths(tmp_path / "runtime")
    paths.ensure_dirs()
    catalog = DataClassCatalog(paths)
    catalog.seed_if_empty()
    reg = SkillRegistry()
    reg.auto_discover("app.skills.platform")
    reg.auto_discover("app.skills.business")
    return reg


def test_validate_loop_with_finalize(registry: SkillRegistry):
    loop = LoopControl(
        judge_skill="wechat_judge_accounts",
        max_iterations=10,
        finalize_skill="wechat_finalize_daily",
        finalize_inputs={"paths": {"config_root": "/tmp"}},
    )
    assert validate_loop(registry, loop) == []


def test_validate_loop_missing_judge(registry: SkillRegistry):
    loop = LoopControl(judge_skill="no_such_skill", max_iterations=1)
    assert any("不存在" in e for e in validate_loop(registry, loop))


def test_loop_delay_bounds():
    assert LoopControl(judge_skill="test", max_iterations=1, iteration_delay_seconds=20).iteration_delay_seconds == 20
    with pytest.raises(ValueError):
        LoopControl(judge_skill="test", max_iterations=1, iteration_delay_seconds=301)
