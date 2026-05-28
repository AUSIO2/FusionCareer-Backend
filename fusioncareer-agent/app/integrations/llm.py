"""LLM Client — OpenAI 标准格式，兼容 DeepSeek 等"""

import logging
from typing import Any

from openai import AsyncOpenAI

from app.config import settings

logger = logging.getLogger(__name__)


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
    ) -> dict:
        """
        发送聊天请求，要求返回 JSON 格式，自动解析。

        Returns:
            解析后的 dict
        """
        import json

        raw = await self.chat(
            user_message=user_message,
            system_prompt=system_prompt,
            model=model,
            temperature=temperature,
            response_format={"type": "json_object"},
        )

        # 容错：去除可能的 markdown 包裹
        text = raw.strip()
        if text.startswith("```"):
            lines = text.split("\n")
            lines = [l for l in lines if not l.strip().startswith("```")]
            text = "\n".join(lines)

        return json.loads(text)
