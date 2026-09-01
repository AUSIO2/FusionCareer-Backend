"""Typed internal algorithm APIs called by Java."""

from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field

from app.algorithms.job_structuring import structureJobs
from app.algorithms.resume_parser import parseResume
from app.api.deps.internal_auth import requireInternal
from app.integrations.backend import BackendClient


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


class ResumeParseBody(BaseModel):
    userId: str = Field(pattern=r"^\d+$")
    fileId: str = Field(pattern=r"^\d+$")


class ResumeParseResult(BaseModel):
    profilePatch: dict[str, Any]
    resumePatch: dict[str, Any]
    warnings: list[str]


@router.get("/health")
async def readHealth() -> dict[str, str]:
    return {"status": "ok"}


@router.post("/job/structure", response_model=JobStructureResult)
async def structureJob(readBody: JobStructureBody) -> dict[str, Any]:
    return await structureJobs(readBody.text, readBody.sourceUrl, readBody.sourceType)


async def readResumeFile(
    readBackend: BackendClient,
    readUserId: int,
    readFileId: int,
) -> tuple[dict, bytes]:
    return await readBackend.read_resume_file(readUserId, readFileId)


@router.post("/resume/parse", response_model=ResumeParseResult)
async def parseResumeFile(readBody: ResumeParseBody, readRequest: Request) -> dict[str, Any]:
    readFile, readBytes = await readResumeFile(
        readRequest.app.state.backend_client,
        int(readBody.userId),
        int(readBody.fileId),
    )
    if len(readBytes) > 20 * 1024 * 1024:
        raise HTTPException(status_code=413, detail="resume file exceeds 20 MB")
    readSuffix = Path(str(readFile.get("originalName") or "")).suffix.lower()
    if readSuffix not in {".pdf", ".docx", ".png", ".jpg", ".jpeg"}:
        raise HTTPException(status_code=422, detail="unsupported resume format")
    with TemporaryDirectory(prefix="fusioncareer-resume-") as readDirectory:
        readPath = Path(readDirectory) / ("resume" + readSuffix)
        readPath.write_bytes(readBytes)
        return await parseResume(readPath)
