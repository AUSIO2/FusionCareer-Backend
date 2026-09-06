from app.wechat_range_runner import filterArticles, readTimestamp


def testFilterArticles():
    readStart = readTimestamp("2026-07-01")
    readEnd = readTimestamp("2026-09-01")
    readArticles = [
        {"create_time": readTimestamp("2026-09-01"), "link": "https://example.test/sep"},
        {"create_time": readTimestamp("2026-08-10"), "link": "https://example.test/aug"},
        {"create_time": readTimestamp("2026-06-30"), "link": "https://example.test/jun"},
    ]
    createArticles, readReached = filterArticles(readArticles, readStart, readEnd)
    assert [readArticle["link"] for readArticle in createArticles] == ["https://example.test/aug"]
    assert readReached is True
