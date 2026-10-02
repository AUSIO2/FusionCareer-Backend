"""官网抓取编排与工作流 Skill 入口。"""

import json
import time
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

from app.core.base_skill import BaseSkill
from app.skills.business.crawlers.content import BEIJING_TZ
from app.skills.business.crawlers.io import build_http_session, load_json_file
from app.skills.business.crawlers.paths import CrawlPaths, resolve_config_root
from app.skills.business.crawlers.store import CrawlStore
from .api_lists import readJyxt, readCareerList, readHit, readFudan, readRuc, parseUestc, parseUstc
from .archive import saveArticle
from .common import getPage
from .html_lists import parseArticles, readPages

SOURCES_FILE = Path(__file__).resolve().parents[4] / "presets" / "official_sources.json"


def crawlSites(readPaths: CrawlPaths, readStart: int, readEnd: int) -> dict[str, int]:
    readSources = json.loads(SOURCES_FILE.read_text(encoding="utf-8"))
    readSession = build_http_session()
    readStore = CrawlStore(readPaths.database_file)
    readSaved = 0
    readFound = 0
    for readSource in readSources:
        readRun = readStore.startRun("official", readSource["fakeid"])
        saveCount = 0
        try:
            if readSource.get("format") == "jyxt":
                readArticles = readJyxt(readSession, readSource, readStart, readEnd)
            elif readSource.get("format") == "career_list":
                readArticles = readCareerList(readSession, readSource, readStart, readEnd)
            elif readSource.get("format") == "hit":
                readArticles = readHit(readSession, readSource, readStart, readEnd)
            elif readSource.get("format") == "fudan":
                readArticles = readFudan(readSession, readSource, readStart, readEnd)
            elif readSource.get("format") == "ruc":
                readArticles = readRuc(readSession, readSource, readStart, readEnd)
            elif readSource.get("pageUrl"):
                readArticles = readPages(readSession, readSource, readStart, readEnd)
            else:
                readResponse = getPage(readSession, readSource, readSource["listUrl"])
                if readSource.get("format") == "uestc":
                    readArticles = parseUestc(readResponse.json(), readSource, readStart, readEnd)
                elif readSource.get("format") == "ustc":
                    readArticles = parseUstc(readResponse.json(), readSource, readStart, readEnd)
                else:
                    readArticles = parseArticles(readResponse.content, readSource, readStart, readEnd)
            readFound += len(readArticles)
            for readArticle in readArticles:
                if readStore.hasArticleRecord(readSource["fakeid"], readArticle):
                    continue
                if saveArticle(readSession, readPaths, readStore, readSource, readArticle) == "saved":
                    saveCount += 1
                time.sleep(float(readSource.get("delaySeconds", 0)))
            readSaved += saveCount
            readStore.finishRun(readRun, "SUCCESS", saveCount)
        except Exception as readError:  # noqa: BLE001 - one source must not stop the remaining sources
            readStore.finishRun(readRun, "FAILED", saveCount, str(readError))
    return {"sourceCount": len(readSources), "articleCount": readFound, "savedCount": readSaved}


class OfficialCrawlSitesSkill(BaseSkill):
    def define(self) -> dict:
        return {
            "name": "official_crawl_sites",
            "description": "抓取免登录的高校官方就业信息源",
            "inputs": {"paths": "json_obj"},
            "outputs": {"json_obj": "json_obj"},
        }

    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        readPaths = CrawlPaths(resolve_config_root(inputs["paths"]))
        readConfig = load_json_file(readPaths.config_file)
        readDays = int(readConfig.get("official_lookback_days", 3))
        readEnd = datetime.now(BEIJING_TZ) + timedelta(days=1)
        readStart = readEnd - timedelta(days=readDays + 1)
        return {"json_obj": crawlSites(readPaths, int(readStart.timestamp()), int(readEnd.timestamp()))}
