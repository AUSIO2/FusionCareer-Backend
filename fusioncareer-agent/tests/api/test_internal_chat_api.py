from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.api.routers import chat as chat_router
from app.chat.tools import WRITE_TOOLS
from app.config import settings
from app.main import app


class FakeChatClient:
    async def plan_tools(self, messages, tools, **readOptions):
        assert tools
        if messages[-1].get("content") == "找岗位":
            return {
                "content": "",
                "toolCalls": [{
                    "id": "call-1",
                    "type": "function",
                    "function": {"name": "search_jobs", "arguments": "{\"keyword\":\"媒体\"}"},
                }],
            }
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
        assert arguments == {"keyword": "媒体"}
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
