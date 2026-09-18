"""采集模块目录布局（相对 config_root）。"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class CrawlPaths:
    config_root: Path

    @property
    def config_file(self) -> Path:
        return self.config_root / "config.json"

    @property
    def database_file(self) -> Path:
        current = self.config_root / "crawl.db"
        legacy = self.config_root / "wechat.db"
        return legacy if legacy.exists() and not current.exists() else current

    @property
    def fakeids_file(self) -> Path:
        return self.config_root / "gzh.txt"

    @property
    def account_names_file(self) -> Path:
        return self.config_root / "公众号名字"

    @property
    def daily_report_file(self) -> Path:
        return self.config_root / "daily_report.jsonl"

    @property
    def manifest_dir(self) -> Path:
        return self.config_root / "manifest"

    def articles_base_dir(self, config: dict) -> Path:
        name = config.get("articles_base_dir", "公众号文章")
        p = Path(name)
        return p if p.is_absolute() else self.config_root / name

    def daily_report_path(self, config: dict) -> Path:
        raw = config.get("daily_report_path", "daily_report.jsonl")
        p = Path(raw)
        return p if p.is_absolute() else self.config_root / raw


def resolve_config_root(paths_payload: dict) -> Path:
    from app.config import settings

    raw = (paths_payload.get("config_root") or paths_payload.get("configRoot")
           or settings.crawl_config_root or settings.wechat_config_root)
    if not raw:
        raise ValueError("paths 缺少 config_root，且 CRAWL_CONFIG_ROOT 未配置")
    return Path(str(raw)).resolve()
