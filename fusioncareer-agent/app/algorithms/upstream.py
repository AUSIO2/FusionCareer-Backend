"""Run the pinned algorithm repository without leaking its global state into the Agent."""

from __future__ import annotations

import asyncio
import json
import re
import sys
import uuid
from pathlib import Path

from app.config import settings

OPERATIONS = (
    "job_structure", "job_admin", "job_markdown", "job_batch", "job_deduplicate",
    "job_export_json", "job_export_xlsx", "job_upload", "resume_parse", "resume_batch",
    "wechat_bootstrap", "wechat_daily", "wechat_archive", "wechat_sync_session",
)


async def run_algorithm(operation: str, request: dict) -> dict:
    if operation not in OPERATIONS:
        raise ValueError(f"Unknown algorithm operation: {operation}")
    request = dict(request)
    workspace = request.pop("workspace", None) or f"run-{uuid.uuid4().hex}"
    if not isinstance(workspace, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", workspace):
        raise ValueError("workspace must contain only letters, digits, underscores or hyphens")
    root = (Path(settings.agent_runtime_dir).resolve() / "algorithm" / workspace)
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    payload = {
        "operation": operation, "request": request, "root": str(root),
        "config": {
            "llm_api_key": settings.llm_api_key,
            "llm_base_url": settings.llm_base_url,
            "llm_model": settings.llm_model,
            "llm_fast_model": settings.llm_model,
            "backend_base_url": settings.backend_base_url,
            "internal_service_token": settings.internal_service_token,
            "token": settings.wechat_token, "cookie": settings.wechat_cookie,
        },
    }
    worker = Path(__file__).with_name("upstream_worker.py")
    # Upstream has process-global paths and synchronous clients. Each run owns a process;
    # cancellation kills it, so no background write survives a cancelled workflow node.
    process = await asyncio.create_subprocess_exec(
        sys.executable, str(worker), stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
    )
    try:
        async with asyncio.timeout(settings.algorithm_timeout_seconds):
            stdout, _stderr = await process.communicate(json.dumps(payload).encode())
    except BaseException:
        if process.returncode is None:
            process.kill()
        await process.communicate()
        raise
    if process.returncode:
        # Upstream stdout/errors can contain article text and credentials; keep them
        # out of HTTP responses. Its normal I/O logs live in the private workspace.
        raise RuntimeError(f"Algorithm worker exited with code {process.returncode}")
    result = json.loads(stdout)
    if "error" in result:
        error = result["error"]
        if error["type"] in {"ValueError", "FileNotFoundError"}:
            raise ValueError(error["message"])
        raise RuntimeError(error["message"])
    return {**result["result"], "workspace": workspace}
