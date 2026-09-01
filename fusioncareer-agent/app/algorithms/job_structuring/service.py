"""In-memory job structuring service."""

from __future__ import annotations

from typing import Any

from app.algorithms.job_structuring.normalize import deduplicateJobs, normalizeJob
from app.algorithms.job_structuring.prompt import JOB_PROMPT
from app.integrations.llm import LLMClient


async def structureJobs(
    readText: str,
    readSourceUrl: str = "",
    readSourceType: str = "PLATFORM",
    readClient: LLMClient | None = None,
) -> dict[str, Any]:
    if not readText.strip():
        raise ValueError("job text is required")
    createClient = readClient or LLMClient()
    readResponse = await createClient.chat_json(
        user_message=readText[:28000],
        system_prompt=JOB_PROMPT,
        temperature=0.1,
    )
    readItems = readResponse.get("jobs", []) if isinstance(readResponse, dict) else []
    if not isinstance(readItems, list):
        raise ValueError("model jobs must be a list")

    readJobs = []
    readWarnings = list(readResponse.get("warnings", [])) if isinstance(readResponse, dict) else []
    for readIndex, readItem in enumerate(readItems):
        if not isinstance(readItem, dict):
            readWarnings.append(f"job {readIndex + 1} is not an object")
            continue
        createJob, createWarnings = normalizeJob(readItem, readSourceUrl, readSourceType)
        if createWarnings:
            readWarnings.extend(f"job {readIndex + 1}: {readWarning}" for readWarning in createWarnings)
            continue
        readJobs.append(createJob)
    return {"jobs": deduplicateJobs(readJobs), "warnings": readWarnings}
