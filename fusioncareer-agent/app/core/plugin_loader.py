"""从 runtime/skills/{name}/skill.py 动态加载 Skill 插件"""

from __future__ import annotations

import ast
import importlib.util
import logging
import sys
from pathlib import Path
from types import ModuleType

from app.core.base_skill import BaseSkill

logger = logging.getLogger(__name__)

FORBIDDEN_IMPORT_ROOTS = frozenset(
    {
        "os",
        "subprocess",
        "socket",
        "shutil",
        "pathlib",
        "ctypes",
        "multiprocessing",
        "pickle",
        "builtins",
    }
)


class PluginLoadError(Exception):
    def __init__(self, message: str, *, code: str = "plugin_load_failed", status_code: int = 422):
        super().__init__(message)
        self.message = message
        self.code = code
        self.status_code = status_code


def _check_source_safe(source: str, *, filename: str = "<skill>") -> None:
    try:
        tree = ast.parse(source, filename=filename)
    except SyntaxError as e:
        raise PluginLoadError(f"Python 语法错误: {e}", code="invalid_syntax") from e

    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                root = alias.name.split(".", 1)[0]
                if root in FORBIDDEN_IMPORT_ROOTS:
                    raise PluginLoadError(f"禁止 import {root}", code="forbidden_import")
        elif isinstance(node, ast.ImportFrom):
            if node.module:
                root = node.module.split(".", 1)[0]
                if root in FORBIDDEN_IMPORT_ROOTS:
                    raise PluginLoadError(f"禁止 from {root} import", code="forbidden_import")


def _module_name(skill_name: str) -> str:
    return f"fusioncareer_agent_plugin_{skill_name}"


def unload_plugin_module(skill_name: str) -> None:
    mod_name = _module_name(skill_name)
    sys.modules.pop(mod_name, None)


def load_skill_from_source(skill_name: str, source: str) -> BaseSkill:
    _check_source_safe(source, filename=f"{skill_name}/skill.py")
    mod_name = _module_name(skill_name)
    unload_plugin_module(skill_name)

    spec = importlib.util.spec_from_loader(mod_name, loader=None)
    if spec is None:
        raise PluginLoadError("无法创建模块 spec")
    module = importlib.util.module_from_spec(spec)
    sys.modules[mod_name] = module
    code = compile(source, f"{skill_name}/skill.py", "exec")
    exec(code, module.__dict__)  # noqa: S102 — 受 AST 黑名单约束的管理员上传

    instances: list[BaseSkill] = []
    for attr_name in dir(module):
        attr = getattr(module, attr_name)
        if isinstance(attr, type) and issubclass(attr, BaseSkill) and attr is not BaseSkill:
            instances.append(attr())

    if not instances:
        raise PluginLoadError("skill.py 中未找到 BaseSkill 子类", code="no_skill_class")
    if len(instances) > 1:
        raise PluginLoadError("skill.py 中只能有一个 BaseSkill 子类", code="multiple_skill_classes")

    skill = instances[0]
    if skill.name != skill_name:
        raise PluginLoadError(
            f"define().name 为 '{skill.name}'，与目录名 '{skill_name}' 不一致",
            code="skill_name_mismatch",
        )
    return skill


def load_all_from_disk(skills_root: Path) -> dict[str, BaseSkill]:
    loaded: dict[str, BaseSkill] = {}
    if not skills_root.is_dir():
        return loaded

    for skill_dir in sorted(skills_root.iterdir()):
        if not skill_dir.is_dir():
            continue
        skill_py = skill_dir / "skill.py"
        if not skill_py.is_file():
            continue
        name = skill_dir.name
        source = skill_py.read_text(encoding="utf-8")
        try:
            loaded[name] = load_skill_from_source(name, source)
            logger.info("PluginLoader: 已加载 %s", name)
        except PluginLoadError:
            logger.exception("PluginLoader: 跳过 %s", name)
            raise
    return loaded
