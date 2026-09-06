"""mock HTTP 下 wechat 单号 daily / finalize。"""

from __future__ import annotations

from pathlib import Path
from unittest.mock import patch

import pytest

from app.runtime.paths import atomic_write_json
from app.skills.business.wechat.core import (
    finalize_daily_run,
    process_account_daily,
)
from app.skills.business.wechat.paths import WechatPaths
from app.skills.business.wechat.store import WechatStore


def _setup_config_root(root: Path) -> WechatPaths:
    root.mkdir(parents=True, exist_ok=True)
    atomic_write_json(
        root / "config.json",
        {
            "token": "t",
            "cookie": "c",
            "articles_base_dir": "articles",
            "daily_mirror_to_dated_folder": False,
        },
    )
    (root / "gzh.txt").write_text("fakeid_a\nfakeid_b\n", encoding="utf-8")
    (root / "公众号名字").write_text("AccountA\nAccountB\n", encoding="utf-8")
    paths = WechatPaths(root)
    store = WechatStore(paths.database_file)
    store.importAccounts(paths.fakeids_file, paths.account_names_file)
    store.saveCheckpoint("fakeid_a", "https://mp.weixin.qq.com/s/old")
    return paths


def test_process_account_daily_pagination_stop(tmp_path: Path):
    paths = _setup_config_root(tmp_path)
    page1 = [
        {"title": "New 1", "link": "https://mp.weixin.qq.com/s/new1", "create_time": 1},
        {"title": "Old Title", "link": "https://mp.weixin.qq.com/s/old", "create_time": 2},
    ]

    def fake_get_articles(session, fakeid, token, cookie, begin=0, count=5):
        if begin == 0:
            return page1, len(page1)
        return [], 0

    html = 'var nickname = "ResolvedA";<div id="js_content"><p>hello</p></div>'

    class FakeResp:
        encoding = "utf-8"
        text = f"<html><body>{html}</body></html>"

        def raise_for_status(self):
            return None

    with (
        patch(
            "app.skills.business.wechat.core.get_articles",
            side_effect=fake_get_articles,
        ),
        patch("app.skills.business.wechat.core.http_get", return_value=FakeResp()),
        patch("app.skills.business.wechat.core.time.sleep"),
    ):
        result = process_account_daily(paths, "fakeid_a", "AccountA")
    assert result["saved_count"] >= 1
    store = WechatStore(paths.database_file)
    assert store.readAccount("fakeid_a").lastArticleUrl == "https://mp.weixin.qq.com/s/new1"
    assert store.readAccount("fakeid_a").name == "AccountA"
    assert store.hasArticle("https://mp.weixin.qq.com/s/new1")
    manifest = (paths.manifest_dir / "manifest.jsonl").read_text(encoding="utf-8")
    assert "new1" in manifest


def test_finalize_daily_run(tmp_path: Path):
    paths = _setup_config_root(tmp_path)
    out = finalize_daily_run(paths, {"stats": {"AccountA": 2, "AccountB": 0}})
    assert out["total_new"] == 2
    report = paths.daily_report_file.read_text(encoding="utf-8")
    assert "AccountA" in report


def testCheckpointFailure(tmp_path: Path):
    paths = _setup_config_root(tmp_path)
    readPage = [
        {"title": "New 1", "link": "https://mp.weixin.qq.com/s/new1", "create_time": 1},
        {"title": "Old", "link": "https://mp.weixin.qq.com/s/old", "create_time": 2},
    ]

    with (
        patch("app.skills.business.wechat.core.get_articles", return_value=(readPage, 2)),
        patch("app.skills.business.wechat.core.http_get", side_effect=RuntimeError("write failed")),
        patch("app.skills.business.wechat.core.time.sleep"),
        pytest.raises(RuntimeError),
    ):
        process_account_daily(paths, "fakeid_a", "AccountA")

    readStore = WechatStore(paths.database_file)
    assert readStore.readAccount("fakeid_a").lastArticleUrl == "https://mp.weixin.qq.com/s/old"
