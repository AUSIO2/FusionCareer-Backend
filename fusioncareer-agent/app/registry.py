"""Skill 注册中心 — 自动扫描 skills/ 目录下的所有 BaseSkill 子类"""

import importlib
import pkgutil
from pathlib import Path

from app.base_skill import BaseSkill


class SkillRegistry:
    """Skill 插件注册中心"""

    def __init__(self):
        self._skills: dict[str, BaseSkill] = {}

    def register(self, skill: BaseSkill):
        """手动注册一个 Skill 实例"""
        name = skill.name
        self._skills[name] = skill

    def get(self, name: str) -> BaseSkill:
        if name not in self._skills:
            raise KeyError(f"Skill '{name}' 未注册。已注册: {list(self._skills.keys())}")
        return self._skills[name]

    def list_all(self) -> list[dict]:
        """返回所有 Skill 的 define() 信息"""
        return [s.define() for s in self._skills.values()]

    def auto_discover(self, package_path: str = "app.skills"):
        """
        自动扫描指定包下所有模块，找到 BaseSkill 子类并实例化注册。
        """
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
