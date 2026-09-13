"""单公众号 bootstrap（最新 N 篇）。"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from app.core.base_skill import BaseSkill
from app.skills.business.wechat.core import (
    init_run_state,
    merge_account_result,
    process_account_bootstrap,
    resolve_config_root,
)
from app.skills.business.wechat.io import load_json_file
from app.skills.business.wechat.paths import WechatPaths


class WechatProcessAccountBootstrapSkill(BaseSkill):
    def define(self) -> dict:
        return {
            "name": "wechat_process_account_bootstrap",
            "description": "单号 bootstrap：拉取最新 N 条并初始化 history",
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
        config = load_json_file(paths.config_file)
        limit = int(
            state.get("article_limit")
            or config.get("bootstrap_article_limit")
            or 10
        )
        readAccount = readAccounts[iteration]
        fakeid = readAccount["fakeid"]
        account_name = readAccount["name"]
        result = process_account_bootstrap(paths, fakeid, account_name, limit)
        merge_account_result(state_path, result)
        return {"json_obj": result}
