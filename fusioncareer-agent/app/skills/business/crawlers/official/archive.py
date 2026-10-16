"""下载官网文章正文，转成 Markdown 并记录采集状态。"""

import hashlib
import json
import re
from datetime import datetime
from urllib.parse import urljoin
from lxml import html as parseHtml

from app.skills.business.crawlers.content import BEIJING_TZ, clean_filename, html_to_markdown
from app.skills.business.crawlers.io import append_jsonl, load_json_file
from app.skills.business.crawlers.paths import CrawlPaths
from app.skills.business.crawlers.store import CrawlStore
from app.skills.business.crawlers.wechat.core import get_headers, save_url_to_md
from .common import USER_AGENT, decodeHtml, getPage
from .html_lists import readContent


def saveArticle(
    readSession,
    readPaths: CrawlPaths,
    readStore: CrawlStore,
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
