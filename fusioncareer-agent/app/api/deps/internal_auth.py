"""Shared service-token authentication for Java/Python traffic."""

import secrets

from fastapi import Header, HTTPException

from app.config import settings


async def requireInternal(
    readToken: str | None = Header(default=None, alias="X-Internal-Token"),
) -> None:
    readExpected = settings.internal_service_token
    if not readExpected:
        raise HTTPException(status_code=503, detail="internal service token is not configured")
    if not readToken or not secrets.compare_digest(readToken, readExpected):
        raise HTTPException(status_code=403, detail="invalid internal service token")
