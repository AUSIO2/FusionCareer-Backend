import asyncio
import json
from copy import deepcopy
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.api.routers import chat as chat_router
from app.chat.tools import READ_TOOLS, WRITE_TOOLS
from app.config import settings
from app.integrations.backend import BackendApiError
from app.main import app


class FakeChatClient:
    async def chat(self, user_message, **readOptions):
        assert "messages" in user_message
        assert readOptions["max_tokens"] == 700
        return "用户正在寻找媒体实习"

    async def plan_tools(self, messages, tools, **readOptions):
        assert tools
        readContent = messages[-1].get("content")
        if readContent in {"找岗位", "慢查询", "错误查询"}:
            readKeyword = {
                "找岗位": "媒体",
                "慢查询": "slow",
                "错误查询": "invalid",
            }[readContent]
            return {
                "content": "",
                "toolCalls": [{
                    "id": "call-1",
                    "type": "function",
                    "function": {
                        "name": "search_jobs",
                        "arguments": f'{{"keyword":"{readKeyword}"}}',
                    },
                }],
            }
        if readContent == "慢模型":
            await asyncio.sleep(0.05)
            return {"content": "", "toolCalls": []}
        if messages[-1].get("content") == "更新资料":
            return {
                "content": "",
                "toolCalls": [{
                    "id": "call-profile-1",
                    "type": "function",
                    "function": {
                        "name": "propose_profile_patch",
                        "arguments": (
                            '{"changes":[{"field":"major","operation":"SET",'
                            '"value":"人工智能"}],"reason":"按用户要求更新专业"}'
                        ),
                    },
                }],
            }
        return {"content": "", "toolCalls": []}

    async def stream_chat(self, messages, **readOptions):
        assert messages[-1]["role"] in {"user", "tool"}
        assert "system_prompt" in readOptions
        yield {"type": "delta", "text": "你"}
        yield {"type": "delta", "text": "好"}
        yield {
            "type": "done",
            "model": "fake-model",
            "finishReason": "stop",
            "usage": {"promptTokens": 3, "completionTokens": 2},
        }


class FakeBackend:
    def __init__(self):
        self.context = None
        self.tool_call_id = None

    async def run_agent_tool(self, name, arguments, context, tool_call_id=None):
        self.context = context
        self.tool_call_id = tool_call_id
        if name == "propose_profile_patch":
            assert arguments["changes"][0]["field"] == "major"
            return {
                "actionId": "2001",
                "status": "PENDING",
                "reason": "按用户要求更新专业",
                "resourceType": "PROFILE",
                "changedFields": ["major"],
                "baseVersion": 2,
                "requiresConfirmation": True,
            }
        assert name == "search_jobs"
        if arguments == {"keyword": "slow"}:
            await asyncio.sleep(0.05)
        if arguments == {"keyword": "invalid"}:
            raise BackendApiError(400, "关键词格式无效\n请修改", 400)
        assert arguments in ({"keyword": "媒体"}, {"keyword": "slow"})
        return {"jobs": [{"positionName": "编辑"}]}


class CapturingChatClient(FakeChatClient):
    def __init__(self):
        self.plans = []
        self.streams = []
        self.summaries = []

    async def plan_tools(self, messages, tools, **readOptions):
        self.plans.append((deepcopy(messages), readOptions["system_prompt"]))
        return await super().plan_tools(messages, tools, **readOptions)

    async def stream_chat(self, messages, **readOptions):
        self.streams.append((deepcopy(messages), readOptions["system_prompt"]))
        async for event in super().stream_chat(messages, **readOptions):
            yield event

    async def chat(self, user_message, **readOptions):
        self.summaries.append((json.loads(user_message), readOptions["system_prompt"]))
        return await super().chat(user_message, **readOptions)


@pytest.fixture
def client(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(settings, "agent_runtime_dir", str(tmp_path / "runtime"))
    monkeypatch.setattr(settings, "internal_service_token", "internal-test")
    monkeypatch.setattr(settings, "ai_chat_write_enabled", True)
    monkeypatch.setattr(settings, "ai_chat_topic_guard_enabled", False)
    monkeypatch.setattr(chat_router, "chatClient", FakeChatClient())
    with TestClient(app) as readClient:
        readClient.app.state.backend_client = FakeBackend()
        yield readClient


def readBody() -> dict:
    return {
        "runId": "6274f9c9-bf88-42ea-b811-cdf37ef73a6f",
        "epoch": 1,
        "requestId": "request-1",
        "userMessageId": "1001",
        "assistantMessageId": "1002",
        "input": "你好",
        "attachments": [],
        "memory": {},
        "summary": "",
        "history": [],
    }


def readHeaders() -> dict[str, str]:
    return {
        "X-Internal-Token": "internal-test",
        "X-Agent-Context": "agent-context-test-value",
    }


def testWriteToolRegistryIncludesReversibleFileDelete():
    readNames = {readTool["function"]["name"] for readTool in WRITE_TOOLS}
    assert "propose_file_delete" in readNames
    assert "propose_file_restore" in readNames
    assert "propose_questionnaire_draft" in readNames
    assert "propose_questionnaire_submit" in readNames
    assert "parse_resume_file" in readNames


def testReadToolRegistryIncludesReadOnlyResumeFileAnalysis():
    readNames = {readTool["function"]["name"] for readTool in READ_TOOLS}
    assert "analyze_resume_file" in readNames
    assert "get_job" in readNames
    assert "get_my_resume" in readNames
    assert "advise_resume" in readNames
    assert "resume_advice_context" not in readNames


@pytest.mark.parametrize("extra", [
    {"interaction": {"type": "resume_advice", "source": "online"}},
    {"jobId": "100", "interaction": {"type": "resume_advice", "source": "file", "fileIds": ["1"]}},
    {"jobId": "100", "interaction": {"type": "resume_advice", "source": "online", "fileIds": ["1"]}},
])
def testAdviceInteractionRequiresPageJobAndCurrentAttachment(client: TestClient, extra):
    response = client.post("/api/internal/chat/stream", headers=readHeaders(), json={**readBody(), **extra})
    assert response.status_code == 422


def testAdviceInteractionRunsSameFixedAgentToolAndEmitsReport(client: TestClient, monkeypatch):
    from app.chat import resume_advice

    calls = []
    capture = CapturingChatClient()

    async def fixed_advice(backend, context, arguments, call_id):
        calls.append((context, arguments, call_id))
        return {"status": "needs_resume", "jobId": "100", "resumeVersion": "missing",
                "summary": "请先完善简历。", "fitLevel": "unknown", "requirements": [],
                "sectionSuggestions": [], "proofQuestions": []}

    monkeypatch.setattr(resume_advice, "advise_resume", fixed_advice)
    monkeypatch.setattr(chat_router, "chatClient", capture)
    response = client.post("/api/internal/chat/stream", headers=readHeaders(), json={
        **readBody(), "jobId": "100", "input": "分析当前岗位", "attachments": [{
            "fileId": "1", "name": "resume.pdf", "mimeType": "application/pdf"}],
        "interaction": {"schemaVersion": 1, "type": "resume_advice", "source": "file", "fileIds": ["1"]},
    })
    assert response.status_code == 200
    assert "event: resume_advice" in response.text
    assert "event: action_proposed" not in response.text
    assert len(calls) == 1
    assert calls[0][1] == {"source": "file", "fileId": "1", "question": "分析当前岗位"}
    assert capture.plans == []
    assert len(capture.streams) == 1


def testProtectStream(client: TestClient):
    assert client.post("/api/internal/chat/stream", json=readBody()).status_code == 403


def testStreamText(client: TestClient):
    readResponse = client.post(
        "/api/internal/chat/stream",
        headers=readHeaders(),
        json=readBody(),
    )

    assert readResponse.status_code == 200
    assert readResponse.headers["content-type"].startswith("text/event-stream")
    assert "event: start" in readResponse.text
    assert '"seq":1,"text":"你"' in readResponse.text
    assert '"seq":2,"text":"好"' in readResponse.text
    assert '"content":"你好"' in readResponse.text
    assert readResponse.text.index("event: start") < readResponse.text.index("event: delta")
    assert readResponse.text.rfind("event: done") > readResponse.text.index("event: delta")


def testUnrelatedTopicStopsBeforeMainModel(client: TestClient, monkeypatch):
    from app.chat.topic_guard import TopicDecision

    capture = CapturingChatClient()
    monkeypatch.setattr(settings, "ai_chat_topic_guard_enabled", True)
    monkeypatch.setattr(chat_router, "chatClient", capture)

    async def reject(body, model):
        return TopicDecision(False, "与就业主题无关", "small-model", 8, 4)

    monkeypatch.setattr(chat_router, "checkTopic", reject)
    response = client.post("/api/internal/chat/stream", headers=readHeaders(), json={
        **readBody(), "input": "给我讲一个游戏攻略",
    })

    assert response.status_code == 200
    assert "event: topic_check" in response.text
    assert '"relevant":false' in response.text
    assert '"finishReason":"topic_rejected"' in response.text
    assert capture.plans == []
    assert capture.streams == []


def testValidateStream(client: TestClient):
    updateBody = readBody()
    updateBody["input"] = " "
    readResponse = client.post(
        "/api/internal/chat/stream",
        headers=readHeaders(),
        json=updateBody,
    )
    assert readResponse.status_code == 422


@pytest.mark.parametrize("status", [
    "REJECTED", "APPLIED", "SUPERSEDED", "CONFLICT", "FAILED", "UNKNOWN",
])
def testCurrentActionStateReachesPlanningAndAnswerDespiteStaleHistory(
    client: TestClient, monkeypatch: pytest.MonkeyPatch, status: str,
):
    capture = CapturingChatClient()
    monkeypatch.setattr(chat_router, "chatClient", capture)
    staleSummary = "用户希望更新专业，提案 2001 待确认。"
    staleHistory = [{"role": "assistant", "content": "提案 2001 待确认，请点击确认。"}]
    response = client.post("/api/internal/chat/stream", headers=readHeaders(), json={
        **readBody(), "input": "现在是什么情况？", "summary": staleSummary,
        "history": staleHistory,
        "actionStates": [{"actionId": "2001", "status": status,
                          "requiresConfirmation": True, "toolName": "propose_profile_patch"}],
    })

    assert response.status_code == 200
    assert "event: done" in response.text
    assert "event: action_proposed" not in response.text
    assert len(capture.plans) == len(capture.streams) == 1
    for messages, prompt in [*capture.plans, *capture.streams]:
        # Current state follows the historical text and is present in both calls.
        assert messages[0] == staleHistory[0]
        context = json.loads(messages[-2]["content"])["contextData"]
        assert context["summary"] == staleSummary
        assert context["actionStates"] == [{
            "actionId": "2001", "status": status, "requiresConfirmation": False,
            "toolName": "propose_profile_patch",
        }]
        assert messages[-1]["content"] == "现在是什么情况？"
        assert "contextData.actionStates" in prompt
        assert "优先于历史消息和 summary 中的旧文字" in prompt
        assert "不得只基于历史或 summary 重建提案" in prompt


def testPendingActionNormalizesConfirmationAndWorksWithoutOtherContext(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
):
    capture = CapturingChatClient()
    monkeypatch.setattr(chat_router, "chatClient", capture)
    response = client.post("/api/internal/chat/stream", headers=readHeaders(), json={
        **readBody(), "actionStates": [{"actionId": "2001", "status": "PENDING",
                                      "requiresConfirmation": False}],
    })

    assert response.status_code == 200
    for messages, _ in [*capture.plans, *capture.streams]:
        states = json.loads(messages[-2]["content"])["contextData"]["actionStates"]
        assert states == [{"actionId": "2001", "status": "PENDING",
                           "requiresConfirmation": True, "toolName": None}]


def testMixedProposalHistoryRefsReachBothModelsWithoutProviderExtraFields(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
):
    capture = CapturingChatClient()
    monkeypatch.setattr(chat_router, "chatClient", capture)
    history = [
        {"role": "user", "content": "请更新资料和求职目标"},
        {"role": "assistant", "content": "提案 2001 和 2002 待确认。",
         "actionIds": ["2001", "2002"]},
    ]
    response = client.post("/api/internal/chat/stream", headers=readHeaders(), json={
        **readBody(), "input": "还有什么需要确认？", "history": history,
        "actionStates": [
            {"actionId": "2001", "status": "REJECTED", "requiresConfirmation": True,
             "toolName": "propose_profile_patch"},
            {"actionId": "2002", "status": "PENDING", "requiresConfirmation": False,
             "toolName": "propose_memory_patch"},
        ],
    })

    assert response.status_code == 200
    assert len(capture.plans) == len(capture.streams) == 1
    for messages, prompt in [*capture.plans, *capture.streams]:
        assert messages[:2] == [
            {"role": "user", "content": history[0]["content"]},
            {"role": "assistant", "content": history[1]["content"]},
        ]
        assert all(set(message) == {"role", "content"} for message in messages)
        context = json.loads(messages[-2]["content"])["contextData"]
        assert context["historyActionRefs"] == [
            {"historyIndex": 1, "actionIds": ["2001", "2002"]},
        ]
        assert [(state["actionId"], state["status"], state["requiresConfirmation"])
                for state in context["actionStates"]] == [
            ("2001", "REJECTED", False), ("2002", "PENDING", True),
        ]
        assert "contextData.historyActionRefs" in prompt
        assert "必须分别按 actionStates 的最新状态处理" in prompt


def testLegacyHistoryWithoutActionIdsPreservesProviderMessages(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
):
    capture = CapturingChatClient()
    monkeypatch.setattr(chat_router, "chatClient", capture)
    history = [{"role": "assistant", "content": "之前讨论了求职目标。"}]
    body = {**readBody(), "history": history}
    assert chat_router.ChatStreamBody.model_validate(body).history[0].actionIds == []
    response = client.post("/api/internal/chat/stream", headers=readHeaders(), json=body)

    assert response.status_code == 200
    for messages, _ in [*capture.plans, *capture.streams]:
        assert messages == [*history, {"role": "user", "content": "你好"}]


@pytest.mark.parametrize("actionIds", [["2001"] * 33, ["bad-id"], ["1" * 33]])
def testHistoryActionRefsAreBoundedNumericIds(client: TestClient, actionIds: list):
    response = client.post("/api/internal/chat/stream", headers=readHeaders(), json={
        **readBody(), "history": [{"role": "assistant", "content": "待确认。",
                                  "actionIds": actionIds}],
    })
    assert response.status_code == 422


def testLegacyStreamWithoutActionStatesStillReachesBothModelCalls(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
):
    capture = CapturingChatClient()
    monkeypatch.setattr(chat_router, "chatClient", capture)
    assert chat_router.ChatStreamBody.model_validate(readBody()).actionStates == []
    response = client.post("/api/internal/chat/stream", headers=readHeaders(), json=readBody())

    assert response.status_code == 200
    assert capture.plans[0][0] == [{"role": "user", "content": "你好"}]
    assert capture.streams[0][0] == capture.plans[0][0]


@pytest.mark.parametrize("states", [
    [{"actionId": "2001", "status": "PENDING"}] * 101,
    [{"actionId": "2001", "status": "INVALID"}],
    [{"actionId": "not-an-id", "status": "REJECTED"}],
    [{"actionId": "2001", "status": "REJECTED", "toolName": "x" * 65}],
])
def testActionStatesRejectInvalidOrOversizedContext(client: TestClient, states: list):
    response = client.post("/api/internal/chat/stream", headers=readHeaders(),
                           json={**readBody(), "actionStates": states})
    assert response.status_code == 422


def testReadTool(client: TestClient):
    updateBody = readBody()
    updateBody["input"] = "找岗位"
    readResponse = client.post(
        "/api/internal/chat/stream", headers=readHeaders(), json=updateBody,
    )

    assert readResponse.status_code == 200
    assert readResponse.text.count("event: tool_status") == 2
    assert '"name":"search_jobs","status":"RUNNING"' in readResponse.text
    assert client.app.state.backend_client.context == "agent-context-test-value"
    assert client.app.state.backend_client.tool_call_id == "call-1"


def testProposalToolEmitsConfirmationEvent(client: TestClient):
    updateBody = readBody()
    updateBody["input"] = "更新资料"
    readResponse = client.post(
        "/api/internal/chat/stream", headers=readHeaders(), json=updateBody,
    )

    assert readResponse.status_code == 200
    assert "event: action_proposed" in readResponse.text
    assert '"toolName":"propose_profile_patch"' in readResponse.text
    assert '"actionId":"2001","status":"PENDING"' in readResponse.text
    assert client.app.state.backend_client.tool_call_id == "call-profile-1"


def testWriteFlagBlocksUnexpectedProposalCall(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
):
    monkeypatch.setattr(settings, "ai_chat_write_enabled", False)
    updateBody = readBody()
    updateBody["input"] = "更新资料"
    readResponse = client.post(
        "/api/internal/chat/stream", headers=readHeaders(), json=updateBody,
    )

    assert readResponse.status_code == 200
    assert "event: action_proposed" not in readResponse.text
    assert client.app.state.backend_client.tool_call_id is None


def testToolTimeoutIsSafeFailure(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
):
    monkeypatch.setattr(settings, "ai_chat_read_tool_timeout_seconds", 0.001)
    updateBody = readBody()
    updateBody["input"] = "慢查询"
    readResponse = client.post(
        "/api/internal/chat/stream", headers=readHeaders(), json=updateBody,
    )

    assert '"name":"search_jobs","status":"FAILED"' in readResponse.text
    assert "TOOL_TIMEOUT" not in readResponse.text
    assert "event: done" in readResponse.text


def test_named_tool_timeout_can_override_the_short_read_default(client, monkeypatch):
    monkeypatch.setattr(settings, "ai_chat_read_tool_timeout_seconds", 0.001)
    monkeypatch.setattr(settings, "ai_chat_tool_timeouts", {"search_jobs": 0.5})
    response = client.post("/api/internal/chat/stream", headers=readHeaders(),
                           json={**readBody(), "input": "慢查询"})
    assert '"name":"search_jobs","status":"COMPLETED"' in response.text


def test_structured_recommendation_uses_the_same_tool_and_emits_cards(client, monkeypatch):
    from app.chat import recommendations
    async def recommend(backend, context, args, call_id):
        assert args["workCities"] == ["上海"]
        assert context == "agent-context-test-value"
        return {"jobs": [{"id": "2094674091431800833", "positionName": "记者"}],
                "method": "rules", "degraded": False, "filters": args,
                "candidateCount": 1, "algorithmVersion": "1f3d8a7"}
    monkeypatch.setattr(recommendations, "recommend_jobs", recommend)
    response = client.post("/api/internal/chat/stream", headers=readHeaders(), json={
        **readBody(), "interaction": {"schemaVersion": 1, "type": "job_recommendation",
                                    "preferences": {"workCities": ["上海"]}},
    })
    assert "event: job_results" in response.text
    assert '"jobIds":["2094674091431800833"]' in response.text
    assert "event: done" in response.text


def testBusinessErrorDoesNotLeakInternals(client: TestClient):
    updateBody = readBody()
    updateBody["input"] = "错误查询"
    readResponse = client.post(
        "/api/internal/chat/stream", headers=readHeaders(), json=updateBody,
    )

    assert '"name":"search_jobs","status":"FAILED"' in readResponse.text
    assert "BackendApiError" not in readResponse.text
    assert "event: done" in readResponse.text


def testRunTimeout(client: TestClient, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(settings, "ai_chat_run_timeout_seconds", 0.001)
    updateBody = readBody()
    updateBody["input"] = "慢模型"
    readResponse = client.post(
        "/api/internal/chat/stream", headers=readHeaders(), json=updateBody,
    )

    assert "event: error" in readResponse.text
    assert '"reason":"AI_RUN_TIMEOUT"' in readResponse.text


def testConcurrencyLimit(client: TestClient, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(settings, "ai_chat_queue_timeout_seconds", 0.001)
    client.app.state.chat_semaphore = asyncio.Semaphore(0)
    readResponse = client.post(
        "/api/internal/chat/stream", headers=readHeaders(), json=readBody(),
    )

    assert "event: error" in readResponse.text
    assert '"reason":"AI_RATE_LIMITED"' in readResponse.text


def testSummarizeWithoutAgentContext(client: TestClient):
    readResponse = client.post(
        "/api/internal/chat/summarize",
        headers={"X-Internal-Token": "internal-test"},
        json={
            "previousSummary": "",
            "messages": [{"role": "user", "content": "我在找媒体实习"}],
            "throughMessageId": "42",
        },
    )

    assert readResponse.status_code == 200
    assert readResponse.json() == {
        "summary": "用户正在寻找媒体实习",
        "throughMessageId": "42",
    }


def testSummaryInstructsRemovalOfStaleProposalLifecycle(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
):
    capture = CapturingChatClient()
    monkeypatch.setattr(chat_router, "chatClient", capture)
    previous = "用户在找媒体实习。更新专业提案待确认。"
    response = client.post("/api/internal/chat/summarize",
                           headers={"X-Internal-Token": "internal-test"}, json={
        "previousSummary": previous,
        "messages": [{"role": "assistant", "content": "请确认修改专业的提案。"}],
        "throughMessageId": "42",
    })

    assert response.status_code == 200
    payload, prompt = capture.summaries[0]
    assert payload["previousSummary"] == previous
    assert "不得保留提案生命周期、待确认或等待确认的待办信息" in prompt
    assert "previousSummary 中已有的这类信息也必须删除" in prompt
    assert "仍可保留用户的长期目标和偏好" in prompt
