"""Pre-extraction near-duplicate checks against successfully persisted articles.

MinHash/LSH only recalls candidates. Every skip is decided using the exact
character n-gram Jaccard value and the original canonical Markdown. Signatures
are stored by CrawlStore together with completion, never before extraction.
"""

from __future__ import annotations

import hashlib
import inspect
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from datasketch import MinHash, MinHashLSH

_MARKDOWN = re.compile(r"[#*_`>\-\[\]()!]|https?://\S+")
_WHITESPACE = re.compile(r"\s+")
_MINHASH_OPTIONS = {"scheme": "legacy"} if "scheme" in inspect.signature(MinHash).parameters else {}


def normalizeText(text: str) -> str:
    return _WHITESPACE.sub("", _MARKDOWN.sub(" ", text).lower())


def textNgrams(text: str, n: int = 3) -> set[str]:
    text = normalizeText(text)
    if not text:
        return set()
    if len(text) <= n:
        return {text}
    return {text[offset:offset + n] for offset in range(len(text) - n + 1)}


def exactJaccard(first: set[str], second: set[str]) -> float:
    if not first or not second:
        return 0.0
    return len(first & second) / len(first | second)


@dataclass(frozen=True)
class ArticleSignature:
    url: str
    markdownPath: str
    contentDigest: str
    grams: set[str]
    minhash: Any
    version: str

    def record(self) -> dict[str, str]:
        return {
            "version": self.version,
            "contentDigest": self.contentDigest,
            "hashvalues": json.dumps([int(value) for value in self.minhash.hashvalues]),
        }


@dataclass(frozen=True)
class DuplicateMatch:
    winnerUrl: str
    winnerDigest: str
    score: float
    version: str


class ArticleDeduplicator:
    def __init__(self, settings) -> None:
        self.enabled = getattr(settings, "article_dedup_enabled", True)
        self.ngram = getattr(settings, "article_dedup_ngram", 3)
        self.numPerm = getattr(settings, "article_dedup_num_perm", 128)
        self.lshThreshold = getattr(settings, "article_dedup_lsh_threshold", 0.6)
        self.jaccardThreshold = getattr(settings, "article_dedup_jaccard_threshold", 0.85)
        self.version = (
            f"datasketch-legacy-char-exact-v1:n={self.ngram}:p={self.numPerm}:"
            f"lsh={self.lshThreshold:g}:j={self.jaccardThreshold:g}"
        )
        self._history: dict[str, ArticleSignature] = {}
        self._historyKeys: dict[str, tuple[str, str, str]] = {}
        self._historyIndex = MinHashLSH(threshold=self.lshThreshold, num_perm=self.numPerm)

    def signature(self, url: str, path: str, text: str) -> ArticleSignature:
        grams = textNgrams(text, self.ngram)
        # Datasketch 2 changes its default permutation scheme. Explicit legacy
        # mode keeps signatures compatible with supported 1.x installations.
        signature = MinHash(num_perm=self.numPerm, **_MINHASH_OPTIONS)
        for gram in sorted(grams):
            signature.update(gram.encode("utf-8"))
        return ArticleSignature(
            url, path, hashlib.sha256(text.encode("utf-8")).hexdigest(),
            grams, signature, self.version,
        )

    def loadHistory(self, store) -> dict[str, ArticleSignature]:
        rows = store.readArticleDedupHistory()
        active = {row["url"] for row in rows}
        for url in set(self._history) - active:
            self._historyIndex.remove(url)
            del self._history[url]
            del self._historyKeys[url]
        for row in rows:
            url = row["url"]
            key = (row["content_digest"], row["version"], row["markdown_path"])
            if self._historyKeys.get(url) == key:
                continue
            if url in self._history:
                self._historyIndex.remove(url)
                del self._history[url]
                del self._historyKeys[url]
            try:
                if row["version"] == self.version:
                    values = json.loads(row["hashvalues"])
                    if not isinstance(values, list) or len(values) != self.numPerm:
                        continue
                    signature = MinHash(num_perm=self.numPerm, hashvalues=values, **_MINHASH_OPTIONS)
                    document = ArticleSignature(
                        url, row["markdown_path"], row["content_digest"], set(), signature, self.version,
                    )
                else:
                    # Rebuild on parameter/normalization changes; compare to the
                    # same committed body, never index a modified source file.
                    text = Path(row["markdown_path"]).read_text(encoding="utf-8")
                    document = self.signature(url, row["markdown_path"], text)
                    if document.contentDigest != row["content_digest"] or not document.grams:
                        continue
                self._historyIndex.insert(url, document.minhash)
                self._history[url] = document
                self._historyKeys[url] = key
            except (OSError, UnicodeError, ValueError, TypeError):
                continue
        return self._history

    def candidates(self, documents: dict[str, ArticleSignature]) -> MinHashLSH:
        index = MinHashLSH(threshold=self.lshThreshold, num_perm=self.numPerm)
        for document in documents.values():
            if document.grams:
                index.insert(document.url, document.minhash)
        return index

    def dependencies(self, documents: dict[str, ArticleSignature]) -> dict[str, list[str]]:
        """Wait for earlier near candidates, without treating them as completed."""
        index = self.candidates(documents)
        earlier: set[str] = set()
        dependencies = {}
        for document in documents.values():
            dependencies[document.url] = sorted(
                url for url in index.query(document.minhash)
                if url in earlier and exactJaccard(document.grams, documents[url].grams) >= self.jaccardThreshold
            ) if document.grams else []
            earlier.add(document.url)
        return dependencies

    def findDuplicate(self, document: ArticleSignature, store) -> DuplicateMatch | None:
        if not document.grams:
            return None
        # Refresh from SQLite so another completed batch is immediately visible.
        history = self.loadHistory(store)
        best = None
        for url in sorted(self._historyIndex.query(document.minhash)):
            if url == document.url:
                continue
            winner = history[url]
            try:
                text = Path(winner.markdownPath).read_text(encoding="utf-8")
            except (OSError, UnicodeError):
                continue
            if hashlib.sha256(text.encode("utf-8")).hexdigest() != winner.contentDigest:
                continue
            score = exactJaccard(document.grams, textNgrams(text, self.ngram))
            if score >= self.jaccardThreshold and (best is None or score > best.score):
                best = DuplicateMatch(url, winner.contentDigest, score, self.version)
        return best
