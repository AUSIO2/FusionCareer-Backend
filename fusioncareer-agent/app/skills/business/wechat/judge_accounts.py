"""for 循环门控：是否还有未处理的公众号。"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from app.core.base_skill import BaseSkill
from app.skills.business.wechat.io import load_json_file


class WechatJudgeAccountsSkill(BaseSkill):
    def define(self) -> dict:
        return {
            "name": "wechat_judge_accounts",
            "description": "iteration < total 时继续 for 循环",
            "inputs": {
                "state_path": "text",
                "iteration": "int",
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
        iteration = int(inputs["iteration"])
        state = load_json_file(state_path, default={})
        total = int(state.get("total", 0))
        return {"continue": iteration < total}
