"""daily 结束后汇总报表与 summary markdown。"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from app.core.base_skill import BaseSkill
from app.skills.business.wechat.core import finalize_daily_run, resolve_config_root
from app.skills.business.wechat.io import load_json_file
from app.skills.business.wechat.paths import WechatPaths


class WechatFinalizeDailySkill(BaseSkill):
    def define(self) -> dict:
        return {
            "name": "wechat_finalize_daily",
            "description": "汇总 stats 写入 daily_report.jsonl 与当日 summary",
            "inputs": {
                "state_path": "text",
                "paths": "json_obj",
            },
            "outputs": {"json_obj": "json_obj"},
        }

    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        paths = WechatPaths(resolve_config_root(inputs["paths"]))
        state = load_json_file(Path(inputs["state_path"]), default={})
        result = finalize_daily_run(paths, state)
        return {"json_obj": result}
