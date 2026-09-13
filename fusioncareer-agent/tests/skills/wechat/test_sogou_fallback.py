from app.skills.business.wechat.core import readArticles, searchArticles


SEARCH_HTML = """
<li id="sogou_vr_11002601_box_0">
  <h3><a href="/link?url=abc">招聘 <em>公告</em></a></h3>
  <p class="txt-info">岗位摘要</p>
  <div class="s-p"><span>上外就业</span><span><script>document.write(timeConvert('1609430591'))</script></span></div>
</li>
<li id="sogou_vr_11002601_box_1">
  <h3><a href="/link?url=other">其他文章</a></h3>
  <div class="s-p"><span>其他账号</span><span><script>document.write(timeConvert('1609430592'))</script></span></div>
</li>
"""


class FakeResponse:
    def __init__(self, readText="", readJson=None):
        self.text = readText
        self.encoding = "utf-8"
        self.readJson = readJson

    def raise_for_status(self):
        return None

    def json(self):
        return self.readJson


class FakeSession:
    def get(self, readUrl, **readOptions):
        if "appmsgpublish" in readUrl:
            return FakeResponse(readJson={"base_resp": {"ret": 200013, "err_msg": "freq control"}})
        if "/link?" in readUrl:
            return FakeResponse("var url = ''; url += 'https://mp.weixin.qq.com/s/test';")
        return FakeResponse(SEARCH_HTML)


def testSearchArticles():
    readFound = searchArticles(FakeSession(), "上外就业")
    assert readFound == [
        {
            "title": "招聘 公告",
            "link": "https://mp.weixin.qq.com/s/test",
            "create_time": 1609430591,
            "digest": "岗位摘要",
            "author": "上外就业",
            "source": "sogou",
        }
    ]


def testReadArticles():
    readFound, readCount = readArticles(FakeSession(), "fakeid", "上外就业", "token", "cookie")
    assert readCount == 1
    assert readFound[0]["source"] == "sogou"
