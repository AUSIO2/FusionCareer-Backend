"""BackendClient — 封装 FusionCareer Java 后端 Internal API"""

import logging
from typing import Any

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


class BackendApiError(Exception):
    """Java 后端返回业务错误"""

    def __init__(self, code: int, message: str):
        self.code = code
        self.message = message
        super().__init__(f"Backend API Error [{code}]: {message}")


class BackendClient:
    """
    异步 HTTP 客户端，封装 Java 后端 /internal/** 接口。

    所有 Internal 接口无需认证，统一响应格式:
        {"code": 200, "message": "操作成功", "data": ...}
    """

    def __init__(self):
        self.base_url = settings.backend_base_url
        self._client: httpx.AsyncClient | None = None

    async def _ensure_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            read_headers = {"Content-Type": "application/json"}
            if settings.internal_service_token:
                read_headers["X-Internal-Token"] = settings.internal_service_token
            self._client = httpx.AsyncClient(
                base_url=self.base_url,
                timeout=30.0,
                headers=read_headers,
            )
        return self._client

    async def close(self):
        if self._client and not self._client.is_closed:
            await self._client.aclose()

    # ── 简历相关 ──────────────────────────────

    async def create_or_update_resume(self, user_id: int, data: dict) -> None:
        """POST /internal/resume/{userId} — 创建或更新简历"""
        await self._post(f"/internal/resume/{user_id}", data)

    async def get_resume(self, user_id: int) -> dict | None:
        """GET /internal/resume/{userId}"""
        return await self._get(f"/internal/resume/{user_id}")

    # ── 用户资料相关 ──────────────────────────

    async def create_or_update_profile(self, user_id: int, data: dict) -> None:
        """POST /internal/user-profile/{userId} — 创建或更新资料"""
        await self._post(f"/internal/user-profile/{user_id}", data)

    async def get_profile(self, user_id: int) -> dict | None:
        """GET /internal/user-profile/{userId}"""
        return await self._get(f"/internal/user-profile/{user_id}")

    # ── 简历文件相关 ──────────────────────────

    async def list_resume_files(self, user_id: int) -> list[dict]:
        """GET /internal/resume-file/{userId}/list"""
        return await self._get(f"/internal/resume-file/{user_id}/list") or []

    async def download_resume_file(self, file_id: int) -> bytes:
        """GET /internal/resume-file/{fileId}/download — 返回文件字节流"""
        client = await self._ensure_client()
        resp = await client.get(f"/internal/resume-file/{file_id}/download")
        resp.raise_for_status()
        return resp.content

    async def read_resume_file(self, user_id: int, file_id: int) -> tuple[dict, bytes]:
        """Validate ownership through the user file list, then download bytes."""
        read_files = await self.list_resume_files(user_id)
        read_file = next(
            (read_item for read_item in read_files if str(read_item.get("id")) == str(file_id)),
            None,
        )
        if read_file is None:
            raise BackendApiError(403, "resume file does not belong to user")
        return read_file, await self.download_resume_file(file_id)

    # ── 岗位相关 ──────────────────────────────

    async def list_job_posts(self) -> list[dict]:
        read_jobs: list[dict] = []
        read_page = 1
        while True:
            read_result = await self._get(f"/internal/job-post/list?page={read_page}&size=100") or {}
            read_jobs.extend(read_result.get("list") or [])
            if read_page >= int(read_result.get("totalPages") or 0):
                return read_jobs
            read_page += 1

    async def create_job_posts(self, create_jobs: list[dict]) -> None:
        if create_jobs:
            await self._post("/internal/job-post/batch", create_jobs)

    # ── 通用方法 ──────────────────────────────

    async def _post(self, path: str, data: Any) -> Any:
        client = await self._ensure_client()
        logger.debug(f"POST {path}")
        resp = await client.post(path, json=data)
        resp.raise_for_status()
        return self._unwrap(resp)

    async def _get(self, path: str) -> Any:
        client = await self._ensure_client()
        logger.debug(f"GET {path}")
        resp = await client.get(path)
        resp.raise_for_status()
        return self._unwrap(resp)

    async def _put(self, path: str, data: Any) -> Any:
        client = await self._ensure_client()
        logger.debug(f"PUT {path}")
        resp = await client.put(path, json=data)
        resp.raise_for_status()
        return self._unwrap(resp)

    @staticmethod
    def _unwrap(resp: httpx.Response) -> Any:
        """解析统一响应 {"code": 200, "message": "...", "data": ...}"""
        body = resp.json()
        code = body.get("code", -1)
        if code != 200:
            raise BackendApiError(code, body.get("message", "Unknown error"))
        return body.get("data")
