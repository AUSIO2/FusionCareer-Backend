import pytest
from pydantic import ValidationError

from app.algorithms.upstream import run_algorithm
from app.chat.recommendations import recommend_jobs
from app.config import Settings, settings


def test_named_timeouts_override_category_defaults(monkeypatch):
    monkeypatch.setenv("AI_CHAT_TOOL_TIMEOUTS", '{"search_jobs":12,"parse_resume_file":110}')
    config = Settings(_env_file=None, ai_chat_read_tool_timeout_seconds=4)
    assert config.tool_timeout("search_jobs") == 12
    assert config.tool_timeout("get_job") == 4
    assert config.tool_timeout("parse_resume_file", write=True) == 110
    assert config.tool_timeout("propose_profile_patch", write=True) == 15


@pytest.mark.parametrize("value", [0, -1, float("inf"), float("nan")])
def test_bad_timeout_rejected_at_configuration_load(value):
    with pytest.raises(ValidationError):
        Settings(_env_file=None, ai_chat_tool_timeouts={"recommend_jobs": value})


@pytest.mark.asyncio
async def test_real_worker_preserves_rules_and_loads_resources(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "agent_runtime_dir", str(tmp_path))
    prepared = await run_algorithm(
        "job_recommend_prepare",
        {
            "jobCategories": ["MEDIA"],
            "workCities": ["上海市", "杭州"],
            "text": "视频剪辑",
        },
    )
    assert prepared["slots"]["workCities"] == ["上海", "杭州"]
    assert prepared["slots"]["keywordList"]
    result = await run_algorithm(
        "job_recommend_rank",
        {
            "use_llm": False,
            "slots": {"workCities": ["杭州"]},
            "jobs": [
                {"id": "2094674091431800833", "positionName": "编辑", "status": "OFFLINE"},
                {
                    "id": "2094674091431800834",
                    "companyName": "测试公司A",
                    "positionName": "编辑",
                    "status": "PUBLISHED",
                    "workCities": ["北京", "杭州"],
                    "recommended": True,
                },
                {"id": "2094674091431800835", "companyName": "测试公司B", "positionName": "记者",
                 "status": "PUBLISHED", "workCity": "北京"},
                {"id": "2094674091431800836", "companyName": "测试公司A", "positionName": "内容运营",
                 "status": "PUBLISHED", "workCity": "杭州"},
                {"id": "2094674091431800837", "companyName": "测试公司C", "positionName": "后端开发工程师",
                 "status": "PUBLISHED", "workCity": "杭州"},
            ],
        },
    )
    assert [j["id"] for j in result["jobs"]] == ["2094674091431800834", "2094674091431800835"]
    assert all(j["recommendReason"] for j in result["jobs"])
    assert result["method"] == "rules"
    assert result["dropped_unpublished"] == 1
    assert result["dropped_same_company"] == 1
    assert result["dropped_tech"] == 1
    assert not list(tmp_path.rglob("logs/llm_io/*"))
    assert not list(tmp_path.rglob("logs/recommend/*"))
    pref = await run_algorithm("job_prefilter", {"text": "新闻记者招聘"})
    assert pref["ok"] and pref["term_count"] > 0
    weak = await run_algorithm("job_prefilter", {"text": "招聘编辑"})
    assert weak["skipped"] and "过宽词" in weak["reason"]


@pytest.mark.asyncio
async def test_rerank_failure_preserves_real_candidates(monkeypatch):
    from app.chat import recommendations

    async def algorithm(op, request):
        if op == "job_recommend_prepare":
            return {"slots": {}, "filters": {"workCities": ["上海"]}}
        if request["use_llm"]:
            raise TimeoutError()
        return {"jobs": request["jobs"], "method": "rules"}

    class Backend:
        async def run_agent_tool(self, name, filters, context, tool_call_id):
            assert name == "recommendation_candidates" and context == "signed-user-context"
            return {"jobs": [{"id": "123", "positionName": "记者"}], "total": 1}

    monkeypatch.setattr(recommendations, "run_algorithm", algorithm)
    monkeypatch.setattr(settings, "recommendation_llm_enabled", True)
    result = await recommend_jobs(Backend(), "signed-user-context", {}, "call-1")
    assert result["jobs"][0]["id"] == "123"
    assert result["degraded"] and result["method"] == "rules"
    with pytest.raises(ValidationError):
        await recommend_jobs(Backend(), "signed-user-context", {"userId": 9}, "call-2")


@pytest.mark.asyncio
async def test_prefilter_audit_and_stats_do_not_count_daily_mirrors(tmp_path, monkeypatch):
    from app.skills.business.crawlers.store import CrawlStore

    monkeypatch.setattr(settings, "agent_runtime_dir", str(tmp_path / "runtime"))
    monkeypatch.setattr(settings, "crawl_config_root", str(tmp_path / "crawl"))
    store = CrawlStore(tmp_path / "crawl/crawl.db")
    store.saveAccount("test", "新闻就业")
    store.saveArticle(
        "test",
        {"link": "https://example.test/article", "title": "招聘记者", "create_time": "2026-09-29"},
        tmp_path / "article.md",
        "hash",
    )
    store.markPrefiltered("https://example.test/article", "未命中", "0123456789abcdef")
    assert store.countPendingArticles() == 0
    assert store.requeuePrefiltered("old-version") == 0
    assert store.requeuePrefiltered("0123456789abcdef") == 1
    result = await run_algorithm(
        "job_source_stats",
        {
            "jobs": [
                {"id": "1", "sourceUrl": "https://example.test/article", "positionName": "新闻记者"},
                {"id": "1", "sourceUrl": "https://example.test/article", "positionName": "新闻记者"},
            ]
        },
    )
    assert result["summary"]["articles"] == 1
    assert result["summary"]["jobs"] == 1
    assert len(result["files"]) == 2
