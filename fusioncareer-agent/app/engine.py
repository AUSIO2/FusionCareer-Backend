"""工作流引擎 — 并行 DAG 执行"""

import asyncio
import logging
from typing import Any

from app.base_skill import BaseSkill
from app.registry import SkillRegistry

logger = logging.getLogger(__name__)


class WorkflowEngine:
    """
    解析 workflow JSON → 所有节点并发提交 →
    每个节点 await 自己的上游 Future → 就绪即执行 →
    独立分支天然并行，AI 慢调用不阻塞其他分支。
    """

    def __init__(self, registry: SkillRegistry):
        self.registry = registry

    async def run(self, workflow: dict) -> dict[str, Any]:
        """
        执行一个工作流。

        Args:
            workflow: {"name": "...", "nodes": {"n1": {...}, "n2": {...}, ...}}

        Returns:
            cache: {"nid.slot_name": value, ...} 所有节点的输出
        """
        nodes = workflow["nodes"]
        cache: dict[str, Any] = {}  # "nid.slot" -> 数据
        futures: dict[str, asyncio.Future] = {}

        loop = asyncio.get_running_loop()
        for nid in nodes:
            futures[nid] = loop.create_future()

        async def _run_node(nid: str):
            node = nodes[nid]
            skill_name = node["skill"]
            skill: BaseSkill = self.registry.get(skill_name)
            defn = skill.define()

            # ① 收集上游依赖
            deps: set[str] = set()
            for slot, src in node.get("inputs", {}).items():
                if "from" in src:
                    dep_nid = src["from"].split(".")[0]
                    deps.add(dep_nid)

            # ② 等待所有上游完成
            if deps:
                logger.debug(f"[{nid}] 等待上游: {deps}")
                await asyncio.gather(*(futures[d] for d in deps))

            # ③ 解析输入
            inputs: dict[str, Any] = {}
            for slot, src in node.get("inputs", {}).items():
                if "value" in src:
                    inputs[slot] = src["value"]
                elif "from" in src:
                    ref = src["from"]
                    if ref not in cache:
                        raise ValueError(f"[{nid}] 输入 '{slot}' 引用了 '{ref}'，但数据不存在")
                    inputs[slot] = cache[ref]

            # ④ 类型检查（开发阶段）
            self._check_inputs(defn, inputs, nid)

            # ⑤ 执行 Skill
            logger.info(f"[{nid}] 执行 {skill_name}")
            try:
                result = await skill.execute(inputs)
            except Exception as e:
                logger.error(f"[{nid}] {skill_name} 执行失败: {e}")
                result = await skill.on_error(e)

            # ⑥ 缓存输出，标记完成 → 激活下游
            for slot, val in result.items():
                cache[f"{nid}.{slot}"] = val
            futures[nid].set_result(True)
            logger.info(f"[{nid}] {skill_name} 完成")

        # 所有节点同时提交，各自等待自己的依赖
        await asyncio.gather(*(_run_node(nid) for nid in nodes))
        return cache

    def validate(self, workflow: dict) -> list[str]:
        """
        校验工作流合法性，返回错误列表（空 = 合法）。
        检查：Skill 是否存在、连线类型是否匹配、是否有环。
        """
        errors: list[str] = []
        nodes = workflow.get("nodes", {})

        # Skill 存在性
        for nid, node in nodes.items():
            skill_name = node.get("skill", "")
            try:
                self.registry.get(skill_name)
            except KeyError:
                errors.append(f"节点 {nid}: Skill '{skill_name}' 不存在")
                continue

            skill = self.registry.get(skill_name)
            defn = skill.define()

            # 连线类型检查
            for slot, src in node.get("inputs", {}).items():
                if "from" in src:
                    ref = src["from"]
                    parts = ref.split(".", 1)
                    if len(parts) != 2:
                        errors.append(f"节点 {nid}: 输入 '{slot}' 的 from 格式错误: '{ref}'")
                        continue
                    src_nid, src_slot = parts
                    if src_nid not in nodes:
                        errors.append(f"节点 {nid}: 引用了不存在的节点 '{src_nid}'")
                        continue
                    # 检查类型匹配
                    try:
                        src_skill = self.registry.get(nodes[src_nid]["skill"])
                        src_defn = src_skill.define()
                        src_type = src_defn["outputs"].get(src_slot)
                        dst_type = defn["inputs"].get(slot)
                        if src_type and dst_type and src_type != dst_type:
                            errors.append(
                                f"节点 {nid}: 输入 '{slot}'({dst_type}) ← "
                                f"节点 {src_nid}.{src_slot}({src_type}) 类型不匹配"
                            )
                    except KeyError:
                        pass

        # 环路检测
        if not errors:
            cycle = self._detect_cycle(nodes)
            if cycle:
                errors.append(f"工作流存在环路: {' → '.join(cycle)}")

        return errors

    @staticmethod
    def _check_inputs(defn: dict, inputs: dict, nid: str):
        """检查必需输入是否齐全"""
        for slot in defn.get("inputs", {}):
            if slot not in inputs:
                raise ValueError(f"节点 {nid}: 缺少必需输入 '{slot}'")

    @staticmethod
    def _detect_cycle(nodes: dict) -> list[str] | None:
        """DFS 检测环路"""
        WHITE, GRAY, BLACK = 0, 1, 2
        color = {nid: WHITE for nid in nodes}
        path: list[str] = []

        def dfs(nid: str) -> list[str] | None:
            color[nid] = GRAY
            path.append(nid)
            node = nodes[nid]
            for src in node.get("inputs", {}).values():
                if "from" in src:
                    dep_nid = src["from"].split(".")[0]
                    if dep_nid in color:
                        if color[dep_nid] == GRAY:
                            idx = path.index(dep_nid)
                            return path[idx:] + [dep_nid]
                        if color[dep_nid] == WHITE:
                            cycle = dfs(dep_nid)
                            if cycle:
                                return cycle
            color[nid] = BLACK
            path.pop()
            return None

        for nid in nodes:
            if color[nid] == WHITE:
                cycle = dfs(nid)
                if cycle:
                    return cycle
        return None
