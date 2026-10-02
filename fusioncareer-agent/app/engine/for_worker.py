"""For 节点单轮子进程执行入口。"""

from __future__ import annotations

import asyncio
import copy
import json
import sys
from pathlib import Path

from app.catalog.catalog import DataClassCatalog
from app.config import settings
from app.core.registry import SkillRegistry
from app.engine.runner import WorkflowEngine
from app.runtime.paths import RuntimePaths


def _load_engine() -> WorkflowEngine:
    runtime_paths = RuntimePaths(Path(settings.agent_runtime_dir).resolve())
    runtime_paths.ensure_dirs()
    catalog = DataClassCatalog(runtime_paths)
    if not catalog.seed_if_empty():
        catalog.load_from_disk()
    registry = SkillRegistry()
    registry.auto_discover("app.skills.platform")
    registry.auto_discover("app.skills.business")
    return WorkflowEngine(registry, catalog)


def _inject_iteration_context(workflow: dict, state_path: str, iteration: int) -> dict:
    wf = copy.deepcopy(workflow)
    nodes = wf.get("nodes") or {}
    for node in nodes.values():
        inputs = node.get("inputs") or {}
        if "state_path" in inputs and isinstance(inputs["state_path"], dict):
            if inputs["state_path"].get("_loop_inject") or "value" not in inputs["state_path"]:
                inputs["state_path"] = {"value": state_path}
        if "iteration" in inputs and isinstance(inputs["iteration"], dict):
            if inputs["iteration"].get("_loop_inject") or "value" not in inputs["iteration"]:
                inputs["iteration"] = {"value": iteration}
    return wf


async def _main(payload_path: Path) -> int:
    payload = json.loads(payload_path.read_text(encoding="utf-8"))
    workflow = _inject_iteration_context(
        payload["workflow"],
        payload.get("state_path", ""),
        int(payload.get("iteration", 0)),
    )
    engine = _load_engine()
    outputs = await engine.run(workflow)
    sys.stdout.write(json.dumps({"status": "completed", "outputs": outputs}, ensure_ascii=False))
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: python -m app.engine.for_worker <payload_json>")
    rc = asyncio.run(_main(Path(sys.argv[1])))
    raise SystemExit(rc)
