"""全局异常处理 — 统一错误响应格式"""

import logging
import traceback

import httpx
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.clients.backend import BackendApiError

logger = logging.getLogger(__name__)


def register_exception_handlers(app: FastAPI):
    """注册所有全局异常处理器到 FastAPI app"""

    @app.exception_handler(BackendApiError)
    async def backend_api_error_handler(request: Request, exc: BackendApiError):
        """Java 后端返回的业务错误"""
        logger.warning(f"后端业务错误: [{exc.code}] {exc.message} | {request.method} {request.url}")
        return JSONResponse(
            status_code=502,
            content={
                "code": exc.code,
                "error": "backend_error",
                "message": exc.message,
                "detail": f"Java 后端返回错误: {exc.message}",
            },
        )

    @app.exception_handler(httpx.ConnectError)
    async def connect_error_handler(request: Request, exc: httpx.ConnectError):
        """无法连接到 Java 后端"""
        logger.error(f"后端连接失败: {exc} | {request.method} {request.url}")
        return JSONResponse(
            status_code=503,
            content={
                "code": 503,
                "error": "backend_unavailable",
                "message": "无法连接到 Java 后端服务",
                "detail": str(exc),
            },
        )

    @app.exception_handler(httpx.HTTPStatusError)
    async def http_status_error_handler(request: Request, exc: httpx.HTTPStatusError):
        """Java 后端返回 HTTP 错误状态码"""
        status = exc.response.status_code
        logger.error(f"后端 HTTP 错误: {status} | {request.method} {request.url}")
        return JSONResponse(
            status_code=502,
            content={
                "code": status,
                "error": "backend_http_error",
                "message": f"Java 后端返回 HTTP {status}",
                "detail": exc.response.text[:500] if exc.response else str(exc),
            },
        )

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(request: Request, exc: RequestValidationError):
        """请求参数校验失败"""
        logger.warning(f"参数校验失败: {request.method} {request.url}")
        return JSONResponse(
            status_code=422,
            content={
                "code": 422,
                "error": "validation_error",
                "message": "请求参数校验失败",
                "detail": exc.errors(),
            },
        )

    @app.exception_handler(KeyError)
    async def key_error_handler(request: Request, exc: KeyError):
        """Skill 未找到或缺少必需字段"""
        logger.warning(f"KeyError: {exc} | {request.method} {request.url}")
        return JSONResponse(
            status_code=400,
            content={
                "code": 400,
                "error": "missing_key",
                "message": f"缺少必需字段或 Skill 未注册: {exc}",
            },
        )

    @app.exception_handler(ValueError)
    async def value_error_handler(request: Request, exc: ValueError):
        """值错误（类型不匹配、缺少输入等）"""
        logger.warning(f"ValueError: {exc} | {request.method} {request.url}")
        return JSONResponse(
            status_code=400,
            content={
                "code": 400,
                "error": "value_error",
                "message": str(exc),
            },
        )

    @app.exception_handler(Exception)
    async def global_exception_handler(request: Request, exc: Exception):
        """兜底：未被捕获的所有异常"""
        logger.error(
            f"未处理异常: {type(exc).__name__}: {exc} | {request.method} {request.url}\n"
            f"{traceback.format_exc()}"
        )
        return JSONResponse(
            status_code=500,
            content={
                "code": 500,
                "error": "internal_error",
                "message": "服务内部错误",
                "detail": f"{type(exc).__name__}: {exc}",
            },
        )
