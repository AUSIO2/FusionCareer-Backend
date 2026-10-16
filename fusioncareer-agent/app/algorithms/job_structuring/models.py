"""Internal models for bounded, traceable job extraction."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class ArticleBlock:
    blockId: str
    headingPath: tuple[str, ...]
    text: str
    startOffset: int
    endOffset: int


@dataclass(frozen=True)
class ArticleContext:
    title: str = ""
    sourceUrl: str = ""
    commonText: str = ""


@dataclass
class JobTarget:
    targetId: str
    companyName: str
    positionName: str
    evidenceBlockIds: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class DetailBatch:
    targets: tuple[JobTarget, ...]
    blocks: tuple[ArticleBlock, ...]
    contextChars: int


@dataclass
class JobStructureTrace:
    blockCount: int = 0
    targetCount: int = 0
    indexCalls: int = 0
    detailCalls: int = 0
    splitRetries: int = 0
    promptTokens: int = 0
    completionTokens: int = 0

    def add(self, completion: Any, *, phase: str) -> None:
        if phase == "index":
            self.indexCalls += 1
        else:
            self.detailCalls += 1
        self.promptTokens += int(completion.promptTokens or 0)
        self.completionTokens += int(completion.completionTokens or 0)

    def to_dict(self) -> dict[str, Any]:
        return {
            "blockCount": self.blockCount,
            "targetCount": self.targetCount,
            "indexCalls": self.indexCalls,
            "detailCalls": self.detailCalls,
            "splitRetries": self.splitRetries,
            "promptTokens": self.promptTokens,
            "completionTokens": self.completionTokens,
        }
