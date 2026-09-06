import asyncio

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
