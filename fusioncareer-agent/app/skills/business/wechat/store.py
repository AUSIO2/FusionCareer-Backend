"""SQLite state for WeChat accounts and later crawl checkpoints."""

from __future__ import annotations

import hashlib
import sqlite3
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path


@dataclass(frozen=True)
class WechatAccount:
    fakeid: str
    name: str
    enabled: bool
    nameSource: str
    lastArticleUrl: str


class WechatStore:
    def __init__(self, readPath: Path) -> None:
        self.readPath = readPath
        self.readPath.parent.mkdir(parents=True, exist_ok=True)
        self.createSchema()

    def openDatabase(self) -> sqlite3.Connection:
        readDatabase = sqlite3.connect(self.readPath, timeout=30)
        readDatabase.row_factory = sqlite3.Row
        readDatabase.execute("PRAGMA journal_mode=WAL")
        return readDatabase

    def createSchema(self) -> None:
        with self.openDatabase() as updateDatabase:
            updateDatabase.execute(
                """
                CREATE TABLE IF NOT EXISTS accounts (
                    fakeid TEXT PRIMARY KEY,
                    name TEXT NOT NULL DEFAULT '',
                    name_source TEXT NOT NULL DEFAULT 'AUTO',
                    enabled INTEGER NOT NULL DEFAULT 1,
                    last_article_url TEXT NOT NULL DEFAULT '',
                    updated_at TEXT NOT NULL
                )
                """
            )
            updateDatabase.execute(
                """
                CREATE TABLE IF NOT EXISTS articles (
                    url TEXT PRIMARY KEY,
                    fakeid TEXT NOT NULL,
                    title TEXT NOT NULL DEFAULT '',
                    published_at TEXT NOT NULL DEFAULT '',
                    markdown_path TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    structured INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL
                )
                """
            )
            updateDatabase.execute(
                """
                CREATE TABLE IF NOT EXISTS runs (
                    id TEXT PRIMARY KEY,
                    mode TEXT NOT NULL,
                    fakeid TEXT NOT NULL,
                    started_at TEXT NOT NULL,
                    finished_at TEXT,
                    status TEXT NOT NULL,
                    added_count INTEGER NOT NULL DEFAULT 0,
                    error TEXT NOT NULL DEFAULT ''
                )
                """
            )

    def importAccounts(self, readFakeids: Path, readNames: Path) -> int:
        if not readFakeids.is_file():
            return 0
        readIds = [readLine.strip() for readLine in readFakeids.read_text(encoding="utf-8").splitlines()
                   if readLine.strip()]
        readValues = []
        if readNames.is_file():
            readValues = [readLine.strip() for readLine in readNames.read_text(encoding="utf-8").splitlines()]
        for readIndex, readFakeid in enumerate(readIds):
            readName = readValues[readIndex] if readIndex < len(readValues) else ""
            self.saveAccount(readFakeid, readName, bool(readName))
        return len(readIds)

    def saveAccount(self, readFakeid: str, readName: str = "", readManual: bool = False) -> None:
        if not readFakeid.strip():
            raise ValueError("fakeid is required")
        readSource = "MANUAL" if readManual else "AUTO"
        readTime = datetime.now(timezone.utc).isoformat()
        with self.openDatabase() as updateDatabase:
            updateDatabase.execute(
                """
                INSERT INTO accounts (fakeid, name, name_source, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(fakeid) DO UPDATE SET
                    name = CASE
                        WHEN accounts.name_source = 'MANUAL' THEN accounts.name
                        WHEN excluded.name <> '' THEN excluded.name
                        ELSE accounts.name
                    END,
                    name_source = CASE
                        WHEN accounts.name_source = 'MANUAL' THEN accounts.name_source
                        WHEN excluded.name <> '' THEN excluded.name_source
                        ELSE accounts.name_source
                    END,
                    updated_at = excluded.updated_at
                """,
                (readFakeid.strip(), readName.strip(), readSource, readTime),
            )

    def saveName(self, readFakeid: str, updateName: str, updateManual: bool = False) -> None:
        self.saveAccount(readFakeid)
        updateSource = "MANUAL" if updateManual else "AUTO"
        updateTime = datetime.now(timezone.utc).isoformat()
        with self.openDatabase() as updateDatabase:
            updateDatabase.execute(
                """
                UPDATE accounts
                SET name = ?, name_source = ?, updated_at = ?
                WHERE fakeid = ? AND (? = 'MANUAL' OR name_source <> 'MANUAL')
                """,
                (updateName.strip(), updateSource, updateTime, readFakeid.strip(), updateSource),
            )

    def readAccounts(self, readEnabled: bool = True) -> list[WechatAccount]:
        readQuery = "SELECT * FROM accounts"
        if readEnabled:
            readQuery += " WHERE enabled = 1"
        readQuery += " ORDER BY fakeid"
        with self.openDatabase() as readDatabase:
            readRows = readDatabase.execute(readQuery).fetchall()
        return [self.mapAccount(readRow) for readRow in readRows]

    def readAccount(self, readFakeid: str) -> WechatAccount:
        with self.openDatabase() as readDatabase:
            readRow = readDatabase.execute(
                "SELECT * FROM accounts WHERE fakeid = ?", (readFakeid,)
            ).fetchone()
        if readRow is None:
            raise KeyError(readFakeid)
        return self.mapAccount(readRow)

    def hasArticle(self, readUrl: str) -> bool:
        with self.openDatabase() as readDatabase:
            readRow = readDatabase.execute(
                "SELECT 1 FROM articles WHERE url = ?", (readUrl,)
            ).fetchone()
        return readRow is not None

    def hasArticleRecord(self, readFakeid: str, readArticle: dict) -> bool:
        with self.openDatabase() as readDatabase:
            readRow = readDatabase.execute(
                """
                SELECT 1 FROM articles
                WHERE url = ? OR (fakeid = ? AND title = ? AND published_at = ?)
                """,
                (
                    readArticle.get("link") or "",
                    readFakeid,
                    readArticle.get("title") or "",
                    str(readArticle.get("create_time") or ""),
                ),
            ).fetchone()
        return readRow is not None

    def saveArticle(
        self,
        readFakeid: str,
        readArticle: dict,
        readPath: Path,
        readHash: str,
    ) -> None:
        readTime = datetime.now(timezone.utc).isoformat()
        with self.openDatabase() as updateDatabase:
            updateDatabase.execute(
                """
                INSERT INTO articles
                    (url, fakeid, title, published_at, markdown_path, content_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(url) DO UPDATE SET
                    title = excluded.title,
                    published_at = excluded.published_at,
                    markdown_path = excluded.markdown_path,
                    content_hash = excluded.content_hash
                """,
                (
                    readArticle.get("link") or "",
                    readFakeid,
                    readArticle.get("title") or "",
                    str(readArticle.get("create_time") or ""),
                    str(readPath),
                    readHash,
                    readTime,
                ),
            )

    def saveCheckpoint(self, readFakeid: str, updateUrl: str) -> None:
        updateTime = datetime.now(timezone.utc).isoformat()
        with self.openDatabase() as updateDatabase:
            updateDatabase.execute(
                "UPDATE accounts SET last_article_url = ?, updated_at = ? WHERE fakeid = ?",
                (updateUrl, updateTime, readFakeid),
            )

    def startRun(self, readMode: str, readFakeid: str) -> str:
        createId = str(uuid.uuid4())
        createTime = datetime.now(timezone.utc).isoformat()
        with self.openDatabase() as updateDatabase:
            updateDatabase.execute(
                """
                INSERT INTO runs (id, mode, fakeid, started_at, status)
                VALUES (?, ?, ?, ?, 'RUNNING')
                """,
                (createId, readMode, readFakeid, createTime),
            )
        return createId

    def finishRun(
        self,
        updateId: str,
        updateStatus: str,
        updateCount: int = 0,
        updateError: str = "",
    ) -> None:
        updateTime = datetime.now(timezone.utc).isoformat()
        with self.openDatabase() as updateDatabase:
            updateDatabase.execute(
                """
                UPDATE runs
                SET finished_at = ?, status = ?, added_count = ?, error = ?
                WHERE id = ?
                """,
                (updateTime, updateStatus, updateCount, updateError[:1000], updateId),
            )

    def readPendingArticles(self, readLimit: int = 20) -> list[dict]:
        with self.openDatabase() as readDatabase:
            readRows = readDatabase.execute(
                """
                SELECT url, fakeid, title, markdown_path
                FROM articles
                WHERE structured = 0
                ORDER BY created_at
                LIMIT ?
                """,
                (readLimit,),
            ).fetchall()
        return [dict(readRow) for readRow in readRows]

    def markStructured(self, readUrl: str) -> None:
        with self.openDatabase() as updateDatabase:
            updateDatabase.execute(
                "UPDATE articles SET structured = 1 WHERE url = ?", (readUrl,)
            )

    def deferArticle(self, readUrl: str) -> None:
        updateTime = datetime.now(timezone.utc).isoformat()
        with self.openDatabase() as updateDatabase:
            updateDatabase.execute(
                "UPDATE articles SET created_at = ? WHERE url = ?", (updateTime, readUrl)
            )

    @staticmethod
    def buildFallback(readFakeid: str) -> str:
        readHash = hashlib.sha256(readFakeid.encode("utf-8")).hexdigest()[:8]
        return f"account_{readHash}"

    @classmethod
    def mapAccount(cls, readRow: sqlite3.Row) -> WechatAccount:
        readName = readRow["name"] or cls.buildFallback(readRow["fakeid"])
        return WechatAccount(
            fakeid=readRow["fakeid"],
            name=readName,
            enabled=bool(readRow["enabled"]),
            nameSource=readRow["name_source"],
            lastArticleUrl=readRow["last_article_url"],
        )
