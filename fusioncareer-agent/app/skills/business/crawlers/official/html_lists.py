"""常规 HTML 列表页、分页和正文节点定位。"""

import re
import time
from datetime import datetime
from urllib.parse import urljoin
from lxml import html as parseHtml

from app.skills.business.crawlers.content import BEIJING_TZ
from .common import cleanTitle, decodeHtml, getPage, hasDate, parseDate


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


def readContent(readTree, readXpath: str):
    readNodes = readTree.xpath(readXpath)
    if readNodes:
        return readNodes[0]
    readNodes = readTree.xpath("//article|//main|//div")
    return max(readNodes, key=lambda readNode: len("".join(readNode.itertext())), default=None)
