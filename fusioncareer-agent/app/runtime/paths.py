"""AGENT_RUNTIME_DIR 布局与原子写盘"""

from __future__ import annotations

import json
import os
from pathlib import Path


class RuntimePaths:
    def __init__(self, root: Path):
        self.root = root
        self.data_classes = root / "data_classes"
        self.data_class_refs = root / "data_class_refs.json"
        self.workflows = root / "workflows"
        self.skills = root / "skills"
        self.schedules = root / "schedules"

    def ensure_dirs(self) -> None:
        for d in (
            self.root,
            self.data_classes,
            self.workflows,
            self.skills,
            self.schedules,
        ):
            d.mkdir(parents=True, exist_ok=True)

    def data_class_file(self, name: str) -> Path:
        return self.data_classes / f"{name}.json"


def atomic_write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    payload = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(payload)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)
