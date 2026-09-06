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
    readJobs = []
    readWarnings = []
    readBody = readText[:28000]
    readHeader = readBody[:300]
    readParts = [readBody] if len(readBody) <= 2000 else [
        f"以下开头仅供识别单位，不要重复抽取其中岗位：\n{readHeader}"
        f"\n\n--- 只抽取以下原文分段 ---\n\n{readBody[readStart:readStart + 2000]}"
        for readStart in range(0, len(readBody), 2000)
    ]
    for readPart in readParts:
        readResponse = await createClient.chat_json(
            user_message=readPart,
            system_prompt=JOB_PROMPT,
            temperature=0.1,
            max_tokens=16384,
        )
        readItems = readResponse.get("jobs", []) if isinstance(readResponse, dict) else []
        if not isinstance(readItems, list):
            raise TypeError("model jobs must be a list")
        readWarnings.extend(readResponse.get("warnings", []) if isinstance(readResponse, dict) else [])
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
