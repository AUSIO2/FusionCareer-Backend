"""Release configuration keeps resume advice bounded and independently tunable."""

import pytest
from pydantic import ValidationError

from app.config import Settings


def test_resume_advice_timeouts_are_distinct_from_read_and_parse_tools(monkeypatch):
    monkeypatch.delenv("AI_CHAT_TOOL_TIMEOUTS", raising=False)
    config = Settings(
        _env_file=None,
        ai_chat_read_tool_timeout_seconds=4,
        ai_chat_parse_timeout_seconds=87,
        ai_chat_resume_advice_timeout_seconds=145,
        ai_chat_resume_advice_llm_timeout_seconds=42,
    )
    assert config.tool_timeout("get_job") == 4
    assert config.tool_timeout("analyze_resume_file") == 87
    assert config.tool_timeout("resume_advice_context") == 87
    assert config.tool_timeout("advise_resume") == 145
    assert config.ai_chat_resume_advice_llm_timeout_seconds == 42


def test_named_resume_advice_timeout_override_keeps_existing_precedence():
    config = Settings(_env_file=None, ai_chat_tool_timeouts={"advise_resume": 175, "resume_advice_context": 95})
    assert config.tool_timeout("advise_resume") == 175
    assert config.tool_timeout("resume_advice_context") == 95


@pytest.mark.parametrize("field", [
    "ai_chat_resume_advice_timeout_seconds",
    "ai_chat_resume_advice_llm_timeout_seconds",
    "ai_chat_run_timeout_seconds",
])
@pytest.mark.parametrize("value", [0, -1, float("inf"), float("nan")])
def test_resume_advice_and_run_budgets_require_finite_positive_seconds(field, value):
    with pytest.raises(ValidationError):
        Settings(_env_file=None, **{field: value})


def test_native_dedup_release_defaults_match_upstream():
    config = Settings(_env_file=None)
    assert config.job_record_dedup_enabled is True
    assert config.article_dedup_enabled is True
    assert config.article_dedup_ngram == 3
    assert config.article_dedup_num_perm == 128
    assert config.article_dedup_lsh_threshold == 0.6
    assert config.article_dedup_jaccard_threshold == 0.85


@pytest.mark.parametrize("lsh,jaccard", [(0.6, 0.85), (0.85, 0.85), (0.9, 1.0)])
def test_native_dedup_lsh_may_equal_but_not_exceed_exact_threshold(lsh, jaccard):
    config = Settings(
        _env_file=None,
        article_dedup_lsh_threshold=lsh,
        article_dedup_jaccard_threshold=jaccard,
    )
    assert config.article_dedup_lsh_threshold == lsh


def test_native_dedup_rejects_lsh_above_exact_threshold():
    with pytest.raises(ValidationError):
        Settings(_env_file=None, article_dedup_lsh_threshold=0.9, article_dedup_jaccard_threshold=0.85)


@pytest.mark.parametrize("field", ["article_dedup_lsh_threshold", "article_dedup_jaccard_threshold"])
@pytest.mark.parametrize("value", [0, -1, 1.01, float("inf"), float("nan")])
def test_native_dedup_rejects_out_of_range_and_nonfinite_thresholds(field, value):
    with pytest.raises(ValidationError):
        Settings(_env_file=None, **{field: value})
