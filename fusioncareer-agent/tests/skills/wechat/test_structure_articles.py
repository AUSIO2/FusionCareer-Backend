import asyncio
import json
from pathlib import Path

import pytest

from app.skills.business.wechat.paths import WechatPaths
from app.skills.business.wechat.store import WechatStore
from app.skills.business.wechat.structure_articles import structureArticles
from app.skills.business.wechat import structure_articles as structure_module


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


def testOfficialStructureDrainsPendingArticles(tmp_path: Path, monkeypatch):
    readLimits = []
    readResults = [
        {"articleCount": 2000, "jobCount": 100, "failedCount": 0, "skippedCount": 1900},
        {"articleCount": 3, "jobCount": 2, "failedCount": 0, "skippedCount": 1},
        {"articleCount": 0, "jobCount": 0, "failedCount": 0, "skippedCount": 0},
    ]

    async def readStructure(readPaths, readBackend, readClient=None, readLimit=20):
        readLimits.append(readLimit)
        return readResults.pop(0)

    monkeypatch.setattr(structure_module, "readBackend", object())
    monkeypatch.setattr(structure_module, "structureArticles", readStructure)
    result = asyncio.run(structure_module.OfficialStructureArticlesSkill().execute({
        "paths": {"config_root": str(tmp_path)},
        "result": {},
    }))

    assert readLimits == [structure_module.OFFICIAL_STRUCTURE_BATCH_SIZE] * 3
    assert result["json_obj"] == {
        "articleCount": 2003,
        "jobCount": 102,
        "failedCount": 0,
        "skippedCount": 1901,
    }


def testOfficialStructureStopsWhenNothingCanProgress(tmp_path: Path, monkeypatch):
    async def readStructure(readPaths, readBackend, readClient=None, readLimit=20):
        return {"articleCount": 2, "jobCount": 0, "failedCount": 2, "skippedCount": 0}

    monkeypatch.setattr(structure_module, "readBackend", object())
    monkeypatch.setattr(structure_module, "structureArticles", readStructure)

    with pytest.raises(RuntimeError, match="all 2 pending articles failed"):
        asyncio.run(structure_module.OfficialStructureArticlesSkill().execute({
            "paths": {"config_root": str(tmp_path)},
            "result": {},
        }))


def testStructureArticles(tmp_path: Path):
    readPaths = WechatPaths(tmp_path)
    readStore = WechatStore(readPaths.database_file)
    readStore.saveAccount("fakeid-a", "AccountA", True)
    readMarkdown = tmp_path / "article.md"
    readMarkdown.write_text("# 招聘编辑\n投递邮箱：job@example.com", encoding="utf-8")
    readArticle = {
        "title": "招聘编辑", "link": "https://example.test/article", "create_time": 1,
    }
    readStore.saveArticle("fakeid-a", readArticle, readMarkdown, "hash")
    readBackend = FakeBackend()

    readResult = asyncio.run(structureArticles(readPaths, readBackend, FakeClient()))

    assert readResult == {"articleCount": 1, "jobCount": 1, "failedCount": 0, "skippedCount": 0}
    assert readBackend.createJobs[0]["status"] == "OFFLINE"
    assert readStore.readPendingArticles() == []


def testKeepPendingArticle(tmp_path: Path):
    readPaths = WechatPaths(tmp_path)
    readStore = WechatStore(readPaths.database_file)
    readStore.saveAccount("fakeid-a", "AccountA", True)
    readMarkdown = tmp_path / "article.md"
    readMarkdown.write_text("# 招聘编辑\n投递邮箱：job@example.com", encoding="utf-8")
    readArticle = {"title": "招聘编辑", "link": "https://example.test/article", "create_time": 1}
    readStore.saveArticle("fakeid-a", readArticle, readMarkdown, "hash")

    readResult = asyncio.run(structureArticles(readPaths, FakeBackend(readFail=True), FakeClient()))

    assert readResult == {"articleCount": 1, "jobCount": 0, "failedCount": 1, "skippedCount": 0}
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
        readMarkdown.write_text("# 招聘编辑\n投递邮箱：job@example.com", encoding="utf-8")
        readArticle = {
            "title": "招聘编辑",
            "link": f"https://example.test/article-{readIndex}",
            "create_time": readIndex + 1,
        }
        readStore.saveArticle("fakeid-a", readArticle, readMarkdown, f"hash-{readIndex}")

    readClient = SlowClient()
    readResult = asyncio.run(structureArticles(readPaths, FakeBackend(), readClient))

    assert readResult == {"articleCount": 6, "jobCount": 6, "failedCount": 0, "skippedCount": 0}
    assert readClient.readMaximum == 5


def testCreateSummary(tmp_path: Path):
    class BrokenClient:
        async def chat_json(self, **readOptions):
            raise json.JSONDecodeError("broken", "{", 1)

    readPaths = WechatPaths(tmp_path)
    readStore = WechatStore(readPaths.database_file)
    readStore.saveAccount("fakeid-a", "AccountA", True)
    readMarkdown = tmp_path / "article.md"
    readMarkdown.write_text("# 超大招聘名单\n投递邮箱：job@example.com", encoding="utf-8")
    readArticle = {
        "title": "超大招聘名单",
        "link": "https://example.test/large",
        "create_time": 1,
    }
    readStore.saveArticle("fakeid-a", readArticle, readMarkdown, "hash")
    readBackend = FakeBackend()

    readResult = asyncio.run(structureArticles(readPaths, readBackend, BrokenClient()))

    assert readResult == {"articleCount": 1, "jobCount": 1, "failedCount": 0, "skippedCount": 0}
    assert readBackend.createJobs[0]["positionName"] == "招聘岗位汇总"
    assert readStore.readPendingArticles() == []


def testSkipUnactionableArticle(tmp_path: Path):
    readPaths = WechatPaths(tmp_path)
    readStore = WechatStore(readPaths.database_file)
    readStore.saveAccount("fakeid-a", "AccountA", True)
    readMarkdown = tmp_path / "article.md"
    readMarkdown.write_text("# 外校校内讲座\n欢迎参加职业讲座。", encoding="utf-8")
    readArticle = {
        "title": "外校校内讲座",
        "link": "https://example.test/talk",
        "create_time": 1,
    }
    readStore.saveArticle("fakeid-a", readArticle, readMarkdown, "hash")
    readBackend = FakeBackend()

    readResult = asyncio.run(structureArticles(readPaths, readBackend, FakeClient()))

    assert readResult == {"articleCount": 1, "jobCount": 0, "failedCount": 0, "skippedCount": 1}
    assert readBackend.createJobs == []
    assert readStore.readPendingArticles() == []


def testSkipRestrictedArticle(tmp_path: Path):
    readPaths = WechatPaths(tmp_path)
    readStore = WechatStore(readPaths.database_file)
    readStore.saveAccount("fakeid-a", "AccountA", True)
    readMarkdown = tmp_path / "article.md"
    readMarkdown.write_text("# 外校招聘会\n仅限本校学生，联系 job@example.com", encoding="utf-8")
    readArticle = {
        "title": "外校招聘会",
        "link": "https://example.test/fair",
        "create_time": 1,
    }
    readStore.saveArticle("fakeid-a", readArticle, readMarkdown, "hash")

    readResult = asyncio.run(structureArticles(readPaths, FakeBackend(), FakeClient()))

    assert readResult["skippedCount"] == 1


def testFilterTechnicalJob(tmp_path: Path):
    class TechnicalClient:
        async def chat_json(self, **readOptions):
            return {
                "jobs": [{
                    "单位名称": "示例公司", "岗位名称": "Java开发工程师",
                    "专业要求": "计算机科学", "岗位大类": "企业公司", "招聘类型": "应届生招聘",
                }],
                "warnings": [],
            }

    readPaths = WechatPaths(tmp_path)
    readStore = WechatStore(readPaths.database_file)
    readStore.saveAccount("fakeid-a", "AccountA", True)
    readMarkdown = tmp_path / "technical.md"
    readMarkdown.write_text("# 招聘\n投递邮箱：job@example.com", encoding="utf-8")
    readStore.saveArticle("fakeid-a", {
        "title": "招聘", "link": "https://example.test/technical", "create_time": 1,
    }, readMarkdown, "hash")
    readBackend = FakeBackend()

    readResult = asyncio.run(structureArticles(readPaths, readBackend, TechnicalClient()))

    assert readResult == {"articleCount": 1, "jobCount": 1, "failedCount": 0, "skippedCount": 0}
    assert readBackend.createJobs[0]["status"] == "RECYCLED"
    assert readBackend.createJobs[0]["recycleReason"].startswith("初筛未通过：")
