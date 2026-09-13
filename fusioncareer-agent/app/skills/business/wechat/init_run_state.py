"""初始化 for 循环 run state（accounts / total）。"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from app.core.base_skill import BaseSkill
from app.skills.business.wechat.core import init_run_state, resolve_config_root
from app.skills.business.wechat.paths import WechatPaths


class WechatInitRunStateSkill(BaseSkill):
    def define(self) -> dict:
        return {
            "name": "wechat_init_run_state",
            "description": "加载公众号账号并写入 run state.json",
            "inputs": {
                "paths": "json_obj",
                "state_path": "text",
                "iteration": "int",
            },
            "outputs": {"json_obj": "json_obj"},
        }

    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        paths = WechatPaths(resolve_config_root(inputs["paths"]))
        state_path = Path(inputs["state_path"])
        iteration = int(inputs["iteration"])
        state = init_run_state(paths, state_path, iteration)
        payload = {**inputs["paths"], "run_state": state}
        return {"json_obj": payload}
