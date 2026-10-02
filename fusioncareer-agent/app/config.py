"""配置管理 — 从 .env 读取"""

from typing import Annotated

from pydantic import Field
from pydantic_settings import BaseSettings

Seconds = Annotated[float, Field(gt=0, allow_inf_nan=False)]


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
    internal_service_token: str = ""
    ai_chat_write_enabled: bool = False
    ai_chat_recommend_enabled: bool = True
    ai_chat_run_timeout_seconds: Seconds = 120.0
    ai_chat_read_tool_timeout_seconds: Seconds = 3.0
    ai_chat_write_tool_timeout_seconds: Seconds = 15.0
    ai_chat_parse_timeout_seconds: Seconds = 90.0
    # JSON object in AI_CHAT_TOOL_TIMEOUTS, e.g. {"recommend_jobs":45,"get_job":5}.
    # Named overrides take precedence over the existing per-category defaults.
    ai_chat_tool_timeouts: dict[str, Seconds] = Field(default_factory=dict)
    ai_chat_recommend_timeout_seconds: Seconds = 30.0
    recommendation_llm_enabled: bool = True
    recommendation_llm_timeout_seconds: Seconds = 15.0
    # The latest upstream can call a public market-data service once per company
    # and persist recommendation audit rows. Keep both opt-in for latency/privacy.
    recommendation_company_score_enabled: bool = False
    recommendation_audit_log_enabled: bool = False
    prefilter_enabled: bool = True
    prefilter_min_hits: int = Field(default=1, ge=1)
    ai_chat_max_concurrency: int = 16
    ai_chat_queue_timeout_seconds: float = 0.1

    # 定时任务时区
    schedule_timezone: str = "Asia/Shanghai"

    # 官网和公众号共用的采集数据目录；旧环境变量保留为兼容回退。
    crawl_config_root: str = ""
    wechat_config_root: str = ""
    wechat_token: str = ""
    wechat_cookie: str = ""

    # 生产环境启动时预加载 OCR，避免首个用户承担模型冷启动。
    ocr_preload: bool = False

    # Includes lock wait, HTTP calls and batch work; workflow node timeouts may be shorter.
    algorithm_timeout_seconds: Seconds = 1800

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8", "env_ignore_empty": True}

    def tool_timeout(self, name: str, *, write: bool = False) -> float:
        if name in self.ai_chat_tool_timeouts:
            return self.ai_chat_tool_timeouts[name]
        if name == "parse_resume_file":
            return self.ai_chat_parse_timeout_seconds
        if name == "recommend_jobs":
            return self.ai_chat_recommend_timeout_seconds
        return self.ai_chat_write_tool_timeout_seconds if write else self.ai_chat_read_tool_timeout_seconds


settings = Settings()
