"""Skill 注册中心 — 内置 discover + runtime 插件"""

from __future__ import annotations

import importlib
import logging
import pkgutil
from pathlib import Path

from app.core.base_skill import BaseSkill
from app.core.plugin_loader import PluginLoadError, load_all_from_disk
from app.runtime.paths import RuntimePaths

logger = logging.getLogger(__name__)


class SkillRegistry:
    """Skill 插件注册中心"""

    def __init__(self):
        self._skills: dict[str, BaseSkill] = {}
        self._builtin_names: set[str] = set()

    def register(self, skill: BaseSkill):
        """手动注册一个 Skill 实例"""
        self._skills[skill.name] = skill

    def register_plugin(self, skill: BaseSkill) -> None:
        if skill.name in self._builtin_names:
            raise ValueError(f"插件不能覆盖内置 Skill '{skill.name}'")
        self._skills[skill.name] = skill

    def unregister_plugin(self, skill_name: str) -> None:
        if skill_name in self._builtin_names:
            return
        self._skills.pop(skill_name, None)

    def is_builtin(self, skill_name: str) -> bool:
        return skill_name in self._builtin_names

    def has(self, skill_name: str) -> bool:
        return skill_name in self._skills

    def get(self, name: str) -> BaseSkill:
        if name not in self._skills:
            raise KeyError(f"Skill '{name}' 未注册。已注册: {list(self._skills.keys())}")
        return self._skills[name]

    def list_all(self) -> list[dict]:
        """返回所有 Skill 的 define() 信息"""
        return [s.define() for s in self._skills.values()]

    def list_entries(self) -> list[dict]:
        items = []
        for name in sorted(self._skills):
            items.append(
                {
                    "name": name,
                    "source": "builtin" if name in self._builtin_names else "runtime",
                    **{k: v for k, v in self.get(name).define().items() if k != "name"},
                }
            )
        return items

    def auto_discover(self, package_path: str = "app.skills.business"):
        """扫描镜像内置 Skill 包。"""
        pkg = importlib.import_module(package_path)
        pkg_dir = Path(pkg.__file__).parent

        for _importer, module_name, _is_pkg in pkgutil.walk_packages(
            [str(pkg_dir)], prefix=package_path + "."
        ):
            module = importlib.import_module(module_name)
            for attr_name in dir(module):
                attr = getattr(module, attr_name)
                if (
                    isinstance(attr, type)
                    and issubclass(attr, BaseSkill)
                    and attr is not BaseSkill
                ):
                    instance = attr()
                    self.register(instance)

        self._builtin_names = set(self._skills.keys())
        logger.info("SkillRegistry: %d 个内置 Skill", len(self._builtin_names))

    def reload_plugins(self, paths: RuntimePaths) -> None:
        """卸载 runtime 插件并从磁盘重新加载。"""
        for name in list(self._skills):
            if name not in self._builtin_names:
                del self._skills[name]

        try:
            plugins = load_all_from_disk(paths.skills)
        except PluginLoadError:
            raise

        for name, skill in plugins.items():
            if name in self._builtin_names:
                logger.warning("跳过与内置冲突的插件 %s", name)
                continue
            self._skills[name] = skill

        logger.info(
            "SkillRegistry: 共 %d 个 Skill（内置 %d + 插件 %d）",
            len(self._skills),
            len(self._builtin_names),
            len(self._skills) - len(self._builtin_names),
        )
