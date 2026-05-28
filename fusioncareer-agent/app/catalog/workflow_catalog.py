"""工作流目录 — 内置 preset + runtime 磁盘，runtime 同名覆盖"""

from __future__ import annotations

import json
import logging
import shutil
from pathlib import Path
from typing import Literal

from app.engine.validator import WorkflowValidator
from app.runtime.paths import RuntimePaths, atomic_write_json

logger = logging.getLogger(__name__)

WorkflowSource = Literal["builtin", "runtime"]
BUILTIN_DIR = Path(__file__).resolve().parent.parent / "presets" / "workflows"


class WorkflowCatalogError(Exception):
    def __init__(
        self,
        message: str,
        *,
        code: str,
        status_code: int = 400,
        errors: list[str] | None = None,
    ):
        super().__init__(message)
        self.message = message
        self.code = code
        self.status_code = status_code
        self.errors = errors or []


class WorkflowCatalog:
    def __init__(self, paths: RuntimePaths) -> None:
        self._paths = paths
        self._workflows: dict[str, dict] = {}
        self._sources: dict[str, WorkflowSource] = {}

    def load_all(self) -> None:
        self._workflows.clear()
        self._sources.clear()

        if BUILTIN_DIR.is_dir():
            for path in sorted(BUILTIN_DIR.glob("*.json")):
                body = json.loads(path.read_text(encoding="utf-8"))
                name = body.get("name") or path.stem
                self._workflows[path.stem] = body
                self._sources[path.stem] = "builtin"

        self._paths.workflows.mkdir(parents=True, exist_ok=True)
        for path in sorted(self._paths.workflows.glob("*.json")):
            if path.name.startswith("_"):
                continue
            body = json.loads(path.read_text(encoding="utf-8"))
            stem = path.stem
            self._workflows[stem] = body
            self._sources[stem] = "runtime"

        logger.info(
            "WorkflowCatalog: %d 个工作流 (%d builtin, %d runtime)",
            len(self._workflows),
            sum(1 for s in self._sources.values() if s == "builtin"),
            sum(1 for s in self._sources.values() if s == "runtime"),
        )

    def seed_runtime_if_empty(self) -> bool:
        """runtime/workflows 为空时从内置 preset 复制。返回是否执行了 seed。"""
        self._paths.workflows.mkdir(parents=True, exist_ok=True)
        existing = list(self._paths.workflows.glob("*.json"))
        if existing:
            return False
        if not BUILTIN_DIR.is_dir():
            return False
        for src in BUILTIN_DIR.glob("*.json"):
            shutil.copy2(src, self._paths.workflows / src.name)
        logger.info("WorkflowCatalog: 已从 preset 初始化 %s", self._paths.workflows)
        return True

    def has(self, name: str) -> bool:
        return name in self._workflows

    def get(self, name: str) -> dict:
        if name not in self._workflows:
            raise WorkflowCatalogError(
                f"工作流 '{name}' 不存在",
                code="workflow_not_found",
                status_code=404,
            )
        return self._workflows[name]

    def source(self, name: str) -> WorkflowSource:
        if name not in self._sources:
            raise WorkflowCatalogError(
                f"工作流 '{name}' 不存在",
                code="workflow_not_found",
                status_code=404,
            )
        return self._sources[name]

    def list_entries(self) -> list[dict]:
        items = []
        for name in sorted(self._workflows):
            wf = self._workflows[name]
            items.append(
                {
                    "name": name,
                    "source": self._sources[name],
                    "node_count": len(wf.get("nodes") or {}),
                    "title": wf.get("name"),
                }
            )
        return items

    def put(self, name: str, body: dict, validator: WorkflowValidator) -> str:
        errors = validator.validate(body, allow_source_literals_only=True)
        if errors:
            raise WorkflowCatalogError(
                "工作流校验失败",
                code="workflow_validation_failed",
                status_code=422,
                errors=errors,
            )

        existed_runtime = self._sources.get(name) == "runtime"
        self._paths.workflows.mkdir(parents=True, exist_ok=True)
        path = self._paths.workflows / f"{name}.json"
        atomic_write_json(path, body)
        self._workflows[name] = body
        self._sources[name] = "runtime"
        return "updated" if existed_runtime else "created"

    def delete(self, name: str) -> None:
        if name not in self._workflows:
            raise WorkflowCatalogError(
                f"工作流 '{name}' 不存在",
                code="workflow_not_found",
                status_code=404,
            )
        if self._sources.get(name) != "runtime":
            raise WorkflowCatalogError(
                f"工作流 '{name}' 为内置 preset，不可删除",
                code="workflow_builtin",
                status_code=409,
            )
        path = self._paths.workflows / f"{name}.json"
        if path.is_file():
            path.unlink()
        del self._workflows[name]
        del self._sources[name]
