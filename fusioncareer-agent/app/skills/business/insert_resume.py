"""
InsertResumeSkill — 按 Java ResumeEntity / ResumeRequest 字段写入简历

输入: user_id (int) + resume_data (resume_data)
输出: result (api_result)
"""

from __future__ import annotations

import logging
from typing import Any

from app.core.base_skill import BaseSkill
from app.integrations.backend import BackendClient

logger = logging.getLogger(__name__)

_backend: BackendClient | None = None


def set_backend_client(client: BackendClient):
    global _backend
    _backend = client


class InsertResumeSkill(BaseSkill):
    """写入 Java /internal/resume/{userId}。"""

    def define(self) -> dict:
        return {
            "name": "insert_resume",
            "description": "按 Java 实体字段写入简历（insert/update）",
            "retry_policy": {
                # 写接口默认禁用重试，避免重放破坏数据唯一性
                "enabled": False,
                "max_retries": 0,
                "retry_on": [],
                "backoff_seconds": 0.0,
            },
            "inputs": {
                "user_id": "int",
                "resume_data": "resume_data",
            },
            "outputs": {
                "result": "api_result",
            },
        }

    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        if _backend is None:
            raise RuntimeError("BackendClient 未初始化")

        user_id: int = inputs["user_id"]
        resume_data: dict = inputs["resume_data"]

        # 对齐 Java ResumeEntity / ResumeRequest 字段
        allowed_fields = {
            "personalIntro",
            "basicInfo",
            "education",
            "internship",
            "campus",
            "awards",
            "skills",
            "portfolio",
            "remark",
        }
        payload = {k: v for k, v in resume_data.items() if k in allowed_fields}
        logger.info("insert_resume: user_id=%s, fields=%s", user_id, sorted(payload.keys()))

        await _backend.create_or_update_resume(user_id, payload)
        return {
            "result": {
                "success": True,
                "message": f"简历已写入 user_id={user_id}",
                "user_id": user_id,
            }
        }
