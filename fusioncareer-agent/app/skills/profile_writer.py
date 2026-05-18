"""
ProfileWriterSkill — 将结构化用户资料 POST 到 Java 后端落库

输入: user_id (int) + profile_data (profile_data)
输出: result (api_result)

profile_data 格式 (对应 Java UserProfileRequest):
{
    "realName":        "姓名",
    "gender":          1,          # 1=男 2=女 3=其他
    "birthDate":       "2000-01-15",
    "politicalStatus": 2,          # 1=群众 2=共青团员 3=中共党员 4=其他
    "phone":           "13800138000",
    "email":           "xxx@fudan.edu.cn",
    "wechat":          "wechat_id",
    "hometown":        "上海",
    "grade":           "2022级",
    "major":           "新闻学",
    "eduLevel":        2,          # 1=本科 2=学术硕士 3=专业硕士 4=博士
    "supervisor":      "导师姓名",
    "intentionOrder":  "企业公司,新闻媒体",
    "intentionCity":   "[\"上海\",\"北京\"]",
    "intentionDream":  "梦中情岗描述",
    "mindset":         2           # 1=有把握 2=谨慎乐观 3=信心不足 4=焦虑 5=佛系 9=完蛋了
}
"""

import logging
from typing import Any

from app.base_skill import BaseSkill
from app.clients.backend import BackendClient

logger = logging.getLogger(__name__)

_backend: BackendClient | None = None


def set_backend_client(client: BackendClient):
    global _backend
    _backend = client


class ProfileWriterSkill(BaseSkill):
    """将结构化用户资料写入 Java 后端 /internal/user-profile/{userId}"""

    def define(self) -> dict:
        return {
            "name": "profile_writer",
            "description": "将结构化用户资料 POST 到 Java 后端落库",
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

        logger.info(f"写入用户资料: user_id={user_id}")

        # 字段过滤 — 只保留 Java UserProfileRequest 接受的字段
        allowed_fields = {
            "realName", "gender", "birthDate", "politicalStatus",
            "phone", "email", "wechat", "hometown",
            "grade", "major", "eduLevel", "supervisor",
            "intentionOrder", "intentionCity", "intentionDream", "mindset",
        }
        payload = {k: v for k, v in profile_data.items() if k in allowed_fields}

        await _backend.create_or_update_profile(user_id, payload)

        return {
            "result": {
                "success": True,
                "message": f"用户资料已写入 user_id={user_id}",
                "user_id": user_id,
            }
        }
