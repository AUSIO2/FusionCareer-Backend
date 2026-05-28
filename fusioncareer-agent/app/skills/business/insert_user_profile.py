"""
InsertUserProfileSkill — 按 Java UserProfileEntity / UserProfileRequest 字段写入用户档案

输入: user_id (int) + profile_data (profile_data)
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


class InsertUserProfileSkill(BaseSkill):
    """写入 Java /internal/user-profile/{userId}。"""

    def define(self) -> dict:
        return {
            "name": "insert_user_profile",
            "description": "按 Java 实体字段写入用户档案（insert/update）",
            "retry_policy": {
                # 写接口默认禁用重试，避免重放破坏数据唯一性
                "enabled": False,
                "max_retries": 0,
                "retry_on": [],
                "backoff_seconds": 0.0,
            },
            "inputs": {
                "user_id": "int",
                "profile_data": "profile_data",
            },
            "outputs": {
                "result": "api_result",
            },
        }

    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        if _backend is None:
            raise RuntimeError("BackendClient 未初始化")

        user_id: int = inputs["user_id"]
        profile_data: dict = inputs["profile_data"]

        # 对齐 Java UserProfileEntity / UserProfileRequest 字段
        allowed_fields = {
            "realName",
            "gender",
            "birthDate",
            "politicalStatus",
            "phone",
            "email",
            "wechat",
            "hometown",
            "grade",
            "major",
            "eduLevel",
            "supervisor",
            "intentionOrder",
            "intentionCity",
            "intentionDream",
            "mindset",
        }
        payload = {k: v for k, v in profile_data.items() if k in allowed_fields}
        logger.info(
            "insert_user_profile: user_id=%s, fields=%s",
            user_id,
            sorted(payload.keys()),
        )

        await _backend.create_or_update_profile(user_id, payload)
        return {
            "result": {
                "success": True,
                "message": f"用户档案已写入 user_id={user_id}",
                "user_id": user_id,
            }
        }
