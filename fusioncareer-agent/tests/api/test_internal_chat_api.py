import asyncio
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.api.routers import chat as chat_router
from app.chat.tools import WRITE_TOOLS
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


@pytest.fixture
def client(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(settings, "agent_runtime_dir", str(tmp_path / "runtime"))
    monkeypatch.setattr(settings, "internal_service_token", "internal-test")
    monkeypatch.setattr(settings, "ai_chat_write_enabled", True)
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


def testValidateStream(client: TestClient):
    updateBody = readBody()
    updateBody["input"] = " "
    readResponse = client.post(
        "/api/internal/chat/stream",
        headers=readHeaders(),
        json=updateBody,
    )
    assert readResponse.status_code == 422


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
