"""Skill 对数据类的引用 → 锁定不可改删"""

from __future__ import annotations

import json
import logging
from pathlib import Path

from app.core.registry import SkillRegistry
from app.runtime.paths import RuntimePaths, atomic_write_json

logger = logging.getLogger(__name__)


def types_from_skill_define(defn: dict) -> set[str]:
    names: set[str] = set()
    for slot_map in (defn.get("inputs") or {}, defn.get("outputs") or {}):
        for type_name in slot_map.values():
            if isinstance(type_name, str) and type_name != "any":
                names.add(type_name)
    return names


class DataClassRefIndex:
    def __init__(self) -> None:
        self._refs: dict[str, set[str]] = {}

    def rebuild_from_registry(self, registry: SkillRegistry) -> None:
        self._refs.clear()
        for skill in registry.list_all():
            skill_name = skill["name"]
            for type_name in types_from_skill_define(skill):
                self._refs.setdefault(type_name, set()).add(skill_name)
        logger.info("DataClassRefIndex: %d 个类型被引用", len(self._refs))

    def load_from_disk(self, paths: RuntimePaths) -> None:
        if not paths.data_class_refs.is_file():
            return
        raw = json.loads(paths.data_class_refs.read_text(encoding="utf-8"))
        self._refs = {k: set(v) for k, v in raw.items()}

    def save_to_disk(self, paths: RuntimePaths) -> None:
        payload = {k: sorted(v) for k, v in sorted(self._refs.items())}
        atomic_write_json(paths.data_class_refs, payload)

    def register_skill(self, skill_name: str, type_names: set[str]) -> None:
        for type_name in type_names:
            self._refs.setdefault(type_name, set()).add(skill_name)

    def unregister_skill(self, skill_name: str, type_names: set[str] | None = None) -> None:
        if type_names is None:
            for referrers in self._refs.values():
                referrers.discard(skill_name)
            self._refs = {k: v for k, v in self._refs.items() if v}
            return
        for type_name in type_names:
            if type_name in self._refs:
                self._refs[type_name].discard(skill_name)
                if not self._refs[type_name]:
                    del self._refs[type_name]

    def is_locked(self, type_name: str) -> bool:
        return bool(self._refs.get(type_name))

    def referrers(self, type_name: str) -> list[str]:
        return sorted(self._refs.get(type_name, set()))

    def all_locked_names(self) -> set[str]:
        return set(self._refs.keys())
