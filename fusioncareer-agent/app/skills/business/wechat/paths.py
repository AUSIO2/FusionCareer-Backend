"""微信公众号爬虫目录布局（相对 config_root）。"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class WechatPaths:
    config_root: Path

    @property
    def config_file(self) -> Path:
        return self.config_root / "config.json"

    @property
    def database_file(self) -> Path:
        return self.config_root / "wechat.db"

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
