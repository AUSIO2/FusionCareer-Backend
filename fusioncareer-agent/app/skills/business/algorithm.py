"""Pinned upstream algorithms as composable workflow nodes."""

import base64
from pathlib import Path

from fastapi import HTTPException

from app.algorithms.contracts import JobStructureBody, ResumeParseBody
from app.algorithms.job_structuring.normalize import deduplicateJobs, normalizeJob
from app.algorithms.resume_parser.normalize import normalizeResume
from app.algorithms.upstream import OPERATIONS, run_algorithm
from app.core.base_skill import BaseSkill
from app.integrations.backend import BackendClient

_backend: BackendClient | None = None


def set_backend_client(client: BackendClient):
    global _backend
    _backend = client


class UpstreamAlgorithmSkill(BaseSkill):
    def define(self):
        return {
            "name": "upstream_algorithm",
            "description": "FusionCareer-Algorithm: " + ", ".join(OPERATIONS),
            "inputs": {"operation": "text", "request": "json_obj"},
            "outputs": {"result": "json_obj"},
        }

    async def execute(self, inputs):
        return {"result": await run_algorithm(inputs["operation"], inputs["request"])}


class DownloadResumeSkill(BaseSkill):
    def define(self):
        return {
            "name": "download_resume",
            "description": "校验简历归属、大小和类型，准备算法输入",
            "inputs": {"request": "json_obj"},
            "outputs": {"request": "json_obj"},
        }

    async def execute(self, inputs):
        body = ResumeParseBody.model_validate(inputs["request"])
        if _backend is None:
            raise RuntimeError("BackendClient 未初始化")
        file, data = await _backend.read_resume_file(body.userId, body.fileId)
        if len(data) > 20 * 1024 * 1024:
            raise HTTPException(status_code=413, detail="resume file exceeds 20 MB")
        suffix = Path(str(file.get("originalName") or "")).suffix.lower()
        if suffix not in {".pdf", ".docx", ".png", ".jpg", ".jpeg"}:
            raise HTTPException(status_code=422, detail="unsupported resume format")
        return {"request": {"filename": "resume" + suffix, "file_base64": base64.b64encode(data).decode()}}


class NormalizeResumeResultSkill(BaseSkill):
    def define(self):
        return {
            "name": "normalize_resume_result",
            "description": "将上游简历字段转换成 Java 的非空资料/简历补丁",
            "inputs": {"result": "json_obj"},
            "outputs": {"result": "json_obj"},
        }

    async def execute(self, inputs):
        return {"result": normalizeResume(inputs["result"]["record"])}


class NormalizeJobResultSkill(BaseSkill):
    def define(self):
        return {
            "name": "normalize_job_result",
            "description": "将上游岗位结果转换成 Java 草稿字段并去重",
            "inputs": {"result": "json_obj", "request": "json_obj"},
            "outputs": {"result": "json_obj"},
        }

    async def execute(self, inputs):
        body = JobStructureBody.model_validate(inputs["request"])
        jobs, warnings = [], []
        for index, row in enumerate(inputs["result"]["positions"]):
            job, errors = normalizeJob(row, body.sourceUrl or "", body.sourceType)
            if errors:
                warnings.extend(f"job {index + 1}: {error}" for error in errors)
            else:
                jobs.append(job)
        return {"result": {"jobs": deduplicateJobs(jobs), "warnings": warnings}}
