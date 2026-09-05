"""Public official-site article discovery and archiving."""

from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any
from urllib.parse import urljoin

from lxml import html as parseHtml

from app.core.base_skill import BaseSkill
from app.skills.business.wechat.core import (
    BEIJING_TZ,
    append_jsonl,
    build_http_session,
    clean_filename,
    get_headers,
    html_to_markdown,
    load_json_file,
    resolve_config_root,
    save_url_to_md,
)
from app.skills.business.wechat.paths import WechatPaths
from app.skills.business.wechat.store import WechatStore

DATE_PATTERN = re.compile(r"(20\d{2})[年./-](\d{1,2})[月./-](\d{1,2})")
USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/151 Safari/537.36"
UESTC_SLUGS = {
    "NOTICE_ANNOUNCEMENT": "notice",
    "EMPLOYMENT_NEWS": "news",
    "JOB_INFORMATION": "jobs",
    "GOVERNMENT_RECRUITMENT": "recruitment",
    "INTERNATIONAL_JOB": "international",
}


def parseDate(readText: str) -> int:
    readMatch = DATE_PATTERN.search(readText)
    if not readMatch:
        return 0
    readDate = datetime(*(int(readPart) for readPart in readMatch.groups()), tzinfo=BEIJING_TZ)
    return int(readDate.timestamp())


def readContext(readNode, readPattern: re.Pattern, readBase: str) -> str:
    readParent = readNode
    for _ in range(5):
        readLinks = readParent.xpath(".//a[@href]/@href")
        if sum(
            bool(readPattern.search(str(readLink)) or readPattern.search(urljoin(readBase, str(readLink))))
            for readLink in readLinks
        ) > 1:
            return ""
        readText = " ".join("".join(readParent.itertext()).split())
        if DATE_PATTERN.search(readText):
            return readText
        readParent = readParent.getparent()
        if readParent is None:
            break
    return ""


def parseArticles(readHtml: str | bytes, readSource: dict, readStart: int, readEnd: int) -> list[dict]:
    readTree = parseHtml.fromstring(readHtml)
    readPattern = re.compile(readSource["linkPattern"])
    readKeywords = tuple(readSource.get("keywords") or ())
    readArticles = []
    readSeen = set()
    for readNode in readTree.xpath("//a[@href]"):
        readTitle = " ".join("".join(readNode.itertext()).split())
        readHref = str(readNode.get("href") or "").strip()
        readUrl = urljoin(readSource["listUrl"], readHref)
        if not readTitle or not (readPattern.search(readHref) or readPattern.search(readUrl)):
            continue
        if readKeywords and not any(readWord in readTitle for readWord in readKeywords):
            continue
        readTime = parseDate(readContext(readNode, readPattern, readSource["listUrl"]))
        if not readTime or readTime < readStart or readTime >= readEnd:
            continue
        readKey = (readUrl, readTitle, readTime)
        if readKey in readSeen:
            continue
        readSeen.add(readKey)
        readArticles.append(
            {
                "title": readTitle,
                "link": readUrl,
                "create_time": readTime,
                "digest": "",
                "author": readSource["name"],
                "origin": readSource["listUrl"],
            }
        )
    return readArticles


def parseUestc(readPayload: dict, readSource: dict, readStart: int, readEnd: int) -> list[dict]:
    readArticles = []
    readTypes = set(readSource["types"])
    for readEntry in readPayload.get("data") or []:
        readType = readEntry.get("bannerTypeCode")
        if readType not in readTypes:
            continue
        try:
            readDate = datetime.fromisoformat(readEntry["publishTime"]).replace(tzinfo=BEIJING_TZ)
        except (KeyError, TypeError, ValueError):
            continue
        readTime = int(readDate.timestamp())
        if readTime < readStart or readTime >= readEnd:
            continue
        readSlug = UESTC_SLUGS[readType]
        readArticles.append(
            {
                "title": str(readEntry.get("title") or "").strip(),
                "link": f"https://jiuye.uestc.edu.cn/career/news/{readSlug}/{readEntry['id']}",
                "create_time": readTime,
                "digest": "",
                "author": readSource["name"],
                "origin": readSource["listUrl"],
                "content": readEntry.get("content") or "",
            }
        )
    return [readArticle for readArticle in readArticles if readArticle["title"]]


def parseJyxt(readPayload: dict, readSource: dict, readStart: int, readEnd: int) -> list[dict]:
    readArticles = []
    for readEntry in (readPayload.get("object") or {}).get("list") or []:
        try:
            readDate = datetime.fromisoformat(readEntry["startTime"]).replace(tzinfo=BEIJING_TZ)
        except (KeyError, TypeError, ValueError):
            continue
        readTime = int(readDate.timestamp())
        if readTime < readStart or readTime >= readEnd:
            continue
        readArticles.append(
            {
                "title": str(readEntry.get("title") or "").strip(),
                "link": urljoin(readSource["homepage"], str(readEntry.get("url") or "")),
                "create_time": readTime,
                "digest": str(readEntry.get("corporationName") or "").strip(),
                "author": readSource["name"],
                "origin": readSource["listUrl"],
                "id": str(readEntry.get("id") or ""),
            }
        )
    return [readArticle for readArticle in readArticles if readArticle["title"] and readArticle["link"]]


def readJyxt(readSession, readSource: dict, readStart: int, readEnd: int) -> list[dict]:
    readArticles = []
    readPage = 1
    readPages = int(readSource.get("maxPages", 60))
    while readPage <= readPages:
        readResponse = readSession.post(
            readSource["listUrl"],
            headers={"User-Agent": USER_AGENT},
            data={"pageNo": readPage, "pageSize": 100},
            timeout=60,
        )
        readResponse.raise_for_status()
        readPayload = readResponse.json()
        readObject = readPayload.get("object") or {}
        readRows = readObject.get("list") or []
        readArticles.extend(parseJyxt(readPayload, readSource, readStart, readEnd))
        readDates = [
            str(readRow.get("startTime") or "")
            for readRow in readRows
            if str(readRow.get("topFlag") or "0") == "0"
        ]
        if not readRows or readPage >= int(readObject.get("totalPage") or readPage):
            break
        if readDates and min(readDates) < datetime.fromtimestamp(readStart, BEIJING_TZ).isoformat(sep=" "):
            break
        readPage += 1
    return readArticles


def readContent(readTree, readXpath: str):
    readNodes = readTree.xpath(readXpath)
    if readNodes:
        return readNodes[0]
    readNodes = readTree.xpath("//article|//main|//div")
    return max(readNodes, key=lambda readNode: len("".join(readNode.itertext())), default=None)


def saveArticle(
    readSession,
    readPaths: WechatPaths,
    readStore: WechatStore,
    readSource: dict,
    readArticle: dict,
) -> str:
    if "mp.weixin.qq.com" in readArticle["link"]:
        return save_url_to_md(
            readSession,
            readArticle,
            get_headers("", ""),
            readSource["fakeid"],
            readSource["name"],
            readPaths.articles_base_dir(load_json_file(readPaths.config_file)),
            load_json_file(readPaths.config_file),
            readStore,
            manifest_dir=readPaths.manifest_dir,
        )

    if readSource.get("format") == "uestc":
        readTree = parseHtml.fromstring(readArticle["content"])
    elif readSource.get("format") == "jyxt":
        readResponse = readSession.post(
            urljoin(readSource["homepage"], "/f/recruitmentinfo/ajax_show"),
            headers={"User-Agent": USER_AGENT},
            data={"recruitmentId": readArticle["id"]},
            timeout=60,
        )
        readResponse.raise_for_status()
        readContentHtml = (
            ((readResponse.json().get("object") or {}).get("recruitmentinfo") or {}).get("content")
            or ""
        )
        if not readContentHtml:
            return "error"
        readTree = parseHtml.fromstring(readContentHtml)
    else:
        readResponse = readSession.get(
            readArticle["link"],
            headers={"User-Agent": USER_AGENT},
            timeout=60,
        )
        readResponse.raise_for_status()
        readTree = parseHtml.fromstring(readResponse.content)
    readNode = readContent(readTree, readSource["contentXpath"])
    if readNode is None:
        return "error"
    for removeNode in readNode.xpath(".//script|.//style|.//nav|.//form"):
        removeNode.drop_tree()
    readMarkdown = html_to_markdown(parseHtml.tostring(readNode, encoding="unicode"))
    if len(readMarkdown) < 100:
        return "error"

    readDate = datetime.fromtimestamp(readArticle["create_time"], BEIJING_TZ).strftime("%Y-%m-%d")
    readFolder = readPaths.config_root / "官网文章" / clean_filename(readSource["name"])
    readFolder.mkdir(parents=True, exist_ok=True)
    readFile = readFolder / f"{readDate}_{clean_filename(readArticle['title'])}.md"
    readText = (
        f"# {readArticle['title']}\n\n"
        f"**Date:** {readDate}\n"
        f"**Link:** {readArticle['link']}\n"
        f"**Source:** {readSource['name']}\n\n"
        f"{readMarkdown}\n"
    )
    readFile.write_text(readText, encoding="utf-8")
    readHash = hashlib.sha256(readFile.read_bytes()).hexdigest()
    readStore.saveArticle(readSource["fakeid"], readArticle, readFile, readHash)
    append_jsonl(
        readPaths.manifest_dir / "official.jsonl",
        {
            "source_id": hashlib.sha256(readArticle["link"].encode()).hexdigest(),
            "account": readSource["name"],
            "title": readArticle["title"],
            "link": readArticle["link"],
            "origin": readArticle["origin"],
            "md_path": str(readFile),
            "crawled_at": datetime.now(BEIJING_TZ).isoformat(timespec="seconds"),
        },
    )
    return "saved"


def crawlSites(readPaths: WechatPaths, readStart: int, readEnd: int) -> dict[str, int]:
    readFile = Path(__file__).resolve().parents[2] / "presets" / "official_sources.json"
    readSources = json.loads(readFile.read_text(encoding="utf-8"))
    readSession = build_http_session()
    readStore = WechatStore(readPaths.database_file)
    readSaved = 0
    readFound = 0
    for readSource in readSources:
        readRun = readStore.startRun("official", readSource["fakeid"])
        saveCount = 0
        try:
            if readSource.get("format") == "jyxt":
                readArticles = readJyxt(readSession, readSource, readStart, readEnd)
            else:
                readResponse = readSession.get(
                    readSource["listUrl"], headers={"User-Agent": USER_AGENT}, timeout=60
                )
                readResponse.raise_for_status()
                if readSource.get("format") == "uestc":
                    readArticles = parseUestc(readResponse.json(), readSource, readStart, readEnd)
                else:
                    readArticles = parseArticles(readResponse.content, readSource, readStart, readEnd)
            readFound += len(readArticles)
            for readArticle in readArticles:
                if readStore.hasArticleRecord(readSource["fakeid"], readArticle):
                    continue
                if saveArticle(readSession, readPaths, readStore, readSource, readArticle) == "saved":
                    saveCount += 1
            readSaved += saveCount
            readStore.finishRun(readRun, "SUCCESS", saveCount)
        except Exception as readError:
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
        readPaths = WechatPaths(resolve_config_root(inputs["paths"]))
        readConfig = load_json_file(readPaths.config_file)
        readDays = int(readConfig.get("official_lookback_days", 3))
        readEnd = datetime.now(BEIJING_TZ) + timedelta(days=1)
        readStart = readEnd - timedelta(days=readDays + 1)
        return {"json_obj": crawlSites(readPaths, int(readStart.timestamp()), int(readEnd.timestamp()))}
