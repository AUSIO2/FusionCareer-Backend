"""Java-facing algorithm contracts, shared by API validation and workflow skills."""

from typing import Any, Literal

from pydantic import BaseModel, Field


class JobStructureBody(BaseModel):
    text: str = Field(min_length=1, max_length=100_000)
    sourceUrl: str | None = Field(default=None, max_length=2048)
    sourceType: Literal["PLATFORM", "CRAWL"] = "PLATFORM"
    defaultStatus: Literal["OFFLINE"] = "OFFLINE"


class JobStructureResult(BaseModel):
    jobs: list[dict[str, Any]]
    warnings: list[str]


class ResumeParseBody(BaseModel):
    userId: int = Field(gt=0)
    fileId: int = Field(gt=0)


class ResumeParseResult(BaseModel):
    profilePatch: dict[str, Any]
    resumePatch: dict[str, Any]
    warnings: list[str]
