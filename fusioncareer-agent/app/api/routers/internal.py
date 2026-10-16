"""Typed internal algorithm APIs called by Java."""

import asyncio
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request

from app.algorithms.contracts import (
    JobStructureBody,
    JobStructureResult,
    ResumeParseBody,
    ResumeParseResult,
)
from app.algorithms.workflows import run_algorithm_workflow
from app.api.deps.internal_auth import requireInternal
from app.api.routers.admin import _start_structure_drain, _structure_status
from app.config import settings

router = APIRouter(
    prefix="/api/internal",
    tags=["internal"],
    dependencies=[Depends(requireInternal)],
)


async def runAlgorithmWorkflow(request: Request, name: str, body: dict) -> dict:
    return await run_algorithm_workflow(
        request.app.state.workflow_engine, request.app.state.workflow_catalog, name, body,
    )


@router.get("/health")
async def readHealth() -> dict[str, str]:
    return {"status": "ok"}


@router.post("/job/structure", response_model=JobStructureResult)
async def structureJob(readBody: JobStructureBody, readRequest: Request) -> dict[str, Any]:
    return await runAlgorithmWorkflow(readRequest, "job_structure", readBody.model_dump())


@router.get("/job/structure-pending")
async def readStructurePending(readRequest: Request) -> dict[str, Any]:
    return _structure_status(readRequest)


@router.post("/job/structure-pending", status_code=202)
async def startStructurePending(readRequest: Request) -> dict[str, Any]:
    return _start_structure_drain(readRequest)


@router.post("/resume/parse", response_model=ResumeParseResult)
async def parseResumeFile(readBody: ResumeParseBody, readRequest: Request) -> dict[str, Any]:
    try:
        async with asyncio.timeout(settings.resume_parse_timeout_seconds):
            return await runAlgorithmWorkflow(readRequest, "resume_parse", readBody.model_dump())
    except TimeoutError as error:
        raise HTTPException(status_code=504, detail="resume parsing exceeded the processing deadline") from error
