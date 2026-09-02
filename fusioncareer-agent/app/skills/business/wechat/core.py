"""微信公众号爬虫核心逻辑（单号处理、落盘、manifest）。"""

from __future__ import annotations

import hashlib
import logging
import re
import shutil
import time
from datetime import datetime
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

import requests

from app.config import settings
from app.skills.business.wechat.io import (
    append_jsonl,
    build_http_session,
    http_get,
    load_json_file,
    save_json_file,
)
from app.skills.business.wechat.paths import WechatPaths
from app.skills.business.wechat.store import WechatStore

logger = logging.getLogger(__name__)

BEIJING_TZ = ZoneInfo("Asia/Shanghai")
DEFAULT_ARTICLES_BASE_DIR = "公众号文章"


class WechatApiError(RuntimeError):
    pass


def beijing_now() -> datetime:
    return datetime.now(BEIJING_TZ)


def resolve_config_root(paths_payload: dict[str, Any]) -> Path:
    raw = paths_payload.get("config_root") or paths_payload.get("configRoot")
    if not raw:
        raise ValueError("paths 缺少 config_root")
    return Path(str(raw)).resolve()


def readWechatAuth(readConfig: dict) -> tuple[str, str]:
    readToken = settings.wechat_token or readConfig.get("token") or ""
    readCookie = settings.wechat_cookie or readConfig.get("cookie") or ""
    if not readToken or not readCookie:
        raise ValueError("缺少 WECHAT_TOKEN 或 WECHAT_COOKIE")
    return readToken, readCookie


def get_headers(cookie: str, token: str) -> dict[str, str]:
    return {
        "Host": "mp.weixin.qq.com",
        "Connection": "keep-alive",
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
        ),
        "Cookie": cookie,
        "Accept": "application/json, text/javascript, */*; q=0.01",
        "X-Requested-With": "XMLHttpRequest",
        "Referer": (
            f"https://mp.weixin.qq.com/cgi-bin/appmsg?t=media/appmsg_edit_v2&action=edit"
            f"&isNew=1&type=10&token={token}&lang=zh_CN"
        ),
        "Origin": "https://mp.weixin.qq.com",
    }


def get_articles(
    session: requests.Session,
    fakeid: str,
    token: str,
    cookie: str,
    begin: int = 0,
    count: int = 5,
) -> tuple[list[dict], int]:
    url = "https://mp.weixin.qq.com/cgi-bin/appmsgpublish"
    headers = get_headers(cookie, token)
    params = {
        "sub": "list",
        "begin": str(begin),
        "count": str(count),
        "fakeid": fakeid,
        "token": token,
        "lang": "zh_CN",
        "f": "json",
        "ajax": "1",
    }
    response = http_get(session, url, headers=headers, params=params)
    response.raise_for_status()
    data = response.json()
    readResp = data.get("base_resp") or {}
    if readResp.get("ret", 0) != 0:
        raise WechatApiError(f"WeChat API {readResp.get('ret')}: {readResp.get('err_msg', '')}")
    if "publish_page" not in data:
        raise WechatApiError("WeChat API response missing publish_page")
    publish_page = json_loads_embedded(data["publish_page"])
    publish_list = publish_page.get("publish_list", [])
    articles: list[dict] = []
    for publish_item in publish_list:
        publish_info = json_loads_embedded(publish_item.get("publish_info", "{}"))
        for appmsg in publish_info.get("appmsg_info", []):
            articles.append(
                {
                    "title": appmsg.get("title"),
                    "link": appmsg.get("content_url"),
                    "create_time": publish_info.get("sent_info", {}).get("time", 0),
                    "digest": appmsg.get("digest", ""),
                    "author": appmsg.get("author", ""),
                }
            )
    return articles, int(publish_page.get("total_count", 0))


def json_loads_embedded(raw: str | dict) -> dict:
    import json

    if isinstance(raw, dict):
        return raw
    try:
        return json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        return {}


def is_valid_article_link(link: str | None) -> bool:
    if not link:
        return False
    return "tempkey=" not in link


def clean_filename(title: str | None) -> str:
    return re.sub(r'[\\/*?:"<>|]', "", (title or "")).strip()


def readAccountName(readHtml: str) -> str:
    readMatch = re.search(r'var nickname = "([^"]+)"', readHtml)
    if readMatch:
        return readMatch.group(1).strip()
    readMatch = re.search(
        r'class="profile_meta_value">([^<]+)<', readHtml
    )
    return readMatch.group(1).strip() if readMatch else ""


def html_to_markdown(html: str) -> str:
    html = re.sub(r"<style.*?>.*?</style>", "", html, flags=re.DOTALL)
    html = re.sub(r"<script.*?>.*?</script>", "", html, flags=re.DOTALL)

    def replace_img(match):
        src = match.group(1) or match.group(2)
        return f"\n![]({src})\n"

    html = re.sub(r'<img[^>]+data-src="([^"]+)"[^>]*>', replace_img, html)
    html = re.sub(r'<img[^>]+src="([^"]+)"[^>]*>', replace_img, html)

    def replace_pre_code(match):
        code_content = match.group(1)
        code_content = re.sub(r"<code[^>]*>(.*?)</code>", r"\1", code_content, flags=re.DOTALL)
        code_content = (
            code_content.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", '"')
            .replace("&nbsp;", " ")
        )
        return f"\n```\n{code_content}\n```\n"

    html = re.sub(r"<pre[^>]*>(.*?)</pre>", replace_pre_code, html, flags=re.DOTALL)
    html = re.sub(r"<code[^>]*>(.*?)</code>", r"`\1`", html, flags=re.DOTALL)
    for i in range(6, 0, -1):
        html = re.sub(f"<h{i}[^>]*>(.*?)</h{i}>", "#" * i + r" \1\n", html)
    html = re.sub(r"<p[^>]*>", "\n", html)
    html = re.sub(r"</p>", "\n", html)
    html = re.sub(r"<br\s*/?>", "\n", html)
    html = re.sub(r"<(b|strong)[^>]*>(.*?)</\1>", r"**\2**", html)
    html = re.sub(r"<li[^>]*>(.*?)</li>", r"- \1\n", html)
    html = re.sub(r"<[^>]+>", "", html)
    html = (
        html.replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&quot;", '"')
    )
    html = re.sub(r"\n{3,}", "\n\n", html)
    html = re.sub(r" +", " ", html)
    return html.strip()


def append_manifest(
    manifest_dir: Path,
    *,
    account: str,
    article: dict,
    md_path: Path,
) -> None:
    manifest_dir.mkdir(parents=True, exist_ok=True)
    link = article.get("link") or ""
    source_id = hashlib.sha256(link.encode("utf-8")).hexdigest()
    record = {
        "source_id": source_id,
        "account": account,
        "title": article.get("title"),
        "link": link,
        "md_path": str(md_path),
        "crawled_at": beijing_now().isoformat(timespec="seconds"),
    }
    append_jsonl(manifest_dir / "manifest.jsonl", record)


def save_url_to_md(
    session: requests.Session,
    article: dict,
    headers: dict[str, str],
    fakeid: str,
    account_name: str,
    articles_base_dir: Path,
    config: dict,
    store: WechatStore,
    *,
    mirror_base_dir: Path | None = None,
    manifest_dir: Path | None = None,
) -> str:
    """返回 saved | jump | error | skip。"""
    url = article.get("link")
    title = article.get("title")
    digest = article.get("digest", "")
    if not url:
        return "skip"
    try:
        create_time = article.get("create_time")
        date_str = time.strftime("%Y-%m-%d", time.localtime(create_time))
    except Exception:
        date_str = "Unknown"

    try:
        resp = http_get(session, url, headers=headers, timeout=90)
        resp.encoding = "utf-8"
        content_html = resp.text
        readName = readAccountName(content_html)
        if readName:
            store.saveName(fakeid, readName)
        folder_name = store.readAccount(fakeid).name if readName else account_name
        articles_base_dir.mkdir(parents=True, exist_ok=True)
        safe_account = clean_filename(folder_name)
        account_dir = articles_base_dir / safe_account
        account_dir.mkdir(parents=True, exist_ok=True)
        safe_title = clean_filename(title)
        filename = account_dir / f"{date_str}_{safe_title}.md"
        if filename.is_file():
            readHash = hashlib.sha256(filename.read_bytes()).hexdigest()
            store.saveArticle(fakeid, article, filename, readHash)
            return "jump"

        content_match = re.search(
            r'<div[^>]*id="js_content"[^>]*>(.*?)</div>', content_html, re.DOTALL
        )
        if content_match:
            main_content = content_match.group(1)
        else:
            body_match = re.search(r"<body[^>]*>(.*?)</body>", content_html, re.DOTALL)
            main_content = body_match.group(1) if body_match else content_html

        markdown_content = f"# {title}\n\n**Date:** {date_str}\n**Link:** {url}\n"
        markdown_content += f"**Account:** {folder_name}\n"
        if digest:
            markdown_content += f"**Summary:** {digest}\n"
        markdown_content += "\n" + html_to_markdown(main_content)
        filename.write_text(markdown_content, encoding="utf-8")

        file_size = filename.stat().st_size
        min_kb = config.get("min_file_size_kb", 0)
        delete_small = config.get("delete_small_files", False)
        if delete_small and min_kb > 0 and file_size < min_kb * 1024:
            filename.unlink()
            return "error"

        if mirror_base_dir:
            mirror_account = mirror_base_dir / safe_account
            mirror_account.mkdir(parents=True, exist_ok=True)
            shutil.copy2(filename, mirror_account / filename.name)

        if manifest_dir is not None:
            append_manifest(manifest_dir, account=folder_name, article=article, md_path=filename)

        readHash = hashlib.sha256(filename.read_bytes()).hexdigest()
        store.saveArticle(fakeid, article, filename, readHash)

        time.sleep(1)
        return "saved"
    except Exception as e:
        logger.warning("save_url_to_md failed %s: %s", title, e)
        return "error"


def get_daily_folder_basename(config: dict) -> str:
    suffix = config.get("daily_folder_suffix", "新增")
    return beijing_now().strftime("%Y%m%d") + suffix


def get_daily_increment_dir(config_root: Path, config: dict) -> Path:
    return config_root / get_daily_folder_basename(config)


def process_account_daily(
    paths: WechatPaths,
    fakeid: str,
    account_name: str,
    *,
    session: requests.Session | None = None,
) -> dict[str, Any]:
    config = load_json_file(paths.config_file)
    token, cookie = readWechatAuth(config)

    session = session or build_http_session()
    headers = get_headers(cookie, token)
    store = WechatStore(paths.database_file)
    readAccount = store.readAccount(fakeid)
    account_name = readAccount.name
    readRun = store.startRun("daily", fakeid)
    articles_base = paths.articles_base_dir(config)
    daily_mirror: Path | None = None
    if config.get("daily_mirror_to_dated_folder", True):
        daily_mirror = get_daily_increment_dir(paths.config_root, config)
        daily_mirror.mkdir(parents=True, exist_ok=True)

    if not readAccount.lastArticleUrl:
        store.finishRun(readRun, "SKIPPED")
        return {"account": account_name, "saved_count": 0, "skipped": "no_history"}

    begin = 0
    count = 10
    new_articles: list[dict] = []
    found_overlap = False
    while not found_overlap:
        articles, _ = get_articles(session, fakeid, token, cookie, begin, count)
        if not articles:
            break
        for article in articles:
            link = article.get("link")
            if link == readAccount.lastArticleUrl:
                found_overlap = True
                break
            if not is_valid_article_link(link):
                continue
            new_articles.append(article)
        if len(articles) < count or found_overlap:
            break
        begin += count
        time.sleep(3)

    valid = [
        readArticle for readArticle in new_articles
        if is_valid_article_link(readArticle.get("link"))
        and not store.hasArticle(readArticle.get("link") or "")
    ]
    saved_count = 0
    try:
        for article in valid:
            status = save_url_to_md(
                session,
                article,
                headers,
                fakeid,
                account_name,
                articles_base,
                config,
                store,
                mirror_base_dir=daily_mirror,
                manifest_dir=paths.manifest_dir,
            )
            if status == "saved":
                saved_count += 1
            elif status not in {"jump", "skip"}:
                raise RuntimeError(f"article save failed: {article.get('title')}")
        if new_articles:
            store.saveCheckpoint(fakeid, new_articles[0].get("link") or "")
        store.finishRun(readRun, "SUCCESS", saved_count)
        return {"account": store.readAccount(fakeid).name, "saved_count": saved_count}
    except Exception as readError:
        store.finishRun(readRun, "FAILED", saved_count, str(readError))
        raise


def process_account_bootstrap(
    paths: WechatPaths,
    fakeid: str,
    account_name: str,
    article_limit: int,
    *,
    session: requests.Session | None = None,
) -> dict[str, Any]:
    config = load_json_file(paths.config_file)
    token, cookie = readWechatAuth(config)

    session = session or build_http_session()
    headers = get_headers(cookie, token)
    store = WechatStore(paths.database_file)
    account_name = store.readAccount(fakeid).name
    readRun = store.startRun("bootstrap", fakeid)
    articles_base = paths.articles_base_dir(config)
    articles_base.mkdir(parents=True, exist_ok=True)

    articles, _ = get_articles(session, fakeid, token, cookie, begin=0, count=max(article_limit, 10))
    if not articles:
        store.finishRun(readRun, "SUCCESS")
        return {"account": account_name, "saved_count": 0}

    collected: list[dict] = []
    for article in articles:
        if len(collected) >= article_limit:
            break
        if is_valid_article_link(article.get("link")):
            collected.append(article)

    if not collected:
        return {"account": account_name, "saved_count": 0}

    saved_count = 0
    try:
        for article in collected:
            status = save_url_to_md(
                session,
                article,
                headers,
                fakeid,
                account_name,
                articles_base,
                config,
                store,
                manifest_dir=paths.manifest_dir,
            )
            if status == "saved":
                saved_count += 1
            elif status not in {"jump", "skip"}:
                raise RuntimeError(f"article save failed: {article.get('title')}")
        store.saveCheckpoint(fakeid, collected[0].get("link") or "")
        store.finishRun(readRun, "SUCCESS", saved_count)
        time.sleep(2)
        return {"account": store.readAccount(fakeid).name, "saved_count": saved_count}
    except Exception as readError:
        store.finishRun(readRun, "FAILED", saved_count, str(readError))
        raise


def write_daily_summary_md(
    summary_path: Path,
    stats: dict[str, int],
    run_time_str: str,
    daily_dir_name: str,
    articles_base_dir: str,
) -> None:
    total = sum(stats.values())
    active = [(n, c) for n, c in stats.items() if c > 0]
    lines = [
        "# 公众号新增推文统计",
        "",
        f"**统计日期（北京时间）**：{beijing_now().strftime('%Y-%m-%d')}",
        f"**执行时间**：{run_time_str}",
        f"**增量目录**：`{daily_dir_name}`",
        f"**新增合计**：{total} 篇",
        f"**有更新的公众号数**：{len(active)} / {len(stats)}",
        "",
        "## 分号统计",
        "",
        "| 序号 | 公众号 | 新增篇数 |",
        "|------|--------|----------|",
    ]
    for i, (name, count) in enumerate(
        sorted(stats.items(), key=lambda x: (-x[1], x[0])), start=1
    ):
        lines.append(f"| {i} | {name} | {count} |")
    lines.extend(
        [
            "",
            "## 说明",
            "",
            "- 仅统计本次 daily 监测中新下载并写入本目录的文章。",
            f"- 主存档目录：`{articles_base_dir}/`",
        ]
    )
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def finalize_daily_run(paths: WechatPaths, state: dict[str, Any]) -> dict[str, Any]:
    config = load_json_file(paths.config_file)
    stats: dict[str, int] = dict(state.get("stats") or {})
    run_time = beijing_now().strftime("%Y-%m-%d %H:%M:%S")
    total = sum(stats.values())
    report_path = paths.daily_report_path(config)
    line: dict[str, Any] = {
        "time": run_time,
        "total_new": total,
        "by_account": stats,
    }
    daily_dir: Path | None = None
    if config.get("daily_mirror_to_dated_folder", True):
        daily_dir = get_daily_increment_dir(paths.config_root, config)
        daily_dir.mkdir(parents=True, exist_ok=True)
        line["daily_folder"] = daily_dir.name
    append_jsonl(report_path, line)

    summary_path: Path | None = None
    if daily_dir:
        summary_name = config.get("daily_summary_filename", "新增统计.md")
        summary_path = daily_dir / summary_name
        articles_base = str(paths.articles_base_dir(config))
        write_daily_summary_md(
            summary_path,
            stats,
            run_time,
            daily_dir.name,
            articles_base,
        )

    return {
        "total_new": total,
        "report_path": str(report_path),
        "summary_path": str(summary_path) if summary_path else None,
    }


def init_run_state(paths: WechatPaths, state_path: Path, iteration: int) -> dict[str, Any]:
    state = load_json_file(state_path, default={})
    if state.get("accounts") and state.get("total"):
        return state
    readStore = WechatStore(paths.database_file)
    readStore.importAccounts(paths.fakeids_file, paths.account_names_file)
    readAccounts = readStore.readAccounts()
    if not readAccounts:
        raise ValueError("未配置可用的微信公众号")
    state = {
        "account_index": iteration,
        "accounts": [
            {"fakeid": readAccount.fakeid, "name": readAccount.name}
            for readAccount in readAccounts
        ],
        "total": len(readAccounts),
        "stats": state.get("stats") or {},
    }
    save_json_file(state_path, state)
    return state


def merge_account_result(state_path: Path, result: dict[str, Any]) -> None:
    state = load_json_file(state_path, default={})
    stats: dict[str, int] = dict(state.get("stats") or {})
    account = result.get("account")
    if account:
        stats[account] = int(result.get("saved_count", 0))
    state["stats"] = stats
    save_json_file(state_path, state)
