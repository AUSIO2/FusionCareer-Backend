"""平台源节点 Skill：input_json_obj（数据类 json_obj）"""

from __future__ import annotations

from typing import Any

from app.core.base_skill import BaseSkill
from app.core.source_skills import SOURCE_KIND


class InputJsonObjSkill(BaseSkill):
    def define(self) -> dict:
        return {
            "name": "input_json_obj",
            "kind": SOURCE_KIND,
            "description": "工作流入口：输出 json_obj",
            "inputs": {"json_obj": "json_obj"},
            "outputs": {"json_obj": "json_obj"},
        }

    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        return {"json_obj": inputs["json_obj"]}
