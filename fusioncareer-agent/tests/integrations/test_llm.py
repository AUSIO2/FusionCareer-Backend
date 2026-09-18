import asyncio
import json

import pytest

from app.integrations.llm import LLMClient


class FakeClient(LLMClient):
    def __init__(self):
        self.calls = 0
        self.tokens = []

    async def chat(self, **readOptions):
        self.calls += 1
        self.tokens.append(readOptions["max_tokens"])
        return '{"jobs":' if self.calls == 1 else '{"jobs": []}'


def testRetryJson():
    readClient = FakeClient()
    readResult = asyncio.run(readClient.chat_json("招聘", max_tokens=8192))

    assert readResult == {"jobs": []}
    assert readClient.calls == 2
    assert readClient.tokens == [8192, 8192]


@pytest.mark.parametrize("readText", [
    '```json\n{"jobs": []}\n```',
    '以下是结果：{"jobs": []}。',
    '{"jobs": [],}',
    "{'jobs': [], 'warnings': None}",
    '{"message": "first line\nsecond line"}',
])
def testRepairJson(readText):
    class RepairClient(LLMClient):
        async def chat(self, **readOptions):
            return readText

    readResult = asyncio.run(RepairClient().chat_json("招聘"))

    assert isinstance(readResult, dict)


def testRejectBrokenJson():
    class BrokenClient(LLMClient):
        def __init__(self):
            self.readCalls = 0

        async def chat(self, **readOptions):
            self.readCalls += 1
            return '{"jobs": ['

    readClient = BrokenClient()
    with pytest.raises(json.JSONDecodeError):
        asyncio.run(readClient.chat_json("招聘"))
    assert readClient.readCalls == 3


@pytest.mark.parametrize("model, response_format, disables_thinking", [
    ("deepseek-v4-flash", {"type": "json_object"}, True),
    ("deepseek-v4-pro", {"type": "json_object"}, True),
    ("deepseek-v4-flash", None, False),
    ("gpt-4o-mini", {"type": "json_object"}, False),
])
def testStructuredDeepSeekDisablesThinking(model, response_format, disables_thinking):
    from types import SimpleNamespace
    from unittest.mock import AsyncMock

    create = AsyncMock(return_value=SimpleNamespace(choices=[SimpleNamespace(
        message=SimpleNamespace(content='{"jobs": []}'), finish_reason="stop",
    )]))
    client = LLMClient()
    client._client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=create)))

    assert asyncio.run(client.chat("招聘", model=model, response_format=response_format)) == '{"jobs": []}'
    options = create.call_args.kwargs
    if disables_thinking:
        assert options["extra_body"] == {"thinking": {"type": "disabled"}}
    else:
        assert "extra_body" not in options
