"""SQLite state shared by official-site and WeChat crawlers."""

from __future__ import annotations

import hashlib
import sqlite3
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path


@dataclass(frozen=True)
class CrawlAccount:
    fakeid: str
    name: str
    enabled: bool
    nameSource: str
    lastArticleUrl: str


class CrawlStore:
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
            updateDatabase.execute("BEGIN IMMEDIATE")
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

            columns = {row[1] for row in updateDatabase.execute("PRAGMA table_info(articles)")}
            if "prefilter_reason" not in columns:
                updateDatabase.execute("ALTER TABLE articles ADD COLUMN prefilter_reason TEXT NOT NULL DEFAULT ''")
            if "prefilter_version" not in columns:
                updateDatabase.execute("ALTER TABLE articles ADD COLUMN prefilter_version TEXT NOT NULL DEFAULT ''")
            structure_columns = {
                "structure_status": "TEXT NOT NULL DEFAULT 'PENDING'",
                "structure_attempts": "INTEGER NOT NULL DEFAULT 0",
                "structure_next_retry_at": "TEXT NOT NULL DEFAULT ''",
                "structure_last_attempt_at": "TEXT NOT NULL DEFAULT ''",
                "structure_last_error": "TEXT NOT NULL DEFAULT ''",
                "structure_claim_id": "TEXT NOT NULL DEFAULT ''",
                "structure_claimed_at": "TEXT NOT NULL DEFAULT ''",
            }
            added_status = "structure_status" not in columns
            for name, declaration in structure_columns.items():
                if name not in columns:
                    updateDatabase.execute(f"ALTER TABLE articles ADD COLUMN {name} {declaration}")
            if added_status:
                updateDatabase.execute(
                    "UPDATE articles SET structure_status = CASE "
                    "WHEN structured = 1 THEN 'COMPLETED' ELSE 'PENDING' END"
                )
            dedup_columns = {
                "article_duplicate_of": "TEXT NOT NULL DEFAULT ''",
                "article_duplicate_score": "REAL NOT NULL DEFAULT 0",
                "article_dedup_version": "TEXT NOT NULL DEFAULT ''",
                "article_dedup_bypass": "INTEGER NOT NULL DEFAULT 0",
            }
            for name, declaration in dedup_columns.items():
                if name not in columns:
                    updateDatabase.execute(f"ALTER TABLE articles ADD COLUMN {name} {declaration}")
            updateDatabase.execute(
                """
                CREATE TABLE IF NOT EXISTS article_dedup_index (
                    url TEXT PRIMARY KEY REFERENCES articles(url),
                    content_digest TEXT NOT NULL,
                    version TEXT NOT NULL,
                    hashvalues TEXT NOT NULL,
                    completed_at TEXT NOT NULL
                )
                """
            )
            updateDatabase.execute(
                """
                CREATE TABLE IF NOT EXISTS article_dedup_skips (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    article_url TEXT NOT NULL,
                    winner_url TEXT NOT NULL,
                    score REAL NOT NULL,
                    version TEXT NOT NULL,
                    article_digest TEXT NOT NULL,
                    winner_digest TEXT NOT NULL,
                    skipped_at TEXT NOT NULL
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
        readTime = datetime.now(UTC).isoformat()
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
        updateTime = datetime.now(UTC).isoformat()
        with self.openDatabase() as updateDatabase:
            updateDatabase.execute(
                """
                UPDATE accounts
                SET name = ?, name_source = ?, updated_at = ?
                WHERE fakeid = ? AND (? = 'MANUAL' OR name_source <> 'MANUAL')
                """,
                (updateName.strip(), updateSource, updateTime, readFakeid.strip(), updateSource),
            )

    def readAccounts(self, readEnabled: bool = True) -> list[CrawlAccount]:
        readQuery = "SELECT * FROM accounts"
        if readEnabled:
            readQuery += " WHERE enabled = 1"
        readQuery += " ORDER BY fakeid"
        with self.openDatabase() as readDatabase:
            readRows = readDatabase.execute(readQuery).fetchall()
        return [self.mapAccount(readRow) for readRow in readRows]

    def readAccount(self, readFakeid: str) -> CrawlAccount:
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
        readTime = datetime.now(UTC).isoformat()
        with self.openDatabase() as updateDatabase:
            updateDatabase.execute("BEGIN IMMEDIATE")
            existing = updateDatabase.execute(
                "SELECT content_hash FROM articles WHERE url=?", (readArticle.get("link") or "",),
            ).fetchone()
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
            if existing is not None and existing["content_hash"] != readHash:
                updateDatabase.execute(
                    """
                    UPDATE articles SET structured=0, structure_status='PENDING',
                        structure_attempts=0, structure_next_retry_at='', structure_last_error='',
                        structure_claim_id='', structure_claimed_at='', prefilter_reason='', prefilter_version='',
                        article_duplicate_of='', article_duplicate_score=0, article_dedup_version='',
                        article_dedup_bypass=0
                    WHERE url=?
                    """,
                    (readArticle.get("link") or "",),
                )
                updateDatabase.execute(
                    "DELETE FROM article_dedup_index WHERE url=?", (readArticle.get("link") or "",),
                )

    def saveCheckpoint(self, readFakeid: str, updateUrl: str) -> None:
        updateTime = datetime.now(UTC).isoformat()
        with self.openDatabase() as updateDatabase:
            updateDatabase.execute(
                "UPDATE accounts SET last_article_url = ?, updated_at = ? WHERE fakeid = ?",
                (updateUrl, updateTime, readFakeid),
            )

    def startRun(self, readMode: str, readFakeid: str) -> str:
        createId = str(uuid.uuid4())
        createTime = datetime.now(UTC).isoformat()
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
        updateTime = datetime.now(UTC).isoformat()
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
                SELECT url, fakeid, title, markdown_path, article_dedup_bypass, content_hash
                FROM articles
                WHERE structured = 0
                  AND structure_status IN ('PENDING', 'RETRY_WAIT')
                ORDER BY created_at
                LIMIT ?
                """,
                (readLimit,),
            ).fetchall()
        return [dict(readRow) for readRow in readRows]

    def countPendingArticles(self) -> int:
        with self.openDatabase() as readDatabase:
            return int(readDatabase.execute(
                "SELECT COUNT(*) FROM articles WHERE structured = 0 "
                "AND structure_status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT')"
            ).fetchone()[0])

    def claimPendingArticles(
        self,
        readLimit: int,
        claimId: str,
        now: datetime | None = None,
        maxAttempts: int = 3,
        excludeUrls: list[str] | None = None,
    ) -> list[dict]:
        updateNow = (now or datetime.now(UTC)).isoformat()
        excluded = excludeUrls or []
        exclusion = f" AND url NOT IN ({','.join('?' for _ in excluded)})" if excluded else ""
        with self.openDatabase() as updateDatabase:
            updateDatabase.execute("BEGIN IMMEDIATE")
            rows = updateDatabase.execute(
                f"""
                SELECT url, fakeid, title, markdown_path, article_dedup_bypass, content_hash
                FROM articles
                WHERE structured = 0
                  AND structure_status IN ('PENDING', 'RETRY_WAIT')
                  AND structure_attempts < ?
                  AND (structure_next_retry_at = '' OR structure_next_retry_at <= ?)
                  {exclusion}
                ORDER BY created_at
                LIMIT ?
                """,
                (maxAttempts, updateNow, *excluded, readLimit),
            ).fetchall()
            urls = [row["url"] for row in rows]
            if urls:
                placeholders = ",".join("?" for _ in urls)
                updateDatabase.execute(
                    f"UPDATE articles SET structure_status='PROCESSING', structure_claim_id=?, "
                    f"structure_claimed_at=?, structure_last_attempt_at=? WHERE url IN ({placeholders})",
                    (claimId, updateNow, updateNow, *urls),
                )
        return [dict(row) for row in rows]

    def hasArticleClaim(
        self, url: str, claimId: str, leaseSeconds: float = 7200, now: datetime | None = None,
    ) -> bool:
        cutoff = ((now or datetime.now(UTC)) - timedelta(seconds=leaseSeconds)).isoformat()
        with self.openDatabase() as database:
            row = database.execute(
                "SELECT 1 FROM articles WHERE url=? AND structured=0 AND structure_status='PROCESSING' "
                "AND structure_claim_id=? AND structure_claimed_at>=?",
                (url, claimId, cutoff),
            ).fetchone()
        return row is not None

    def renewClaim(
        self, claimId: str, leaseSeconds: float = 7200, now: datetime | None = None,
    ) -> int:
        now = now or datetime.now(UTC)
        cutoff = (now - timedelta(seconds=leaseSeconds)).isoformat()
        with self.openDatabase() as database:
            result = database.execute(
                "UPDATE articles SET structure_claimed_at=? "
                "WHERE structured=0 AND structure_status='PROCESSING' AND structure_claim_id=? "
                "AND structure_claimed_at>=?",
                (now.isoformat(), claimId, cutoff),
            )
        return result.rowcount

    def reclaimExpiredArticles(
        self,
        now: datetime | None = None,
        leaseSeconds: int = 7200,
    ) -> int:
        updateNow = now or datetime.now(UTC)
        cutoff = (updateNow - timedelta(seconds=leaseSeconds)).isoformat()
        with self.openDatabase() as updateDatabase:
            result = updateDatabase.execute(
                """
                UPDATE articles
                SET structure_status='PENDING', structure_claim_id='', structure_claimed_at=''
                WHERE structured=0 AND structure_status='PROCESSING'
                  AND structure_claimed_at <> '' AND structure_claimed_at < ?
                """,
                (cutoff,),
            )
        return result.rowcount

    def markStructureCompleted(
        self,
        readUrl: str,
        dedupRecord: dict[str, str] | None = None,
        claimId: str | None = None,
        leaseSeconds: float = 7200,
    ) -> bool:
        cutoff = (datetime.now(UTC) - timedelta(seconds=leaseSeconds)).isoformat()
        with self.openDatabase() as updateDatabase:
            updateDatabase.execute("BEGIN IMMEDIATE")
            result = updateDatabase.execute(
                """
                UPDATE articles SET structured=1, structure_status='COMPLETED',
                    structure_next_retry_at='', structure_last_error='',
                    structure_claim_id='', structure_claimed_at='',
                    article_duplicate_of='', article_duplicate_score=0, article_dedup_version='',
                    article_dedup_bypass=0
                WHERE url=? AND (? IS NULL OR (structure_status='PROCESSING' AND structure_claim_id=?
                    AND structure_claimed_at>=?))
                """,
                (readUrl, claimId, claimId, cutoff),
            )
            if result.rowcount and dedupRecord is not None:
                updateDatabase.execute(
                    """
                    INSERT INTO article_dedup_index (url, content_digest, version, hashvalues, completed_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(url) DO UPDATE SET content_digest=excluded.content_digest,
                        version=excluded.version, hashvalues=excluded.hashvalues, completed_at=excluded.completed_at
                    """,
                    (readUrl, dedupRecord["contentDigest"], dedupRecord["version"],
                     dedupRecord["hashvalues"], datetime.now(UTC).isoformat()),
                )
        return bool(result.rowcount)

    def readArticleDedupHistory(self, limit: int = 4000) -> list[dict]:
        with self.openDatabase() as readDatabase:
            rows = readDatabase.execute(
                """
                SELECT i.url, i.content_digest, i.version, i.hashvalues, a.markdown_path
                FROM article_dedup_index i JOIN articles a ON a.url=i.url
                WHERE a.structured=1 AND a.structure_status='COMPLETED'
                ORDER BY i.completed_at DESC LIMIT ?
                """,
                (limit,),
            ).fetchall()
        return [dict(row) for row in rows]

    def markArticleDuplicate(
        self,
        url: str,
        winnerUrl: str,
        score: float,
        version: str,
        articleDigest: str,
        winnerDigest: str,
        claimId: str,
        leaseSeconds: float = 7200,
    ) -> bool:
        cutoff = (datetime.now(UTC) - timedelta(seconds=leaseSeconds)).isoformat()
        with self.openDatabase() as database:
            database.execute("BEGIN IMMEDIATE")
            winner = database.execute(
                """
                SELECT 1 FROM article_dedup_index i JOIN articles a ON a.url=i.url
                WHERE i.url=? AND i.content_digest=? AND a.structured=1 AND a.structure_status='COMPLETED'
                """,
                (winnerUrl, winnerDigest),
            ).fetchone()
            if winner is None:
                return False
            reason = f"文章近重复：{winnerUrl}；exact Jaccard={score:.6f}；{version}"
            result = database.execute(
                """
                UPDATE articles SET structured=1, structure_status='SKIPPED', structure_last_error=?,
                    structure_next_retry_at='', structure_claim_id='', structure_claimed_at='',
                    article_duplicate_of=?, article_duplicate_score=?, article_dedup_version=?, article_dedup_bypass=0
                WHERE url=? AND structure_status='PROCESSING' AND structure_claim_id=? AND structure_claimed_at>=?
                """,
                (reason[:1000], winnerUrl, score, version, url, claimId, cutoff),
            )
            if result.rowcount:
                database.execute(
                    """
                    INSERT INTO article_dedup_skips
                        (article_url, winner_url, score, version, article_digest, winner_digest, skipped_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    (url, winnerUrl, score, version, articleDigest, winnerDigest, datetime.now(UTC).isoformat()),
                )
        return bool(result.rowcount)

    def requeueArticleDuplicates(self, readUrls: list[str]) -> int:
        """Explicit recovery; retain skip audit events and never requeue implicitly."""
        if not readUrls:
            return 0
        placeholders = ",".join("?" for _ in readUrls)
        with self.openDatabase() as database:
            result = database.execute(
                "UPDATE articles SET structured=0, structure_status='PENDING', structure_attempts=0, "
                "structure_next_retry_at='', structure_last_error='', article_duplicate_of='', "
                "article_duplicate_score=0, article_dedup_version='', article_dedup_bypass=1 "
                f"WHERE structure_status='SKIPPED' AND article_duplicate_of<>'' AND url IN ({placeholders})",
                tuple(readUrls),
            )
        return result.rowcount

    def markStructureSkipped(
        self, readUrl: str, reason: str = "", claimId: str | None = None, leaseSeconds: float = 7200,
    ) -> bool:
        cutoff = (datetime.now(UTC) - timedelta(seconds=leaseSeconds)).isoformat()
        with self.openDatabase() as updateDatabase:
            result = updateDatabase.execute(
                """
                UPDATE articles SET structured=1, structure_status='SKIPPED',
                    structure_last_error=?, structure_next_retry_at='',
                    structure_claim_id='', structure_claimed_at=''
                WHERE url=? AND (? IS NULL OR (structure_status='PROCESSING' AND structure_claim_id=?
                    AND structure_claimed_at>=?))
                """,
                (reason[:1000], readUrl, claimId, claimId, cutoff),
            )
        return bool(result.rowcount)

    def markStructureFailed(
        self,
        readUrl: str,
        error: str,
        retryAt: datetime | None = None,
        maxAttempts: int = 3,
        claimId: str | None = None,
        leaseSeconds: float = 7200,
    ) -> str:
        cutoff = (datetime.now(UTC) - timedelta(seconds=leaseSeconds)).isoformat()
        with self.openDatabase() as updateDatabase:
            updateDatabase.execute("BEGIN IMMEDIATE")
            row = updateDatabase.execute(
                "SELECT structure_attempts, structure_status, structure_claim_id, structure_claimed_at "
                "FROM articles WHERE url=?", (readUrl,)
            ).fetchone()
            if row is None:
                raise KeyError(readUrl)
            if claimId is not None and (row["structure_status"] != "PROCESSING"
                                        or row["structure_claim_id"] != claimId
                                        or row["structure_claimed_at"] < cutoff):
                return str(row["structure_status"])
            attempts = int(row[0]) + 1
            status = "QUARANTINED" if attempts >= maxAttempts else "RETRY_WAIT"
            delays = (3600, 21600, 86400)
            updateRetryAt = retryAt or datetime.now(UTC) + timedelta(
                seconds=delays[min(attempts - 1, len(delays) - 1)]
            )
            updateDatabase.execute(
                """
                UPDATE articles SET structure_status=?, structure_attempts=?,
                    structure_next_retry_at=?, structure_last_error=?,
                    structure_claim_id='', structure_claimed_at=''
                WHERE url=?
                """,
                (status, attempts, updateRetryAt.isoformat(), error[:1000], readUrl),
            )
        return status

    def releaseClaim(self, claimId: str) -> int:
        with self.openDatabase() as updateDatabase:
            result = updateDatabase.execute(
                """
                UPDATE articles SET structure_status='PENDING',
                    structure_claim_id='', structure_claimed_at=''
                WHERE structured=0 AND structure_status='PROCESSING' AND structure_claim_id=?
                """,
                (claimId,),
            )
        return result.rowcount

    def requeueQuarantined(self, readUrls: list[str] | None = None) -> int:
        with self.openDatabase() as updateDatabase:
            if readUrls:
                placeholders = ",".join("?" for _ in readUrls)
                result = updateDatabase.execute(
                    f"UPDATE articles SET structure_status='PENDING', structure_attempts=0, "
                    f"structure_next_retry_at='', structure_last_error='' "
                    f"WHERE structure_status='QUARANTINED' AND url IN ({placeholders})",
                    tuple(readUrls),
                )
            else:
                result = updateDatabase.execute(
                    "UPDATE articles SET structure_status='PENDING', structure_attempts=0, "
                    "structure_next_retry_at='', structure_last_error='' "
                    "WHERE structure_status='QUARANTINED'"
                )
        return result.rowcount

    def readStructureCounts(self) -> dict[str, int]:
        with self.openDatabase() as readDatabase:
            rows = readDatabase.execute(
                "SELECT structure_status, COUNT(*) FROM articles GROUP BY structure_status"
            ).fetchall()
        return {str(row[0]): int(row[1]) for row in rows}

    def markStructured(self, readUrl: str) -> None:
        self.markStructureCompleted(readUrl)

    def markPrefiltered(
        self, url: str, reason: str, version: str, claimId: str | None = None, leaseSeconds: float = 7200,
    ) -> bool:
        cutoff = (datetime.now(UTC) - timedelta(seconds=leaseSeconds)).isoformat()
        with self.openDatabase() as db:
            result = db.execute(
                "UPDATE articles SET structured=1, structure_status='SKIPPED', "
                "structure_claim_id='', structure_claimed_at='', "
                "prefilter_reason=?, prefilter_version=? WHERE url=? "
                "AND (? IS NULL OR (structure_status='PROCESSING' AND structure_claim_id=? AND structure_claimed_at>=?))",
                (reason, version, url, claimId, claimId, cutoff),
            )
        return bool(result.rowcount)

    def requeuePrefiltered(self, version: str) -> int:
        with self.openDatabase() as db:
            return db.execute(
                "UPDATE articles SET structured=0, structure_status='PENDING', "
                "structure_attempts=0, structure_next_retry_at='', prefilter_reason='' "
                "WHERE prefilter_version=? AND prefilter_reason<>''",
                (version,),
            ).rowcount

    def deferArticle(self, readUrl: str) -> None:
        updateTime = datetime.now(UTC).isoformat()
        with self.openDatabase() as updateDatabase:
            updateDatabase.execute(
                "UPDATE articles SET created_at = ? WHERE url = ?", (updateTime, readUrl)
            )

    @staticmethod
    def buildFallback(readFakeid: str) -> str:
        readHash = hashlib.sha256(readFakeid.encode("utf-8")).hexdigest()[:8]
        return f"account_{readHash}"

    @classmethod
    def mapAccount(cls, readRow: sqlite3.Row) -> CrawlAccount:
        readName = readRow["name"] or cls.buildFallback(readRow["fakeid"])
        return CrawlAccount(
            fakeid=readRow["fakeid"],
            name=readName,
            enabled=bool(readRow["enabled"]),
            nameSource=readRow["name_source"],
            lastArticleUrl=readRow["last_article_url"],
        )
