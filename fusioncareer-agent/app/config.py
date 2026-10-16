"""配置管理 — 从 .env 读取"""

from typing import Annotated, Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings

Seconds = Annotated[float, Field(gt=0, allow_inf_nan=False)]


class Settings(BaseSettings):
    # Java 后端
    backend_base_url: str = "http://localhost:9100"

    # LLM (OpenAI 兼容格式)
    llm_base_url: str = "https://api.openai.com/v1"
    llm_api_key: str = "sk-xxx"
    llm_model: str = "gpt-4o-mini"

    # 岗位长文结构化：语义分块、详情组批及有限重试。
    job_structure_mode: Literal["chunked", "legacy"] = "chunked"
    job_structure_short_article_chars: int = Field(default=6000, ge=1000, le=50000)
    job_structure_block_max_chars: int = Field(default=8000, ge=1000, le=30000)
    job_structure_block_overlap_chars: int = Field(default=400, ge=0, le=3000)
    job_structure_detail_batch_jobs: int = Field(default=4, ge=1, le=20)
    job_structure_detail_context_chars: int = Field(default=12000, ge=2000, le=50000)
    job_structure_index_concurrency: int = Field(default=3, ge=1, le=10)
    job_structure_article_concurrency: int = Field(default=3, ge=1, le=10)
    job_structure_max_attempts: int = Field(default=3, ge=1, le=10)
    job_structure_claim_lease_seconds: int = Field(default=7200, ge=300, le=86400)

    # Agent 服务
    agent_port: int = 8900

    # 热更新 runtime（开发默认 ./runtime）
    agent_runtime_dir: str = "./runtime"

    # 管理员 API（PUT /api/admin/*）；未配置则管理接口返回 503
    agent_admin_token: str = ""
    internal_service_token: str = ""
    ai_chat_write_enabled: bool = False
    ai_chat_recommend_enabled: bool = True
    ai_chat_resume_advice_enabled: bool = True
    ai_chat_resume_advice_timeout_seconds: Seconds = 150.0
    ai_chat_resume_advice_llm_timeout_seconds: Seconds = 45.0
    ai_chat_topic_guard_enabled: bool = True
    ai_chat_topic_model: str = "deepseek-v4-flash"
    ai_chat_topic_timeout_seconds: Seconds = 6.0
    ai_chat_run_timeout_seconds: Seconds = 180.0
    ai_chat_read_tool_timeout_seconds: Seconds = 3.0
    ai_chat_write_tool_timeout_seconds: Seconds = 15.0
    ai_chat_parse_timeout_seconds: Seconds = 90.0
    resume_parse_timeout_seconds: Seconds = 75.0
    # The upstream repository keeps the 261013 parser beside the legacy entry.
    # Select it explicitly so production can roll back without replacing images.
    resume_parser_version: Literal["legacy", "261013"] = "legacy"
    # The upstream parser can persist model I/O. Keep it opt-in in the Agent;
    # resume workspaces are private, but their content is still personal data.
    resume_model_io_log_enabled: bool = False
    resume_model_io_log_max_text_chars: int = Field(default=120000, ge=1000, le=1000000)
    resume_model_io_actor_hmac_secret: str = ""
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
    # Post-extraction record dedup catches the same employer campaign reposted
    # by different universities. It is independent from whole-article dedup.
    job_record_dedup_enabled: bool = True
    article_dedup_enabled: bool = True
    article_dedup_ngram: int = Field(default=3, ge=1, le=10)
    article_dedup_num_perm: int = Field(default=128, ge=16, le=512)
    article_dedup_lsh_threshold: float = Field(default=0.6, gt=0, lt=1, allow_inf_nan=False)
    article_dedup_jaccard_threshold: float = Field(default=0.85, gt=0, le=1, allow_inf_nan=False)
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

    @model_validator(mode="after")
    def validate_dedup_thresholds(self):
        if self.article_dedup_lsh_threshold > self.article_dedup_jaccard_threshold:
            raise ValueError("article dedup LSH threshold must not exceed the exact Jaccard threshold")
        return self

    def tool_timeout(self, name: str, *, write: bool = False) -> float:
        if name in self.ai_chat_tool_timeouts:
            return self.ai_chat_tool_timeouts[name]
        if name in {"parse_resume_file", "analyze_resume_file", "resume_advice_context"}:
            return self.ai_chat_parse_timeout_seconds
        if name == "recommend_jobs":
            return self.ai_chat_recommend_timeout_seconds
        if name == "advise_resume":
            return self.ai_chat_resume_advice_timeout_seconds
        return self.ai_chat_write_tool_timeout_seconds if write else self.ai_chat_read_tool_timeout_seconds


settings = Settings()
