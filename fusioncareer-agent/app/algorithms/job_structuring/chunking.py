"""Deterministic Markdown chunking for long recruitment articles."""

from __future__ import annotations

import hashlib
import re
from dataclasses import replace

from app.algorithms.job_structuring.models import ArticleBlock, ArticleContext, JobTarget

_HEADING = re.compile(r"^(#{1,4})\s+(.+?)\s*$")
_NUMBERED = re.compile(
    r"^\s*(?:[一二三四五六七八九十]+[、.]|\d{1,3}[、.)．]|（[一二三四五六七八九十\d]+）)\s*\S+"
)
_COMMON_LINE = re.compile(
    r"(截止|报名|投递|申请|网申|邮箱|邮件|联系人|联系电话|二维码|扫码|招聘官网|简历)"
)
_COMPILATION = re.compile(r"(招聘岗位汇总|招聘汇总|实习汇总|秋招速递|岗位合集|招聘信息汇总)")


def _create_block(text: str, heading: tuple[str, ...], start: int, end: int) -> ArticleBlock:
    return ArticleBlock("", heading, text, start, end)


def splitMarkdownSections(readText: str) -> list[ArticleBlock]:
    """Split at Markdown and strong numbered headings without dropping source text."""
    text = readText
    lines = text.splitlines(keepends=True)
    blocks: list[ArticleBlock] = []
    heading: list[str] = []
    buffer: list[str] = []
    buffer_start = 0
    offset = 0

    def flush(end: int) -> None:
        nonlocal buffer, buffer_start
        if buffer:
            value = "".join(buffer)
            if value:
                blocks.append(_create_block(value, tuple(heading), buffer_start, end))
        buffer = []
        buffer_start = end

    for line in lines:
        match = _HEADING.match(line.rstrip("\r\n"))
        numbered = bool(_NUMBERED.match(line))
        if match:
            flush(offset)
            level = len(match.group(1))
            heading[level - 1:] = [match.group(2).strip()]
            buffer_start = offset
        elif numbered and buffer and len("".join(buffer)) >= 800:
            flush(offset)
            buffer_start = offset
        buffer.append(line)
        offset += len(line)
    flush(len(text))
    if not blocks and text.strip():
        blocks.append(_create_block(text, (), 0, len(text)))
    return blocks


def _split_text_windows(readBlock: ArticleBlock, maxChars: int, overlapChars: int) -> list[ArticleBlock]:
    text = readBlock.text
    if len(text) <= maxChars:
        return [readBlock]
    result: list[ArticleBlock] = []
    start = 0
    while start < len(text):
        hard_end = min(start + maxChars, len(text))
        end = hard_end
        if hard_end < len(text):
            search_start = start + max(maxChars // 2, 1)
            candidates = [
                text.rfind("\n\n", search_start, hard_end),
                text.rfind("\n", search_start, hard_end),
                text.rfind("。", search_start, hard_end),
                text.rfind("；", search_start, hard_end),
            ]
            boundary = max(candidates)
            if boundary >= search_start:
                end = boundary + (2 if text[boundary:boundary + 2] == "\n\n" else 1)
        value = text[start:end]
        if value:
            result.append(_create_block(
                value,
                readBlock.headingPath,
                readBlock.startOffset + start,
                readBlock.startOffset + end,
            ))
        if end >= len(text):
            break
        update_start = max(end - overlapChars, start + 1)
        # Align only within text already covered by this window. Searching past
        # end could skip the next paragraph when its newline follows hard_end.
        newline = text.find("\n", update_start, end)
        start = newline + 1 if newline >= 0 else update_start
    return result


def splitOversizedBlock(
    readBlock: ArticleBlock,
    maxChars: int = 8000,
    overlapChars: int = 400,
) -> list[ArticleBlock]:
    if maxChars < 1000:
        raise ValueError("maxChars must be at least 1000")
    if overlapChars < 0 or overlapChars >= maxChars // 2:
        raise ValueError("overlapChars must be non-negative and less than half maxChars")
    return _split_text_windows(readBlock, maxChars, overlapChars)


def mergeSmallBlocks(
    readBlocks: list[ArticleBlock],
    targetChars: int = 6000,
    maxChars: int = 8000,
) -> list[ArticleBlock]:
    if not readBlocks:
        return []
    result: list[ArticleBlock] = []
    current = readBlocks[0]
    for block in readBlocks[1:]:
        gap = max(block.startOffset - current.endOffset, 0)
        combined_size = len(current.text) + gap + len(block.text)
        compatible = current.headingPath == block.headingPath or len(current.text) < targetChars // 3
        # Overlapping windows already repeat evidence deliberately. Joining
        # their strings would duplicate text and invalidate source offsets.
        if compatible and combined_size <= maxChars and block.startOffset == current.endOffset:
            current = ArticleBlock(
                "",
                current.headingPath or block.headingPath,
                current.text + ("\n" * min(gap, 2)) + block.text,
                current.startOffset,
                block.endOffset,
            )
        else:
            result.append(current)
            current = block
    result.append(current)
    return result


def splitArticleBlocks(
    readText: str,
    maxChars: int = 8000,
    overlapChars: int = 400,
    targetChars: int = 6000,
) -> list[ArticleBlock]:
    sections = splitMarkdownSections(readText)
    expanded = [
        part
        for section in sections
        for part in splitOversizedBlock(section, maxChars=maxChars, overlapChars=overlapChars)
    ]
    merged = mergeSmallBlocks(expanded, targetChars=targetChars, maxChars=maxChars)
    digest = hashlib.sha256(readText.encode("utf-8")).hexdigest()[:12]
    return [replace(block, blockId=f"{digest}-{index:04d}") for index, block in enumerate(merged, 1)]


def extractArticleContext(readText: str, sourceUrl: str = "") -> ArticleContext:
    lines = readText.splitlines()
    title = ""
    for line in lines:
        match = _HEADING.match(line.strip())
        if match:
            title = match.group(2).strip()
            break
        if line.strip():
            title = line.strip()[:200]
            break
    selected: list[str] = []
    for line in lines:
        clean = line.strip()
        if clean and _COMMON_LINE.search(clean) and clean not in selected:
            selected.append(clean[:500])
        if len(selected) >= 20:
            break
    intro = "\n".join(lines[:20]).strip()[:1500]
    common = "\n".join([intro, *selected]).strip()
    return ArticleContext(title=title, sourceUrl=sourceUrl, commonText=common[:5000])


def selectEvidenceBlocks(
    readTargets: list[JobTarget],
    readBlockMap: dict[str, ArticleBlock],
    maxChars: int = 12000,
) -> list[ArticleBlock]:
    selected: list[ArticleBlock] = []
    seen: set[str] = set()
    used = 0
    for target in readTargets:
        for block_id in target.evidenceBlockIds:
            block = readBlockMap.get(block_id)
            if block is None or block_id in seen:
                continue
            if selected and used + len(block.text) > maxChars:
                continue
            selected.append(block)
            seen.add(block_id)
            used += len(block.text)
    return selected


def isLongOrCompilationArticle(readText: str, shortArticleChars: int = 6000) -> bool:
    return len(readText) > shortArticleChars or bool(_COMPILATION.search(readText[:1200]))
