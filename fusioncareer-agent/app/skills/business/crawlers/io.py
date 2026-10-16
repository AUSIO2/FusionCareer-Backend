"""文件 I/O、HTTP 会话、锁。"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

from app.runtime.paths import atomic_write_json

logger = logging.getLogger(__name__)


def load_json_file(path: Path, default: Any = None) -> Any:
    if not path.is_file():
        return {} if default is None else default
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {} if default is None else default


def save_json_file(path: Path, data: Any) -> None:
    if isinstance(data, dict):
        atomic_write_json(path, data)
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def append_jsonl(path: Path, record: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as f:
        f.write(json.dumps(record, ensure_ascii=False) + "\n")


def build_http_session() -> requests.Session:
    retry = Retry(
        total=5,
        connect=5,
        read=5,
        backoff_factor=1.5,
        status_forcelist=(429, 500, 502, 503, 504),
        allowed_methods=frozenset(["GET", "HEAD"]),
        raise_on_status=False,
    )
    adapter = HTTPAdapter(max_retries=retry, pool_connections=10, pool_maxsize=10)
    session = requests.Session()
    session.mount("https://", adapter)
    session.mount("http://", adapter)
    return session


def http_get(session: requests.Session, url: str, *, headers=None, params=None, timeout=60):
    return session.get(url, headers=headers, params=params, timeout=timeout)
