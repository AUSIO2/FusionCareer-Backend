from app.config import settings
from app.skills.business.crawlers.paths import CrawlPaths, resolve_config_root
from app.skills.business.crawlers.store import CrawlStore


def test_shared_database_reuses_existing_records(tmp_path):
    legacy = tmp_path / "wechat.db"
    store = CrawlStore(legacy)
    article = {"title": "官网招聘", "link": "https://example.test/job", "create_time": 1}
    markdown = tmp_path / "article.md"
    markdown.write_text("招聘正文")
    store.saveArticle("official-source", article, markdown, "hash")
    paths = CrawlPaths(tmp_path)
    assert paths.database_file == legacy
    assert CrawlStore(paths.database_file).hasArticleRecord("official-source", article)
    assert not (tmp_path / "crawl.db").exists()


def test_new_database_and_config_root_fallback(tmp_path, monkeypatch):
    assert CrawlPaths(tmp_path).database_file == tmp_path / "crawl.db"
    monkeypatch.setattr(settings, "wechat_config_root", str(tmp_path / "legacy"))
    monkeypatch.setattr(settings, "crawl_config_root", "")
    assert resolve_config_root({}) == (tmp_path / "legacy").resolve()
    monkeypatch.setattr(settings, "crawl_config_root", str(tmp_path / "crawl"))
    assert resolve_config_root({}) == (tmp_path / "crawl").resolve()
    assert resolve_config_root({"config_root": str(tmp_path / "explicit")}) == (tmp_path / "explicit").resolve()
