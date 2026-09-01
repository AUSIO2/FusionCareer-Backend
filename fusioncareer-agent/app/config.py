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

    # 热更新 runtime（开发默认 ./runtime）
    agent_runtime_dir: str = "./runtime"

    # 管理员 API（PUT /api/admin/*）；未配置则管理接口返回 503
    agent_admin_token: str = ""

    # 定时任务时区
    schedule_timezone: str = "Asia/Shanghai"

    # 微信公众号爬虫数据目录（config.json / gzh.txt / history.json 等）
    wechat_config_root: str = ""
    wechat_token: str = ""
    wechat_cookie: str = ""

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8"}


settings = Settings()
