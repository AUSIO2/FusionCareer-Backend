import asyncio
import json
from pathlib import Path

from app.algorithms.job_structuring import structureJobs
from app.algorithms.job_structuring.normalize import normalizeJob


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
    assert readClient.readOptions["max_tokens"] == 16384


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
            "招聘类型": "暑期提前批实习",
            "学历要求": "本科及以上",
            "工作城市": "城市" * 30,
        },
        "https://example.test/job",
        "CRAWL",
    )

    assert readWarnings == []
    assert readJob["recruitType"] == "OTHER"
    assert "reqEduLevel" not in readJob
    assert len(readJob["workCity"]) == 32
