"""In-memory job structuring service."""

from __future__ import annotations

import json
from typing import Any

from app.algorithms.job_structuring.normalize import deduplicateJobs, normalizeJob
from app.algorithms.job_structuring.prompt import JOB_INDEX_PROMPT, JOB_PROMPT
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
    readPrompts = [JOB_PROMPT]
    if len(readBody) > 2000:
        readIndex = await createClient.chat_json(
            user_message=readBody,
            system_prompt=JOB_INDEX_PROMPT,
            temperature=0.1,
            max_tokens=4096,
        )
        readItems = readIndex.get("jobs", []) if isinstance(readIndex, dict) else []
        if not isinstance(readItems, list):
            raise TypeError("model job index must be a list")
        readTargets = []
        readKeys = set()
        for readItem in readItems:
            if not isinstance(readItem, dict):
                continue
            readTarget = {
                "companyName": str(readItem.get("companyName") or "").strip(),
                "positionName": str(readItem.get("positionName") or "").strip(),
            }
            readKey = (readTarget["companyName"].casefold(), readTarget["positionName"].casefold())
            if not all(readKey) or readKey in readKeys:
                continue
            readKeys.add(readKey)
            readTargets.append(readTarget)
        if int(readIndex.get("count") or 0) != len(readTargets):
            readWarnings.append("job index count corrected")
        readPrompts = [
            f"{JOB_PROMPT}\n\n本轮只抽取 TARGET_JOBS_JSON 中的岗位，禁止输出其他岗位。\n"
            f"TARGET_JOBS_JSON={json.dumps(readTargets[readStart:readStart + 8], ensure_ascii=False)}"
            for readStart in range(0, len(readTargets), 8)
        ]
    for readPrompt in readPrompts:
        readResponse = await createClient.chat_json(
            user_message=readBody,
            system_prompt=readPrompt,
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
