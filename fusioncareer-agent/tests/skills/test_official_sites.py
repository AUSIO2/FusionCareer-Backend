import json
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from app.skills.business.official_sites import (
    cleanTitle,
    parseArticles,
    parseCareerList,
    parseJyxt,
    parseUestc,
    parseUstc,
    parseDate,
)


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


def testCoverAccounts():
    readRoot = Path(__file__).resolve().parents[2] / "app" / "presets"
    readAccounts = json.loads((readRoot / "official_accounts.json").read_text(encoding="utf-8"))
    readSources = json.loads((readRoot / "official_sources.json").read_text(encoding="utf-8"))
    assert len(readAccounts) == 60
    assert len({readAccount["fakeid"] for readAccount in readAccounts}) == 60
    readIds = {readAccount["fakeid"] for readAccount in readAccounts}
    assert all(readSource["fakeid"] in readIds for readSource in readSources)


def testParseUestc():
    readSource = {
        "name": "成电就业",
        "listUrl": "https://jiuye.uestc.edu.cn/career/api/home/banner",
        "types": ["JOB_INFORMATION"],
    }
    readPayload = {
        "data": [
            {
                "id": "1",
                "title": "某公司招聘",
                "publishTime": "2026-08-06 11:45:14",
                "bannerTypeCode": "JOB_INFORMATION",
                "content": "<p>招聘正文</p>",
            },
            {
                "id": "2",
                "title": "校园新闻",
                "publishTime": "2026-08-07 11:45:14",
                "bannerTypeCode": "COLLEGE_NEWS",
                "content": "<p>新闻正文</p>",
            },
        ]
    }
    readZone = ZoneInfo("Asia/Shanghai")
    readStart = int(datetime(2026, 7, 1, tzinfo=readZone).timestamp())
    readEnd = int(datetime(2026, 9, 1, tzinfo=readZone).timestamp())
    readFound = parseUestc(readPayload, readSource, readStart, readEnd)
    assert len(readFound) == 1
    assert readFound[0]["link"].endswith("/news/jobs/1")


def testParseJyxt():
    readSource = {
        "name": "CAU就业",
        "homepage": "https://scc.cau.edu.cn/",
        "listUrl": "https://scc.cau.edu.cn/f/recruitmentinfo/ajax_frontRecruitinfo",
    }
    readPayload = {
        "object": {
            "list": [
                {
                    "title": "某公司校园招聘",
                    "startTime": "2026-08-25 17:25:12",
                    "corporationName": "某公司",
                    "url": "/f/recruitmentinfo/show?recruitmentId=1",
                }
            ]
        }
    }
    readZone = ZoneInfo("Asia/Shanghai")
    readStart = int(datetime(2026, 7, 1, tzinfo=readZone).timestamp())
    readEnd = int(datetime(2026, 9, 1, tzinfo=readZone).timestamp())
    readFound = parseJyxt(readPayload, readSource, readStart, readEnd)
    assert len(readFound) == 1
    assert readFound[0]["digest"] == "某公司"
    assert readFound[0]["id"] == ""


def testCleanTitle():
    assert cleanTitle("顶 2026-08-20 某公司招聘") == "某公司招聘"


def testParseMonthDate():
    readHtml = '<li><div><p>08月</p><p>25日</p><a href="/jobfair/view/id/1">校园招聘会</a></div></li>'
    readSource = {
        "name": "测试就业网",
        "listUrl": "https://job.example.edu.cn/",
        "linkPattern": r"/jobfair/view/",
        "keywords": [],
    }
    readZone = ZoneInfo("Asia/Shanghai")
    readStart = int(datetime(2026, 7, 1, tzinfo=readZone).timestamp())
    readEnd = int(datetime(2026, 9, 1, tzinfo=readZone).timestamp())
    assert len(parseArticles(readHtml, readSource, readStart, readEnd)) == 1


def testParseShortDate():
    readHtml = '<li><a href="/news/1.html">校园招聘会</a><span>08/25</span></li>'
    readSource = {
        "name": "测试就业网",
        "listUrl": "https://job.example.edu.cn/",
        "linkPattern": r"/news/",
        "keywords": [],
    }
    readZone = ZoneInfo("Asia/Shanghai")
    readStart = int(datetime(2026, 7, 1, tzinfo=readZone).timestamp())
    readEnd = int(datetime(2026, 9, 1, tzinfo=readZone).timestamp())
    assert len(parseArticles(readHtml, readSource, readStart, readEnd)) == 1


def testParseUstc():
    readPayload = {
        "Content": {
            "Contentclass": [
                "<tr><td><a href='Article.html?cid=9083'>就业通知</a></td><td>2026-07-29</td></tr>"
            ]
        }
    }
    readSource = {"name": "科大就业", "listUrl": "https://ustc.example/list"}
    readZone = ZoneInfo("Asia/Shanghai")
    readStart = int(datetime(2026, 7, 1, tzinfo=readZone).timestamp())
    readEnd = int(datetime(2026, 9, 1, tzinfo=readZone).timestamp())
    readFound = parseUstc(readPayload, readSource, readStart, readEnd)
    assert readFound[0]["id"] == "9083"


def testParseCustomNode():
    readHtml = """
    <article><div class="job-card" data-url="/career/zwxx/view/1">
      <h3 class="title">算法工程师</h3><span>2026-08-20</span>
    </div></article>
    """
    readSource = {
        "name": "测试就业网",
        "listUrl": "https://job.example.edu.cn/career/index",
        "nodeXpath": "//div[@data-url]",
        "linkAttribute": "data-url",
        "titleXpath": ".//h3//text()",
        "linkPattern": r"/career/zwxx/view/",
        "keywords": [],
    }
    readZone = ZoneInfo("Asia/Shanghai")
    readStart = int(datetime(2026, 7, 1, tzinfo=readZone).timestamp())
    readEnd = int(datetime(2026, 9, 1, tzinfo=readZone).timestamp())
    readFound = parseArticles(readHtml, readSource, readStart, readEnd)
    assert readFound[0]["title"] == "算法工程师"


def testParseCareerList():
    readPayload = {
        "data": {
            "list": [
                {
                    "zpxxid": "1",
                    "zpzt": "银行校园招聘",
                    "dwmc": "某银行",
                    "fbrq": "2026-08-20",
                }
            ]
        }
    }
    readSource = {
        "name": "交大就业",
        "homepage": "https://job.example.edu.cn/",
        "listUrl": "https://job.example.edu.cn/career/zpxx/search/zpxx",
    }
    readZone = ZoneInfo("Asia/Shanghai")
    readStart = int(datetime(2026, 7, 1, tzinfo=readZone).timestamp())
    readEnd = int(datetime(2026, 9, 1, tzinfo=readZone).timestamp())
    readFound = parseCareerList(readPayload, readSource, readStart, readEnd)
    assert readFound[0]["digest"] == "某银行"


def testIgnoreInvalidDate():
    assert parseDate("14:00-18:00", datetime(2026, 9, 1, tzinfo=ZoneInfo("Asia/Shanghai"))) == 0
