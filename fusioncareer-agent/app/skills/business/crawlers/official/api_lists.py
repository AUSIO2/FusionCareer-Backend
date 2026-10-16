"""各高校就业平台的 JSON 列表接口。"""

import base64
import json
import re
from datetime import datetime
from urllib.parse import urljoin
from lxml import html as parseHtml

from app.skills.business.crawlers.content import BEIJING_TZ
from .common import USER_AGENT, cleanTitle, parseDate

UESTC_SLUGS = {
    "NOTICE_ANNOUNCEMENT": "notice",
    "EMPLOYMENT_NEWS": "news",
    "JOB_INFORMATION": "jobs",
    "GOVERNMENT_RECRUITMENT": "recruitment",
    "INTERNATIONAL_JOB": "international",
}


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
