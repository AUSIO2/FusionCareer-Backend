import asyncio

from app.config import settings
from app.skills.business import job_structure as module


def testLegacyModePreservesNormalizedContract(monkeypatch):
    async def run(operation, request):
        assert operation == "job_structure"
        assert request["text"] == "招聘编辑"
        return {"positions": [{
            "companyName": "示例公司",
            "positionName": "编辑",
            "jobCategory": "ENTERPRISE",
            "jobSubCategory": "PRIVATE_ENTERPRISE",
            "recruitType": "OTHER",
        }]}

    monkeypatch.setattr(settings, "job_structure_mode", "legacy")
    monkeypatch.setattr(module, "run_algorithm", run)

    result = asyncio.run(module.NativeJobStructureSkill().execute({
        "request": {"text": "招聘编辑", "sourceUrl": "https://example.test/job"}
    }))

    assert result["result"]["warnings"] == []
    assert result["result"]["jobs"][0]["companyName"] == "示例公司"
    assert result["result"]["jobs"][0]["sourceUrl"] == "https://example.test/job"
