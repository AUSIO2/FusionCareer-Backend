import base64
import json
import zlib
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from app.skills.business.crawlers.official.common import cleanTitle, decodeHtml, getPage, parseDate
from app.skills.business.crawlers.official.html_lists import parseArticles
from app.skills.business.crawlers.official.api_lists import parseCareerList, parseJyxt, parseUestc, parseUstc


def testDecodeHtml():
    readInner = "前缀" + "<ul><li>招聘信息</li></ul>"
    readOuter = "占位" + base64.b64encode(readInner.encode()).decode()
    readPayload = base64.b64encode(zlib.compress(readOuter.encode())).decode()
    readHtml = (
        f'<script>Base64.decode(unzip("{readPayload}").substr(2)).substr(2)</script>'
    )
    assert decodeHtml(readHtml) == "<ul><li>招聘信息</li></ul>"


def testRetryLimitedPage():
    class ReadResponse:
        def __init__(self, readText):
            self.text = readText

        def raise_for_status(self):
            return None

    class ReadSession:
        def __init__(self):
            self.calls = 0

        def get(self, *args, **kwargs):
            self.calls += 1
            return ReadResponse("非法访问" if self.calls < 3 else "正常页面")

    readSession = ReadSession()
    readSource = {"id": "test", "limitRetries": 2, "limitDelaySeconds": 0}
    assert getPage(readSession, readSource, "https://example.edu.cn/").text == "正常页面"
    assert readSession.calls == 3


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
    readRoot = Path(__file__).resolve().parents[3] / "app" / "presets"
    readAccounts = json.loads((readRoot / "official_accounts.json").read_text(encoding="utf-8"))
    readSources = json.loads((readRoot / "official_sources.json").read_text(encoding="utf-8"))
    assert len(readAccounts) == 60
    assert len({readAccount["fakeid"] for readAccount in readAccounts}) == 60
    readIds = {readAccount["fakeid"] for readAccount in readAccounts}
    assert all(readSource["fakeid"] in readIds for readSource in readSources)
    readSourceIds = {readSource["id"] for readSource in readSources}
    readSourceAccounts = {readSource["fakeid"] for readSource in readSources}
    assert not any(readAccount["status"] == "probe" for readAccount in readAccounts)
    for readAccount in readAccounts:
        if readAccount["status"] == "integrated":
            assert (
                readAccount["fakeid"] in readSourceAccounts
                or readAccount.get("sourceId") in readSourceIds
            )
        else:
            assert readAccount["reason"]
            assert readAccount["alternative"]


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


def testCrawlArchivesAndDeduplicatesAfterSourceFailure(tmp_path, monkeypatch):
    from app.skills.business.crawlers.official import runner
    from app.skills.business.crawlers.paths import CrawlPaths
    from app.skills.business.crawlers.store import CrawlStore

    sources = tmp_path / "sources.json"
    common = {"linkPattern": r"/job.html", "contentXpath": "//article", "keywords": ["招聘"]}
    sources.write_text(json.dumps([
        {**common, "id": "broken", "fakeid": "broken", "name": "故障站点", "listUrl": "https://broken.test/list"},
        {**common, "id": "official", "fakeid": "official", "name": "高校就业网", "listUrl": "https://example.test/list"},
    ]))

    class Response:
        def __init__(self, text):
            self.text = '<html><head><meta charset="utf-8"></head><body>' + text + '</body></html>'
            self.content = self.text.encode()

        def raise_for_status(self):
            pass

    class Session:
        def get(self, url, **kwargs):
            if "broken" in url:
                raise RuntimeError("source unavailable")
            if url.endswith("/list"):
                return Response('<li><a href="/job.html">招聘编辑</a><span>2026-08-20</span></li>')
            return Response('<article><p>' + '招聘编辑，负责新闻采编。' * 30 + '</p></article>')

    monkeypatch.setattr(runner, "SOURCES_FILE", sources)
    monkeypatch.setattr(runner, "build_http_session", Session)
    paths = CrawlPaths(tmp_path)
    start, end = parseDate("2026-08-01"), parseDate("2026-09-01")
    result = runner.crawlSites(paths, start, end)
    assert result == {"sourceCount": 2, "articleCount": 1, "savedCount": 1}
    assert runner.crawlSites(paths, start, end)["savedCount"] == 0
    assert len(list((tmp_path / "官网文章").rglob("*.md"))) == 1
    manifest = (paths.manifest_dir / "official.jsonl").read_text().splitlines()
    assert len(manifest) == 1
    assert json.loads(manifest[0])["origin"] == "https://example.test/list"
    with CrawlStore(paths.database_file).openDatabase() as database:
        statuses = [row["status"] for row in database.execute("SELECT status FROM runs")]
    assert statuses.count("FAILED") == 2
    assert statuses.count("SUCCESS") == 2
