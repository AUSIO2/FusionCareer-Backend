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
