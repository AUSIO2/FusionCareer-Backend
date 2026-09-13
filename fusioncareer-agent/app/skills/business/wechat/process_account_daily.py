"""单公众号 daily 增量（分页 + 逐篇落盘）。"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from app.core.base_skill import BaseSkill
from app.skills.business.wechat.core import (
    init_run_state,
    merge_account_result,
    process_account_daily,
    resolve_config_root,
)
from app.skills.business.wechat.paths import WechatPaths


class WechatProcessAccountDailySkill(BaseSkill):
    def define(self) -> dict:
        return {
            "name": "wechat_process_account_daily",
            "description": "单号 daily：分页检测增量并保存 markdown",
            "inputs": {
                "paths": "json_obj",
                "state_path": "text",
                "iteration": "int",
            },
            "outputs": {"json_obj": "json_obj"},
            "retry_policy": {
                "enabled": True,
                "max_retries": 2,
                "retry_on": ["ConnectionError", "TimeoutError", "ReadTimeout"],
                "backoff_seconds": 2.0,
            },
        }

    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        paths = WechatPaths(resolve_config_root(inputs["paths"]))
        state_path = Path(inputs["state_path"])
        iteration = int(inputs["iteration"])
        state = init_run_state(paths, state_path, iteration)
        readAccounts: list[dict] = state["accounts"]
        if iteration >= len(readAccounts):
            return {"json_obj": {"account": None, "saved_count": 0, "skipped": "index_out_of_range"}}
        readAccount = readAccounts[iteration]
        fakeid = readAccount["fakeid"]
        account_name = readAccount["name"]
        result = process_account_daily(paths, fakeid, account_name)
        merge_account_result(state_path, result)
        return {"json_obj": result}
