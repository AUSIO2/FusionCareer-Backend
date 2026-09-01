"""SQLite state for WeChat accounts and later crawl checkpoints."""

from __future__ import annotations

import hashlib
import sqlite3
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
