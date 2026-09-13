"""LLM Client — OpenAI 标准格式，兼容 DeepSeek 等"""

import ast
import json
import logging
from typing import Any

from openai import AsyncOpenAI

from app.config import settings

logger = logging.getLogger(__name__)


def extractObject(readText: str) -> str:
    readStart = readText.find("{")
    if readStart < 0:
        return readText
    readDepth = 0
    readQuote = ""
    readEscape = False
    for readIndex, readChar in enumerate(readText[readStart:], readStart):
        if readQuote:
            if readEscape:
                readEscape = False
            elif readChar == "\\":
                readEscape = True
            elif readChar == readQuote:
                readQuote = ""
            continue
        if readChar in {'"', "'"}:
            readQuote = readChar
        elif readChar == "{":
            readDepth += 1
        elif readChar == "}":
            readDepth -= 1
            if readDepth == 0:
                return readText[readStart:readIndex + 1]
    return readText[readStart:]


def removeCommas(readText: str) -> str:
    createText = []
    readQuote = ""
    readEscape = False
    for readIndex, readChar in enumerate(readText):
        if readQuote:
            createText.append(readChar)
            if readEscape:
                readEscape = False
            elif readChar == "\\":
                readEscape = True
            elif readChar == readQuote:
                readQuote = ""
            continue
        if readChar in {'"', "'"}:
            readQuote = readChar
        if readChar == "," and readText[readIndex + 1:].lstrip().startswith(("}", "]")):
            continue
        createText.append(readChar)
    return "".join(createText)


def parseJson(readText: str) -> dict:
    readObject = extractObject(readText.strip())
    readCandidates = (readText.strip(), readObject, removeCommas(readObject))
    readError: json.JSONDecodeError | None = None
    for readCandidate in dict.fromkeys(readCandidates):
        try:
            readValue, _ = json.JSONDecoder(strict=False).raw_decode(readCandidate.lstrip())
            if isinstance(readValue, dict):
                return readValue
        except json.JSONDecodeError as createError:
            readError = createError
    try:
        readValue = ast.literal_eval(removeCommas(readObject))
        if isinstance(readValue, dict):
            return readValue
    except (SyntaxError, ValueError):
        pass
    raise readError or json.JSONDecodeError("response is not a JSON object", readText, 0)


class LLMClient:
    """
    异步 LLM 客户端，使用 OpenAI SDK 标准格式。
    通过 base_url 配置可对接 OpenAI / DeepSeek / 其他兼容服务。
    """

    def __init__(self):
        self._client: AsyncOpenAI | None = None

    def _ensure_client(self) -> AsyncOpenAI:
        if self._client is None:
            self._client = AsyncOpenAI(
                api_key=settings.llm_api_key,
                base_url=settings.llm_base_url,
                timeout=120.0,
                max_retries=1,
            )
        return self._client

    async def chat(
        self,
        user_message: str,
        system_prompt: str = "",
        model: str | None = None,
        temperature: float = 0.3,
        max_tokens: int = 4096,
        response_format: dict | None = None,
    ) -> str:
        """
        发送聊天请求，返回文本响应。

        Args:
            user_message:    用户消息
            system_prompt:   系统提示词
            model:           模型名（默认用配置中的 llm_model）
            temperature:     温度
            max_tokens:      最大输出 token
            response_format: 响应格式（如 {"type": "json_object"}）

        Returns:
            模型生成的文本内容
        """
        client = self._ensure_client()
        model = model or settings.llm_model

        messages: list[dict[str, str]] = []
        if system_prompt:
            messages.append({"role": "system", "content": system_prompt})
        messages.append({"role": "user", "content": user_message})

        kwargs: dict[str, Any] = {
            "model": model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
        if response_format:
            kwargs["response_format"] = response_format

        logger.info(f"LLM 请求: model={model}, msg_len={len(user_message)}")
        response = await client.chat.completions.create(**kwargs)
        content = response.choices[0].message.content or ""
        logger.info(f"LLM 响应: {len(content)} chars")
        return content

    async def chat_json(
        self,
        user_message: str,
        system_prompt: str = "",
        model: str | None = None,
        temperature: float = 0.1,
        max_tokens: int = 4096,
    ) -> dict:
        """
        发送聊天请求，要求返回 JSON 格式，自动解析。

        Returns:
            解析后的 dict
        """
        for readAttempt in range(3):
            raw = await self.chat(
                user_message=user_message,
                system_prompt=system_prompt,
                model=model,
                temperature=temperature,
                max_tokens=max_tokens,
                response_format={"type": "json_object"},
            )

            try:
                return parseJson(raw)
            except json.JSONDecodeError:
                if readAttempt == 2:
                    raise
        raise RuntimeError("LLM JSON retry exhausted")
