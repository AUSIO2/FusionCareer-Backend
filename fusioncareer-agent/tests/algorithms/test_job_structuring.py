import asyncio
import json
from pathlib import Path

from app.algorithms.job_structuring import structureJobs
from app.algorithms.job_structuring.normalize import normalizeJob
from app.algorithms.job_structuring.prompt import JOB_INDEX_PROMPT, JOB_PROMPT
from app.integrations.llm import LLMCompletion, LLMOutputTruncatedError


READ_FIXTURES = Path(__file__).parents[1] / "fixtures" / "algorithm"


class FakeJobClient:
    def __init__(self):
        self.readOptions = {}

    async def chat_json(self, **readOptions):
        self.readOptions = readOptions
        return {
            "jobs": [
                {
                    "单位名称": "示例科技有限公司",
                    "岗位名称": "后端开发工程师",
                    "岗位大类": "企业公司",
                    "岗位二级分类": "民企",
                    "招聘类型": "应届生招聘",
                    "工作省份": "上海",
                    "工作城市": "上海",
                    "学历要求": "学术硕士研究生",
                    "技能要求": "Java; SQL",
                },
                {
                    "单位名称": "示例科技有限公司",
                    "岗位名称": "内容运营实习生",
                    "岗位大类": "企业公司",
                    "岗位二级分类": "民企",
                    "招聘类型": "日常实习",
                    "工作省份": "北京",
                    "工作城市": "北京",
                    "每周工作天数类型": "一周3-4天",
                    "实习总时长类型": "3-6个月",
                },
            ],
            "warnings": [],
        }


def testStructureJobs():
    readArticle = (READ_FIXTURES / "job_article.md").read_text(encoding="utf-8")
    readContract = json.loads((READ_FIXTURES / "job_contract.json").read_text(encoding="utf-8"))
    readClient = FakeJobClient()
    readResult = asyncio.run(structureJobs(
        readArticle,
        "https://example.test/jobs/2026-campus",
        "PLATFORM",
        readClient,
    ))

    assert readResult == readContract
    assert readClient.readOptions["max_tokens"] == 8192


def testSkipInvalidJob():
    class FakeInvalidClient:
        async def chat_json(self, **readOptions):
            return {"jobs": [{"单位名称": "只有公司"}], "warnings": []}

    readResult = asyncio.run(structureJobs("招聘信息", readClient=FakeInvalidClient()))
    assert readResult["jobs"] == []
    assert "missing positionName" in readResult["warnings"][0]


def testNormalizeModelVariants():
    readJob, readWarnings = normalizeJob(
        {
            "单位名称": "示例公司",
            "岗位名称": "示例岗位",
            "招聘类型": "其他",
            "学历要求": "本科及以上",
            "工作城市": "城市" * 30,
            "投递截止日": "2026-10-31",
        },
        "https://example.test/job",
        "CRAWL",
    )

    assert readWarnings == []
    assert readJob["recruitType"] == "OTHER"
    assert readJob["reqEduLevel"] == "UNDERGRADUATE"
    assert len(readJob["workCity"]) == 32
    assert readJob["applicationDeadline"] == "2026-10-31"


def testNormalizePreservesMergedMultiValueRequirements():
    readJob, readWarnings = normalizeJob(
        {
            "companyName": "示例公司",
            "positionName": "行政管培生",
            "workCities": ["北京", "天津", "北京"],
            "reqEduLevels": ["本科生", "ACADEMIC_MASTER"],
        },
        "https://example.test/job",
        "CRAWL",
    )

    assert readWarnings == []
    assert readJob["workCities"] == ["北京", "天津"]
    assert readJob["reqEduLevels"] == ["UNDERGRADUATE", "ACADEMIC_MASTER"]


def testRejectInvalidModelFields():
    _, readWarnings = normalizeJob(
        {
            "positionName": "编辑",
            "position_name": "记者",
            "jobCategory": 2,
            "headcount": 2.5,
            "workStartDate": "2026-99-99",
        },
        "",
        "PLATFORM",
    )

    assert "duplicate positionName" in readWarnings
    assert "invalid jobCategory: 2" in readWarnings
    assert "invalid headcount: 2.5" in readWarnings
    assert "invalid workStartDate: 2026-99-99" in readWarnings


def testStructureLongArticle():
    class ChunkClient:
        def __init__(self):
            self.readCalls = 0
            self.readBatches = []

        async def chat_json(self, **readOptions):
            self.readCalls += 1
            if readOptions["system_prompt"] == JOB_INDEX_PROMPT:
                block_id = readOptions["user_message"].split("BLOCK_ID=", 1)[1].splitlines()[0]
                return {
                    "count": 17,
                    "jobs": [
                        {
                            "companyName": "示例公司",
                            "positionName": f"岗位{readIndex}",
                            "evidenceBlockIds": [block_id],
                        }
                        for readIndex in range(17)
                    ],
                }
            readTargets = json.loads(readOptions["system_prompt"].split("TARGET_JOBS_JSON=", 1)[1])
            self.readBatches.append(len(readTargets))
            return {
                "jobs": [
                        {
                            "targetId": readTarget["targetId"],
                            "单位名称": readTarget["companyName"],
                        "岗位名称": readTarget["positionName"],
                        "岗位大类": "企业公司",
                        "岗位二级分类": "民企",
                        "招聘类型": "应届生招聘",
                    }
                    for readTarget in readTargets
                ],
                "warnings": [],
            }

    readClient = ChunkClient()
    readResult = asyncio.run(structureJobs("招聘" * 7000, readClient=readClient))

    assert readClient.readCalls == 7
    assert readClient.readBatches == [4, 4, 4, 4, 1]
    assert len(readResult["jobs"]) == 17


def testLimitLargeJobList():
    assert "无论岗位数量多少" in JOB_PROMPT
    assert "不能合并成“招聘岗位汇总”" in JOB_INDEX_PROMPT
    assert "招聘岗位汇总" in JOB_PROMPT


def testTruncatedDetailBatchIsSplitInsteadOfRepeated():
    class AdaptiveClient:
        def __init__(self):
            self.detail_batches = []

        async def chat_json_with_meta(self, **options):
            if options["system_prompt"] == JOB_INDEX_PROMPT:
                block_id = options["user_message"].split("BLOCK_ID=", 1)[1].splitlines()[0]
                return ({"count": 4, "jobs": [
                    {"companyName": "示例公司", "positionName": f"岗位{index}",
                     "evidenceBlockIds": [block_id]}
                    for index in range(4)
                ]}, LLMCompletion("", "test", "stop"))
            targets = json.loads(options["system_prompt"].split("TARGET_JOBS_JSON=", 1)[1])
            self.detail_batches.append(len(targets))
            if len(targets) > 2:
                raise LLMOutputTruncatedError(LLMCompletion(
                    '{"jobs":[', "test", "length", completionTokens=8192
                ))
            return ({"jobs": [{
                "targetId": target["targetId"],
                "单位名称": target["companyName"],
                "岗位名称": target["positionName"],
            } for target in targets], "warnings": []}, LLMCompletion("", "test", "stop"))

    client = AdaptiveClient()
    result = asyncio.run(structureJobs("招聘正文。" * 1400, readClient=client))

    assert len(result["jobs"]) == 4
    assert client.detail_batches == [4, 2, 2]


def testLongArticleTailIsIndexedBeyondOldCharacterLimit():
    marker = "尾部唯一岗位"

    class TailClient:
        async def chat_json(self, **options):
            if options["system_prompt"] == JOB_INDEX_PROMPT:
                block_id = options["user_message"].split("BLOCK_ID=", 1)[1].splitlines()[0]
                jobs = [{
                    "companyName": "尾部公司", "positionName": marker,
                    "evidenceBlockIds": [block_id],
                }] if marker in options["user_message"] else []
                return {"count": len(jobs), "jobs": jobs}
            targets = json.loads(options["system_prompt"].split("TARGET_JOBS_JSON=", 1)[1])
            return {"jobs": [{
                "targetId": target["targetId"], "单位名称": target["companyName"],
                "岗位名称": target["positionName"],
            } for target in targets], "warnings": []}

    text = "# 超长招聘\n" + ("公司介绍与招聘要求。\n" * 3500) + f"\n## {marker}\n岗位职责。"
    result = asyncio.run(structureJobs(text, readClient=TailClient()))

    assert [job["positionName"] for job in result["jobs"]] == [marker]


def testSourceTypeUsesCallerInsteadOfModel():
    class SourceClient:
        async def chat_json(self, **options):
            return {"jobs": [{
                "单位名称": "上海第二工业大学", "岗位名称": "宣传岗",
                "来源类型": "兰大就业", "source_type": "微信公众号",
            }], "warnings": []}

    result = asyncio.run(structureJobs(
        "招聘宣传岗", "https://example.test/article", "CRAWL", SourceClient(),
    ))

    assert result["warnings"] == []
    assert len(result["jobs"]) == 1
    assert result["jobs"][0]["sourceType"] == "CRAWL"
    assert result["jobs"][0]["sourceUrl"] == "https://example.test/article"
