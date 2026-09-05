from datetime import datetime
from zoneinfo import ZoneInfo

from app.skills.business.official_sites import parseArticles


def testParseArticles():
    readHtml = """
    <ul>
      <li><a href="/2026/08/20/job.html">某公司2027届校园招聘</a><span>2026-08-20</span></li>
      <li><a href="/2026/06/20/old.html">旧招聘</a><span>2026-06-20</span></li>
      <li><a href="/2026/08/21/news.html">校园新闻</a><span>2026-08-21</span></li>
    </ul>
    """
    readSource = {
        "name": "测试就业网",
        "listUrl": "https://job.example.edu.cn/list.html",
        "linkPattern": r"/20\d{2}/.*\.html",
        "keywords": ["招聘"],
    }
    readZone = ZoneInfo("Asia/Shanghai")
    readStart = int(datetime(2026, 7, 1, tzinfo=readZone).timestamp())
    readEnd = int(datetime(2026, 9, 1, tzinfo=readZone).timestamp())
    readFound = parseArticles(readHtml, readSource, readStart, readEnd)
    assert len(readFound) == 1
    assert readFound[0]["title"] == "某公司2027届校园招聘"
    assert readFound[0]["link"] == "https://job.example.edu.cn/2026/08/20/job.html"


def testIgnoreOuterDate():
    readHtml = """
    <ul>
      <li><a href="/old.html">旧招聘</a></li>
      <li><a href="/new.html">新招聘</a><span>2026-08-20</span></li>
    </ul>
    """
    readSource = {
        "name": "测试就业网",
        "listUrl": "https://job.example.edu.cn/list.html",
        "linkPattern": r"\.html",
        "keywords": ["招聘"],
    }
    readZone = ZoneInfo("Asia/Shanghai")
    readStart = int(datetime(2026, 7, 1, tzinfo=readZone).timestamp())
    readEnd = int(datetime(2026, 9, 1, tzinfo=readZone).timestamp())
    readFound = parseArticles(readHtml, readSource, readStart, readEnd)
    assert [readArticle["title"] for readArticle in readFound] == ["新招聘"]
