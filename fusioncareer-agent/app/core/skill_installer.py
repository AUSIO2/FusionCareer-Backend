"""Skill 插件安装 — 校验、introduces 写盘、落盘 skill.py"""

from __future__ import annotations

import logging
from pathlib import Path

from app.catalog.catalog import DataClassCatalog
from app.catalog.errors import CatalogError
from app.catalog.models import DataClassUpsertBody
from app.catalog.ref_index import DataClassRefIndex, types_from_skill_define
from app.catalog.skill_types import validate_skill_type_names
from app.core.base_skill import BaseSkill
from app.core.plugin_loader import PluginLoadError, load_skill_from_source
from app.core.registry import SkillRegistry
from app.runtime.paths import RuntimePaths, atomic_write_json

logger = logging.getLogger(__name__)


class SkillInstallError(Exception):
    def __init__(self, message: str, *, code: str, status_code: int = 422):
        super().__init__(message)
        self.message = message
        self.code = code
        self.status_code = status_code


def _skill_dir(paths: RuntimePaths, skill_name: str) -> Path:
    return paths.skills / skill_name


def install_skill(
    skill_name: str,
    source: str,
    *,
    paths: RuntimePaths,
    catalog: DataClassCatalog,
    ref_index: DataClassRefIndex,
    registry: SkillRegistry,
    introduces: dict[str, dict] | None = None,
) -> BaseSkill:
    if registry.is_builtin(skill_name):
        raise SkillInstallError(
            f"Skill '{skill_name}' 为内置 Skill，不可覆盖",
            code="builtin_skill_conflict",
            status_code=409,
        )

    try:
        skill = load_skill_from_source(skill_name, source)
    except PluginLoadError as e:
        raise SkillInstallError(e.message, code=e.code, status_code=e.status_code) from e

    defn = skill.define()
    if defn.get("name") != skill_name:
        raise SkillInstallError("define().name 与路径不一致", code="skill_name_mismatch")

    out_slots = defn.get("outputs") or {}
    if len(out_slots) > 1:
        raise SkillInstallError("outputs 最多 1 个槽位", code="too_many_outputs")

    input_types = set((defn.get("inputs") or {}).values())
    output_types = set(out_slots.values())

    for type_name, raw in (introduces or {}).items():
        if not isinstance(raw, dict):
            raise SkillInstallError("introduces 项必须是对象", code="invalid_introduces")
        upsert = DataClassUpsertBody.model_validate(raw)
        try:
            catalog.upsert(type_name, upsert, ref_index)
        except CatalogError as e:
            raise SkillInstallError(e.message, code=e.code, status_code=e.status_code) from e

    try:
        validate_skill_type_names(catalog, input_types, output_types)
    except CatalogError as e:
        raise SkillInstallError(e.message, code=e.code, status_code=e.status_code) from e

    skill_dir = _skill_dir(paths, skill_name)
    skill_dir.mkdir(parents=True, exist_ok=True)
    (skill_dir / "skill.py").write_text(source, encoding="utf-8")
    if introduces:
        atomic_write_json(skill_dir / "types.json", {"introduces": introduces})

    # 先释放旧引用再注册
    old_types: set[str] | None = None
    if registry.has(skill_name) and not registry.is_builtin(skill_name):
        old_types = types_from_skill_define(registry.get(skill_name).define())
        ref_index.unregister_skill(skill_name, old_types)

    registry.register_plugin(skill)

    type_names = types_from_skill_define(defn)
    ref_index.register_skill(skill_name, type_names)
    ref_index.save_to_disk(paths)

    logger.info("SkillInstaller: 已安装 %s", skill_name)
    return skill


def delete_skill(
    skill_name: str,
    *,
    paths: RuntimePaths,
    registry: SkillRegistry,
    ref_index: DataClassRefIndex,
) -> None:
    if registry.is_builtin(skill_name):
        raise SkillInstallError(
            f"Skill '{skill_name}' 为内置 Skill，不可删除",
            code="builtin_skill_protected",
            status_code=409,
        )
    if not registry.has(skill_name) and not _skill_dir(paths, skill_name).is_dir():
        raise SkillInstallError(
            f"Skill '{skill_name}' 不存在",
            code="skill_not_found",
            status_code=404,
        )

    if registry.has(skill_name):
        types = types_from_skill_define(registry.get(skill_name).define())
        ref_index.unregister_skill(skill_name, types)
        registry.unregister_plugin(skill_name)

    skill_dir = _skill_dir(paths, skill_name)
    if skill_dir.is_dir():
        import shutil

        shutil.rmtree(skill_dir)

    from app.core.plugin_loader import unload_plugin_module

    unload_plugin_module(skill_name)
    ref_index.save_to_disk(paths)
