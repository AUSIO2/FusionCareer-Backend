"""字符串拼接 — 两个 text 输入，输出拼接结果"""

from __future__ import annotations

from typing import Any

from app.core.base_skill import BaseSkill


class StringConcatSkill(BaseSkill):
    def define(self) -> dict:
        return {
            "name": "string_concat",
            "description": "字符串拼接：left + right",
            "inputs": {
                "left": "text",
                "right": "text",
            },
            "outputs": {"text": "text"},
        }

    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        left = inputs["left"]
        right = inputs["right"]
        if not isinstance(left, str) or not isinstance(right, str):
            raise TypeError("left 与 right 须为字符串")
        return {"text": left + right}
