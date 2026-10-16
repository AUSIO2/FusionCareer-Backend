"""Bounded, traceable job structuring service."""

from __future__ import annotations

import asyncio
import hashlib
import logging
from dataclasses import replace
from typing import Any

from app.algorithms.job_structuring.chunking import (
    extractArticleContext,
    isLongOrCompilationArticle,
    selectEvidenceBlocks,
    splitArticleBlocks,
    splitOversizedBlock,
)
from app.algorithms.job_structuring.models import ArticleBlock, DetailBatch, JobStructureTrace, JobTarget
from app.algorithms.job_structuring.normalize import deduplicateJobs, normalizeJob, normalizeText
from app.algorithms.job_structuring.prompt import (
    JOB_INDEX_PROMPT,
    JOB_SINGLE_PROMPT,
    buildDetailSystemPrompt,
    buildDetailUserMessage,
    buildIndexUserMessage,
    buildSingleUserMessage,
)
from app.config import settings
from app.integrations.llm import (
    LLMClient,
    LLMCompletion,
    LLMInvalidJsonError,
    LLMOutputTruncatedError,
)

logger = logging.getLogger(__name__)


class JobCoverageError(RuntimeError):
    def __init__(
        self,
        message: str,
        *,
        items: list[dict] | None = None,
        warnings: list[str] | None = None,
        missing: set[str] | None = None,
    ):
        super().__init__(message)
        self.items = items or []
        self.warnings = warnings or []
        self.missing = missing or set()


def _setting(name: str, default: Any) -> Any:
    return getattr(settings, name, default)


def _target_key(company: str, position: str) -> tuple[str, str]:
    return normalizeText(company).casefold(), normalizeText(position).casefold()


def _target_id(company: str, position: str) -> str:
    value = "\x00".join(_target_key(company, position))
    return "job-" + hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


async def _call_json_meta(
    readClient: Any,
    *,
    user_message: str,
    system_prompt: str,
    max_tokens: int,
    operation: str,
) -> tuple[dict, LLMCompletion]:
    method = getattr(readClient, "chat_json_with_meta", None)
    if method is not None:
        return await method(
            user_message=user_message,
            system_prompt=system_prompt,
            temperature=0.1,
            max_tokens=max_tokens,
            operation=operation,
        )
    response = await readClient.chat_json(
        user_message=user_message,
        system_prompt=system_prompt,
        temperature=0.1,
        max_tokens=max_tokens,
    )
    return response, LLMCompletion("", "fake", "stop")


def mergeJobTargets(
    readTargets: list[JobTarget],
    readBlockMap: dict[str, ArticleBlock] | None = None,
) -> list[JobTarget]:
    merged: dict[tuple[str, str], JobTarget] = {}
    for target in readTargets:
        key = _target_key(target.companyName, target.positionName)
        if not all(key):
            continue
        existing = merged.get(key)
        if existing is None:
            merged[key] = JobTarget(
                targetId=_target_id(target.companyName, target.positionName),
                companyName=normalizeText(target.companyName),
                positionName=normalizeText(target.positionName),
                evidenceBlockIds=list(dict.fromkeys(target.evidenceBlockIds)),
            )
        else:
            for block_id in target.evidenceBlockIds:
                if block_id in existing.evidenceBlockIds:
                    continue
                block = (readBlockMap or {}).get(block_id)
                overlaps = False
                if block is not None:
                    for existing_id in existing.evidenceBlockIds:
                        evidence = (readBlockMap or {}).get(existing_id)
                        if evidence is not None and max(block.startOffset, evidence.startOffset) \
                                < min(block.endOffset, evidence.endOffset):
                            overlaps = True
                            break
                if not overlaps:
                    existing.evidenceBlockIds.append(block_id)
    return list(merged.values())


async def indexArticleBlocks(
    readBlocks: list[ArticleBlock],
    readContext: Any,
    readClient: Any,
    readTrace: JobStructureTrace,
) -> tuple[list[JobTarget], list[str]]:
    semaphore = asyncio.Semaphore(int(_setting("job_structure_index_concurrency", 3)))

    async def index_block(block: ArticleBlock) -> tuple[list[JobTarget], list[str]]:
        try:
            async with semaphore:
                response, completion = await _call_json_meta(
                    readClient,
                    user_message=buildIndexUserMessage(block, readContext),
                    system_prompt=JOB_INDEX_PROMPT,
                    max_tokens=2048,
                    operation="job_index",
                )
                readTrace.add(completion, phase="index")
        except (LLMOutputTruncatedError, LLMInvalidJsonError):
            if len(block.text) <= 2000:
                raise
            readTrace.splitRetries += 1
            parts = splitOversizedBlock(
                block,
                maxChars=max(len(block.text) // 2, 1000),
                overlapChars=min(200, max(len(block.text) // 8, 0)),
            )
            parts = [replace(part, blockId=f"{block.blockId}-{index}") for index, part in enumerate(parts, 1)]
            split_results = await asyncio.gather(*(index_block(part) for part in parts))
            split_targets = [target for targets, _ in split_results for target in targets]
            for target in split_targets:
                target.evidenceBlockIds = [block.blockId]
            return (
                split_targets,
                [warning for _, warnings in split_results for warning in warnings],
            )
        items = response.get("jobs", []) if isinstance(response, dict) else []
        if not isinstance(items, list):
            raise TypeError("model job index must be a list")
        targets: list[JobTarget] = []
        warnings: list[str] = []
        for item in items:
            if not isinstance(item, dict):
                continue
            company = normalizeText(item.get("companyName"))
            position = normalizeText(item.get("positionName"))
            if not company or not position:
                continue
            evidence = item.get("evidenceBlockIds")
            block_ids = [value for value in evidence if value == block.blockId] \
                if isinstance(evidence, list) else []
            targets.append(JobTarget("", company, position, block_ids or [block.blockId]))
        if int(response.get("count") or 0) != len(targets):
            warnings.append(f"job index count corrected for {block.blockId}")
        return targets, warnings

    results = await asyncio.gather(*(index_block(block) for block in readBlocks))
    targets = mergeJobTargets(
        [target for block_targets, _ in results for target in block_targets],
        {block.blockId: block for block in readBlocks},
    )
    warnings = [warning for _, block_warnings in results for warning in block_warnings]
    return targets, warnings


def buildDetailBatches(
    readTargets: list[JobTarget],
    readBlockMap: dict[str, ArticleBlock],
    maxJobs: int | None = None,
    maxContextChars: int | None = None,
) -> list[DetailBatch]:
    max_jobs = int(maxJobs or _setting("job_structure_detail_batch_jobs", 4))
    max_chars = int(maxContextChars or _setting("job_structure_detail_context_chars", 12000))
    batches: list[DetailBatch] = []
    current: list[JobTarget] = []

    def blocks_for(targets: list[JobTarget]) -> list[ArticleBlock]:
        ids = list(dict.fromkeys(
            block_id for target in targets for block_id in target.evidenceBlockIds
        ))
        return [readBlockMap[block_id] for block_id in ids if block_id in readBlockMap]

    def append_current() -> None:
        nonlocal current
        if not current:
            return
        current_blocks = blocks_for(current)
        batches.append(DetailBatch(
            tuple(current), tuple(current_blocks), sum(len(block.text) for block in current_blocks)
        ))
        current = []

    for target in readTargets:
        target_blocks = blocks_for([target])
        if sum(len(block.text) for block in target_blocks) > max_chars:
            append_current()
            block_group: list[ArticleBlock] = []
            block_chars = 0
            for block in target_blocks:
                if block_group and block_chars + len(block.text) > max_chars:
                    batches.append(DetailBatch((target,), tuple(block_group), block_chars))
                    block_group = []
                    block_chars = 0
                block_group.append(block)
                block_chars += len(block.text)
            if block_group:
                batches.append(DetailBatch((target,), tuple(block_group), block_chars))
            continue
        candidate = [*current, target]
        blocks = blocks_for(candidate)
        context_chars = sum(len(block.text) for block in blocks)
        if current and (len(candidate) > max_jobs or context_chars > max_chars):
            append_current()
            current = [target]
        else:
            current = candidate
    append_current()
    return batches


def _read_detail_items(response: dict, readTargets: list[JobTarget]) -> tuple[list[dict], list[str]]:
    items = response.get("jobs", []) if isinstance(response, dict) else []
    if not isinstance(items, list):
        raise TypeError("model jobs must be a list")
    expected = {target.targetId for target in readTargets}
    seen: set[str] = set()
    result: list[dict] = []
    warnings = list(response.get("warnings", []) if isinstance(response, dict) else [])
    for item in items:
        if not isinstance(item, dict):
            continue
        value = dict(item)
        target_id = normalizeText(value.pop("targetId", ""))
        if target_id not in expected:
            warnings.append(f"unknown targetId: {target_id or '<empty>'}")
            continue
        if target_id in seen:
            warnings.append(f"duplicate targetId: {target_id}")
            continue
        seen.add(target_id)
        value["_targetId"] = target_id
        result.append(value)
    missing = sorted(expected - seen)
    if missing:
        raise JobCoverageError(
            "missing targetIds: " + ", ".join(missing),
            items=result,
            warnings=warnings,
            missing=set(missing),
        )
    return result, warnings


def mergePartialJobDetails(readItems: list[dict]) -> list[dict]:
    merged: dict[str, dict] = {}
    for item in readItems:
        target_id = str(item.get("_targetId") or "")
        current = merged.setdefault(target_id, {"_targetId": target_id})
        for key, value in item.items():
            if key == "_targetId" or value in (None, "", []):
                continue
            if current.get(key) in (None, "", []):
                current[key] = value
    return list(merged.values())


async def extractDetailBatch(
    readBatch: DetailBatch,
    readContext: Any,
    readClient: Any,
    readTrace: JobStructureTrace,
) -> tuple[list[dict], list[str]]:
    response, completion = await _call_json_meta(
        readClient,
        user_message=buildDetailUserMessage(readBatch, readContext),
        system_prompt=buildDetailSystemPrompt(list(readBatch.targets)),
        max_tokens=8192,
        operation="job_detail",
    )
    readTrace.add(completion, phase="detail")
    return _read_detail_items(response, list(readBatch.targets))


async def extractDetailBatchAdaptive(
    readBatch: DetailBatch,
    readContext: Any,
    readClient: Any,
    readTrace: JobStructureTrace,
) -> tuple[list[dict], list[str]]:
    try:
        return await extractDetailBatch(readBatch, readContext, readClient, readTrace)
    except JobCoverageError as error:
        missing_targets = [target for target in readBatch.targets if target.targetId in error.missing]
        if not missing_targets or len(readBatch.targets) == 1:
            raise
        readTrace.splitRetries += 1
        block_map = {block.blockId: block for block in readBatch.blocks}
        blocks = selectEvidenceBlocks(missing_targets, block_map, max(readBatch.contextChars, 1))
        retry = DetailBatch(
            tuple(missing_targets), tuple(blocks), sum(len(block.text) for block in blocks)
        )
        retry_items, retry_warnings = await extractDetailBatchAdaptive(
            retry, readContext, readClient, readTrace
        )
        return [*error.items, *retry_items], [*error.warnings, *retry_warnings]
    except (LLMOutputTruncatedError, LLMInvalidJsonError):
        readTrace.splitRetries += 1
        if len(readBatch.targets) > 1:
            middle = len(readBatch.targets) // 2
            results: list[dict] = []
            result_warnings: list[str] = []
            block_map = {block.blockId: block for block in readBatch.blocks}
            for targets in (readBatch.targets[:middle], readBatch.targets[middle:]):
                blocks = selectEvidenceBlocks(list(targets), block_map, max(readBatch.contextChars, 1))
                child = DetailBatch(tuple(targets), tuple(blocks), sum(len(block.text) for block in blocks))
                items, child_warnings = await extractDetailBatchAdaptive(
                    child, readContext, readClient, readTrace
                )
                results.extend(items)
                result_warnings.extend(child_warnings)
            return results, result_warnings
        if len(readBatch.blocks) > 1:
            results = []
            result_warnings = []
            middle = len(readBatch.blocks) // 2
            for partial_blocks in (readBatch.blocks[:middle], readBatch.blocks[middle:]):
                child = DetailBatch(
                    readBatch.targets,
                    tuple(partial_blocks),
                    sum(len(block.text) for block in partial_blocks),
                )
                try:
                    items, child_warnings = await extractDetailBatchAdaptive(
                        child, readContext, readClient, readTrace
                    )
                except JobCoverageError:
                    continue
                results.extend(items)
                result_warnings.extend(child_warnings)
            merged = mergePartialJobDetails(results)
            if not merged:
                raise JobCoverageError(f"no details returned for {readBatch.targets[0].targetId}")
            return merged, result_warnings
        raise


def finalizeJobs(
    readItems: list[dict],
    readSourceUrl: str,
    readSourceType: str,
) -> tuple[list[dict], list[str]]:
    jobs: list[dict] = []
    warnings: list[str] = []
    for index, item in enumerate(readItems):
        value = {key: data for key, data in item.items() if key != "_targetId"}
        job, errors = normalizeJob(value, readSourceUrl, readSourceType)
        if errors:
            warnings.extend(f"job {index + 1}: {error}" for error in errors)
        else:
            jobs.append(job)
    return deduplicateJobs(jobs), warnings


async def structureShortArticle(
    readText: str,
    readSourceUrl: str,
    readSourceType: str,
    readClient: Any,
    readTrace: JobStructureTrace,
) -> dict[str, Any]:
    context = extractArticleContext(readText, readSourceUrl)
    response, completion = await _call_json_meta(
        readClient,
        user_message=buildSingleUserMessage(readText, context),
        system_prompt=JOB_SINGLE_PROMPT,
        max_tokens=8192,
        operation="job_single",
    )
    readTrace.add(completion, phase="detail")
    items = response.get("jobs", []) if isinstance(response, dict) else []
    if not isinstance(items, list):
        raise TypeError("model jobs must be a list")
    jobs, warnings = finalizeJobs(
        [item for item in items if isinstance(item, dict)], readSourceUrl, readSourceType
    )
    warnings.extend(response.get("warnings", []) if isinstance(response, dict) else [])
    return {"jobs": jobs, "warnings": warnings}


async def structureLongArticle(
    readText: str,
    readSourceUrl: str,
    readSourceType: str,
    readClient: Any,
    readTrace: JobStructureTrace,
) -> dict[str, Any]:
    blocks = splitArticleBlocks(
        readText,
        maxChars=int(_setting("job_structure_block_max_chars", 8000)),
        overlapChars=int(_setting("job_structure_block_overlap_chars", 400)),
    )
    readTrace.blockCount = len(blocks)
    context = extractArticleContext(readText, readSourceUrl)
    targets, warnings = await indexArticleBlocks(blocks, context, readClient, readTrace)
    readTrace.targetCount = len(targets)
    if not targets:
        return {"jobs": [], "warnings": warnings}
    block_map = {block.blockId: block for block in blocks}
    items: list[dict] = []
    for batch in buildDetailBatches(targets, block_map):
        batch_items, batch_warnings = await extractDetailBatchAdaptive(
            batch, context, readClient, readTrace
        )
        items.extend(batch_items)
        warnings.extend(batch_warnings)
    items = mergePartialJobDetails(items)
    returned = {str(item.get("_targetId") or "") for item in items}
    expected = {target.targetId for target in targets}
    if returned != expected:
        raise JobCoverageError("incomplete target coverage after extraction")
    jobs, normalize_warnings = finalizeJobs(items, readSourceUrl, readSourceType)
    warnings.extend(normalize_warnings)
    return {"jobs": jobs, "warnings": warnings}


async def structureJobs(
    readText: str,
    readSourceUrl: str = "",
    readSourceType: str = "PLATFORM",
    readClient: LLMClient | None = None,
) -> dict[str, Any]:
    if not readText.strip():
        raise ValueError("job text is required")
    client = readClient or LLMClient()
    trace = JobStructureTrace()
    long_article = isLongOrCompilationArticle(
        readText, int(_setting("job_structure_short_article_chars", 6000))
    )
    try:
        if not long_article:
            try:
                result = await structureShortArticle(
                    readText, readSourceUrl, readSourceType, client, trace
                )
            except (LLMOutputTruncatedError, LLMInvalidJsonError):
                trace.splitRetries += 1
                result = await structureLongArticle(
                    readText, readSourceUrl, readSourceType, client, trace
                )
        else:
            result = await structureLongArticle(
                readText, readSourceUrl, readSourceType, client, trace
            )
        logger.info("job structure completed source=%s trace=%s", readSourceUrl, trace.to_dict())
        return result
    except Exception:
        logger.warning("job structure failed source=%s trace=%s", readSourceUrl, trace.to_dict())
        raise
