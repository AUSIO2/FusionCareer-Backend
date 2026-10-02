"""Read-only recommendation orchestration; Java owns identity and candidate visibility."""

from __future__ import annotations

import asyncio
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints

from app.algorithms.upstream import ALGORITHM_VERSION, run_algorithm
from app.config import settings
from app.integrations.llm import LLMClient

Term = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=100)]
Category = Literal["MEDIA", "ENTERPRISE", "GOVERNMENT", "ACADEMIC", "OTHER"]
Recruit = Literal[
    "BIG_INTERNSHIP",
    "SMALL_INTERNSHIP",
    "DAILY_INTERNSHIP",
    "CAMPUS_RECRUITMENT",
    "CAMPUS_SCREENING",
    "BOTH_INTERNSHIP",
    "OTHER",
]


class RecommendationPreferences(BaseModel):
    model_config = ConfigDict(extra="forbid")
    jobCategories: list[Category] = Field(default_factory=list, max_length=5)
    workCities: list[Term] = Field(default_factory=list, max_length=20)
    keywords: list[Term] = Field(default_factory=list, max_length=20)
    recruitType: Recruit | None = None
    text: str = Field(default="", max_length=1000)


class RecommendationInteraction(BaseModel):
    model_config = ConfigDict(extra="forbid")
    schemaVersion: Literal[1] = 1
    type: Literal["job_recommendation"]
    preferences: RecommendationPreferences


async def recommend_jobs(backend, context: str, arguments: dict, call_id: str) -> dict:
    preferences = RecommendationPreferences.model_validate(arguments)
    prepared = await run_algorithm("job_recommend_prepare", preferences.model_dump())
    candidates = await backend.run_agent_tool(
        "recommendation_candidates",
        prepared["filters"],
        context,
        tool_call_id=call_id,
    )
    request = {
        "jobs": candidates.get("jobs") or [],
        "slots": prepared["slots"],
        "resume": candidates.get("resume") or {},
        "use_llm": False,
        "user_text": preferences.text,
    }
    # The optional upstream market-cap cache/audit log must use a stable,
    # serialized private workspace; ordinary recommendations remain parallel.
    if settings.recommendation_company_score_enabled or settings.recommendation_audit_log_enabled:
        request["workspace"] = "recommend-shared"
    ranked = await run_algorithm("job_recommend_rank", request)
    if settings.recommendation_llm_enabled and request["jobs"]:
        rank_config = {"llm_timeout_seconds": settings.recommendation_llm_timeout_seconds}
        extra = LLMClient.tool_chat_options(settings.llm_model).get("extra_body")
        if extra:
            rank_config["llm_extra_body"] = extra
        try:
            async with asyncio.timeout(settings.recommendation_llm_timeout_seconds):
                ranked = await run_algorithm(
                    "job_recommend_rank",
                    {
                        **request,
                        "use_llm": True,
                        "config": rank_config,
                    },
                )
        except (TimeoutError, RuntimeError, ValueError):
            ranked["degraded"] = True
    jobs = ranked["jobs"][:10]
    return {
        "jobs": jobs,
        "method": ranked["method"],
        "degraded": ranked.get("degraded", False),
        "candidateCount": len(request["jobs"]),
        "total": candidates.get("total", len(request["jobs"])),
        "filters": prepared["filters"],
        "algorithmVersion": ALGORITHM_VERSION,
    }
