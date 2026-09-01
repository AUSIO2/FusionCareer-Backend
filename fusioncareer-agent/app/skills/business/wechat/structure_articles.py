"""Structure pending crawled articles and create offline Java job drafts."""

from __future__ import annotations

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


def set_backend_client(updateBackend: BackendClient) -> None:
    global readBackend
    readBackend = updateBackend


def buildJobKey(readJob: dict) -> tuple[str, str, str]:
    return (
        str(readJob.get("sourceUrl") or "").strip().casefold(),
        str(readJob.get("companyName") or "").strip().casefold(),
        str(readJob.get("positionName") or "").strip().casefold(),
    )


async def structureArticles(
    readPaths: WechatPaths,
    updateBackend: BackendClient,
    readClient: LLMClient | None = None,
) -> dict[str, int]:
    readStore = WechatStore(readPaths.database_file)
    readExisting = {buildJobKey(readJob) for readJob in await updateBackend.list_job_posts()}
    readArticles = readStore.readPendingArticles()
    createCount = 0
    for readArticle in readArticles:
        readText = Path(readArticle["markdown_path"]).read_text(encoding="utf-8")
        readResult = await structureJobs(readText, readArticle["url"], "CRAWL", readClient)
        createJobs = [
            createJob for createJob in readResult["jobs"]
            if buildJobKey(createJob) not in readExisting
        ]
        await updateBackend.create_job_posts(createJobs)
        for createJob in createJobs:
            readExisting.add(buildJobKey(createJob))
        readStore.markStructured(readArticle["url"])
        createCount += len(createJobs)
    return {"articleCount": len(readArticles), "jobCount": createCount}


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
