"""管理员 Token 校验"""

import secrets

from fastapi import Header, HTTPException

from app.config import settings


async def require_agent_admin(
    x_agent_admin_token: str | None = Header(default=None, alias="X-Agent-Admin-Token"),
) -> None:
    expected = settings.agent_admin_token
    if not expected:
        raise HTTPException(
            status_code=503,
            detail={
                "code": "admin_not_configured",
                "message": "未配置 AGENT_ADMIN_TOKEN，拒绝管理接口",
            },
        )
    if not x_agent_admin_token or not secrets.compare_digest(x_agent_admin_token, expected):
        raise HTTPException(
            status_code=403,
            detail={"code": "forbidden", "message": "无效或缺少 X-Agent-Admin-Token"},
        )
