"""Native bounded job-structure workflow skill with a legacy rollback path."""

from __future__ import annotations

from typing import Any

from app.algorithms.contracts import JobStructureBody
from app.algorithms.job_structuring import structureJobs
from app.algorithms.job_structuring.normalize import deduplicateJobs, normalizeJob
from app.algorithms.upstream import run_algorithm
from app.config import settings
from app.core.base_skill import BaseSkill


def normalizeLegacyPositions(readResult: dict, readBody: JobStructureBody) -> dict[str, Any]:
    jobs: list[dict[str, Any]] = []
    warnings: list[str] = []
    for index, row in enumerate(readResult.get("positions") or []):
        if not isinstance(row, dict):
            warnings.append(f"job {index + 1}: not an object")
            continue
        job, errors = normalizeJob(row, readBody.sourceUrl or "", readBody.sourceType)
        if errors:
            warnings.extend(f"job {index + 1}: {error}" for error in errors)
        else:
            jobs.append(job)
    return {"jobs": deduplicateJobs(jobs), "warnings": warnings}


class NativeJobStructureSkill(BaseSkill):
    def define(self) -> dict:
        return {
            "name": "native_job_structure",
            "description": "按语义块和目标岗位分批抽取长招聘文章",
            "inputs": {"request": "json_obj"},
            "outputs": {"result": "json_obj"},
            "retry_policy": {
                "enabled": False,
                "max_retries": 0,
                "backoff_seconds": 0,
                "retry_on": [],
            },
        }

    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        body = JobStructureBody.model_validate(inputs["request"])
        if settings.job_structure_mode == "legacy":
            result = await run_algorithm("job_structure", body.model_dump())
            return {"result": normalizeLegacyPositions(result, body)}
        result = await structureJobs(
            body.text,
            body.sourceUrl or "",
            body.sourceType,
        )
        return {"result": result}
