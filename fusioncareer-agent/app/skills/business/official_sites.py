"""Public official-site article discovery and archiving."""

from __future__ import annotations

import base64
import hashlib
import json
import re
import time
import zlib
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
REVERSE_DATE_PATTERN = re.compile(r"(\d{1,2})\s+(20\d{2})[年./-](\d{1,2})")
MONTH_DATE_PATTERN = re.compile(r"(\d{1,2})月\s*(\d{1,2})日")
SHORT_DATE_PATTERN = re.compile(r"(?<!\d)(\d{1,2})[-/.](\d{1,2})(?!\d)")
USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/151 Safari/537.36"
UESTC_SLUGS = {
    "NOTICE_ANNOUNCEMENT": "notice",
    "EMPLOYMENT_NEWS": "news",
    "JOB_INFORMATION": "jobs",
    "GOVERNMENT_RECRUITMENT": "recruitment",
    "INTERNATIONAL_JOB": "international",
}


def decodeHtml(readHtml: str | bytes) -> str | bytes:
    readBytes = readHtml.encode() if isinstance(readHtml, str) else readHtml
    readMatch = re.search(
        rb'Base64\.decode\(unzip\("([^"]+)"\)\.substr\((\d+)\)\)\.substr\((\d+)\)',
        readBytes,
    )
    if not readMatch:
        return readHtml
    try:
        readOuter = zlib.decompress(base64.b64decode(readMatch.group(1))).decode()
        readInner = base64.b64decode(readOuter[int(readMatch.group(2)) :]).decode()
        return readInner[int(readMatch.group(3)) :]
    except (ValueError, UnicodeDecodeError, zlib.error):
        return readHtml


def getPage(readSession, readSource: dict, readUrl: str):
    readRetries = int(readSource.get("limitRetries", 0))
    for readAttempt in range(readRetries + 1):
        readResponse = readSession.get(
            readUrl,
            headers={"User-Agent": USER_AGENT},
            timeout=60,
            verify=readSource.get("verify", True),
        )
        readResponse.raise_for_status()
        if "非法访问" not in readResponse.text:
            return readResponse
        if readAttempt < readRetries:
            time.sleep(float(readSource.get("limitDelaySeconds", 0)))
    raise RuntimeError(f"official source rate limit persisted: {readSource['id']}")


def cleanTitle(readTitle: str) -> str:
    readTitle = re.sub(r"^(顶|置顶|\[置顶\]|【置顶】)\s*", "", readTitle.strip())
    readTitle = re.sub(r"^\d{1,2}\s+20\d{2}[-./]\d{1,2}\s*", "", readTitle)
    return re.sub(r"^20\d{2}[-./]\d{1,2}[-./]\d{1,2}\s*", "", readTitle).strip()


def hasDate(readText: str) -> bool:
    return bool(
        DATE_PATTERN.search(readText)
        or REVERSE_DATE_PATTERN.search(readText)
        or MONTH_DATE_PATTERN.search(readText)
        or SHORT_DATE_PATTERN.search(readText)
    )


def parseDate(readText: str, readReference: datetime | None = None) -> int:
    readMatch = DATE_PATTERN.search(readText)
    if readMatch:
        readParts = tuple(int(readPart) for readPart in readMatch.groups())
    else:
        readMatch = REVERSE_DATE_PATTERN.search(readText)
        if readMatch:
            readDay, readYear, readMonth = (int(readPart) for readPart in readMatch.groups())
            readParts = (readYear, readMonth, readDay)
        else:
            readMatch = MONTH_DATE_PATTERN.search(readText) or SHORT_DATE_PATTERN.search(readText)
            if not readMatch or readReference is None:
                return 0
            readMonth, readDay = (int(readPart) for readPart in readMatch.groups())
            readYear = readReference.year - int(readMonth > readReference.month + 1)
            readParts = (readYear, readMonth, readDay)
    try:
        readDate = datetime(*readParts, tzinfo=BEIJING_TZ)
    except ValueError:
        return 0
    return int(readDate.timestamp())


def readContext(readNode, readPattern: re.Pattern, readBase: str, readCustom: bool = False) -> str:
    readParent = readNode
    for _ in range(3 if readCustom else 5):
        readLinks = readParent.xpath(".//a[@href]/@href")
        if not readCustom and sum(
            bool(readPattern.search(str(readLink)) or readPattern.search(urljoin(readBase, str(readLink))))
            for readLink in readLinks
        ) > 1:
            return ""
        readText = " ".join("".join(readParent.itertext()).split())
        if hasDate(readText):
            return readText
        readParent = readParent.getparent()
        if readParent is None:
            break
    return ""


def parseArticles(readHtml: str | bytes, readSource: dict, readStart: int, readEnd: int) -> list[dict]:
    readTree = parseHtml.fromstring(decodeHtml(readHtml))
    readPattern = re.compile(readSource["linkPattern"])
    readKeywords = tuple(readSource.get("keywords") or ())
    readReference = datetime.fromtimestamp(readEnd - 1, BEIJING_TZ)
    readArticles = []
    readSeen = set()
    readXpath = readSource.get("nodeXpath") or "//a[@href]"
    readAttribute = readSource.get("linkAttribute") or "href"
    for readNode in readTree.xpath(readXpath):
        readTitleNodes = readNode.xpath(readSource["titleXpath"]) if readSource.get("titleXpath") else []
        readTitleText = "".join(readTitleNodes) if readTitleNodes else "".join(readNode.itertext())
        readTitle = cleanTitle(" ".join(readTitleText.split()))
        readHref = str(readNode.get(readAttribute) or "").strip()
        readUrl = urljoin(readSource["listUrl"], readHref)
        if not readTitle or not (readPattern.search(readHref) or readPattern.search(readUrl)):
            continue
        if readKeywords and not any(readWord in readTitle for readWord in readKeywords):
            continue
        readTime = parseDate(
            readContext(readNode, readPattern, readSource["listUrl"], bool(readSource.get("nodeXpath"))),
            readReference,
        )
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


def readPages(readSession, readSource: dict, readStart: int, readEnd: int) -> list[dict]:
    readArticles = []
    readPages = int(readSource.get("maxPages", 1))
    for readPage in range(1, readPages + 1):
        readUrl = readSource.get("pageUrl", readSource["listUrl"]).format(page=readPage)
        readResponse = getPage(readSession, readSource, readUrl)
        readArticles.extend(parseArticles(readResponse.content, readSource, readStart, readEnd))
        time.sleep(float(readSource.get("delaySeconds", 0)))
    return list({readArticle["link"]: readArticle for readArticle in readArticles}.values())


def parseHit(readPayload: dict, readSource: dict, readStart: int, readEnd: int) -> list[dict]:
    readArticles = []
    for readEntry in (readPayload.get("module") or {}).get("data") or []:
        readTime = parseDate(str(readEntry.get("fbsj") or ""))
        if not readTime or readTime < readStart or readTime >= readEnd:
            continue
        readId = str(readEntry.get("id") or "")
        readArticles.append(
            {
                "title": cleanTitle(str(readEntry.get("fbxxbt") or "")),
                "link": urljoin(
                    readSource["homepage"],
                    f"tzgg/tzggxq?id={base64.b64encode(readId.encode()).decode()}",
                ),
                "create_time": readTime,
                "digest": "",
                "author": readSource["name"],
                "origin": readSource["listUrl"],
                "id": readId,
            }
        )
    return [readArticle for readArticle in readArticles if readArticle["title"] and readArticle["id"]]


def readHit(readSession, readSource: dict, readStart: int, readEnd: int) -> list[dict]:
    readResponse = readSession.post(
        readSource["listUrl"],
        headers={"User-Agent": USER_AGENT},
        data={"info": json.dumps({"page": 1, "pageSize": 1000, "take": 1000, "skip": 0, "xxfl": "100"})},
        timeout=60,
    )
    readResponse.raise_for_status()
    return parseHit(readResponse.json(), readSource, readStart, readEnd)


def readFudan(readSession, readSource: dict, readStart: int, readEnd: int) -> list[dict]:
    readCommon = {
        "login_user_id": 1,
        "login_admin_school_id": readSource["schoolId"],
        "login_admin_school_code": readSource["schoolCode"],
    }
    readResponse = readSession.get(readSource["authUrl"], params=readCommon, timeout=60)
    readResponse.raise_for_status()
    readAuth = (readResponse.json().get("data") or {}).get("lock")
    if not readAuth:
        raise ValueError("Fudan public API did not return an auth lock")
    readSource["_auth"] = readAuth
    readSource["_common"] = readCommon
    readArticles = []
    for readType in readSource.get("types", [1, 2, 3]):
        readPayload = {
            **readCommon,
            "school_id": readSource["schoolId"],
            "type": readType,
            "page": 1,
            "size": 1000,
        }
        readResponse = readSession.post(
            readSource["listUrl"], headers={"auth": readAuth}, data=readPayload, timeout=60
        )
        readResponse.raise_for_status()
        for readEntry in (readResponse.json().get("data") or {}).get("list") or []:
            readTime = int(readEntry.get("addtime") or 0)
            if readTime < readStart or readTime >= readEnd:
                continue
            readId = str(readEntry.get("id") or "")
            readArticles.append(
                {
                    "title": str(readEntry.get("title") or "").strip(),
                    "link": urljoin(
                        readSource["homepage"],
                        f"/Zhaopin/xiaozhao.html?type={readType}&id={readId}",
                    ),
                    "create_time": readTime,
                    "digest": str(readEntry.get("com_id_name") or "").strip(),
                    "author": readSource["name"],
                    "origin": readSource["listUrl"],
                    "id": readId,
                }
            )
    return list(
        {
            readArticle["id"]: readArticle
            for readArticle in readArticles
            if readArticle["title"] and readArticle["id"]
        }.values()
    )


def readRuc(readSession, readSource: dict, readStart: int, readEnd: int) -> list[dict]:
    readArticles = []
    for readCategory in readSource.get("categories", ["pc95"]):
        readResponse = readSession.post(
            readSource["listUrl"],
            headers={"User-Agent": USER_AGENT},
            json={"current": 1, "size": 1000, "category": readCategory},
            timeout=60,
        )
        readResponse.raise_for_status()
        for readEntry in (readResponse.json().get("data") or {}).get("records") or []:
            readTime = parseDate(str(readEntry.get("publishTime") or ""))
            if not readTime or readTime < readStart or readTime >= readEnd:
                continue
            readId = str(readEntry.get("postId") or "")
            readLink = str(readEntry.get("externalUrl") or "").strip()
            if not readLink:
                readLink = urljoin(readSource["homepage"], f"recruit-detail?id={readId}")
            readArticles.append(
                {
                    "title": str(readEntry.get("title") or "").strip(),
                    "link": readLink,
                    "create_time": readTime,
                    "digest": "",
                    "author": readSource["name"],
                    "origin": readSource["listUrl"],
                    "id": readId,
                    "category": readCategory,
                }
            )
    return list({readArticle["id"]: readArticle for readArticle in readArticles}.values())


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


def parseUstc(readPayload: dict, readSource: dict, readStart: int, readEnd: int) -> list[dict]:
    readArticles = []
    for readHtml in ((readPayload.get("Content") or {}).get("Contentclass") or []):
        readTree = parseHtml.fromstring(readHtml)
        readLinks = readTree.xpath("//a[@href]")
        readDates = readTree.xpath("//td[last()]//text()")
        if not readLinks or not readDates:
            continue
        readMatch = re.search(r"[?&]cid=(\d+)", str(readLinks[0].get("href") or ""))
        readTime = parseDate(" ".join(readDates))
        if not readMatch or not readTime or readTime < readStart or readTime >= readEnd:
            continue
        readId = readMatch.group(1)
        readArticles.append(
            {
                "title": cleanTitle(" ".join("".join(readLinks[0].itertext()).split())),
                "link": f"https://job.ustc.edu.cn/Announcement/info.aspx?itemid={readId}",
                "create_time": readTime,
                "digest": "",
                "author": readSource["name"],
                "origin": readSource["listUrl"],
                "id": readId,
            }
        )
    return readArticles


def parseCareerList(readPayload: dict, readSource: dict, readStart: int, readEnd: int) -> list[dict]:
    readArticles = []
    for readEntry in (readPayload.get("data") or {}).get("list") or []:
        try:
            readDate = datetime.fromisoformat(readEntry["fbrq"]).replace(tzinfo=BEIJING_TZ)
        except (KeyError, TypeError, ValueError):
            continue
        readTime = int(readDate.timestamp())
        if readTime < readStart or readTime >= readEnd:
            continue
        readId = str(readEntry.get("zpxxid") or "")
        readArticles.append(
            {
                "title": str(readEntry.get("zpzt") or "").strip(),
                "link": urljoin(readSource["homepage"], f"/career/zpxx/view/zpxx/{readId}"),
                "create_time": readTime,
                "digest": str(readEntry.get("dwmc") or "").strip(),
                "author": readSource["name"],
                "origin": readSource["listUrl"],
                "id": readId,
            }
        )
    return [readArticle for readArticle in readArticles if readArticle["title"] and readArticle["id"]]


def readCareerList(readSession, readSource: dict, readStart: int, readEnd: int) -> list[dict]:
    readArticles = []
    for readPage in range(1, int(readSource.get("maxPages", 5)) + 1):
        readUrl = f"{readSource['listUrl']}/{readPage}/10"
        readResponse = readSession.post(
            readUrl, headers={"User-Agent": USER_AGENT}, data={}, timeout=60
        )
        readResponse.raise_for_status()
        readPayload = readResponse.json()
        if readPayload.get("code") != 200:
            break
        readRows = (readPayload.get("data") or {}).get("list") or []
        readArticles.extend(parseCareerList(readPayload, readSource, readStart, readEnd))
        readDates = [str(readRow.get("fbrq") or "") for readRow in readRows]
        if not readRows or (readDates and min(readDates) < datetime.fromtimestamp(readStart, BEIJING_TZ).date().isoformat()):
            break
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
    elif readSource.get("format") == "hit":
        readResponse = readSession.post(
            readSource["detailUrl"],
            headers={"User-Agent": USER_AGENT},
            data={"info": json.dumps({"id": readArticle["id"]})},
            timeout=60,
        )
        readResponse.raise_for_status()
        readContentHtml = (((readResponse.json().get("module") or {}).get("xwtz_xq") or {}).get("pcdxxnr") or "")
        if not readContentHtml:
            return "error"
        readTree = parseHtml.fromstring(readContentHtml)
    elif readSource.get("format") == "fudan":
        readResponse = readSession.post(
            readSource["detailUrl"],
            headers={"auth": readSource["_auth"]},
            data={**readSource["_common"], "id": readArticle["id"]},
            timeout=60,
        )
        readResponse.raise_for_status()
        readContentHtml = (readResponse.json().get("data") or {}).get("remarks") or ""
        if not readContentHtml:
            return "error"
        readTree = parseHtml.fromstring(readContentHtml)
    elif readSource.get("format") == "ruc":
        readResponse = readSession.post(
            readSource["detailUrl"],
            headers={"User-Agent": USER_AGENT},
            json={"category": readArticle["category"], "post_id": readArticle["id"]},
            timeout=60,
        )
        readResponse.raise_for_status()
        readContentHtml = (readResponse.json().get("data") or {}).get("content") or ""
        if not readContentHtml:
            return "error"
        readTree = parseHtml.fromstring(readContentHtml)
    elif readSource.get("format") == "career_v2":
        readMatch = re.search(r"/zwxx/view/([^/?]+)", readArticle["link"])
        if not readMatch:
            return "error"
        readUrl = urljoin(readSource["homepage"], f"/career/zwxx/data/{readMatch.group(1)}")
        readResponse = readSession.get(readUrl, headers={"User-Agent": USER_AGENT}, timeout=60)
        readResponse.raise_for_status()
        readJob = readResponse.json().get("data") or {}
        readContentHtml = (
            f"<p>单位：{readJob.get('dwmc') or ''}</p>"
            f"<p>岗位：{readJob.get('zwmc') or ''}</p>"
            f"<p>地点：{readJob.get('gzdzxx') or readJob.get('gzdz') or ''}</p>"
            f"<p>学历：{readJob.get('xlyqmc') or ''}</p>"
            f"<div>{readJob.get('zwms') or ''}</div>"
            f"<div>{readJob.get('dwjs') or ''}</div>"
        )
        readTree = parseHtml.fromstring(readContentHtml)
    elif readSource.get("format") == "career_list":
        readUrl = urljoin(readSource["homepage"], f"/career/zpxx/data/zpxx/{readArticle['id']}")
        readResponse = readSession.post(
            readUrl, headers={"User-Agent": USER_AGENT}, data={}, timeout=60
        )
        readResponse.raise_for_status()
        readJob = readResponse.json().get("data") or {}
        readContentHtml = (
            f"<p>单位：{readJob.get('dwmc') or ''}</p>"
            f"<p>主题：{readJob.get('zpzt') or ''}</p>"
            f"<div>{readJob.get('zpxxEditor') or ''}</div>"
            f"<div>{readJob.get('dwjs') or ''}</div>"
        )
        readTree = parseHtml.fromstring(readContentHtml)
    elif readSource.get("format") == "ustc":
        readResponse = readSession.get(
            readSource["detailUrl"].format(id=readArticle["id"]),
            headers={"User-Agent": USER_AGENT},
            timeout=60,
        )
        readResponse.raise_for_status()
        readContentHtml = readResponse.json().get("ContentInfo") or ""
        if not readContentHtml:
            return "error"
        readTree = parseHtml.fromstring(readContentHtml)
    elif readSource.get("format") == "jyxt":
        readResponse = readSession.post(
            urljoin(readSource["homepage"], "/f/recruitmentinfo/ajax_show"),
            headers={"User-Agent": USER_AGENT},
            data={"recruitmentId": readArticle["id"]},
            timeout=60,
        )
        readResponse.raise_for_status()
        readObject = readResponse.json().get("object") or {}
        readJob = readObject.get("recruitmentinfo") or {}
        readPositions = readJob.get("recruitmentPositionList") or []
        readContentHtml = readJob.get("content") or readJob.get("shortContent") or ""
        if not readContentHtml:
            readContentHtml = "".join(
                f"<h2>{readPosition.get('positionName') or ''}</h2>"
                f"<p>{readPosition.get('positionDescription') or ''}</p>"
                for readPosition in readPositions
            )
        readCompany = readJob.get("corporationinfo") or {}
        readContentHtml += f"<p>{readCompany.get('corporationinfoIntroduction') or ''}</p>"
        if not readContentHtml:
            return "error"
        readTree = parseHtml.fromstring(readContentHtml)
    else:
        readResponse = getPage(readSession, readSource, readArticle["link"])
        readTree = parseHtml.fromstring(decodeHtml(readResponse.content))
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
        readPaths = WechatPaths(resolve_config_root(inputs["paths"]))
        readConfig = load_json_file(readPaths.config_file)
        readDays = int(readConfig.get("official_lookback_days", 3))
        readEnd = datetime.now(BEIJING_TZ) + timedelta(days=1)
        readStart = readEnd - timedelta(days=readDays + 1)
        return {"json_obj": crawlSites(readPaths, int(readStart.timestamp()), int(readEnd.timestamp()))}
