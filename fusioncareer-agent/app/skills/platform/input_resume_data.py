"""平台源节点 Skill：input_resume_data"""

from __future__ import annotations

from typing import Any

from app.core.base_skill import BaseSkill
from app.core.source_skills import SOURCE_KIND


class InputResumeDataSkill(BaseSkill):
    def define(self) -> dict:
        return {
            "name": "input_resume_data",
            "kind": SOURCE_KIND,
            "description": "工作流入口：输出 resume_data",
            "inputs": {"data": "resume_data"},
            "outputs": {"data": "resume_data"},
        }

    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        return {"data": inputs["data"]}
