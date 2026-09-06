"""Structure pending crawled articles and create offline Java job drafts."""

from __future__ import annotations

import asyncio
import json
import logging
import re
from pathlib import Path
from typing import Any

from app.algorithms.job_structuring import structureJobs
from app.core.base_skill import BaseSkill
from app.integrations.backend import BackendClient
from app.integrations.llm import LLMClient
from app.skills.business.wechat.core import resolve_config_root
from app.skills.business.wechat.paths import WechatPaths
from app.skills.business.wechat.store import WechatStore

readBackend: BackendClient | None = None
logger = logging.getLogger(__name__)
CONTACT_PATTERN = re.compile(
    r"[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\.[A-Za-z]{2,}|"
    r"(?<!\d)1[3-9]\d{9}(?!\d)|(?<!\d)0\d{2,3}[- ]?\d{7,8}(?!\d)|"
    r"联系人\s*[:：]\s*\S{1,32}|"
    r"(网申|投递|报名|申请|招聘官网|简历|应聘).{0,80}(https?://|邮箱|邮件|二维码|扫码|入口|系统)|"
    r"(二维码|扫码).{0,30}(投递|报名|申请|应聘)"
)
RESTRICT_PATTERN = re.compile(r"仅限.{0,12}(本校|我校)|只面向.{0,12}(本校|我校)|凭.{0,12}(学生证|校园卡)")


def set_backend_client(updateBackend: BackendClient) -> None:
    global readBackend
    readBackend = updateBackend


def buildJobKey(readJob: dict) -> tuple[str, str, str]:
    return (
        str(readJob.get("sourceUrl") or "").strip().casefold(),
        str(readJob.get("companyName") or "").strip().casefold(),
        str(readJob.get("positionName") or "").strip().casefold(),
    )


def createSummary(readArticle: dict, readText: str) -> dict:
    return {
        "sourceType": "CRAWL",
        "sourceUrl": readArticle["url"],
        "companyName": str(readArticle["title"])[:128],
        "positionName": "招聘岗位汇总",
        "jobCategory": "OTHER",
        "jobSubCategory": "OTHER",
        "recruitType": "OTHER",
        "jobDesc": readText[:12000],
        "status": "OFFLINE",
    }


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
    readPaths: WechatPaths,
    updateBackend: BackendClient,
    readClient: LLMClient | None = None,
) -> dict[str, int]:
    readStore = WechatStore(readPaths.database_file)
    readExisting = {buildJobKey(readJob) for readJob in await updateBackend.list_job_posts()}
    readArticles = readStore.readPendingArticles()
    readFudan = readFudanIds()
    readSemaphore = asyncio.Semaphore(5)

    async def createArticle(readArticle: dict) -> tuple[int, int, int]:
        async with readSemaphore:
            try:
                readText = Path(readArticle["markdown_path"]).read_text(encoding="utf-8")
                if readArticle["fakeid"] not in readFudan and (
                    not checkAction(readText) or checkRestriction(readText)
                ):
                    readStore.markStructured(readArticle["url"])
                    return 0, 0, 1
                readResult = await structureJobs(readText, readArticle["url"], "CRAWL", readClient)
                createJobs = [
                    createJob for createJob in readResult["jobs"]
                    if buildJobKey(createJob) not in readExisting
                ]
                await updateBackend.create_job_posts(createJobs)
                for createJob in createJobs:
                    readExisting.add(buildJobKey(createJob))
                readStore.markStructured(readArticle["url"])
                return len(createJobs), 0, 0
            except json.JSONDecodeError:
                try:
                    createJob = createSummary(readArticle, readText)
                    await updateBackend.create_job_posts([createJob])
                    readStore.markStructured(readArticle["url"])
                    return 1, 0, 0
                except Exception as readError:  # noqa: BLE001 - defer failed Java write
                    logger.warning("structure summary failed %s: %s", readArticle["url"], readError)
                    readStore.deferArticle(readArticle["url"])
                    return 0, 1, 0
            except Exception as readError:  # noqa: BLE001 - defer one article and continue the batch
                logger.warning("structure article failed %s: %s", readArticle["url"], readError)
                readStore.deferArticle(readArticle["url"])
                return 0, 1, 0

    readResults = await asyncio.gather(*(createArticle(readArticle) for readArticle in readArticles))
    createCount = sum(readResult[0] for readResult in readResults)
    failCount = sum(readResult[1] for readResult in readResults)
    skipCount = sum(readResult[2] for readResult in readResults)
    return {
        "articleCount": len(readArticles),
        "jobCount": createCount,
        "failedCount": failCount,
        "skippedCount": skipCount,
    }


class WechatStructureArticlesSkill(BaseSkill):
    def define(self) -> dict:
        return {
            "name": "wechat_structure_articles",
            "description": "将未处理公众号 Markdown 转为 Java 岗位草稿",
            "inputs": {"paths": "json_obj", "result": "json_obj"},
            "outputs": {"json_obj": "json_obj"},
        }

    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        if readBackend is None:
            raise RuntimeError("BackendClient 未初始化")
        readPaths = WechatPaths(resolve_config_root(inputs["paths"]))
        return {"json_obj": await structureArticles(readPaths, readBackend)}
