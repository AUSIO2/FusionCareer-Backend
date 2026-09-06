"""Workflow 校验：类型名 + role + 拓扑（可选允许字面量 value）"""

from __future__ import annotations

from app.catalog.catalog import DataClassCatalog
from app.catalog.errors import CatalogError
from app.catalog.models import DataClassRole
from app.core.registry import SkillRegistry
from app.core.source_skills import is_source_skill


class WorkflowValidator:
    def __init__(self, registry: SkillRegistry, catalog: DataClassCatalog):
        self._registry = registry
        self._catalog = catalog

    def validate(
        self,
        workflow: dict,
        *,
        allow_literals: bool = False,
        allow_source_literals_only: bool = False,
    ) -> list[str]:
        errors: list[str] = []
        nodes = workflow.get("nodes") or {}
        if not isinstance(nodes, dict) or not nodes:
            errors.append("workflow.nodes 不能为空")
            return errors

        for nid, node in nodes.items():
            self._validate_node(
                nid, node, nodes, errors, allow_literals, allow_source_literals_only
            )

        cycle = self._detect_cycle(nodes)
        if cycle:
            errors.append(f"工作流存在环路: {' → '.join(cycle)}")

        return errors

    def _validate_node(
        self,
        nid: str,
        node: dict,
        nodes: dict,
        errors: list[str],
        allow_literals: bool,
        allow_source_literals_only: bool,
    ) -> None:
        skill_name = node.get("skill", "")
        if not skill_name:
            errors.append(f"节点 {nid}: 缺少 skill")
            return
        timeout_seconds = node.get("timeout_seconds")
        if timeout_seconds is not None:
            if not isinstance(timeout_seconds, (int, float)):
                errors.append(f"节点 {nid}: timeout_seconds 必须是数字")
            elif timeout_seconds <= 0:
                errors.append(f"节点 {nid}: timeout_seconds 必须大于 0")

        try:
            skill = self._registry.get(skill_name)
        except KeyError:
            errors.append(f"节点 {nid}: Skill '{skill_name}' 不存在")
            return

        defn = skill.define()
        source_node = is_source_skill(defn)
        out_slots = list((defn.get("outputs") or {}).keys())
        if len(out_slots) > 1:
            errors.append(f"节点 {nid}: Skill '{skill_name}' 的 outputs 超过 1 个")
            return

        in_types = defn.get("inputs") or {}
        node_inputs = node.get("inputs") or {}

        for slot, type_name in in_types.items():
            if type_name == "any":
                errors.append(f"节点 {nid}: 输入槽 '{slot}' 使用禁止类型 any")
                continue
            if not self._catalog.has(type_name):
                errors.append(f"节点 {nid}: 类型 '{type_name}' 不在数据类目录")
                continue
            try:
                self._catalog.assert_usable_as_input(type_name)
            except CatalogError as e:
                errors.append(f"节点 {nid}: {e.message}")

        for slot, type_name in (defn.get("outputs") or {}).items():
            if type_name == "any":
                errors.append(f"节点 {nid}: 输出槽 '{slot}' 使用禁止类型 any")
            elif not self._catalog.has(type_name):
                errors.append(f"节点 {nid}: 类型 '{type_name}' 不在数据类目录")
            else:
                record = self._catalog.get(type_name)
                if record.role not in (DataClassRole.O, DataClassRole.IO):
                    errors.append(f"节点 {nid}: 类型 '{type_name}' role 无效")

        for slot in in_types:
            if slot not in node_inputs:
                errors.append(f"节点 {nid}: 缺少必需输入槽 '{slot}'")

        for slot, spec in node_inputs.items():
            if slot not in in_types:
                errors.append(f"节点 {nid}: 未定义的输入槽 '{slot}'")
                continue
            if not isinstance(spec, dict):
                errors.append(f"节点 {nid}.{slot}: inputs 规格必须是对象")
                continue

            if spec.get("_loop_inject"):
                continue

            has_from = "from" in spec
            has_value = "value" in spec
            if has_from and has_value:
                errors.append(f"节点 {nid}.{slot}: 不能同时有 from 和 value")
                continue
            if not has_from and not has_value:
                errors.append(f"节点 {nid}.{slot}: 需要 from 或 value")
                continue
            if has_value:
                if allow_literals or (allow_source_literals_only and source_node):
                    pass
                elif source_node:
                    errors.append(f"节点 {nid}.{slot}: 不允许 value（请使用 allow_source_literals_only 模式）")
                else:
                    errors.append(
                        f"节点 {nid}.{slot}: 业务节点不允许 value，请使用 from 连接上游 output"
                    )
                if not (allow_literals or (allow_source_literals_only and source_node)):
                    continue
            if has_from:
                self._validate_from_edge(nid, slot, in_types[slot], spec["from"], nodes, out_slots, errors)

    def _validate_from_edge(
        self,
        nid: str,
        slot: str,
        dst_type: str,
        ref: str,
        nodes: dict,
        _current_out_slots: list[str],
        errors: list[str],
    ) -> None:
        parts = ref.split(".", 1)
        if len(parts) != 2:
            errors.append(f"节点 {nid}.{slot}: from 格式错误 '{ref}'，应为 node_id.output_slot")
            return
        src_nid, src_slot = parts
        if src_nid not in nodes:
            errors.append(f"节点 {nid}.{slot}: 引用了不存在的节点 '{src_nid}'")
            return
        if src_nid == nid:
            errors.append(f"节点 {nid}.{slot}: 不能连接自身")

        try:
            src_skill = self._registry.get(nodes[src_nid]["skill"])
        except KeyError:
            errors.append(f"节点 {nid}.{slot}: 上游节点 '{src_nid}' Skill 无效")
            return

        src_defn = src_skill.define()
        src_out = src_defn.get("outputs") or {}
        src_out_slots = list(src_out.keys())
        if len(src_out_slots) == 0:
            errors.append(f"节点 {nid}.{slot}: 上游 '{src_nid}' 无 output，不能作为 from 源")
            return
        if len(src_out_slots) > 1:
            errors.append(f"节点 {nid}.{slot}: 上游 '{src_nid}' output 不唯一")
            return
        if src_slot != src_out_slots[0]:
            errors.append(
                f"节点 {nid}.{slot}: from '{ref}' 应指向 '{src_nid}.{src_out_slots[0]}'"
            )
            return

        src_type = src_out[src_slot]
        if src_type != dst_type:
            errors.append(
                f"节点 {nid}.{slot}: 类型不相等 {dst_type} ← {src_nid}.{src_slot}({src_type})"
            )

    @staticmethod
    def _detect_cycle(nodes: dict) -> list[str] | None:
        WHITE, GRAY, BLACK = 0, 1, 2
        color = {nid: WHITE for nid in nodes}
        path: list[str] = []

        def dfs(nid: str) -> list[str] | None:
            color[nid] = GRAY
            path.append(nid)
            for spec in nodes[nid].get("inputs", {}).values():
                if isinstance(spec, dict) and "from" in spec:
                    dep_nid = spec["from"].split(".", 1)[0]
                    if dep_nid not in color:
                        continue
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
