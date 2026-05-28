"""平台源节点 Skill：input_int（数据类 int）"""

from __future__ import annotations

from typing import Any

from app.core.base_skill import BaseSkill
from app.core.source_skills import SOURCE_KIND


class InputIntSkill(BaseSkill):
    def define(self) -> dict:
        return {
            "name": "input_int",
            "kind": SOURCE_KIND,
            "description": "工作流入口：输出 int",
            "inputs": {"int": "int"},
            "outputs": {"int": "int"},
        }

    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        return {"int": inputs["int"]}
