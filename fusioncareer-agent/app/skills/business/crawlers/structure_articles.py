"""Structure pending crawled articles and create offline Java job drafts."""

from __future__ import annotations

import asyncio
import fcntl
import hashlib
import json
import logging
import os
import re
import sqlite3
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from app.algorithms.article_dedup import ArticleDeduplicator
from app.algorithms.job_filter import filter_jobs
from app.algorithms.job_record_dedup import JobDuplicateIndex
from app.algorithms.job_structuring import structureJobs
from app.algorithms.upstream import run_algorithm
from app.config import settings
from app.core.base_skill import BaseSkill
from app.integrations.backend import BackendApiError, BackendClient
from app.integrations.llm import LLMClient
from app.skills.business.crawlers.paths import CrawlPaths, resolve_config_root
from app.skills.business.crawlers.store import CrawlStore

readBackend: BackendClient | None = None
_algorithm_workflow = None
logger = logging.getLogger(__name__)
OFFICIAL_STRUCTURE_BATCH_SIZE = 2000
ARTICLE_DEDUP_BATCH_SIZE = 64
_drainLock = asyncio.Lock()
CONTACT_PATTERN = re.compile(
    r"[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\.[A-Za-z]{2,}|"
    r"(?<!\d)1[3-9]\d{9}(?!\d)|(?<!\d)0\d{2,3}[- ]?\d{7,8}(?!\d)|"
    r"联系人\s*[:：]\s*\S{1,32}|"
    r"(网申|投递|报名|申请|招聘官网|简历|应聘).{0,80}(https?://|邮箱|邮件|二维码|扫码|入口|系统)|"
    r"(二维码|扫码).{0,30}(投递|报名|申请|应聘)"
)
RESTRICT_PATTERN = re.compile(r"仅限.{0,12}(本校|我校)|只面向.{0,12}(本校|我校)|凭.{0,12}(学生证|校园卡)")


class BalanceExhaustedError(RuntimeError):
    pass


@asynccontextmanager
async def structureRunLock(readPaths: CrawlPaths):
    """Serialize all structure writers in this process and shared crawl volume."""
    async with _drainLock:
        lockRoot = Path(settings.agent_runtime_dir).resolve()
        lockRoot.mkdir(parents=True, exist_ok=True)
        lockPath = lockRoot / ".job-write.lock"
        lockStream = lockPath.open("a")
        os.chmod(lockPath, 0o600)
        try:
            await asyncio.to_thread(fcntl.flock, lockStream.fileno(), fcntl.LOCK_EX)
            yield
        finally:
            fcntl.flock(lockStream.fileno(), fcntl.LOCK_UN)
            lockStream.close()


def isBalanceError(readError: BaseException) -> bool:
    updateError: BaseException | None = readError
    while updateError is not None:
        if getattr(updateError, "status_code", None) == 402 \
                or "insufficient balance" in str(updateError).lower():
            return True
        updateError = updateError.__cause__ or updateError.__context__
    return False


def set_backend_client(updateBackend: BackendClient) -> None:
    global readBackend
    readBackend = updateBackend


def set_algorithm_workflow(runner) -> None:
    global _algorithm_workflow
    _algorithm_workflow = runner


def readArticleBody(readText: str) -> str:
    readParts = readText.split("**Source:**", 1)
    return readParts[1].split("\n", 1)[-1] if len(readParts) == 2 else readText


def checkAction(readText: str) -> bool:
    return bool(CONTACT_PATTERN.search(readArticleBody(readText)))


def checkRestriction(readText: str) -> bool:
    return bool(RESTRICT_PATTERN.search(readArticleBody(readText)))


def readFudanIds() -> set[str]:
    readFile = Path(__file__).resolve().parents[3] / "presets" / "official_accounts.json"
    readAccounts = json.loads(readFile.read_text(encoding="utf-8"))
    return {
        str(readAccount["fakeid"])
        for readAccount in readAccounts
        if str(readAccount.get("institution") or "").startswith("复旦大学")
    }


async def structureArticles(
    readPaths: CrawlPaths,
    updateBackend: BackendClient,
    readClient: LLMClient | None = None,
    readLimit: int = 20,
) -> dict[str, int]:
    readStore = CrawlStore(readPaths.database_file)
    readExistingJobs = await updateBackend.list_job_posts()
    readJobIndex = JobDuplicateIndex(
        readExistingJobs,
        crossSource=getattr(settings, "job_record_dedup_enabled", True),
    )
    # LLM extraction remains concurrent. Only the final match -> Java write ->
    # committed-index transition is serialized, closing the prior TOCTOU race.
    readJobWriteLock = asyncio.Lock()
    leaseSeconds = settings.job_structure_claim_lease_seconds
    readStore.reclaimExpiredArticles(leaseSeconds=leaseSeconds)
    readFudan = readFudanIds()
    readSemaphore = asyncio.Semaphore(settings.job_structure_article_concurrency)
    readBalanceExhausted = asyncio.Event()
    readWriteOutcomeUnknown = asyncio.Event()
    readDedup = ArticleDeduplicator(settings)
    readSignatures = {}

    def prepareDedup(articles: list[dict]) -> dict[str, list[str]]:
        readSignatures.clear()
        if not readDedup.enabled:
            return {}
        for article in articles:
            try:
                text = Path(article["markdown_path"]).read_text(encoding="utf-8")
                readSignatures[article["url"]] = readDedup.signature(article["url"], article["markdown_path"], text)
            except (OSError, UnicodeError):
                # The normal per-article failure path owns retry accounting.
                continue
        return readDedup.dependencies(readSignatures)

    def isCurrent(article: dict, claimId: str, textDigest: str | None = None) -> bool:
        if not readStore.hasArticleClaim(article["url"], claimId, leaseSeconds):
            return False
        if textDigest is not None:
            try:
                currentText = Path(article["markdown_path"]).read_text(encoding="utf-8")
                if hashlib.sha256(currentText.encode("utf-8")).hexdigest() != textDigest:
                    return False
            except (OSError, UnicodeError):
                return False
        return True

    async def keepClaimAlive(claimId: str) -> None:
        while True:
            await asyncio.sleep(min(60, leaseSeconds / 3))
            try:
                if not readStore.renewClaim(claimId, leaseSeconds):
                    return
            except sqlite3.Error as error:
                # The pre-write check remains authoritative if SQLite is
                # temporarily unavailable; expired claims are never renewed.
                logger.warning("article claim renewal failed: %s", type(error).__name__)

    async def createArticle(readArticle: dict, claimId: str) -> tuple[int, int, int, int]:
        nonlocal readJobIndex
        async with readSemaphore:
            if readBalanceExhausted.is_set():
                raise BalanceExhaustedError("LLM balance exhausted; batch stopped")
            if not isCurrent(readArticle, claimId):
                return 0, 0, 0, 0
            try:
                readText = Path(readArticle["markdown_path"]).read_text(encoding="utf-8")
                textDigest = hashlib.sha256(readText.encode("utf-8")).hexdigest()
                if readArticle["fakeid"] not in readFudan and (
                    not checkAction(readText) or checkRestriction(readText)
                ):
                    skipped = isCurrent(readArticle, claimId, textDigest) and readStore.markStructureSkipped(
                        readArticle["url"], "未发现投递动作或仅限外校", claimId, leaseSeconds,
                    )
                    return 0, 0, int(skipped), 0
                if settings.prefilter_enabled and readClient is None:
                    pref = await run_algorithm("job_prefilter", {"text": readText})
                    if not isCurrent(readArticle, claimId, textDigest):
                        return 0, 0, 0, 0
                    if pref.get("skipped"):
                        version = pref["lexiconVersion"]
                        skipped = readStore.markPrefiltered(
                            readArticle["url"], pref.get("reason", "关键词预筛未命中"), version, claimId, leaseSeconds,
                        )
                        return 0, 0, int(skipped), 0
                signature = readSignatures.get(readArticle["url"])
                if readDedup.enabled and (
                    signature is None or signature.contentDigest != textDigest
                ):
                    signature = await asyncio.to_thread(
                        readDedup.signature, readArticle["url"], readArticle["markdown_path"], readText,
                    )
                if signature is not None and not readArticle.get("article_dedup_bypass"):
                    if not isCurrent(readArticle, claimId, textDigest):
                        return 0, 0, 0, 0
                    duplicate = readDedup.findDuplicate(signature, readStore)
                    if duplicate is not None and readStore.markArticleDuplicate(
                        readArticle["url"], duplicate.winnerUrl, duplicate.score, duplicate.version,
                        signature.contentDigest, duplicate.winnerDigest, claimId, leaseSeconds,
                    ):
                        logger.info("skipped near-duplicate %s; winner=%s exact_jaccard=%.6f",
                                    readArticle["url"], duplicate.winnerUrl, duplicate.score)
                        return 0, 0, 1, 0
                if not isCurrent(readArticle, claimId, textDigest):
                    return 0, 0, 0, 0
                if _algorithm_workflow is not None and readClient is None:
                    readResult = await _algorithm_workflow({
                        "text": readText, "sourceUrl": readArticle["url"], "sourceType": "CRAWL",
                    })
                else:
                    readResult = await structureJobs(readText, readArticle["url"], "CRAWL", readClient)
                readInvalid = [
                    warning for warning in readResult["warnings"]
                    if re.match(r"job \d+: ", warning)
                ]
                if readInvalid:
                    raise ValueError("job validation failed: " + "; ".join(readInvalid))
                readJobs, readDropped = filter_jobs(readResult["jobs"])
                if readDropped:
                    logger.info("filtered %d unrelated jobs from %s", len(readDropped), readArticle["url"])
                readRecycled = []
                for readDroppedJob in readDropped:
                    readReason = str(readDroppedJob.get("_filterReason") or "未通过初筛")
                    createRecycled = {
                        readKey: readValue for readKey, readValue in readDroppedJob.items()
                        if readKey != "_filterReason"
                    }
                    createRecycled["status"] = "RECYCLED"
                    createRecycled["recycleReason"] = f"初筛未通过：{readReason}"
                    readRecycled.append(createRecycled)
                if not isCurrent(readArticle, claimId, textDigest):
                    return 0, 0, 0, 0
                async with readJobWriteLock:
                    if not isCurrent(readArticle, claimId, textDigest):
                        return 0, 0, 0, 0
                    if readWriteOutcomeUnknown.is_set():
                        raise RuntimeError("a previous Java write outcome is unknown; retry in the next run")
                    localIndex = JobDuplicateIndex(crossSource=readJobIndex.crossSource)
                    createJobs = []
                    createRecycledJobs = []
                    for createJob, updateList in (
                        *((job, createJobs) for job in readJobs),
                        *((job, createRecycledJobs) for job in readRecycled),
                    ):
                        match = readJobIndex.find(createJob, allowPersistedUpdate=True)
                        if match is not None:
                            winnerId = match.winner.get("id")
                            winnerUpdatedAt = match.winner.get("updatedAt")
                            if match.requiresPersistedUpdate and not (winnerId and winnerUpdatedAt):
                                # A committed batch row may not yet have its Java
                                # ID. Keep the incoming row rather than merging
                                # evidence only in memory.
                                match = None
                            elif winnerId and winnerUpdatedAt:
                                mergeAttempts = 0
                                while match is not None:
                                    mergeAttempts += 1
                                    try:
                                        mergedWinner = await updateBackend.merge_job_post_evidence(
                                            winnerId, str(winnerUpdatedAt), createJob,
                                        )
                                    except BackendApiError as mergeError:
                                        errorStatus = mergeError.http_status or mergeError.code
                                        deterministicConflict = (
                                            errorStatus == 409
                                            and (
                                                "存在冲突" in mergeError.message
                                                or "超过字段长度限制" in mergeError.message
                                            )
                                        )
                                        if deterministicConflict or (
                                            errorStatus in {404, 409} and mergeAttempts >= 2
                                        ):
                                            # Java proved that the candidate cannot be
                                            # losslessly merged. Preserve it as a separate
                                            # row instead of retrying until quarantine.
                                            logger.warning(
                                                "keeper merge rejected; preserving incoming job; "
                                                "keeper=%s status=%s reason=%s",
                                                winnerId, errorStatus, mergeError.message,
                                            )
                                            match = None
                                            break
                                        if errorStatus not in {404, 409}:
                                            # A 4xx response is a known rollback; a 5xx may
                                            # have crossed the commit boundary.
                                            if errorStatus >= 500:
                                                readWriteOutcomeUnknown.set()
                                            raise
                                        try:
                                            refreshedJobs = await updateBackend.list_job_posts()
                                        except Exception:
                                            readWriteOutcomeUnknown.set()
                                            raise mergeError
                                        readJobIndex = JobDuplicateIndex(
                                            refreshedJobs, crossSource=readJobIndex.crossSource,
                                        )
                                        match = readJobIndex.find(
                                            createJob, allowPersistedUpdate=True,
                                        )
                                        if match is None:
                                            break
                                        winnerId = match.winner.get("id")
                                        winnerUpdatedAt = match.winner.get("updatedAt")
                                        if not (winnerId and winnerUpdatedAt):
                                            match = None
                                            break
                                        continue
                                    except Exception:
                                        # A timeout, transport failure, or unexpected 5xx
                                        # may have committed. Fuse later writes and retry
                                        # from a fresh backend snapshot on the next run.
                                        readWriteOutcomeUnknown.set()
                                        raise
                                    if not isinstance(mergedWinner, dict) or str(
                                        mergedWinner.get("id")
                                    ) != str(winnerId):
                                        readWriteOutcomeUnknown.set()
                                        raise RuntimeError("keeper merge returned an invalid row")
                                    # JobPostResponse is intentionally narrower than the
                                    # internal snapshot; never erase evidence it omits.
                                    match.winner.update(mergedWinner)
                                    break
                        if match is not None:
                            # Keep the full direct-evidence cluster in memory so
                            # an unknown field cannot bridge later conflicts.
                            readJobIndex.recordDuplicate(match, createJob, persisted=True)
                        else:
                            match = localIndex.find(createJob)
                            if match is not None:
                                localIndex.recordDuplicate(match, createJob)
                        if match is not None:
                            logger.info(
                                "skipped duplicate job from %s; reason=%s winner=%s",
                                readArticle["url"], match.reason,
                                match.winner.get("id") or match.winner.get("sourceUrl") or "same-batch",
                            )
                            continue
                        localIndex.add(createJob)
                        updateList.append(createJob)
                    createBatch = createJobs + createRecycledJobs

                    # The HTTP write must begin under a current, unexpired claim.
                    # Renewal cannot resurrect an expired or reassigned claim.
                    readStore.renewClaim(claimId, leaseSeconds)
                    if not isCurrent(readArticle, claimId, textDigest):
                        return 0, 0, 0, 0
                    try:
                        await updateBackend.create_job_posts(createBatch)
                    except Exception as writeError:
                        # A timeout can happen after Java committed. Refresh while
                        # still holding the lock; otherwise a waiting repost could
                        # immediately write the same jobs again from a stale index.
                        try:
                            refreshedJobs = await updateBackend.list_job_posts()
                        except Exception:
                            readWriteOutcomeUnknown.set()
                            raise writeError
                        refreshedIndex = JobDuplicateIndex(
                            refreshedJobs, crossSource=readJobIndex.crossSource,
                        )
                        readJobIndex = refreshedIndex
                        if not createBatch or not all(refreshedIndex.find(job) for job in createBatch):
                            raise writeError
                        logger.warning(
                            "Java write response was uncertain but committed jobs were verified; source=%s",
                            readArticle["url"],
                        )
                    else:
                        # Failed/cancelled Java writes never reserve a semantic key.
                        for createJob in createBatch:
                            readJobIndex.add(createJob, persisted=True)
                # Empty/failed Java writes never become canonical history. The
                # completion state and signature commit in one SQLite transaction.
                record = signature.record() if signature is not None and signature.grams and createBatch else None
                if isCurrent(readArticle, claimId, textDigest):
                    readStore.markStructureCompleted(readArticle["url"], record, claimId, leaseSeconds)
                return len(createJobs) + len(createRecycledJobs), 0, 0, 0
            except Exception as readError:
                if isBalanceError(readError):
                    readBalanceExhausted.set()
                    raise BalanceExhaustedError("LLM balance exhausted; batch stopped") from readError
                if not isCurrent(readArticle, claimId):
                    return 0, 0, 0, 0
                logger.warning("structure article failed %s: %s", readArticle["url"], readError)
                status = readStore.markStructureFailed(
                    readArticle["url"],
                    f"{type(readError).__name__}: {str(readError)[:900]}",
                    maxAttempts=settings.job_structure_max_attempts,
                    claimId=claimId,
                    leaseSeconds=leaseSeconds,
                )
                return 0, 1, 0, int(status == "QUARANTINED")

    readResults = []
    articleCount = 0
    seenUrls: list[str] = []
    # Lazily claim each bounded group. Later documents stay PENDING instead of
    # spending hours of their lease waiting behind earlier long articles.
    while articleCount < readLimit:
        claimId = uuid.uuid4().hex
        group = readStore.claimPendingArticles(
            min(ARTICLE_DEDUP_BATCH_SIZE, readLimit - articleCount), claimId,
            maxAttempts=settings.job_structure_max_attempts,
            excludeUrls=seenUrls,
        )
        if not group:
            break
        articleCount += len(group)
        seenUrls.extend(article["url"] for article in group)
        readTasks: list[asyncio.Task] = []
        leaseTask = asyncio.create_task(keepClaimAlive(claimId))
        try:
            dependencies = await asyncio.to_thread(prepareDedup, group)
            tasksByUrl: dict[str, asyncio.Task] = {}

            async def createAfterCandidates(
                article: dict, dependencies: dict[str, list[str]], tasks: dict[str, asyncio.Task], claimId: str,
            ) -> tuple[int, int, int, int]:
                for earlierUrl in dependencies.get(article["url"], []):
                    await tasks[earlierUrl]
                return await createArticle(article, claimId)

            readTasks = []
            for article in group:
                task = asyncio.create_task(createAfterCandidates(article, dependencies, tasksByUrl, claimId))
                tasksByUrl[article["url"]] = task
                readTasks.append(task)
            readResults.extend(await asyncio.gather(*readTasks))
        except BaseException:
            for updateTask in readTasks:
                updateTask.cancel()
            await asyncio.gather(*readTasks, return_exceptions=True)
            raise
        finally:
            leaseTask.cancel()
            await asyncio.gather(leaseTask, return_exceptions=True)
            readStore.releaseClaim(claimId)
    createCount = sum(readResult[0] for readResult in readResults)
    failCount = sum(readResult[1] for readResult in readResults)
    skipCount = sum(readResult[2] for readResult in readResults)
    quarantinedCount = sum(readResult[3] for readResult in readResults)
    return {
        "articleCount": articleCount,
        "jobCount": createCount,
        "failedCount": failCount,
        "skippedCount": skipCount,
        "retryCount": failCount - quarantinedCount,
        "quarantinedCount": quarantinedCount,
    }


async def drainPendingArticles(
    readPaths: CrawlPaths,
    updateBackend: BackendClient,
    readBatchSize: int = OFFICIAL_STRUCTURE_BATCH_SIZE,
) -> dict[str, int]:
    async with structureRunLock(readPaths):
        createTotal = {
            "articleCount": 0,
            "jobCount": 0,
            "failedCount": 0,
            "skippedCount": 0,
            "retryCount": 0,
            "quarantinedCount": 0,
        }
        while True:
            readResult = await structureArticles(readPaths, updateBackend, readLimit=readBatchSize)
            for readKey in createTotal:
                createTotal[readKey] += readResult.get(readKey, 0)
            if readResult["articleCount"] == 0:
                return createTotal


class StructureArticlesSkill(BaseSkill):
    def define(self) -> dict:
        return {
            "name": "wechat_structure_articles",
            "description": "将未处理采集文章转为 Java 岗位草稿",
            "inputs": {"paths": "json_obj", "result": "json_obj"},
            "outputs": {"json_obj": "json_obj"},
        }

    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        if readBackend is None:
            raise RuntimeError("BackendClient 未初始化")
        readPaths = CrawlPaths(resolve_config_root(inputs["paths"]))
        # Share the same run lock as the full drain so two scheduler/admin
        # entrypoints cannot build stale backend snapshots and write duplicates.
        async with structureRunLock(readPaths):
            return {"json_obj": await structureArticles(readPaths, readBackend)}


class OfficialStructureArticlesSkill(StructureArticlesSkill):
    def define(self) -> dict[str, Any]:
        return {
            **super().define(),
            "name": "official_structure_articles",
            "description": "批量消费高校官网抓取产生的待结构化文档",
        }

    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        if readBackend is None:
            raise RuntimeError("BackendClient 未初始化")
        readPaths = CrawlPaths(resolve_config_root(inputs["paths"]))
        return {
            "json_obj": await drainPendingArticles(
                readPaths,
                readBackend,
            )
        }
