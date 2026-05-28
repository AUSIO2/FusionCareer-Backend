"""平台源节点 Skill：input_profile_data"""

from __future__ import annotations

from typing import Any

from app.core.base_skill import BaseSkill
from app.core.source_skills import SOURCE_KIND


class InputProfileDataSkill(BaseSkill):
    def define(self) -> dict:
        return {
            "name": "input_profile_data",
            "kind": SOURCE_KIND,
            "description": "工作流入口：输出 profile_data",
            "inputs": {"data": "profile_data"},
            "outputs": {"data": "profile_data"},
        }

    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        return {"data": inputs["data"]}
