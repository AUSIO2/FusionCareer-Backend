import asyncio
import json
from types import SimpleNamespace

import pytest
from pydantic import ValidationError

from app.chat.topic_guard import checkTopic
from app.config import Settings, settings
from app.integrations.llm import LLMCompletion


@pytest.fixture(autouse=True)
def topicSettings(monkeypatch):
    monkeypatch.setattr(settings, "ai_chat_topic_guard_enabled", True)
    monkeypatch.setattr(settings, "ai_chat_topic_timeout_seconds", 6)


def topicBody():
    return SimpleNamespace(input="帮我分析新闻编辑岗位", jobId="1", interaction=None, history=[])


def testTopicGuardUsesBoundedLowCostRequest(monkeypatch):
    calls = []

    class Client:
        async def chat_json_with_meta(self, **options):
            calls.append(options)
            return ({"relevant": True, "reason": "求职问题"},
                    LLMCompletion("", "small", "stop", 10, 3, 13))

    body = SimpleNamespace(
        input="继续说这个岗位", jobId="1", interaction=None,
        history=[SimpleNamespace(role="assistant", content="岗位分析")],
    )
    monkeypatch.setattr(settings, "ai_chat_topic_guard_enabled", True)
    monkeypatch.setattr(settings, "ai_chat_topic_model", "small-model")

    result = asyncio.run(checkTopic(body, Client()))

    assert result.relevant
    assert calls[0]["model"] == "small-model"
    assert calls[0]["max_tokens"] == 80
    assert json.loads(calls[0]["user_message"])["history"][0]["content"] == "岗位分析"


def testTopicTimeoutDefaultsToSixSecondsAndCanBeConfigured(monkeypatch):
    monkeypatch.delenv("AI_CHAT_TOPIC_TIMEOUT_SECONDS", raising=False)
    assert Settings(_env_file=None).ai_chat_topic_timeout_seconds == 6
    monkeypatch.setenv("AI_CHAT_TOPIC_TIMEOUT_SECONDS", "4.5")
    assert Settings(_env_file=None).ai_chat_topic_timeout_seconds == 4.5


@pytest.mark.parametrize("value", [0, -1, float("inf"), float("nan")])
def testTopicTimeoutRequiresFinitePositiveSeconds(value):
    with pytest.raises(ValidationError):
        Settings(_env_file=None, ai_chat_topic_timeout_seconds=value)


@pytest.mark.asyncio
async def testDisabledTopicGuardDoesNotCallClassifier(monkeypatch):
    class Client:
        async def chat_json_with_meta(self, **options):
            raise AssertionError("disabled classifier must not be called")

    monkeypatch.setattr(settings, "ai_chat_topic_guard_enabled", False)
    result = await checkTopic(topicBody(), Client())
    assert result.relevant and result.reason == "disabled"


@pytest.mark.asyncio
async def testExplicitBooleanFalseStillRejectsUnrelatedTopics():
    class Client:
        async def chat_json_with_meta(self, **options):
            return {"relevant": False, "reason": "娱乐闲聊"}, LLMCompletion("", "small", "stop", 7, 2, 9)

    result = await checkTopic(topicBody(), Client())
    assert result.relevant is False
    assert result.reason == "娱乐闲聊"
    assert result.model == "small" and result.promptTokens == 7


@pytest.mark.parametrize("malformed", [
    {}, {"reason": "缺少 relevant"}, {"relevant": None}, {"relevant": 0}, {"relevant": 1},
    {"relevant": "false"}, {"relevant": "true"}, {"relevant": []}, {"relevant": {}},
    [{"relevant": False}], "false", None,
])
@pytest.mark.asyncio
async def testMalformedClassifierResultsFailOpenInsteadOfRejecting(malformed):
    class Client:
        async def chat_json_with_meta(self, **options):
            return malformed, LLMCompletion("", "small", "stop", 7, 2, 9)

    result = await checkTopic(topicBody(), Client())
    assert result.relevant is True
    assert result.reason == "classifier_unavailable"


@pytest.mark.asyncio
async def testClassifierFailureStillAllowsMainAgent():
    class Client:
        async def chat_json_with_meta(self, **options):
            raise RuntimeError("model temporarily unavailable")

    result = await checkTopic(topicBody(), Client())
    assert result.relevant is True
    assert result.reason == "classifier_unavailable"


@pytest.mark.asyncio
async def testClassifierTimeoutBoundsRetriesAndCancelsPendingCall(monkeypatch):
    class RetryingClient:
        retries = 0
        cancelled = False

        async def chat_json_with_meta(self, **options):
            try:
                raise RuntimeError("first request failed")
            except RuntimeError:
                self.retries += 1
                try:
                    await asyncio.Event().wait()
                except asyncio.CancelledError:
                    self.cancelled = True
                    raise

    monkeypatch.setattr(settings, "ai_chat_topic_timeout_seconds", 0.02)
    client = RetryingClient()
    # The outer bound is only a test watchdog; the classifier's independent
    # timeout must return well before it, rather than consume the whole run.
    async with asyncio.timeout(0.5):
        result = await checkTopic(topicBody(), client)

    assert result.relevant is True and result.reason == "classifier_unavailable"
    assert client.retries == 1 and client.cancelled


@pytest.mark.asyncio
async def testUserCancellationIsNotConvertedToFailOpen():
    entered = asyncio.Event()
    cleaned = asyncio.Event()

    class Client:
        async def chat_json_with_meta(self, **options):
            entered.set()
            try:
                await asyncio.Event().wait()
            finally:
                cleaned.set()

    task = asyncio.create_task(checkTopic(topicBody(), Client()))
    await entered.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert cleaned.is_set()
