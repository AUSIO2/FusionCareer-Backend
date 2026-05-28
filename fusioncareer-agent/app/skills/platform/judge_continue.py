"""平台判断 Skill：依据 run 级状态文件判断是否继续。"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from app.core.base_skill import BaseSkill
from app.runtime.paths import atomic_write_json


class JudgeContinueSkill(BaseSkill):
    def define(self) -> dict:
        return {
            "name": "judge_continue",
            "description": "基于 state.json 的循环门控",
            "inputs": {
                "state_path": "text",
                "iteration": "int",
                "max_true": "int",
            },
            "outputs": {"continue": "bool"},
            "retry_policy": {
                "enabled": False,
                "max_retries": 0,
                "retry_on": [],
                "backoff_seconds": 0,
            },
        }

    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        state_path = Path(inputs["state_path"])
        max_true = int(inputs["max_true"])
        iteration = int(inputs["iteration"])
        count = 0
        if state_path.exists():
            state = json.loads(state_path.read_text(encoding="utf-8"))
            count = int(state.get("judge_count", 0))
        count += 1
        atomic_write_json(
            state_path,
            {"judge_count": count, "last_iteration": iteration},
        )
        return {"continue": count <= max_true}
