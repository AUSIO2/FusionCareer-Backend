"""平台源节点 Skill：input_list（数据类 list）"""

from __future__ import annotations

from typing import Any

from app.core.base_skill import BaseSkill
from app.core.source_skills import SOURCE_KIND


class InputListSkill(BaseSkill):
    def define(self) -> dict:
        return {
            "name": "input_list",
            "kind": SOURCE_KIND,
            "description": "工作流入口：输出 list",
            "inputs": {"list": "list"},
            "outputs": {"list": "list"},
        }

    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        return {"list": inputs["list"]}
