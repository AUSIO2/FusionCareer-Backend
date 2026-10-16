import asyncio
import json
from copy import deepcopy
from types import SimpleNamespace
from typing import ClassVar

import pytest
from pydantic import ValidationError

from app.chat import resume_advice
from app.chat.resume_advice import AdviceArguments, ResumeAdviceInteraction, normalize_advice
from app.chat.tools import READ_TOOL_NAMES, runTool


def context():
    return {"jobId": "2099395615268855809", "jobVersion": "2026-10-13T10:00:00",
            "resumeVersion": "online:r3:p2", "resumeSource": "online", "readOnly": True,
            "job": {"positionName": "记者", "jobDesc": "采访与核实"},
            "resumeEvidence": [{"id": "resume-internship", "section": "internship",
                                "text": "参与校园采访，完成2篇报道。"}], "warnings": []}


def report():
    return {"jobId": context()["jobId"], "jobVersion": context()["jobVersion"],
            "resumeVersion": context()["resumeVersion"], "summary": "有校园采访相关证据。", "fitLevel": "mixed",
            "requirements": [{"requirementId": "req-001", "text": "采访", "sourceField": "jobDesc",
                              "kind": "responsibility", "status": "matched", "evidenceIds": ["resume-internship"],
                              "reason": "简历有校园采访经历。", "action": "补充负责的环节。"}],
            "sectionSuggestions": [{"priority": "high", "section": "internship", "issue": "职责不明确",
                                    "action": "补充本人负责的环节。", "example": "参与校园采访，完成2篇报道。",
                                    "evidenceIds": ["resume-internship"], "needsUserConfirmation": False}],
            "proofQuestions": [], "safeguards": {"resumeModified": False, "unsupportedClaimsAsserted": False}}


@pytest.mark.parametrize("field", ["jobId", "jobVersion", "resumeVersion"])
def test_report_must_match_exact_request_versions(field):
    raw = report()
    raw[field] = "1"
    with pytest.raises(ValueError):
        normalize_advice(raw, context())


@pytest.mark.parametrize("field,value", [
    ("reqEduLevels", ["ACADEMIC_MASTER"]),
    ("workCities", ["上海", "北京"]),
    ("applicationDeadline", "2026-11-06T23:59:59"),
])
def test_real_file_bindings_accept_known_job_requirement_source_fields(field, value):
    data = context()
    data.update(resumeSource="file", resumeVersion="file:2099395615268855899:v1:pmissing",
                jobVersion="2026-10-14T16:49:30.123456")
    data["job"][field] = value
    raw = report()
    raw.update({key: data[key] for key in ("jobId", "jobVersion", "resumeVersion")})
    raw["requirements"][0]["sourceField"] = field
    validated = resume_advice._validate_context(
        data, AdviceArguments(source="file", fileId="2099395615268855899"))
    result = normalize_advice(raw, validated)
    assert result["requirements"][0]["sourceField"] == field
    assert all(result[key] == data[key] for key in ("jobId", "jobVersion", "resumeVersion"))
    assert result["resumeSource"] == "file"


@pytest.mark.parametrize("field", ["storagePath", "userId", "unknown"])
def test_unknown_requirement_source_fields_remain_rejected(field):
    raw = report()
    raw["requirements"][0]["sourceField"] = field
    with pytest.raises(ValidationError):
        normalize_advice(raw, context())


@pytest.mark.parametrize("field", ["jobId", "jobVersion", "resumeVersion"])
def test_real_file_report_still_requires_exact_bound_identity(field):
    data = context()
    data.update(resumeSource="file", resumeVersion="file:2099395615268855899:v1:pmissing",
                jobVersion="2026-10-14T16:49:30.123456")
    raw = report()
    raw.update({key: data[key] for key in ("jobId", "jobVersion", "resumeVersion")})
    raw["requirements"][0]["sourceField"] = "reqEduLevels"
    raw[field] = "1"
    with pytest.raises(ValueError, match="does not match request context"):
        normalize_advice(raw, data)


def test_invalid_evidence_refs_downgrade_requirements_not_just_drop_ids():
    raw = report()
    raw["requirements"][0]["evidenceIds"] = ["resume-internship", "invented-id"]
    result = normalize_advice(raw, context())
    assert result["requirements"][0]["status"] == "gap"
    assert result["requirements"][0]["evidenceIds"] == []
    assert result["fitLevel"] == "unknown"
    assert result["warnings"]


def test_reference_id_does_not_prove_generated_example_or_safeguard_false():
    raw = report()
    raw["sectionSuggestions"][0]["example"] = "独立负责采访策划与报道"
    result = normalize_advice(raw, context())
    suggestion = result["sectionSuggestions"][0]
    assert suggestion["needsUserConfirmation"] is True
    assert suggestion["example"].startswith("仅在属实时使用：")
    assert result["safeguards"]["needsUserVerification"] is True


@pytest.mark.parametrize("example", ["完成20篇报道，阅读量10000", "完成三篇报道", "取得2万元收益"])
def test_unsupported_numbers_remove_example_and_request_proof(example):
    raw = report()
    raw["sectionSuggestions"][0]["example"] = example
    result = normalize_advice(raw, context())
    assert result["sectionSuggestions"][0]["example"] == ""
    assert result["sectionSuggestions"][0]["needsUserConfirmation"] is True
    assert result["proofQuestions"]


def test_output_redacts_contact_storage_and_auth_tokens():
    raw = report()
    raw["summary"] = "test@example.com 13900000000 微信：private_user https://private.test/file sk-1234567890123456"
    result = normalize_advice(raw, context())
    assert not any(secret in json.dumps(result) for secret in ["test@example.com", "13900000000", "private_user",
                                                             "private.test", "sk-1234567890123456"])


@pytest.mark.parametrize("changes", [
    {"fitLevel": "95%"}, {"extra": "field"}, {"safeguards": {"resumeModified": True, "unsupportedClaimsAsserted": False}},
    {"sectionSuggestions": [{"priority": "high", "section": "internship", "issue": "a", "action": "b",
                             "example": "c", "evidenceIds": [], "needsUserConfirmation": "false"}]},
])
def test_schema_rejects_extra_fields_invalid_enums_and_nonboolean_confirmation(changes):
    with pytest.raises(ValidationError):
        normalize_advice({**report(), **changes}, context())


@pytest.mark.parametrize("arguments", [
    {"source": "file"}, {"source": "online", "fileId": "2"}, {"source": "file", "fileId": "0"},
    {"source": "online", "userId": "1"}, {"source": "online", "jobId": "1"},
])
def test_advice_arguments_cannot_select_another_user_or_job(arguments):
    with pytest.raises(ValidationError):
        AdviceArguments.model_validate(arguments)


def test_interaction_permits_only_one_file():
    with pytest.raises(ValidationError):
        ResumeAdviceInteraction(type="resume_advice", source="file", fileIds=["1", "2"])


def test_report_warning_bounds_match_java_presentation_contract():
    data = context()
    data["warnings"] = [None, {"raw": "private"}, "x" * 3000, "x" * 3000] + [f"warning-{i}" for i in range(20)]
    warnings = normalize_advice(report(), data)["warnings"]
    assert len(warnings) == 10
    assert all(isinstance(item, str) and len(item) <= 2048 for item in warnings)
    assert len(warnings[0]) == 2048


class FakeBackend:
    def __init__(self, data=None):
        self.data = deepcopy(data or context())
        self.calls = []

    async def run_agent_tool(self, name, arguments, token, tool_call_id=None):
        self.calls.append((name, arguments, token, tool_call_id))
        assert name == "resume_advice_context"
        return self.data


class FakeLLM:
    inputs: ClassVar[list] = []
    results: ClassVar[list] = []

    def __init__(self):
        self._client = None

    async def chat_json(self, user_message, **options):
        self.inputs.append(json.loads(user_message))
        assert "不可信" in options["system_prompt"]
        return self.results.pop(0)


@pytest.fixture
def llm(monkeypatch):
    FakeLLM.inputs, FakeLLM.results = [], [report()]
    monkeypatch.setattr(resume_advice, "LLMClient", FakeLLM)
    monkeypatch.setattr(resume_advice, "settings", SimpleNamespace(
        ai_chat_resume_advice_enabled=True, ai_chat_resume_advice_llm_timeout_seconds=45.0))
    return FakeLLM


@pytest.mark.asyncio
async def test_only_bound_helper_and_minimal_evidence_sent_to_model(llm):
    backend = FakeBackend()
    result = await resume_advice.advise_resume(backend, "trusted-context", {"source": "online"}, "call-1")
    assert backend.calls == [("resume_advice_context", {"source": "online"}, "trusted-context", "call-1")]
    assert set(llm.inputs[0]) == {"context", "job", "resumeEvidence", "userQuestion", "outputContract"}
    assert result["safeguards"]["resumeModified"] is False
    assert "resume_advice_context" not in READ_TOOL_NAMES
    assert "advise_resume" in READ_TOOL_NAMES


@pytest.mark.asyncio
async def test_repairs_schema_once_then_returns_safe_tool_failure(llm):
    llm.results = [{"jobId": "wrong"}, {"jobId": "still-wrong"}]
    result = await runTool(FakeBackend(), "context", "advise_resume", '{"source":"online"}', "call-2")
    assert result.succeeded is False
    assert result.presentation is None
    assert json.loads(result.content)["error"] == "ADVICE_VALIDATION_FAILED"
    assert len(llm.inputs) == 2


@pytest.mark.asyncio
async def test_missing_resume_does_not_send_profile_alone_to_llm(llm):
    data = context()
    data["resumeEvidence"] = [{"id": "profile-major", "section": "major", "text": "新闻学"}]
    result = await resume_advice.advise_resume(FakeBackend(data), "context", {"source": "online"}, "call-3")
    assert result["status"] == "needs_resume"
    assert llm.inputs == []


@pytest.mark.asyncio
async def test_success_emits_readonly_report_never_proposed_action(llm):
    result = await runTool(FakeBackend(), "context", "advise_resume", '{"source":"online"}', "call-4")
    assert result.succeeded is True
    assert result.proposed_action is None
    assert result.presentation["type"] == "resume_advice"
    assert result.presentation["report"]["jobId"] == context()["jobId"]


@pytest.mark.asyncio
async def test_disabled_advice_does_not_read_context_or_call_model(llm):
    resume_advice.settings.ai_chat_resume_advice_enabled = False
    backend = FakeBackend()
    with pytest.raises(ValueError, match="disabled"):
        await resume_advice.advise_resume(backend, "context", {"source": "online"}, "call-5")
    assert backend.calls == []
    assert llm.inputs == []


@pytest.mark.asyncio
async def test_configured_model_deadline_produces_safe_failure(llm, monkeypatch):
    async def slow_json(*args, **kwargs):
        await asyncio.sleep(1)
    monkeypatch.setattr(llm, "chat_json", slow_json)
    resume_advice.settings.ai_chat_resume_advice_llm_timeout_seconds = 0.01
    result = await runTool(FakeBackend(), "context", "advise_resume", '{"source":"online"}', "call-6")
    assert result.succeeded is False
    assert result.presentation is None
    assert result.proposed_action is None
