"""Asynchronous resume parsing service."""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any

from app.algorithms.resume_parser.extract import extractText
from app.algorithms.resume_parser.normalize import normalizeResume
from app.algorithms.resume_parser.prompt import RESUME_PROMPT
from app.integrations.llm import LLMClient


async def parseText(readText: str, readClient: LLMClient | None = None) -> dict[str, Any]:
    if not readText.strip():
        raise ValueError("resume text is empty")
    createClient = readClient or LLMClient()
    readResponse = await createClient.chat_json(
        user_message=readText[:40_000],
        system_prompt=RESUME_PROMPT,
        temperature=0.1,
    )
    if not isinstance(readResponse, dict):
        raise ValueError("resume response must be an object")
    return normalizeResume(readResponse)


async def parseResume(readPath: Path, readClient: LLMClient | None = None) -> dict[str, Any]:
    readText = await asyncio.to_thread(extractText, readPath)
    return await parseText(readText, readClient)
