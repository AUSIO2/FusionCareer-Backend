"""Typed internal algorithm APIs called by Java."""

from typing import Any, Literal

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from app.algorithms.job_structuring import structureJobs
from app.api.deps.internal_auth import requireInternal


router = APIRouter(
    prefix="/api/internal",
    tags=["internal"],
    dependencies=[Depends(requireInternal)],
)


class JobStructureBody(BaseModel):
    text: str = Field(min_length=1, max_length=100_000)
    sourceUrl: str = Field(default="", max_length=2048)
    sourceType: Literal["PLATFORM", "CRAWL"] = "PLATFORM"
    defaultStatus: Literal["OFFLINE"] = "OFFLINE"


class JobStructureResult(BaseModel):
    jobs: list[dict[str, Any]]
    warnings: list[str]


@router.post("/job/structure", response_model=JobStructureResult)
async def structureJob(readBody: JobStructureBody) -> dict[str, Any]:
    return await structureJobs(readBody.text, readBody.sourceUrl, readBody.sourceType)
