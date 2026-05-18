"""
ResumeWriterSkill — 将结构化简历数据 POST 到 Java 后端落库

输入: user_id (int) + resume_data (resume_data)
输出: result (api_result)

resume_data 格式 (对应 Java ResumeRequest):
{
    "personalIntro": "个人简况",
    "basicInfo":     "基础信息",
    "education":     "教育背景",
    "internship":    "实习经历",
    "campus":        "在校经历",
    "awards":        "荣誉奖励",
    "skills":        "掌握技能",
    "portfolio":     "作品集",
    "remark":        "备注"
}
"""

import logging
from typing import Any

from app.base_skill import BaseSkill
from app.clients.backend import BackendClient

logger = logging.getLogger(__name__)

# 模块级单例，由 main.py 启动时注入
_backend: BackendClient | None = None


def set_backend_client(client: BackendClient):
    global _backend
    _backend = client


class ResumeWriterSkill(BaseSkill):
    """将结构化简历数据写入 Java 后端 /internal/resume/{userId}"""

    def define(self) -> dict:
        return {
            "name": "resume_writer",
            "description": "将结构化简历数据 POST 到 Java 后端落库",
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

        logger.info(f"写入简历: user_id={user_id}")

        # 字段过滤 — 只保留 Java ResumeRequest 接受的字段
        allowed_fields = {
            "personalIntro", "basicInfo", "education", "internship",
            "campus", "awards", "skills", "portfolio", "remark",
        }
        payload = {k: v for k, v in resume_data.items() if k in allowed_fields}

        await _backend.create_or_update_resume(user_id, payload)

        return {
            "result": {
                "success": True,
                "message": f"简历已写入 user_id={user_id}",
                "user_id": user_id,
            }
        }
