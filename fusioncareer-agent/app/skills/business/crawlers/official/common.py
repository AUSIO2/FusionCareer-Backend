"""官网 HTML 解码、日期解析与限流请求。"""

import base64
import re
import time
import zlib
from datetime import datetime

from app.skills.business.crawlers.content import BEIJING_TZ

DATE_PATTERN = re.compile(r"(20\d{2})[年./-](\d{1,2})[月./-](\d{1,2})")
REVERSE_DATE_PATTERN = re.compile(r"(\d{1,2})\s+(20\d{2})[年./-](\d{1,2})")
MONTH_DATE_PATTERN = re.compile(r"(\d{1,2})月\s*(\d{1,2})日")
SHORT_DATE_PATTERN = re.compile(r"(?<!\d)(\d{1,2})[-/.](\d{1,2})(?!\d)")
USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/151 Safari/537.36"


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
