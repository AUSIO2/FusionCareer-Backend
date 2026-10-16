import hashlib
from types import SimpleNamespace

from app.algorithms.article_dedup import ArticleDeduplicator, exactJaccard, textNgrams
from app.skills.business.crawlers.store import CrawlStore


def saveArticle(store, root, name, text):
    path = root / f"{name}.md"
    path.write_text(text, encoding="utf-8")
    url = f"https://example.test/{name}"
    store.saveArticle("source", {"link": url, "title": name}, path, hashlib.sha256(text.encode()).hexdigest())
    return url, path


def commitCanonical(store, dedup, url, path, text):
    store.claimPendingArticles(100, "success")
    signature = dedup.signature(url, str(path), text)
    assert store.markStructureCompleted(url, signature.record(), "success")
    return signature


def testDefaultsAndExactThresholdRecheck(tmp_path):
    store = CrawlStore(tmp_path / "crawl.db")
    dedup = ArticleDeduplicator(SimpleNamespace())
    original = "招聘岗位和投递要求。" + "".join(chr(0x4e00 + index) for index in range(400))
    url, path = saveArticle(store, tmp_path, "canonical", original)
    commitCanonical(store, dedup, url, path, original)
    nearText = original + "另一条补充信息。"
    near = dedup.signature("near", "", nearText)

    assert dedup.ngram == 3 and dedup.numPerm == 128
    assert dedup.lshThreshold == 0.6 and dedup.jaccardThreshold == 0.85
    match = dedup.findDuplicate(near, store)
    assert match is not None
    assert match.winnerUrl == url
    assert match.score == exactJaccard(textNgrams(original), textNgrams(nearText))
    # LSH may recall the candidate, but a looser-than-0.85 article still proceeds.
    different = dedup.signature("different", "", original + "".join(chr(0x6000 + i) for i in range(100)))
    assert exactJaccard(near.grams, different.grams) < 0.85
    assert dedup.findDuplicate(different, store) is None


def testEmptyFailedAndUncommittedArticlesCannotSuppressOthers(tmp_path):
    store = CrawlStore(tmp_path / "crawl.db")
    dedup = ArticleDeduplicator(SimpleNamespace())
    text = "招聘编辑，负责采访与新闻写作。投递邮箱：jobs@example.com"
    url, _path = saveArticle(store, tmp_path, "failed", text)
    store.claimPendingArticles(1, "failed")
    store.markStructureFailed(url, "LLM failed", claimId="failed")

    assert store.readArticleDedupHistory() == []
    assert dedup.findDuplicate(dedup.signature("next", "", text), store) is None
    assert dedup.findDuplicate(dedup.signature("empty", "", " # [] ` \n"), store) is None


def testExternallyChangedOrMissingCanonicalIsNotUsed(tmp_path):
    store = CrawlStore(tmp_path / "crawl.db")
    dedup = ArticleDeduplicator(SimpleNamespace())
    text = "招聘编辑，负责采访与新闻写作。投递邮箱：jobs@example.com"
    url, path = saveArticle(store, tmp_path, "canonical", text)
    commitCanonical(store, dedup, url, path, text)
    incoming = dedup.signature("copy", "", text)
    assert dedup.findDuplicate(incoming, store)

    path.write_text(text + "新增岗位及完全不同的招聘条件", encoding="utf-8")
    assert dedup.findDuplicate(incoming, store) is None
    path.unlink()
    assert dedup.findDuplicate(incoming, store) is None


def testParameterChangeRecomputesHistoryWithoutMixingSignatures(tmp_path):
    store = CrawlStore(tmp_path / "crawl.db")
    old = ArticleDeduplicator(SimpleNamespace(article_dedup_ngram=3, article_dedup_num_perm=128))
    text = "新闻内容编辑招聘。面向大学生，负责图文生产与内容运营。"
    url, path = saveArticle(store, tmp_path, "canonical", text)
    commitCanonical(store, old, url, path, text)
    changed = ArticleDeduplicator(SimpleNamespace(article_dedup_ngram=2, article_dedup_num_perm=64))

    match = changed.findDuplicate(changed.signature("copy", "", text), store)

    assert match is not None and match.score == 1
    assert match.version != old.version


def testCandidateDependenciesDoNotDeclareTransitiveDuplicates():
    dedup = ArticleDeduplicator(SimpleNamespace(article_dedup_ngram=1))
    chars = [chr(0x4e00 + index) for index in range(116)]
    documents = {
        "a": dedup.signature("a", "", "".join(chars[:100])),
        "b": dedup.signature("b", "", "".join(chars[8:108])),
        "c": dedup.signature("c", "", "".join(chars[16:116])),
    }

    dependencies = dedup.dependencies(documents)

    assert dependencies["a"] == []
    assert dependencies["b"] == ["a"]
    assert dependencies["c"] == ["b"]
    assert exactJaccard(documents["a"].grams, documents["c"].grams) < 0.85
