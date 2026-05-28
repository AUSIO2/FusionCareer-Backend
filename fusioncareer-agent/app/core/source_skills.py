"""源节点 Skill 判定（平台入口，非算法组业务 Skill）"""

from __future__ import annotations

SOURCE_KIND = "source"
SOURCE_NAME_PREFIX = "input_"


def is_source_skill(defn: dict) -> bool:
    if defn.get("kind") == SOURCE_KIND:
        return True
    name = defn.get("name") or ""
    return name.startswith(SOURCE_NAME_PREFIX)


def is_source_node(nodes: dict, nid: str, registry) -> bool:
    node = nodes.get(nid)
    if not node:
        return False
    skill_name = node.get("skill", "")
    try:
        skill = registry.get(skill_name)
    except KeyError:
        return skill_name.startswith(SOURCE_NAME_PREFIX)
    return is_source_skill(skill.define())
