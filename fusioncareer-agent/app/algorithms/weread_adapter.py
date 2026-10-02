"""Private workspace adapter for the upstream WeRead crawler."""

from __future__ import annotations

import hashlib
import json
import os
import sys
import time
from pathlib import Path


def run_weread(operation: str, request: dict, root: Path, config: dict, vendor: Path) -> dict:
    sys.path.insert(0, str(vendor / "weread_github_release"))
    from weread import paths

    paths.ROOT = root
    paths.HISTORY_FILE = root / "history_weread.json"
    paths.SESSION_FILE = root / "weread_session.json"
    from weread import auth, modes, storage

    auth.SESSION_FILE = paths.SESSION_FILE
    modes.ROOT, modes.HISTORY_FILE = root, paths.HISTORY_FILE
    http_get = storage.http_get

    def checked_get(*args, **kwargs):
        response = http_get(*args, **kwargs)
        response.raise_for_status()
        return response

    storage.http_get = checked_get
    if operation == "weread_login":
        import qrcode

        def show_qr(url):
            path = root / "weread-login.png"
            qrcode.make(url).save(path)
            return path

        auth._show_login_qr = show_qr
        credentials = auth.login_weread_mobile()
        auth.save_session(credentials)
        os.chmod(paths.SESSION_FILE, 0o600)
        return {"synced": True}
    credentials = auth.load_session()
    if not credentials:
        raise ValueError("WeRead session missing; run weread_login first")
    accounts = request.get("accounts") or []
    if not accounts:
        raise ValueError("accounts must contain fakeid/name entries")
    fakeids = [str(a["fakeid"]) for a in accounts]
    names = {i: str(a.get("name") or a["fakeid"]) for i, a in enumerate(accounts)}
    directory = root / "data/articles"
    directory.mkdir(parents=True, exist_ok=True)
    previous = paths.HISTORY_FILE.read_bytes() if paths.HISTORY_FILE.exists() else None
    # Upstream catches per-account errors; remember them so a failed run is not reported as success.
    failures = []
    fetch = modes.fetch_articles
    save = modes.save_url_to_md

    def checked_fetch(*args, **kwargs):
        if failures:
            raise RuntimeError("WeRead run paused after an earlier account failure")
        try:
            return fetch(*args, **kwargs)
        except Exception:
            failures.append("listing")
            raise

    def checked_save(*args, **kwargs):
        result = save(*args, **kwargs)
        if result not in ("saved", "jump"):
            failures.append("download")
        elif config.get("crawl_root"):
            from weread.storage import clean_filename

            from app.skills.business.crawlers.paths import CrawlPaths
            from app.skills.business.crawlers.store import CrawlStore

            article, name, base = args[:3]
            stamp = int(article.get("create_time") or 0)
            day = time.strftime("%Y-%m-%d", time.localtime(stamp)) if stamp else "Unknown"
            path = Path(base) / clean_filename(name) / f"{day}_{clean_filename(article.get('title'))}.md"
            account_id = next(fakeid for i, fakeid in enumerate(fakeids) if names[i] == name)
            store = CrawlStore(CrawlPaths(Path(config["crawl_root"])).database_file)
            store.saveAccount(account_id, name)
            store.saveArticle(account_id, article, path, hashlib.sha256(path.read_bytes()).hexdigest())
        return result

    modes.fetch_articles, modes.save_url_to_md = checked_fetch, checked_save
    try:
        if operation == "weread_bootstrap":
            modes.mode_bootstrap(
                credentials, fakeids, names, int(config.get("bootstrap_article_limit", 10)), str(directory), config
            )
        else:
            modes.mode_daily(credentials, fakeids, names, str(directory), config)
        if failures:
            raise RuntimeError("WeRead fetch failed; refresh the session or retry later")
    except BaseException:
        if previous is None:
            paths.HISTORY_FILE.unlink(missing_ok=True)
        else:
            paths.HISTORY_FILE.write_bytes(previous)
        raise
    return {
        "accounts": len(accounts),
        "directory": str(directory),
        "source": "weread",
        "history": json.loads(paths.HISTORY_FILE.read_text()) if paths.HISTORY_FILE.exists() else {},
    }
