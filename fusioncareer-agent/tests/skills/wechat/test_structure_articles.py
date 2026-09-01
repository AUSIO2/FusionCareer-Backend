import asyncio
from pathlib import Path

import pytest

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

    assert readResult == {"articleCount": 1, "jobCount": 1}
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

    with pytest.raises(RuntimeError):
        asyncio.run(structureArticles(readPaths, FakeBackend(readFail=True), FakeClient()))

    assert len(readStore.readPendingArticles()) == 1
