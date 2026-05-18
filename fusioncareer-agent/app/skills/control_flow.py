"""
内置控制流 Skill 节点：if_gate / repeat / for_each

全部是普通 Skill，不需要修改引擎。
"""

import logging
from typing import Any

from app.base_skill import BaseSkill

logger = logging.getLogger(__name__)

# 需要 registry 引用 — 由 main.py 启动时注入
_registry = None


def set_registry(registry):
    global _registry
    _registry = registry


# ── if_gate: 条件门控 ──

class IfGateSkill(BaseSkill):
    """
    条件判断：condition 为真输出 value，为假输出 fallback（默认 null）。

    示例:
        "gate": {
            "skill": "if_gate",
            "inputs": {
                "condition": {"from": "check.result"},
                "value":     {"from": "n1.data"},
                "fallback":  {"value": null}
            }
        }
    """

    def define(self):
        return {
            "name": "if_gate",
            "description": "条件判断：为真输出 value，为假输出 fallback",
            "inputs": {
                "condition": "bool",
                "value": "any",
                "fallback": "any",
            },
            "outputs": {
                "result": "any",
                "passed": "bool",
            },
        }

    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        condition = bool(inputs.get("condition", False))
        value = inputs.get("value")
        fallback = inputs.get("fallback")

        if condition:
            logger.info("if_gate: ✅ 条件为真")
            return {"result": value, "passed": True}
        else:
            logger.info("if_gate: ❌ 条件为假")
            return {"result": fallback, "passed": False}


# ── repeat: 重复执行某个 Skill N 次 ──

class RepeatSkill(BaseSkill):
    """
    把指定 Skill 重复执行 N 次，收集每次的输出。

    示例:
        "loop": {
            "skill": "repeat",
            "inputs": {
                "skill_name": {"value": "resume_writer"},
                "times":      {"value": 10},
                "inputs":     {"value": {"user_id": 123, "resume_data": {...}}}
            }
        }
    """

    def define(self):
        return {
            "name": "repeat",
            "description": "重复执行指定 Skill N 次",
            "inputs": {
                "skill_name": "text",
                "times": "int",
                "inputs": "json_obj",
            },
            "outputs": {
                "results": "list",
                "count": "int",
            },
        }

    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        skill_name = inputs["skill_name"]
        times = int(inputs["times"])
        skill_inputs = inputs.get("inputs", {}) or {}

        if _registry is None:
            raise RuntimeError("控制流 registry 未注入")

        skill = _registry.get(skill_name)
        results = []

        logger.info(f"repeat: 执行 {skill_name} × {times} 次")
        for i in range(times):
            logger.info(f"  [{i + 1}/{times}] {skill_name}")
            try:
                result = await skill.execute(skill_inputs)
                results.append(result)
            except Exception as e:
                logger.warning(f"  [{i + 1}/{times}] 失败: {e}")
                results.append({"error": str(e)})

        return {"results": results, "count": len(results)}


# ── for_each: 遍历列表，对每个元素执行 Skill ──

class ForEachSkill(BaseSkill):
    """
    对列表每个元素执行指定 Skill，元素注入到 item_slot 槽位。

    示例:
        "loop": {
            "skill": "for_each",
            "inputs": {
                "items":      {"value": [1001, 1002, 1003]},
                "skill_name": {"value": "resume_writer"},
                "item_slot":  {"value": "user_id"},
                "base_inputs": {"value": {"resume_data": {...}}}
            }
        }
    """

    def define(self):
        return {
            "name": "for_each",
            "description": "遍历列表，对每个元素执行指定 Skill",
            "inputs": {
                "items": "list",
                "skill_name": "text",
                "item_slot": "text",
                "base_inputs": "json_obj",
            },
            "outputs": {
                "results": "list",
                "count": "int",
            },
        }

    async def execute(self, inputs: dict[str, Any]) -> dict[str, Any]:
        items = inputs["items"]
        skill_name = inputs["skill_name"]
        item_slot = inputs["item_slot"]
        base = inputs.get("base_inputs", {}) or {}

        if _registry is None:
            raise RuntimeError("控制流 registry 未注入")
        if not isinstance(items, list):
            raise ValueError(f"for_each: items 必须是列表，收到 {type(items).__name__}")

        skill = _registry.get(skill_name)
        results = []

        logger.info(f"for_each: 遍历 {len(items)} 个元素，调用 {skill_name}")
        for i, item in enumerate(items):
            skill_inputs = {**base, item_slot: item}
            logger.info(f"  [{i + 1}/{len(items)}] {skill_name}({item_slot}={item!r})")
            try:
                result = await skill.execute(skill_inputs)
                results.append(result)
            except Exception as e:
                logger.warning(f"  [{i + 1}/{len(items)}] 失败: {e}")
                results.append({"error": str(e)})

        return {"results": results, "count": len(results)}
