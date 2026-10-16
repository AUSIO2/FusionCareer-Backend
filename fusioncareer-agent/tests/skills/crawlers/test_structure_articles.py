import asyncio
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.algorithms.job_record_dedup import mergeDuplicateFields
from app.integrations.backend import BackendApiError
from app.skills.business.crawlers import structure_articles as structure_module
from app.skills.business.crawlers.paths import CrawlPaths
from app.skills.business.crawlers.store import CrawlStore
from app.skills.business.crawlers.structure_articles import BalanceExhaustedError, structureArticles


class FakeBackend:
    def __init__(self, readExisting=None, readFail=False):
        self.createJobs = []
        self.mergeJobs = []
        self.readExisting = readExisting or []
        self.readFail = readFail

    async def list_job_posts(self):
        return self.readExisting

    async def create_job_posts(self, createJobs):
        if self.readFail:
            raise RuntimeError("backend failed")
        self.createJobs.extend(createJobs)

    async def merge_job_post_evidence(self, jobId, expectedUpdatedAt, incomingJob):
        if self.readFail:
            raise RuntimeError("backend failed")
        snapshot = dict(incomingJob)
        self.mergeJobs.append((str(jobId), expectedUpdatedAt, snapshot))
        for index, existing in enumerate(self.readExisting):
            if str(existing.get("id")) == str(jobId):
                assert existing.get("updatedAt") == expectedUpdatedAt
                merged = dict(existing)
                mergeDuplicateFields(merged, incomingJob)
                merged["id"] = existing["id"]
                merged["updatedAt"] = "2026-10-16T08:00:01"
                self.readExisting[index] = merged
                return dict(merged)
        raise AssertionError(f"unknown fake job id: {jobId}")


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
        "retryCount": 0,
        "quarantinedCount": 0,
    }


def testOfficialStructureStopsWhenNothingCanProgress(tmp_path: Path, monkeypatch):
    readResults = [
        {"articleCount": 2, "jobCount": 0, "failedCount": 2, "skippedCount": 0, "retryCount": 2},
        {"articleCount": 0, "jobCount": 0, "failedCount": 0, "skippedCount": 0},
    ]

    async def readStructure(readPaths, readBackend, readClient=None, readLimit=20):
        return readResults.pop(0)

    monkeypatch.setattr(structure_module, "readBackend", object())
    monkeypatch.setattr(structure_module, "structureArticles", readStructure)

    result = asyncio.run(structure_module.OfficialStructureArticlesSkill().execute({
        "paths": {"config_root": str(tmp_path)},
        "result": {},
    }))
    assert result["json_obj"]["failedCount"] == 2
    assert result["json_obj"]["retryCount"] == 2


def testStructureArticles(tmp_path: Path):
    readPaths = CrawlPaths(tmp_path)
    readStore = CrawlStore(readPaths.database_file)
    readStore.saveAccount("fakeid-a", "AccountA", True)
    readMarkdown = tmp_path / "article.md"
    readMarkdown.write_text("# 招聘编辑\n投递邮箱：job@example.com", encoding="utf-8")
    readArticle = {
        "title": "招聘编辑", "link": "https://example.test/article", "create_time": 1,
    }
    readStore.saveArticle("fakeid-a", readArticle, readMarkdown, "hash")
    readBackend = FakeBackend()

    readResult = asyncio.run(structureArticles(readPaths, readBackend, FakeClient()))

    assert readResult == {"articleCount": 1, "jobCount": 1, "failedCount": 0, "skippedCount": 0,
                          "retryCount": 0, "quarantinedCount": 0}
    assert readBackend.createJobs[0]["status"] == "OFFLINE"
    assert readStore.readPendingArticles() == []


def testKeepPendingArticle(tmp_path: Path):
    readPaths = CrawlPaths(tmp_path)
    readStore = CrawlStore(readPaths.database_file)
    readStore.saveAccount("fakeid-a", "AccountA", True)
    readMarkdown = tmp_path / "article.md"
    readMarkdown.write_text("# 招聘编辑\n投递邮箱：job@example.com", encoding="utf-8")
    readArticle = {"title": "招聘编辑", "link": "https://example.test/article", "create_time": 1}
    readStore.saveArticle("fakeid-a", readArticle, readMarkdown, "hash")

    readResult = asyncio.run(structureArticles(readPaths, FakeBackend(readFail=True), FakeClient()))

    assert readResult == {"articleCount": 1, "jobCount": 0, "failedCount": 1, "skippedCount": 0,
                          "retryCount": 1, "quarantinedCount": 0}
    assert len(readStore.readPendingArticles()) == 1


def testDistinctArticlesKeepStructureConcurrency(tmp_path: Path):
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

    readPaths = CrawlPaths(tmp_path)
    readStore = CrawlStore(readPaths.database_file)
    readStore.saveAccount("fakeid-a", "AccountA", True)
    for readIndex in range(6):
        readMarkdown = tmp_path / f"article-{readIndex}.md"
        uniqueBody = "".join(chr(0x4e00 + readIndex * 300 + index) for index in range(250))
        readMarkdown.write_text(f"# 招聘编辑\n{uniqueBody}\n投递邮箱：job@example.com", encoding="utf-8")
        readArticle = {
            "title": "招聘编辑",
            "link": f"https://example.test/article-{readIndex}",
            "create_time": readIndex + 1,
        }
        readStore.saveArticle("fakeid-a", readArticle, readMarkdown, f"hash-{readIndex}")

    readClient = SlowClient()
    readResult = asyncio.run(structureArticles(readPaths, FakeBackend(), readClient))

    assert readResult == {"articleCount": 6, "jobCount": 6, "failedCount": 0, "skippedCount": 0,
                          "retryCount": 0, "quarantinedCount": 0}
    assert readClient.readMaximum == 3


def testBalanceErrorCancelsBatchWithoutDeferring(tmp_path: Path):
    class EmptyBalanceClient:
        def __init__(self):
            self.calls = 0

        async def chat_json(self, **readOptions):
            self.calls += 1
            if self.calls == 1:
                raise RuntimeError("Error code: 402 - Insufficient Balance")
            await asyncio.sleep(10)

    readPaths = CrawlPaths(tmp_path)
    readStore = CrawlStore(readPaths.database_file)
    readStore.saveAccount("fakeid-a", "AccountA", True)
    for readIndex in range(20):
        readMarkdown = tmp_path / f"balance-{readIndex}.md"
        readMarkdown.write_text("# 招聘编辑\n投递邮箱：job@example.com", encoding="utf-8")
        readStore.saveArticle("fakeid-a", {
            "title": "招聘编辑",
            "link": f"https://example.test/balance-{readIndex}",
            "create_time": readIndex + 1,
        }, readMarkdown, f"hash-{readIndex}")

    readClient = EmptyBalanceClient()
    with pytest.raises(BalanceExhaustedError, match="batch stopped"):
        asyncio.run(structureArticles(readPaths, FakeBackend(), readClient, readLimit=20))

    assert readClient.calls <= 5
    assert len(readStore.readPendingArticles()) == 20


def testMalformedJsonDefersArticleWithoutCreatingSummary(tmp_path: Path):
    class BrokenClient:
        async def chat_json(self, **readOptions):
            raise json.JSONDecodeError("broken", "{", 1)

    readPaths = CrawlPaths(tmp_path)
    readStore = CrawlStore(readPaths.database_file)
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

    assert readResult == {"articleCount": 1, "jobCount": 0, "failedCount": 1, "skippedCount": 0,
                          "retryCount": 1, "quarantinedCount": 0}
    assert readBackend.createJobs == []
    assert [article["url"] for article in readStore.readPendingArticles()] == [readArticle["link"]]


def testSkipUnactionableArticle(tmp_path: Path):
    readPaths = CrawlPaths(tmp_path)
    readStore = CrawlStore(readPaths.database_file)
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

    assert readResult == {"articleCount": 1, "jobCount": 0, "failedCount": 0, "skippedCount": 1,
                          "retryCount": 0, "quarantinedCount": 0}
    assert readBackend.createJobs == []
    assert readStore.readPendingArticles() == []


def testSkipRestrictedArticle(tmp_path: Path):
    readPaths = CrawlPaths(tmp_path)
    readStore = CrawlStore(readPaths.database_file)
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

    readPaths = CrawlPaths(tmp_path)
    readStore = CrawlStore(readPaths.database_file)
    readStore.saveAccount("fakeid-a", "AccountA", True)
    readMarkdown = tmp_path / "technical.md"
    readMarkdown.write_text("# 招聘\n投递邮箱：job@example.com", encoding="utf-8")
    readStore.saveArticle("fakeid-a", {
        "title": "招聘", "link": "https://example.test/technical", "create_time": 1,
    }, readMarkdown, "hash")
    readBackend = FakeBackend()

    readResult = asyncio.run(structureArticles(readPaths, readBackend, TechnicalClient()))

    assert readResult == {"articleCount": 1, "jobCount": 1, "failedCount": 0, "skippedCount": 0,
                          "retryCount": 0, "quarantinedCount": 0}
    assert readBackend.createJobs[0]["status"] == "RECYCLED"
    assert readBackend.createJobs[0]["recycleReason"].startswith("初筛未通过：")


def testInvalidJobDoesNotMarkPartialExtractionSuccessful(tmp_path: Path):
    class PartialClient(FakeClient):
        async def chat_json(self, **options):
            result = await super().chat_json(**options)
            result["jobs"].append({"单位名称": "示例公司", "岗位名称": "宣传岗", "学历要求": "本科生、博士研究生"})
            return result

    paths = CrawlPaths(tmp_path)
    store = CrawlStore(paths.database_file)
    store.saveAccount("fakeid-a", "AccountA", True)
    markdown = tmp_path / "article.md"
    markdown.write_text("# 招聘编辑与宣传岗\n投递邮箱：job@example.com", encoding="utf-8")
    article = {"title": "招聘", "link": "https://example.test/partial", "create_time": 1}
    store.saveArticle("fakeid-a", article, markdown, "hash")
    backend = FakeBackend()

    result = asyncio.run(structureArticles(paths, backend, PartialClient()))

    assert result["failedCount"] == 1
    assert backend.createJobs == []
    assert [row["url"] for row in store.readPendingArticles()] == [article["link"]]


def saveDuplicateFixture(paths, name, text):
    store = CrawlStore(paths.database_file)
    markdown = paths.config_root / f"{name}.md"
    markdown.write_text(text, encoding="utf-8")
    url = f"https://example.test/{name}"
    store.saveArticle("fakeid-a", {"title": "招聘", "link": url, "create_time": 1}, markdown, name)
    return url


def huiceJob(sourceUrl, company="慧策集团（北京掌上先机网络科技有限公司）", year="2027届"):
    return {
        "sourceUrl": sourceUrl,
        "companyName": company,
        "positionName": "行政管培生",
        "jobCategory": "ENTERPRISE",
        "jobSubCategory": "PRIVATE_ENTERPRISE",
        "recruitType": "CAMPUS_RECRUITMENT",
        "workCity": "北京",
        "reqGradYear": year,
        "reqOther": "网申：https://huicecom.zhiye.com/Campus；邮箱：hr@huice.com",
        "status": "OFFLINE",
    }


def testExistingBackendSemanticDuplicateSuppressesUniversityRepost(tmp_path, monkeypatch):
    monkeypatch.setattr(structure_module.settings, "prefilter_enabled", False)
    monkeypatch.setattr(structure_module.settings, "article_dedup_enabled", False)
    monkeypatch.setattr(structure_module.settings, "job_record_dedup_enabled", True)
    paths = CrawlPaths(tmp_path)
    source = saveDuplicateFixture(
        paths, "new-school", "# 慧策招聘\n投递邮箱：hr@huice.com\n不同学校转载正文",
    )
    backend = FakeBackend(readExisting=[
        huiceJob("https://career.fudan.edu.cn/old", "北京掌上先机网络科技有限公司"),
    ])

    async def workflow(request):
        return {"jobs": [huiceJob(request["sourceUrl"], "慧策集团")], "warnings": []}

    monkeypatch.setattr(structure_module, "_algorithm_workflow", workflow)
    result = asyncio.run(structureArticles(paths, backend))

    assert result["articleCount"] == 1 and result["jobCount"] == 0
    assert backend.createJobs == []
    assert CrawlStore(paths.database_file).readStructureCounts() == {"COMPLETED": 1}
    assert source not in [row["url"] for row in CrawlStore(paths.database_file).readArticleDedupHistory()]


def testPersistedKeeperIsUpdatedWithCompatibleDuplicateEvidence(tmp_path, monkeypatch):
    monkeypatch.setattr(structure_module.settings, "prefilter_enabled", False)
    monkeypatch.setattr(structure_module.settings, "article_dedup_enabled", False)
    monkeypatch.setattr(structure_module.settings, "job_record_dedup_enabled", True)
    paths = CrawlPaths(tmp_path)
    source = saveDuplicateFixture(
        paths, "keeper-merge", "# 慧策培训管培生\n2027届，北京、天津\n投递邮箱：hr@huice.com",
    )
    keeper = {
        **huiceJob("https://career.fudan.edu.cn/old", "北京掌上先机网络科技有限公司", year=""),
        "id": "1001",
        "positionName": "培训管培生",
        "workCities": ["北京"],
        "applicationDeadline": "2026-10-16",
        "salaryDisplay": "",
        "updatedAt": "2026-10-16T08:00:00",
    }
    incoming = {
        **huiceJob(source, "慧策集团", year="2027届"),
        "positionName": "培训管培生",
        "workCity": "北京、天津",
        "workCities": ["北京", "天津"],
        "applicationDeadline": "2026-10-30",
        "salaryDisplay": "基本工资+绩效工资；五险一金、餐补",
    }
    backend = FakeBackend(readExisting=[keeper])

    async def workflow(_request):
        return {"jobs": [incoming], "warnings": []}

    monkeypatch.setattr(structure_module, "_algorithm_workflow", workflow)
    result = asyncio.run(structureArticles(paths, backend))

    assert result == {
        "articleCount": 1, "jobCount": 0, "failedCount": 0, "skippedCount": 0,
        "retryCount": 0, "quarantinedCount": 0,
    }
    assert backend.createJobs == []
    assert backend.mergeJobs == [("1001", "2026-10-16T08:00:00", incoming)]
    assert len(backend.readExisting) == 1
    merged = backend.readExisting[0]
    assert merged["id"] == "1001"
    assert set(merged["workCities"]) == {"北京", "天津"}
    assert merged["applicationDeadline"] == "2026-10-30"
    assert merged["salaryDisplay"] == "基本工资+绩效工资；五险一金、餐补"
    assert merged["reqGradYear"] == "2027届"
    assert CrawlStore(paths.database_file).readStructureCounts() == {"COMPLETED": 1}


def testLaterSameBatchDuplicatesStillMatchAfterPartialMergeResponse(tmp_path, monkeypatch):
    monkeypatch.setattr(structure_module.settings, "prefilter_enabled", False)
    monkeypatch.setattr(structure_module.settings, "article_dedup_enabled", False)
    monkeypatch.setattr(structure_module.settings, "job_record_dedup_enabled", True)
    monkeypatch.setattr(structure_module.settings, "job_structure_article_concurrency", 3)
    paths = CrawlPaths(tmp_path)
    sources = [
        saveDuplicateFixture(
            paths, f"partial-merge-{index}",
            f"# 慧策培训管培生转载{index}\n投递邮箱：hr@huice.com",
        )
        for index in range(3)
    ]
    application = "网申：https://huicecom.zhiye.com/Campus；邮箱：hr@huice.com"
    keeper = {
        **huiceJob("https://career.fudan.edu.cn/old", year="2027届"),
        "id": "1001",
        "positionName": "培训管培生",
        "reqOther": "",
        "contactInfo": application,
        "workTimeRequirement": "周一至周五",
        "careerDirection": "培训与组织发展",
        "applicationDeadline": "2026-10-16",
        "updatedAt": "2026-10-16T08:00:00",
    }
    firstMergeReturned = asyncio.Event()

    async def workflow(request):
        if request["sourceUrl"] != sources[0]:
            await asyncio.wait_for(firstMergeReturned.wait(), 1)
        return {
            "jobs": [{
                **huiceJob(request["sourceUrl"], "慧策集团", year="2027届"),
                "positionName": "培训管培生",
                "reqOther": "",
                "contactInfo": application,
                "workTimeRequirement": "周一至周五",
                "careerDirection": "培训与组织发展",
                "applicationDeadline": "2026-10-30",
            }],
            "warnings": [],
        }

    class PartialResponseBackend(FakeBackend):
        async def merge_job_post_evidence(self, jobId, expectedUpdatedAt, incomingJob):
            merged = await super().merge_job_post_evidence(
                jobId, expectedUpdatedAt, incomingJob,
            )
            response = dict(merged)
            # This mirrors the current public JobPostResponse shape. These
            # fields are persisted but absent from the response DTO.
            for field in (
                "contactInfo", "workTimeRequirement", "careerDirection",
                "contactName", "internalCompensation", "internalRemark",
            ):
                response.pop(field, None)
            firstMergeReturned.set()
            return response

    monkeypatch.setattr(structure_module, "_algorithm_workflow", workflow)
    backend = PartialResponseBackend(readExisting=[keeper])
    result = asyncio.run(structureArticles(paths, backend))

    assert result["jobCount"] == 0 and result["failedCount"] == 0
    assert backend.createJobs == []
    assert len(backend.mergeJobs) == 3
    assert {call[2]["sourceUrl"] for call in backend.mergeJobs} == set(sources)
    assert CrawlStore(paths.database_file).readStructureCounts() == {"COMPLETED": 3}


def testFailedPersistedKeeperUpdateRetriesArticleWithoutSwallowingIncoming(tmp_path, monkeypatch):
    monkeypatch.setattr(structure_module.settings, "prefilter_enabled", False)
    monkeypatch.setattr(structure_module.settings, "article_dedup_enabled", False)
    monkeypatch.setattr(structure_module.settings, "job_record_dedup_enabled", True)
    paths = CrawlPaths(tmp_path)
    source = saveDuplicateFixture(
        paths, "keeper-update-fails", "# 慧策培训管培生\n截止10月30日\n投递邮箱：hr@huice.com",
    )
    keeper = {
        **huiceJob("https://career.fudan.edu.cn/old", year="2027届"),
        "id": "1001",
        "positionName": "培训管培生",
        "applicationDeadline": "2026-10-16",
        "updatedAt": "2026-10-16T08:00:00",
    }
    incoming = {
        **huiceJob(source, "慧策集团", year="2027届"),
        "positionName": "培训管培生",
        "applicationDeadline": "2026-10-30",
    }

    class FailingMergeBackend(FakeBackend):
        async def merge_job_post_evidence(self, jobId, expectedUpdatedAt, incomingJob):
            self.mergeJobs.append((str(jobId), expectedUpdatedAt, dict(incomingJob)))
            raise RuntimeError("Java keeper update failed")

    backend = FailingMergeBackend(readExisting=[keeper])

    async def workflow(_request):
        return {"jobs": [incoming], "warnings": []}

    monkeypatch.setattr(structure_module, "_algorithm_workflow", workflow)
    result = asyncio.run(structureArticles(paths, backend))

    assert result["jobCount"] == 0 and result["failedCount"] == 1
    assert backend.mergeJobs == [("1001", "2026-10-16T08:00:00", incoming)]
    assert backend.createJobs == []
    assert backend.readExisting == [keeper]
    store = CrawlStore(paths.database_file)
    assert [row["url"] for row in store.readPendingArticles()] == [source]
    assert store.readStructureCounts() == {"RETRY_WAIT": 1}


def testDeterministicKeeperConflictPreservesIncomingAsSeparateJob(tmp_path, monkeypatch):
    monkeypatch.setattr(structure_module.settings, "prefilter_enabled", False)
    monkeypatch.setattr(structure_module.settings, "article_dedup_enabled", False)
    monkeypatch.setattr(structure_module.settings, "job_record_dedup_enabled", True)
    paths = CrawlPaths(tmp_path)
    source = saveDuplicateFixture(
        paths, "keeper-conflict", "# 慧策培训管培生\n投递邮箱：hr@huice.com",
    )
    keeper = {
        **huiceJob("https://career.fudan.edu.cn/old", year="2027届"),
        "id": "1001",
        "positionName": "培训管培生",
        "updatedAt": "2026-10-16T08:00:00",
    }
    incoming = {
        **huiceJob(source, "慧策集团", year="2027届"),
        "positionName": "培训管培生",
        "jobDesc": "一段无法无损写回 keeper 的明确证据",
    }

    class ConflictBackend(FakeBackend):
        async def merge_job_post_evidence(self, jobId, expectedUpdatedAt, incomingJob):
            self.mergeJobs.append((str(jobId), expectedUpdatedAt, dict(incomingJob)))
            raise BackendApiError(409, "工作地点存在冲突", 409)

    async def workflow(_request):
        return {"jobs": [incoming], "warnings": []}

    monkeypatch.setattr(structure_module, "_algorithm_workflow", workflow)
    backend = ConflictBackend(readExisting=[keeper])
    result = asyncio.run(structureArticles(paths, backend))

    assert result["jobCount"] == 1 and result["failedCount"] == 0
    assert backend.createJobs == [incoming]
    assert len(backend.mergeJobs) == 1
    assert CrawlStore(paths.database_file).readStructureCounts() == {"COMPLETED": 1}


def testStaleKeeperConflictRefreshesAndRetriesMergeOnce(tmp_path, monkeypatch):
    monkeypatch.setattr(structure_module.settings, "prefilter_enabled", False)
    monkeypatch.setattr(structure_module.settings, "article_dedup_enabled", False)
    monkeypatch.setattr(structure_module.settings, "job_record_dedup_enabled", True)
    paths = CrawlPaths(tmp_path)
    source = saveDuplicateFixture(
        paths, "keeper-stale", "# 慧策培训管培生\n投递邮箱：hr@huice.com",
    )
    keeper = {
        **huiceJob("https://career.fudan.edu.cn/old", year="2027届"),
        "id": "1001",
        "positionName": "培训管培生",
        "applicationDeadline": "2026-10-16",
        "updatedAt": "2026-10-16T08:00:00",
    }
    incoming = {
        **huiceJob(source, "慧策集团", year="2027届"),
        "positionName": "培训管培生",
        "applicationDeadline": "2026-10-30",
    }

    class StaleOnceBackend(FakeBackend):
        def __init__(self, readExisting):
            super().__init__(readExisting=readExisting)
            self.listCalls = 0

        async def list_job_posts(self):
            self.listCalls += 1
            return await super().list_job_posts()

        async def merge_job_post_evidence(self, jobId, expectedUpdatedAt, incomingJob):
            if not self.mergeJobs:
                self.mergeJobs.append((str(jobId), expectedUpdatedAt, dict(incomingJob)))
                self.readExisting[0]["updatedAt"] = "2026-10-16T08:00:01"
                raise BackendApiError(409, "岗位已被其他请求更新，请刷新后重试", 409)
            return await super().merge_job_post_evidence(
                jobId, expectedUpdatedAt, incomingJob,
            )

    async def workflow(_request):
        return {"jobs": [incoming], "warnings": []}

    monkeypatch.setattr(structure_module, "_algorithm_workflow", workflow)
    backend = StaleOnceBackend(readExisting=[keeper])
    result = asyncio.run(structureArticles(paths, backend))

    assert result["jobCount"] == 0 and result["failedCount"] == 0
    assert backend.createJobs == []
    assert backend.listCalls == 2
    assert [call[1] for call in backend.mergeJobs] == [
        "2026-10-16T08:00:00", "2026-10-16T08:00:01",
    ]
    assert backend.readExisting[0]["applicationDeadline"] == "2026-10-30"
    assert CrawlStore(paths.database_file).readStructureCounts() == {"COMPLETED": 1}


def testFailedKeeperMergeFusesConcurrentWrites(tmp_path, monkeypatch):
    monkeypatch.setattr(structure_module.settings, "prefilter_enabled", False)
    monkeypatch.setattr(structure_module.settings, "article_dedup_enabled", False)
    monkeypatch.setattr(structure_module.settings, "job_record_dedup_enabled", True)
    monkeypatch.setattr(structure_module.settings, "job_structure_article_concurrency", 2)
    paths = CrawlPaths(tmp_path)
    first = saveDuplicateFixture(
        paths, "keeper-merge-fuse-a", "# 慧策培训管培生\n截止10月30日\n投递邮箱：hr@huice.com",
    )
    second = saveDuplicateFixture(
        paths, "keeper-merge-fuse-b", "# 慧策客户成功管培生\n投递邮箱：hr@huice.com",
    )
    keeper = {
        **huiceJob("https://career.fudan.edu.cn/old", year="2027届"),
        "id": "1001",
        "positionName": "培训管培生",
        "applicationDeadline": "2026-10-16",
        "updatedAt": "2026-10-16T08:00:00",
    }
    mergeStarted = asyncio.Event()

    async def workflow(request):
        if request["sourceUrl"] == second:
            await asyncio.wait_for(mergeStarted.wait(), 1)
            return {
                "jobs": [{**huiceJob(second), "positionName": "客户成功管培生"}],
                "warnings": [],
            }
        return {
            "jobs": [{
                **huiceJob(first),
                "positionName": "培训管培生",
                "applicationDeadline": "2026-10-30",
            }],
            "warnings": [],
        }

    class FailingMergeBackend(FakeBackend):
        async def merge_job_post_evidence(self, jobId, expectedUpdatedAt, incomingJob):
            self.mergeJobs.append((str(jobId), expectedUpdatedAt, dict(incomingJob)))
            mergeStarted.set()
            await asyncio.sleep(0.01)
            raise TimeoutError("keeper merge outcome unknown")

    monkeypatch.setattr(structure_module, "_algorithm_workflow", workflow)
    backend = FailingMergeBackend(readExisting=[keeper])
    result = asyncio.run(structureArticles(paths, backend))

    assert result["jobCount"] == 0 and result["failedCount"] == 2
    assert len(backend.mergeJobs) == 1
    assert backend.createJobs == []
    store = CrawlStore(paths.database_file)
    assert {row["url"] for row in store.readPendingArticles()} == {first, second}
    assert store.readStructureCounts() == {"RETRY_WAIT": 2}


@pytest.mark.parametrize("conflict", ["application-job-id", "disjoint-city"])
def testPersistedKeeperMergeDoesNotCrossHardConflicts(tmp_path, monkeypatch, conflict):
    monkeypatch.setattr(structure_module.settings, "prefilter_enabled", False)
    monkeypatch.setattr(structure_module.settings, "article_dedup_enabled", False)
    monkeypatch.setattr(structure_module.settings, "job_record_dedup_enabled", True)
    paths = CrawlPaths(tmp_path)
    source = saveDuplicateFixture(
        paths, f"hard-conflict-{conflict}", "# 慧策培训管培生\n投递邮箱：hr@huice.com",
    )
    keeper = {
        **huiceJob("https://career.fudan.edu.cn/old", year="2027届"),
        "id": "1001",
        "positionName": "培训管培生",
    }
    incoming = {
        **huiceJob(source, "慧策集团", year="2027届"),
        "positionName": "培训管培生",
    }
    if conflict == "application-job-id":
        keeper["reqOther"] += "；职位链接：https://jobs.example.com/jobs/111111"
        incoming["reqOther"] += "；职位链接：https://jobs.example.com/jobs/222222"
    else:
        keeper["workCity"] = "北京"
        keeper["workCities"] = ["北京"]
        incoming["workCity"] = "上海"
        incoming["workCities"] = ["上海"]
    backend = FakeBackend(readExisting=[keeper])

    async def workflow(_request):
        return {"jobs": [incoming], "warnings": []}

    monkeypatch.setattr(structure_module, "_algorithm_workflow", workflow)
    result = asyncio.run(structureArticles(paths, backend))

    assert result["jobCount"] == 1 and result["failedCount"] == 0
    assert backend.mergeJobs == []
    assert backend.createJobs == [incoming]
    assert CrawlStore(paths.database_file).readStructureCounts() == {"COMPLETED": 1}


def testExpiredKeeperDoesNotAbsorbActiveRepost(tmp_path, monkeypatch):
    monkeypatch.setattr(structure_module.settings, "prefilter_enabled", False)
    monkeypatch.setattr(structure_module.settings, "article_dedup_enabled", False)
    monkeypatch.setattr(structure_module.settings, "job_record_dedup_enabled", True)
    paths = CrawlPaths(tmp_path)
    source = saveDuplicateFixture(
        paths, "expired-keeper", "# 慧策培训管培生\n投递邮箱：hr@huice.com",
    )
    keeper = {
        **huiceJob("https://career.fudan.edu.cn/old", year="2027届"),
        "id": "1001",
        "positionName": "培训管培生",
        "status": "EXPIRED",
        "applicationDeadline": "2026-01-01",
        "updatedAt": "2026-10-16T08:00:00",
    }
    incoming = {
        **huiceJob(source, "慧策集团", year="2027届"),
        "positionName": "培训管培生",
        "status": "OFFLINE",
        "applicationDeadline": "2026-10-30",
    }
    backend = FakeBackend(readExisting=[keeper])

    async def workflow(_request):
        return {"jobs": [incoming], "warnings": []}

    monkeypatch.setattr(structure_module, "_algorithm_workflow", workflow)
    result = asyncio.run(structureArticles(paths, backend))

    assert result["jobCount"] == 1 and result["failedCount"] == 0
    assert backend.mergeJobs == []
    assert backend.createJobs == [incoming]
    assert CrawlStore(paths.database_file).readStructureCounts() == {"COMPLETED": 1}


def testRecordDedupKillSwitchKeepsCrossSourceRepost(tmp_path, monkeypatch):
    monkeypatch.setattr(structure_module.settings, "prefilter_enabled", False)
    monkeypatch.setattr(structure_module.settings, "article_dedup_enabled", False)
    monkeypatch.setattr(structure_module.settings, "job_record_dedup_enabled", False)
    paths = CrawlPaths(tmp_path)
    saveDuplicateFixture(paths, "kill-switch", "# 慧策招聘\n投递邮箱：hr@huice.com")
    backend = FakeBackend(readExisting=[
        huiceJob("https://career.fudan.edu.cn/old", "北京掌上先机网络科技有限公司"),
    ])

    async def workflow(request):
        return {"jobs": [huiceJob(request["sourceUrl"], "慧策集团")], "warnings": []}

    monkeypatch.setattr(structure_module, "_algorithm_workflow", workflow)
    result = asyncio.run(structureArticles(paths, backend))

    assert result["jobCount"] == 1 and len(backend.createJobs) == 1


def testConcurrentSemanticRepostsSubmitOneJob(tmp_path, monkeypatch):
    monkeypatch.setattr(structure_module.settings, "prefilter_enabled", False)
    monkeypatch.setattr(structure_module.settings, "article_dedup_enabled", False)
    monkeypatch.setattr(structure_module.settings, "job_record_dedup_enabled", True)
    monkeypatch.setattr(structure_module.settings, "job_structure_article_concurrency", 2)
    paths = CrawlPaths(tmp_path)
    first = saveDuplicateFixture(paths, "school-a", "# 慧策行政岗\n投递邮箱：hr@huice.com\n甲校转载")
    second = saveDuplicateFixture(paths, "school-b", "# 慧策管培岗\n投递邮箱：hr@huice.com\n乙校转载")
    entered = 0
    bothEntered = asyncio.Event()

    async def workflow(request):
        nonlocal entered
        entered += 1
        if entered == 2:
            bothEntered.set()
        await asyncio.wait_for(bothEntered.wait(), 1)
        company = "慧策集团" if request["sourceUrl"] == first else "北京掌上先机网络科技有限公司"
        return {"jobs": [huiceJob(request["sourceUrl"], company)], "warnings": []}

    class YieldingBackend(FakeBackend):
        async def create_job_posts(self, jobs):
            await asyncio.sleep(0.01)
            await super().create_job_posts(jobs)

    monkeypatch.setattr(structure_module, "_algorithm_workflow", workflow)
    backend = YieldingBackend()
    result = asyncio.run(structureArticles(paths, backend))

    assert result["articleCount"] == 2 and result["jobCount"] == 1
    assert len(backend.createJobs) == 1
    assert CrawlStore(paths.database_file).readStructureCounts() == {"COMPLETED": 2}


def testConcurrentDifferentCampaignYearsAreBothCreated(tmp_path, monkeypatch):
    monkeypatch.setattr(structure_module.settings, "prefilter_enabled", False)
    monkeypatch.setattr(structure_module.settings, "article_dedup_enabled", False)
    monkeypatch.setattr(structure_module.settings, "job_record_dedup_enabled", True)
    paths = CrawlPaths(tmp_path)
    first = saveDuplicateFixture(paths, "year-2027", "# 2027届招聘\n投递邮箱：hr@huice.com")
    second = saveDuplicateFixture(paths, "year-2028", "# 2028届招聘\n投递邮箱：hr@huice.com")

    async def workflow(request):
        year = "2027届" if request["sourceUrl"] == first else "2028届"
        return {"jobs": [huiceJob(request["sourceUrl"], year=year)], "warnings": []}

    monkeypatch.setattr(structure_module, "_algorithm_workflow", workflow)
    backend = FakeBackend()
    result = asyncio.run(structureArticles(paths, backend))

    assert result["jobCount"] == 2 and len(backend.createJobs) == 2


def testCommittedJobWithoutReturnedIdRetainsNewHardEvidence(tmp_path, monkeypatch):
    monkeypatch.setattr(structure_module.settings, "prefilter_enabled", False)
    monkeypatch.setattr(structure_module.settings, "article_dedup_enabled", False)
    monkeypatch.setattr(structure_module.settings, "job_record_dedup_enabled", True)
    monkeypatch.setattr(structure_module.settings, "job_structure_article_concurrency", 1)
    paths = CrawlPaths(tmp_path)
    first = saveDuplicateFixture(paths, "persisted-no-id-a", "# 慧策北京岗\n投递邮箱：hr@huice.com")
    second = saveDuplicateFixture(paths, "persisted-no-id-b", "# 慧策沪京岗\n投递邮箱：hr@huice.com")

    async def workflow(request):
        assert request["sourceUrl"] in {first, second}
        city = "北京" if request["sourceUrl"] == first else "北京、上海"
        return {"jobs": [{**huiceJob(request["sourceUrl"]), "workCity": city}], "warnings": []}

    monkeypatch.setattr(structure_module, "_algorithm_workflow", workflow)
    backend = FakeBackend()
    result = asyncio.run(structureArticles(paths, backend))

    assert result["jobCount"] == 2
    assert [job["workCity"] for job in backend.createJobs] == ["北京", "北京、上海"]


def testConcurrentNoIdCommitNeverAttemptsUnsafeKeeperUpdate(tmp_path, monkeypatch):
    monkeypatch.setattr(structure_module.settings, "prefilter_enabled", False)
    monkeypatch.setattr(structure_module.settings, "article_dedup_enabled", False)
    monkeypatch.setattr(structure_module.settings, "job_record_dedup_enabled", True)
    monkeypatch.setattr(structure_module.settings, "job_structure_article_concurrency", 2)
    paths = CrawlPaths(tmp_path)
    first = saveDuplicateFixture(paths, "concurrent-no-id-a", "# 慧策北京岗\n投递邮箱：hr@huice.com")
    second = saveDuplicateFixture(
        paths, "concurrent-no-id-b", "# 慧策北京天津岗\n投递邮箱：hr@huice.com",
    )
    firstCommitted = asyncio.Event()

    async def workflow(request):
        if request["sourceUrl"] == second:
            await asyncio.wait_for(firstCommitted.wait(), 1)
        city = "北京" if request["sourceUrl"] == first else "北京、天津"
        return {"jobs": [{**huiceJob(request["sourceUrl"]), "workCity": city}], "warnings": []}

    class NoIdBackend(FakeBackend):
        async def create_job_posts(self, jobs):
            # The production batch endpoint returns no created IDs.  Until a
            # refresh can identify the committed row, it is unsafe to PUT a
            # merged payload against a guessed/null ID or suppress new evidence.
            await super().create_job_posts(jobs)
            if jobs and jobs[0]["sourceUrl"] == first:
                firstCommitted.set()

    monkeypatch.setattr(structure_module, "_algorithm_workflow", workflow)
    backend = NoIdBackend()
    result = asyncio.run(structureArticles(paths, backend))

    assert result["jobCount"] == 2 and result["failedCount"] == 0
    assert [job["sourceUrl"] for job in backend.createJobs] == [first, second]
    assert backend.mergeJobs == []
    assert CrawlStore(paths.database_file).readStructureCounts() == {"COMPLETED": 2}


def testFailedSemanticWinnerDoesNotReserveKey(tmp_path, monkeypatch):
    monkeypatch.setattr(structure_module.settings, "prefilter_enabled", False)
    monkeypatch.setattr(structure_module.settings, "article_dedup_enabled", False)
    monkeypatch.setattr(structure_module.settings, "job_record_dedup_enabled", True)
    monkeypatch.setattr(structure_module.settings, "job_structure_article_concurrency", 2)
    paths = CrawlPaths(tmp_path)
    first = saveDuplicateFixture(paths, "failed-winner", "# 慧策招聘甲\n投递邮箱：hr@huice.com")
    second = saveDuplicateFixture(paths, "successful-copy", "# 慧策招聘乙\n投递邮箱：hr@huice.com")
    firstFailed = asyncio.Event()

    async def workflow(request):
        if request["sourceUrl"] == second:
            await asyncio.wait_for(firstFailed.wait(), 1)
        return {"jobs": [huiceJob(request["sourceUrl"])], "warnings": []}

    class FailFirstBackend(FakeBackend):
        async def create_job_posts(self, jobs):
            if jobs and jobs[0]["sourceUrl"] == first:
                firstFailed.set()
                await asyncio.sleep(0.01)
                raise RuntimeError("first Java write failed")
            await super().create_job_posts(jobs)

    monkeypatch.setattr(structure_module, "_algorithm_workflow", workflow)
    backend = FailFirstBackend()
    result = asyncio.run(structureArticles(paths, backend))

    assert result["jobCount"] == 1 and result["failedCount"] == 1
    assert [job["sourceUrl"] for job in backend.createJobs] == [second]
    store = CrawlStore(paths.database_file)
    assert [row["url"] for row in store.readPendingArticles()] == [first]
    assert store.readStructureCounts() == {"COMPLETED": 1, "RETRY_WAIT": 1}


def testCommittedJavaWriteWithLostResponseIsVerifiedBeforeNextRepost(tmp_path, monkeypatch):
    monkeypatch.setattr(structure_module.settings, "prefilter_enabled", False)
    monkeypatch.setattr(structure_module.settings, "article_dedup_enabled", False)
    monkeypatch.setattr(structure_module.settings, "job_record_dedup_enabled", True)
    monkeypatch.setattr(structure_module.settings, "job_structure_article_concurrency", 2)
    paths = CrawlPaths(tmp_path)
    first = saveDuplicateFixture(paths, "commit-timeout-a", "# 慧策招聘甲\n投递邮箱：hr@huice.com")
    second = saveDuplicateFixture(paths, "commit-timeout-b", "# 慧策招聘乙\n投递邮箱：hr@huice.com")
    writeStarted = asyncio.Event()

    async def workflow(request):
        if request["sourceUrl"] == second:
            await asyncio.wait_for(writeStarted.wait(), 1)
        return {"jobs": [huiceJob(request["sourceUrl"])], "warnings": []}

    class CommitThenTimeoutBackend(FakeBackend):
        def __init__(self):
            super().__init__()
            self.firstWrite = True

        async def create_job_posts(self, jobs):
            if not jobs:
                return
            committed = [{**job, "id": str(index + 1)} for index, job in enumerate(jobs)]
            self.createJobs.extend(committed)
            self.readExisting.extend(committed)
            if self.firstWrite:
                self.firstWrite = False
                writeStarted.set()
                await asyncio.sleep(0.01)
                raise TimeoutError("response lost after commit")

    monkeypatch.setattr(structure_module, "_algorithm_workflow", workflow)
    backend = CommitThenTimeoutBackend()
    result = asyncio.run(structureArticles(paths, backend))

    assert result["jobCount"] == 1 and result["failedCount"] == 0
    assert len(backend.createJobs) == 1 and backend.createJobs[0]["sourceUrl"] == first
    assert CrawlStore(paths.database_file).readStructureCounts() == {"COMPLETED": 2}


def testUnknownWriteAndFailedRefreshFuseRemainingWrites(tmp_path, monkeypatch):
    monkeypatch.setattr(structure_module.settings, "prefilter_enabled", False)
    monkeypatch.setattr(structure_module.settings, "article_dedup_enabled", False)
    monkeypatch.setattr(structure_module.settings, "job_record_dedup_enabled", True)
    monkeypatch.setattr(structure_module.settings, "job_structure_article_concurrency", 2)
    paths = CrawlPaths(tmp_path)
    first = saveDuplicateFixture(paths, "unknown-write-a", "# 慧策招聘甲\n投递邮箱：hr@huice.com")
    second = saveDuplicateFixture(paths, "unknown-write-b", "# 慧策招聘乙\n投递邮箱：hr@huice.com")
    writeStarted = asyncio.Event()

    async def workflow(request):
        if request["sourceUrl"] == second:
            await asyncio.wait_for(writeStarted.wait(), 1)
        return {"jobs": [huiceJob(request["sourceUrl"])], "warnings": []}

    class CommitThenLoseBackend(FakeBackend):
        def __init__(self):
            super().__init__()
            self.listCalls = 0

        async def list_job_posts(self):
            self.listCalls += 1
            if self.listCalls > 1:
                raise ConnectionError("refresh unavailable")
            return []

        async def create_job_posts(self, jobs):
            assert jobs and jobs[0]["sourceUrl"] == first
            self.createJobs.extend(jobs)
            writeStarted.set()
            await asyncio.sleep(0.01)
            raise TimeoutError("response lost after commit")

    monkeypatch.setattr(structure_module, "_algorithm_workflow", workflow)
    backend = CommitThenLoseBackend()
    result = asyncio.run(structureArticles(paths, backend))

    assert result["jobCount"] == 0 and result["failedCount"] == 2
    assert len(backend.createJobs) == 1
    assert CrawlStore(paths.database_file).readStructureCounts() == {"RETRY_WAIT": 2}


@pytest.mark.parametrize("groupSize", [1, 64])
def testSameBatchDuplicateWaitsForSuccessfulJavaWrite(tmp_path, monkeypatch, groupSize):
    monkeypatch.setattr(structure_module, "ARTICLE_DEDUP_BATCH_SIZE", groupSize)
    paths = CrawlPaths(tmp_path)
    text = "# 招聘编辑\n采访与新闻写作\n投递邮箱：job@example.com"
    first = saveDuplicateFixture(paths, "first", text)
    second = saveDuplicateFixture(paths, "second", text)
    backend = FakeBackend()

    result = asyncio.run(structureArticles(paths, backend, FakeClient()))

    assert result["jobCount"] == 1 and result["skippedCount"] == 1
    store = CrawlStore(paths.database_file)
    assert [row["url"] for row in store.readArticleDedupHistory()] == [first]
    with store.openDatabase() as connection:
        skipped = dict(connection.execute("SELECT * FROM articles WHERE url=?", (second,)).fetchone())
        audit = dict(connection.execute("SELECT * FROM article_dedup_skips").fetchone())
    assert skipped["article_duplicate_of"] == first
    assert skipped["article_duplicate_score"] == 1
    assert audit["winner_url"] == first and audit["score"] == 1


def testFailedCanonicalDoesNotSkipDuplicateOrLoseRetry(tmp_path):
    class FailFirstBackend(FakeBackend):
        async def create_job_posts(self, jobs):
            if jobs[0]["sourceUrl"].endswith("first"):
                raise RuntimeError("Java refused first article")
            await super().create_job_posts(jobs)

    paths = CrawlPaths(tmp_path)
    text = "# 招聘编辑\n投递邮箱：job@example.com"
    first = saveDuplicateFixture(paths, "first", text)
    second = saveDuplicateFixture(paths, "second", text)

    result = asyncio.run(structureArticles(paths, FailFirstBackend(), FakeClient()))

    assert result["jobCount"] == 1 and result["failedCount"] == 1 and result["skippedCount"] == 0
    store = CrawlStore(paths.database_file)
    assert [row["url"] for row in store.readArticleDedupHistory()] == [second]
    assert [row["url"] for row in store.readPendingArticles()] == [first]


def testCancelledCanonicalLeavesAllDuplicatesRetryable(tmp_path):
    class BlockingBackend(FakeBackend):
        async def create_job_posts(self, jobs):
            await asyncio.sleep(10)

    paths = CrawlPaths(tmp_path)
    text = "# 招聘编辑\n投递邮箱：job@example.com"
    saveDuplicateFixture(paths, "first", text)
    saveDuplicateFixture(paths, "second", text)

    async def cancel():
        task = asyncio.create_task(structureArticles(paths, BlockingBackend(), FakeClient()))
        await asyncio.sleep(0.05)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert not [active for active in asyncio.all_tasks() if "keepClaimAlive" in str(active.get_coro())]

    asyncio.run(cancel())
    store = CrawlStore(paths.database_file)
    assert store.readArticleDedupHistory() == []
    assert store.readStructureCounts() == {"PENDING": 2}


def testDuplicateHistoryPersistsAcrossBatches(tmp_path):
    paths = CrawlPaths(tmp_path)
    text = "# 招聘编辑\n投递邮箱：job@example.com"
    first = saveDuplicateFixture(paths, "first", text)
    assert asyncio.run(structureArticles(paths, FakeBackend(), FakeClient()))["jobCount"] == 1
    saveDuplicateFixture(paths, "later", text)

    result = asyncio.run(structureArticles(paths, FakeBackend(), FakeClient()))

    assert result["skippedCount"] == 1 and result["jobCount"] == 0
    assert [row["url"] for row in CrawlStore(paths.database_file).readArticleDedupHistory()] == [first]


def testBridgeArticleNotDroppedWhenItsNeighborWasOnlySkipped(tmp_path, monkeypatch):
    options = {**structure_module.settings.model_dump(), "article_dedup_ngram": 1}
    monkeypatch.setattr(structure_module, "settings", SimpleNamespace(**options))
    paths = CrawlPaths(tmp_path)
    chars = [chr(0x4e00 + index) for index in range(116)]
    for name, offset in (("a", 0), ("b", 8), ("c", 16)):
        text = "# 招聘编辑\n" + "".join(chars[offset:offset + 100]) + "\n投递邮箱：job@example.com"
        saveDuplicateFixture(paths, name, text)

    result = asyncio.run(structureArticles(paths, FakeBackend(), FakeClient()))

    # The shared heading adds grams. Constructed sets still give A~B, B~C,
    # but A and C stay below the actual threshold.
    assert result["jobCount"] == 2 and result["skippedCount"] == 1
    history = CrawlStore(paths.database_file).readArticleDedupHistory()
    assert {row["url"] for row in history} == {"https://example.test/a", "https://example.test/c"}


def testNativeWorkflowRunsAfterDuplicateDecision(tmp_path, monkeypatch):
    calls = []

    async def workflow(request):
        calls.append(request["sourceUrl"])
        return {"jobs": [{"companyName": "示例公司", "positionName": "编辑", "status": "OFFLINE",
                          "sourceUrl": request["sourceUrl"]}], "warnings": []}

    monkeypatch.setattr(structure_module, "_algorithm_workflow", workflow)
    monkeypatch.setattr(structure_module.settings, "prefilter_enabled", False)
    paths = CrawlPaths(tmp_path)
    text = "# 招聘编辑\n投递邮箱：job@example.com"
    first = saveDuplicateFixture(paths, "first", text)
    saveDuplicateFixture(paths, "second", text)

    result = asyncio.run(structureArticles(paths, FakeBackend()))

    assert result["jobCount"] == 1 and result["skippedCount"] == 1
    assert calls == [first]


def testNoExtractedJobsDoNotCreateCanonicalHistory(tmp_path):
    class NoJobsClient:
        async def chat_json(self, **options):
            return {"jobs": [], "warnings": []}

    paths = CrawlPaths(tmp_path)
    text = "# 招聘编辑\n投递邮箱：job@example.com"
    saveDuplicateFixture(paths, "first", text)
    saveDuplicateFixture(paths, "second", text)

    result = asyncio.run(structureArticles(paths, FakeBackend(), NoJobsClient()))

    assert result["jobCount"] == 0 and result["skippedCount"] == 0
    assert CrawlStore(paths.database_file).readArticleDedupHistory() == []


def testExplicitRequeueCanExtractFormerDuplicate(tmp_path):
    paths = CrawlPaths(tmp_path)
    text = "# 招聘编辑\n投递邮箱：job@example.com"
    saveDuplicateFixture(paths, "first", text)
    second = saveDuplicateFixture(paths, "second", text)
    assert asyncio.run(structureArticles(paths, FakeBackend(), FakeClient()))["skippedCount"] == 1
    store = CrawlStore(paths.database_file)
    assert store.requeueArticleDuplicates([second]) == 1

    result = asyncio.run(structureArticles(paths, FakeBackend(), FakeClient()))

    assert result["jobCount"] == 1 and result["skippedCount"] == 0
    assert len(store.readArticleDedupHistory()) == 2
    with store.openDatabase() as connection:
        assert connection.execute("SELECT article_dedup_bypass FROM articles WHERE url=?", (second,)).fetchone()[0] == 0
        assert connection.execute("SELECT COUNT(*) FROM article_dedup_skips").fetchone()[0] == 1


def testLatePrefilterCannotSkipChangedArticleOwnedByNewWorker(tmp_path, monkeypatch):
    paths = CrawlPaths(tmp_path)
    original = "# 招聘编辑\n投递邮箱：job@example.com"
    url = saveDuplicateFixture(paths, "changed", original)
    store = CrawlStore(paths.database_file)

    async def prefilter(_operation, _request):
        (tmp_path / "changed.md").write_text(original + "\n新的岗位内容", encoding="utf-8")
        store.saveArticle("fakeid-a", {"link": url, "title": "new"}, tmp_path / "changed.md", "new-hash")
        assert store.claimPendingArticles(1, "new-worker")
        return {"skipped": True, "reason": "old document irrelevant", "lexiconVersion": "old-rules"}

    monkeypatch.setattr(structure_module, "run_algorithm", prefilter)
    monkeypatch.setattr(structure_module.settings, "prefilter_enabled", True)
    backend = FakeBackend()

    result = asyncio.run(structureArticles(paths, backend, readLimit=1))

    assert result["skippedCount"] == 0 and result["failedCount"] == 0
    assert backend.createJobs == []
    assert store.hasArticleClaim(url, "new-worker")
    with store.openDatabase() as connection:
        row = dict(connection.execute("SELECT * FROM articles WHERE url=?", (url,)).fetchone())
        assert connection.execute("SELECT COUNT(*) FROM article_dedup_skips").fetchone()[0] == 0
    assert row["prefilter_version"] == "" and row["structure_status"] == "PROCESSING"


@pytest.mark.parametrize("reclaimed", [False, True])
def testExpiredModelResultNeverStartsJavaWrite(tmp_path, reclaimed):
    paths = CrawlPaths(tmp_path)
    url = saveDuplicateFixture(paths, "expired", "# 招聘编辑\n投递邮箱：job@example.com")
    store = CrawlStore(paths.database_file)

    class ExpiringClient(FakeClient):
        async def chat_json(self, **options):
            with store.openDatabase() as connection:
                connection.execute("UPDATE articles SET structure_claimed_at=? WHERE url=?",
                                   ((datetime.now(UTC) - timedelta(hours=3)).isoformat(), url))
            if reclaimed:
                store.reclaimExpiredArticles()
                assert store.claimPendingArticles(1, "new-worker")
            return await super().chat_json(**options)

    class BackendMustNotWrite(FakeBackend):
        async def create_job_posts(self, jobs):
            pytest.fail("expired worker attempted Java write")

    result = asyncio.run(structureArticles(paths, BackendMustNotWrite(), ExpiringClient(), readLimit=1))

    assert result["jobCount"] == 0 and result["failedCount"] == 0
    assert store.readArticleDedupHistory() == []
    assert store.readStructureCounts() == ({"PROCESSING": 1} if reclaimed else {"PENDING": 1})


def testChangedSourceFileDuringModelCallNeverWritesOldJobs(tmp_path):
    paths = CrawlPaths(tmp_path)
    saveDuplicateFixture(paths, "changed-file", "# 招聘编辑\n投递邮箱：job@example.com")

    class ChangingClient(FakeClient):
        async def chat_json(self, **options):
            (tmp_path / "changed-file.md").write_text("# 完全不同的新文档", encoding="utf-8")
            return await super().chat_json(**options)

    backend = FakeBackend()
    result = asyncio.run(structureArticles(paths, backend, ChangingClient(), readLimit=1))

    assert result["jobCount"] == 0 and backend.createJobs == []
    assert CrawlStore(paths.database_file).readStructureCounts() == {"PENDING": 1}


def testLazyClaimsKeepLaterGroupsPendingAndRespectTotalLimit(tmp_path, monkeypatch):
    monkeypatch.setattr(structure_module, "ARTICLE_DEDUP_BATCH_SIZE", 1)
    paths = CrawlPaths(tmp_path)
    store = CrawlStore(paths.database_file)
    for index in range(3):
        body = "".join(chr(0x4e00 + index * 300 + char) for char in range(250))
        saveDuplicateFixture(paths, f"lazy-{index}", f"# 招聘编辑\n{body}\n投递邮箱：job@example.com")
    snapshots = []

    class InspectingClient(FakeClient):
        async def chat_json(self, **options):
            snapshots.append(store.readStructureCounts())
            return await super().chat_json(**options)

    result = asyncio.run(structureArticles(paths, FakeBackend(), InspectingClient(), readLimit=2))

    assert result["articleCount"] == 2 and result["jobCount"] == 2
    assert snapshots[0] == {"PENDING": 2, "PROCESSING": 1}
    assert snapshots[1] == {"COMPLETED": 1, "PENDING": 1, "PROCESSING": 1}
    assert store.readStructureCounts() == {"COMPLETED": 2, "PENDING": 1}


def testHeartbeatKeepsLongModelCallLeasedAndIsCleanedUp(tmp_path, monkeypatch):
    options = {**structure_module.settings.model_dump(), "job_structure_claim_lease_seconds": 0.09}
    monkeypatch.setattr(structure_module, "settings", SimpleNamespace(**options))
    paths = CrawlPaths(tmp_path)
    saveDuplicateFixture(paths, "heartbeat", "# 招聘编辑\n投递邮箱：job@example.com")

    class SlowClient(FakeClient):
        async def chat_json(self, **options):
            await asyncio.sleep(0.18)
            return await super().chat_json(**options)

    async def run():
        result = await structureArticles(paths, FakeBackend(), SlowClient(), readLimit=1)
        assert not [task for task in asyncio.all_tasks() if "keepClaimAlive" in str(task.get_coro())]
        return result

    assert asyncio.run(run())["jobCount"] == 1


def testLostClaimIsNotRepeatedlyReclaimedWithinSameLimit(tmp_path):
    paths = CrawlPaths(tmp_path)
    url = saveDuplicateFixture(paths, "lost", "# 招聘编辑\n投递邮箱：job@example.com")
    store = CrawlStore(paths.database_file)
    calls = []

    class ExpiringClient(FakeClient):
        async def chat_json(self, **options):
            calls.append(1)
            with store.openDatabase() as connection:
                connection.execute("UPDATE articles SET structure_claimed_at=? WHERE url=?",
                                   ((datetime.now(UTC) - timedelta(hours=3)).isoformat(), url))
            return await super().chat_json(**options)

    result = asyncio.run(structureArticles(paths, FakeBackend(), ExpiringClient(), readLimit=5))

    assert result["articleCount"] == 1 and calls == [1]
    assert store.readStructureCounts() == {"PENDING": 1}
