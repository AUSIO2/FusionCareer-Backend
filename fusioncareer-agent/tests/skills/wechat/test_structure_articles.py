import asyncio
from pathlib import Path

from app.skills.business.wechat.paths import WechatPaths
from app.skills.business.wechat.store import WechatStore
from app.skills.business.wechat.structure_articles import structureArticles


class FakeBackend:
    def __init__(self, readExisting=None, readFail=False):
        self.createJobs = []
        self.readExisting = readExisting or []
        self.readFail = readFail

    async def list_job_posts(self):
        return self.readExisting

    async def create_job_posts(self, createJobs):
        if self.readFail:
            raise RuntimeError("backend failed")
        self.createJobs.extend(createJobs)


class FakeClient:
    async def chat_json(self, **readOptions):
        return {
            "jobs": [{
                "单位名称": "示例公司", "岗位名称": "编辑",
                "岗位大类": "企业公司", "岗位二级分类": "民企", "招聘类型": "其他",
            }],
            "warnings": [],
        }


def testStructureArticles(tmp_path: Path):
    readPaths = WechatPaths(tmp_path)
    readStore = WechatStore(readPaths.database_file)
    readStore.saveAccount("fakeid-a", "AccountA", True)
    readMarkdown = tmp_path / "article.md"
    readMarkdown.write_text("# 招聘编辑", encoding="utf-8")
    readArticle = {
        "title": "招聘编辑", "link": "https://example.test/article", "create_time": 1,
    }
    readStore.saveArticle("fakeid-a", readArticle, readMarkdown, "hash")
    readBackend = FakeBackend()

    readResult = asyncio.run(structureArticles(readPaths, readBackend, FakeClient()))

    assert readResult == {"articleCount": 1, "jobCount": 1, "failedCount": 0}
    assert readBackend.createJobs[0]["status"] == "OFFLINE"
    assert readStore.readPendingArticles() == []


def testKeepPendingArticle(tmp_path: Path):
    readPaths = WechatPaths(tmp_path)
    readStore = WechatStore(readPaths.database_file)
    readStore.saveAccount("fakeid-a", "AccountA", True)
    readMarkdown = tmp_path / "article.md"
    readMarkdown.write_text("# 招聘编辑", encoding="utf-8")
    readArticle = {"title": "招聘编辑", "link": "https://example.test/article", "create_time": 1}
    readStore.saveArticle("fakeid-a", readArticle, readMarkdown, "hash")

    readResult = asyncio.run(structureArticles(readPaths, FakeBackend(readFail=True), FakeClient()))

    assert readResult == {"articleCount": 1, "jobCount": 0, "failedCount": 1}
    assert len(readStore.readPendingArticles()) == 1


def testStructureConcurrency(tmp_path: Path):
    class SlowClient(FakeClient):
        def __init__(self):
            self.readActive = 0
            self.readMaximum = 0

        async def chat_json(self, **readOptions):
            self.readActive += 1
            self.readMaximum = max(self.readMaximum, self.readActive)
            await asyncio.sleep(0.01)
            self.readActive -= 1
            return await super().chat_json(**readOptions)

    readPaths = WechatPaths(tmp_path)
    readStore = WechatStore(readPaths.database_file)
    readStore.saveAccount("fakeid-a", "AccountA", True)
    for readIndex in range(6):
        readMarkdown = tmp_path / f"article-{readIndex}.md"
        readMarkdown.write_text("# 招聘编辑", encoding="utf-8")
        readArticle = {
            "title": "招聘编辑",
            "link": f"https://example.test/article-{readIndex}",
            "create_time": readIndex + 1,
        }
        readStore.saveArticle("fakeid-a", readArticle, readMarkdown, f"hash-{readIndex}")

    readClient = SlowClient()
    readResult = asyncio.run(structureArticles(readPaths, FakeBackend(), readClient))

    assert readResult == {"articleCount": 6, "jobCount": 6, "failedCount": 0}
    assert readClient.readMaximum == 5
