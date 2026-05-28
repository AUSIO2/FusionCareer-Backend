"""平台源节点 Skill：input_bool（数据类 bool）"""

from __future__ import annotations

from typing import Any

from app.core.base_skill import BaseSkill
from app.core.source_skills import SOURCE_KIND


class InputBoolSkill(BaseSkill):
    def define(self) -> dict:
        return {
            "name": "input_bool",
            "kind": SOURCE_KIND,
            "description": "工作流入口：输出 bool",
            "inputs": {"bool": "bool"},
            "outputs": {"bool": "bool"},
        }

    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        return {"bool": inputs["bool"]}
