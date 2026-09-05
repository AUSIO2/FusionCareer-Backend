"""One-time date-bounded WeChat archive runner."""

from __future__ import annotations

import argparse
import asyncio
import json
import time
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import requests

from app.config import settings
from app.integrations.backend import BackendClient
from app.skills.business.wechat.core import (
    WechatApiError,
    build_http_session,
    get_headers,
    is_valid_article_link,
    load_json_file,
    readArticles,
    readWechatAuth,
    save_url_to_md,
)
from app.skills.business.wechat.paths import WechatPaths
from app.skills.business.wechat.store import WechatStore
from app.skills.business.wechat.structure_articles import structureArticles


readZone = ZoneInfo("Asia/Shanghai")


def readTimestamp(readDate: str) -> int:
    return int(datetime.fromisoformat(readDate).replace(tzinfo=readZone).timestamp())


def filterArticles(readArticles: list[dict], readStart: int, readEnd: int) -> tuple[list[dict], bool]:
    createArticles = []
    readReached = False
    for readArticle in readArticles:
        readPublished = int(readArticle.get("create_time") or 0)
        if readPublished < readStart:
            readReached = True
        elif readPublished < readEnd and is_valid_article_link(readArticle.get("link")):
            createArticles.append(readArticle)
    return createArticles, readReached


def readPage(
    readSession,
    readFakeid: str,
    readAccount: str,
    readToken: str,
    readCookie: str,
    readBegin: int,
    readRetrySeconds: int,
    readMaxRetries: int,
) -> list[dict]:
    for readAttempt in range(readMaxRetries + 1):
        try:
            return readArticles(
                readSession,
                readFakeid,
                readAccount,
                readToken,
                readCookie,
                readBegin,
                10,
            )[0]
        except WechatApiError as readError:
            if "freq control" not in str(readError) or readAttempt == readMaxRetries:
                raise
            print(json.dumps({"event": "rate_limit", "retry": readAttempt + 1}), flush=True)
            time.sleep(readRetrySeconds)
    return []


def crawlAccounts(
    readPaths: WechatPaths,
    readStart: int,
    readEnd: int,
    readPageDelay: int,
    readAccountDelay: int,
    readRetrySeconds: int,
    readMaxRetries: int,
) -> None:
    readConfig = load_json_file(readPaths.config_file)
    readToken, readCookie = readWechatAuth(readConfig)
    readStore = WechatStore(readPaths.database_file)
    readSession = build_http_session()
    readHeaders = get_headers(readCookie, readToken)
    readArticleRoot = readPaths.articles_base_dir(readConfig)

    for readIndex, readAccount in enumerate(readStore.readAccounts(), 1):
        readRun = readStore.startRun("range", readAccount.fakeid)
        readSaved = 0
        try:
            readBegin = 0
            readFinished = False
            while not readFinished:
                readArticles = readPage(
                    readSession,
                    readAccount.fakeid,
                    readAccount.name,
                    readToken,
                    readCookie,
                    readBegin,
                    readRetrySeconds,
                    readMaxRetries,
                )
                if not readArticles:
                    break
                if readBegin == 0:
                    readStore.saveCheckpoint(readAccount.fakeid, readArticles[0].get("link") or "")
                createArticles, readFinished = filterArticles(readArticles, readStart, readEnd)
                for createArticle in createArticles:
                    if readStore.hasArticleRecord(readAccount.fakeid, createArticle):
                        continue
                    readStatus = save_url_to_md(
                        readSession,
                        createArticle,
                        readHeaders,
                        readAccount.fakeid,
                        readAccount.name,
                        readArticleRoot,
                        readConfig,
                        readStore,
                        manifest_dir=readPaths.manifest_dir,
                    )
                    if readStatus == "saved":
                        readSaved += 1
                    elif readStatus not in {"jump", "skip"}:
                        raise RuntimeError(f"article save failed: {createArticle.get('title')}")
                if len(readArticles) < 10:
                    break
                readBegin += 10
                time.sleep(readPageDelay)
            readStore.finishRun(readRun, "SUCCESS", readSaved)
            print(json.dumps({"event": "account", "index": readIndex, "name": readAccount.name,
                              "saved": readSaved}, ensure_ascii=False), flush=True)
        except Exception as readError:
            readStore.finishRun(readRun, "FAILED", readSaved, str(readError))
            raise
        time.sleep(readAccountDelay)


async def structurePending(readPaths: WechatPaths) -> None:
    createBackend = BackendClient()
    try:
        while True:
            createResult = await structureArticles(readPaths, createBackend)
            print(json.dumps({"event": "structure", **createResult}), flush=True)
            if createResult["articleCount"] == 0:
                return
    finally:
        await createBackend.close()


def createDailySchedule() -> None:
    readBody = {
        "id": "wechat-daily",
        "workflow": "wechat_daily_body",
        "enabled": True,
        "trigger": {"type": "cron", "cron": "0 17 * * *"},
        "overrides": {"paths.json_obj": {"config_root": "/data/wechat"}},
        "loop": {
            "judge_skill": "wechat_judge_accounts",
            "max_iterations": 60,
            "judge_inputs": {},
            "initial_globals": {"stats": {}},
            "finalize_skill": "wechat_finalize_daily",
            "finalize_inputs": {"paths": {"config_root": "/data/wechat"}},
            "iteration_delay_seconds": 20,
        },
        "description": "每天17:00串行抓取公众号增量",
    }
    readResponse = requests.put(
        "http://127.0.0.1:8900/api/admin/schedules/wechat-daily",
        headers={"X-Agent-Admin-Token": settings.agent_admin_token},
        json=readBody,
        timeout=30,
    )
    readResponse.raise_for_status()


def runArchive() -> None:
    createParser = argparse.ArgumentParser()
    createParser.add_argument("--root", default="/data/wechat")
    createParser.add_argument("--start", required=True)
    createParser.add_argument("--end", required=True)
    createParser.add_argument("--page-delay", type=int, default=10)
    createParser.add_argument("--account-delay", type=int, default=20)
    createParser.add_argument("--retry-seconds", type=int, default=600)
    createParser.add_argument("--max-retries", type=int, default=18)
    readArgs = createParser.parse_args()
    readPaths = WechatPaths(Path(readArgs.root))
    crawlAccounts(
        readPaths,
        readTimestamp(readArgs.start),
        readTimestamp(readArgs.end),
        readArgs.page_delay,
        readArgs.account_delay,
        readArgs.retry_seconds,
        readArgs.max_retries,
    )
    asyncio.run(structurePending(readPaths))
    createDailySchedule()


if __name__ == "__main__":
    runArchive()
