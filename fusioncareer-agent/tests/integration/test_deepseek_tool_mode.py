from types import SimpleNamespace

import pytest

from app.integrations.llm import LLMClient


@pytest.mark.asyncio
@pytest.mark.parametrize("model,disabled", [("deepseek-v4-flash", True), ("gpt-4o-mini", False)])
async def test_tool_planning_and_stream_replies_use_a_consistent_mode(model, disabled):
    requests = []

    async def create(**kwargs):
        requests.append(kwargs)
        if kwargs.get("stream"):
            async def chunks():
                yield SimpleNamespace(usage=None, choices=[SimpleNamespace(
                    finish_reason="stop", delta=SimpleNamespace(content="hello"))])
            return chunks()
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content="", tool_calls=[]))])

    client = LLMClient()
    client._client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=create)))
    await client.plan_tools([{"role": "user", "content": "hello"}], [], model=model)
    events = [event async for event in client.stream_chat([
        {"role": "assistant", "content": "", "tool_calls": []},
        {"role": "tool", "tool_call_id": "example", "content": "result"},
    ], model=model)]
    assert events[-1]["type"] == "done"
    for request in requests:
        if disabled:
            assert request["extra_body"] == {"thinking": {"type": "disabled"}}
        else:
            assert "extra_body" not in request
