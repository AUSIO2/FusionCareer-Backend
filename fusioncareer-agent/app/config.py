"""配置管理 — 从 .env 读取"""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # Java 后端
    backend_base_url: str = "http://localhost:9100"

    # LLM (OpenAI 兼容格式)
    llm_base_url: str = "https://api.openai.com/v1"
    llm_api_key: str = "sk-xxx"
    llm_model: str = "gpt-4o-mini"

    # Agent 服务
    agent_port: int = 8900

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8"}


settings = Settings()
