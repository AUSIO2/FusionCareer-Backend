import sqlite3
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace

from app.algorithms.article_dedup import ArticleDeduplicator
from app.skills.business.crawlers.store import CrawlStore


def _save_article(store: CrawlStore, root: Path, suffix: str) -> str:
    store.saveAccount("source", "Source", True)
    markdown = root / f"{suffix}.md"
    markdown.write_text(f"# 招聘 {suffix}", encoding="utf-8")
    url = f"https://example.test/{suffix}"
    store.saveArticle("source", {"title": suffix, "link": url, "create_time": 1}, markdown, suffix)
    return url


def testMigratesLegacyStructuredState(tmp_path: Path):
    database = tmp_path / "legacy.db"
    with sqlite3.connect(database) as connection:
        connection.execute(
            """
            CREATE TABLE articles (
                url TEXT PRIMARY KEY, fakeid TEXT NOT NULL, title TEXT NOT NULL,
                published_at TEXT NOT NULL, markdown_path TEXT NOT NULL,
                content_hash TEXT NOT NULL, structured INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL
            )
            """
        )
        connection.execute(
            "INSERT INTO articles VALUES ('done','s','t','','p','h',1,'2026-01-01')"
        )
        connection.execute(
            "INSERT INTO articles VALUES ('todo','s','t','','p','h',0,'2026-01-01')"
        )

    store = CrawlStore(database)

    assert store.readStructureCounts()["COMPLETED"] == 1
    assert store.readStructureCounts()["PENDING"] == 1


def testClaimIsExclusiveAndCanBeReleased(tmp_path: Path):
    store = CrawlStore(tmp_path / "crawl.db")
    first = _save_article(store, tmp_path, "one")
    second = _save_article(store, tmp_path, "two")

    claimed_a = store.claimPendingArticles(1, "claim-a")
    claimed_b = store.claimPendingArticles(2, "claim-b")

    assert [row["url"] for row in claimed_a] == [first]
    assert [row["url"] for row in claimed_b] == [second]
    assert store.releaseClaim("claim-a") == 1
    assert [row["url"] for row in store.claimPendingArticles(1, "claim-c")] == [first]


def testFailureBackoffAndQuarantine(tmp_path: Path):
    store = CrawlStore(tmp_path / "crawl.db")
    url = _save_article(store, tmp_path, "broken")
    now = datetime.now(UTC)

    assert store.claimPendingArticles(1, "attempt-1", now=now)
    assert store.markStructureFailed(url, "invalid json", now + timedelta(hours=1), 3) == "RETRY_WAIT"
    assert store.claimPendingArticles(1, "too-early", now=now + timedelta(minutes=30)) == []
    assert store.claimPendingArticles(1, "attempt-2", now=now + timedelta(hours=2))
    assert store.markStructureFailed(url, "invalid json", now + timedelta(hours=3), 3) == "RETRY_WAIT"
    assert store.claimPendingArticles(1, "attempt-3", now=now + timedelta(hours=4))
    assert store.markStructureFailed(url, "invalid json", now + timedelta(hours=5), 3) == "QUARANTINED"

    assert store.claimPendingArticles(1, "attempt-4", now=now + timedelta(days=2)) == []
    assert store.readStructureCounts()["QUARANTINED"] == 1
    assert store.requeueQuarantined([url]) == 1
    assert store.claimPendingArticles(1, "manual", now=now + timedelta(days=2))


def testReclaimsExpiredProcessingLease(tmp_path: Path):
    store = CrawlStore(tmp_path / "crawl.db")
    url = _save_article(store, tmp_path, "leased")
    now = datetime.now(UTC)
    assert store.claimPendingArticles(1, "dead-worker", now=now)

    assert store.reclaimExpiredArticles(now + timedelta(hours=3), leaseSeconds=7200) == 1
    assert [row["url"] for row in store.claimPendingArticles(
        1, "new-worker", now=now + timedelta(hours=3)
    )] == [url]


def testCompletionAndIndexRequireCurrentClaim(tmp_path):
    store = CrawlStore(tmp_path / "crawl.db")
    url = _save_article(store, tmp_path, "canonical")
    dedup = ArticleDeduplicator(SimpleNamespace())
    signature = dedup.signature(url, str(tmp_path / "canonical.md"), "# 招聘 canonical")
    store.claimPendingArticles(1, "owner")

    assert not store.markStructureCompleted(url, signature.record(), "other")
    assert store.readArticleDedupHistory() == []
    assert store.markStructureCompleted(url, signature.record(), "owner")
    assert [row["url"] for row in store.readArticleDedupHistory()] == [url]
    assert store.markStructureFailed(url, "stale worker", claimId="owner") == "COMPLETED"


def testExplicitDuplicateRequeuePreservesAuditAndRejectsUncompletedWinner(tmp_path):
    store = CrawlStore(tmp_path / "crawl.db")
    winner = _save_article(store, tmp_path, "winner")
    copy = _save_article(store, tmp_path, "copy")
    store.claimPendingArticles(2, "owner")
    dedup = ArticleDeduplicator(SimpleNamespace())
    signature = dedup.signature(winner, str(tmp_path / "winner.md"), "# 招聘 winner")

    assert not store.markArticleDuplicate(copy, winner, 1, dedup.version, "copy", signature.contentDigest, "owner")
    assert store.markStructureCompleted(winner, signature.record(), "owner")
    assert not store.markArticleDuplicate(copy, winner, 1, dedup.version, "copy", signature.contentDigest, "wrong")
    assert store.markArticleDuplicate(copy, winner, 1, dedup.version, "copy", signature.contentDigest, "owner")
    assert store.requeueArticleDuplicates([copy]) == 1
    assert store.requeueArticleDuplicates([]) == 0
    assert [row["url"] for row in store.claimPendingArticles(1, "retry")] == [copy]
    with store.openDatabase() as connection:
        assert connection.execute("SELECT COUNT(*) FROM article_dedup_skips").fetchone()[0] == 1


def testChangedContentInvalidatesCanonicalAndRequeuesOnlyThatArticle(tmp_path):
    store = CrawlStore(tmp_path / "crawl.db")
    url = _save_article(store, tmp_path, "canonical")
    signature = ArticleDeduplicator(SimpleNamespace()).signature(url, str(tmp_path / "canonical.md"), "old")
    store.claimPendingArticles(1, "owner")
    store.markStructureCompleted(url, signature.record(), "owner")

    store.saveArticle("source", {"title": "new", "link": url}, tmp_path / "canonical.md", "changed-hash")

    assert store.readArticleDedupHistory() == []
    assert [row["url"] for row in store.claimPendingArticles(1, "new-owner")] == [url]


def testConcurrentStoresCannotBothClaimSameArticle(tmp_path):
    path = tmp_path / "crawl.db"
    store = CrawlStore(path)
    _save_article(store, tmp_path, "canonical")

    def claim(owner):
        return CrawlStore(path).claimPendingArticles(1, owner)

    with ThreadPoolExecutor(max_workers=2) as executor:
        first, second = list(executor.map(claim, ["first", "second"]))

    assert len(first) + len(second) == 1


def testExpiredClaimCannotRenewSkipOrCommit(tmp_path):
    store = CrawlStore(tmp_path / "crawl.db")
    url = _save_article(store, tmp_path, "expired")
    old = datetime.now(UTC) - timedelta(hours=3)
    store.claimPendingArticles(1, "expired-worker", now=old)

    assert not store.hasArticleClaim(url, "expired-worker")
    assert store.renewClaim("expired-worker") == 0
    assert not store.markStructureSkipped(url, "stale skip", "expired-worker")
    assert not store.markPrefiltered(url, "stale prefilter", "old-rules", "expired-worker")
    assert not store.markStructureCompleted(url, claimId="expired-worker")
    assert store.markStructureFailed(url, "stale failure", claimId="expired-worker") == "PROCESSING"
    with store.openDatabase() as connection:
        row = dict(connection.execute("SELECT * FROM articles WHERE url=?", (url,)).fetchone())
    assert row["structure_attempts"] == 0 and row["prefilter_version"] == ""
    assert store.readArticleDedupHistory() == []


def testOldWorkerCannotSkipAfterContentChangedAndClaimReassigned(tmp_path):
    store = CrawlStore(tmp_path / "crawl.db")
    url = _save_article(store, tmp_path, "changed")
    store.claimPendingArticles(1, "old-worker")
    store.saveArticle("source", {"link": url, "title": "new"}, tmp_path / "changed.md", "new-hash")
    store.claimPendingArticles(1, "new-worker")

    assert not store.markStructureSkipped(url, "old skip", "old-worker")
    assert not store.markPrefiltered(url, "old prefilter", "old-rules", "old-worker")
    assert store.hasArticleClaim(url, "new-worker")
    with store.openDatabase() as connection:
        row = dict(connection.execute("SELECT * FROM articles WHERE url=?", (url,)).fetchone())
    assert row["structured"] == 0 and row["prefilter_version"] == ""


def testRenewalKeepsActiveClaimButDoesNotResurrectReclaimedOwner(tmp_path):
    store = CrawlStore(tmp_path / "crawl.db")
    url = _save_article(store, tmp_path, "heartbeat")
    now = datetime.now(UTC)
    store.claimPendingArticles(1, "first", now=now)
    assert store.renewClaim("first", now=now + timedelta(hours=1)) == 1
    assert store.hasArticleClaim(url, "first", now=now + timedelta(hours=2, minutes=30))
    assert store.reclaimExpiredArticles(now + timedelta(hours=4)) == 1
    assert store.claimPendingArticles(1, "second", now=now + timedelta(hours=4))
    assert store.renewClaim("first", now=now + timedelta(hours=4)) == 0
    assert not store.markStructureSkipped(url, "old skip", "first")
    assert not store.markPrefiltered(url, "old prefilter", "old-rules", "first")
