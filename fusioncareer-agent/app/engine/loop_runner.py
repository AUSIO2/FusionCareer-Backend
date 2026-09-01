"""工作流外层 for 循环（judge + 子进程 body + 可选 finalize）。"""

from __future__ import annotations

import json
import logging
import shutil
import subprocess
import sys
import uuid
from pathlib import Path
from typing import Any

from pydantic import BaseModel, Field

from app.config import settings
from app.core.registry import SkillRegistry

logger = logging.getLogger(__name__)


class LoopControl(BaseModel):
    judge_skill: str
    max_iterations: int = Field(gt=0)
    judge_inputs: dict[str, Any] = Field(default_factory=dict)
    initial_globals: dict[str, Any] = Field(default_factory=dict)
    finalize_skill: str | None = None
    finalize_inputs: dict[str, Any] = Field(default_factory=dict)


def validate_loop(registry: SkillRegistry, loop: LoopControl | None) -> list[str]:
    if loop is None:
        return []
    errors: list[str] = []
    try:
        skill = registry.get(loop.judge_skill)
    except KeyError:
        return [f"judge_skill '{loop.judge_skill}' 不存在"]

    defn = skill.define()
    out_def = defn.get("outputs") or {}
    if out_def.get("continue") != "bool":
        errors.append(f"judge_skill '{loop.judge_skill}' 必须输出 continue(bool)")

    required_inputs = set((defn.get("inputs") or {}).keys()) - {"state_path", "iteration"}
    missing = sorted(slot for slot in required_inputs if slot not in loop.judge_inputs)
    if missing:
        errors.append(f"judge_inputs 缺少必需字段: {', '.join(missing)}")

    if loop.finalize_skill:
        try:
            fin = registry.get(loop.finalize_skill)
        except KeyError:
            errors.append(f"finalize_skill '{loop.finalize_skill}' 不存在")
        else:
            fin_in = set((fin.define().get("inputs") or {}).keys()) - {"state_path"}
            fin_missing = sorted(
                s for s in fin_in if s not in loop.finalize_inputs and s not in loop.judge_inputs
            )
            if fin_missing:
                errors.append(f"finalize_inputs 缺少必需字段: {', '.join(fin_missing)}")
    return errors


def _run_workflow_subprocess(workflow: dict, state_path: Path, iteration: int) -> dict[str, Any]:
    run_root = state_path.parent
    payload_path = run_root / f"iter_{iteration}.json"
    payload_path.write_text(
        json.dumps(
            {
                "workflow": workflow,
                "state_path": str(state_path),
                "iteration": iteration,
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    proc = subprocess.run(
        [sys.executable, "-m", "app.engine.for_worker", str(payload_path)],
        cwd=str(Path(__file__).resolve().parents[2]),
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(
            f"iteration={iteration} 子进程失败 rc={proc.returncode}: {proc.stderr.strip()}"
        )
    lines = [line for line in proc.stdout.splitlines() if line.strip()]
    if not lines:
        return {}
    return json.loads(lines[-1])


async def _run_finalize(
    registry: SkillRegistry,
    loop: LoopControl,
    state_path: Path,
) -> dict[str, Any]:
    skill = registry.get(loop.finalize_skill)  # type: ignore[arg-type]
    inputs = dict(loop.finalize_inputs)
    defn = skill.define()
    if "state_path" in (defn.get("inputs") or {}):
        inputs["state_path"] = str(state_path)
    return await skill.execute(inputs)


async def run_with_loop(
    registry: SkillRegistry,
    workflow_name: str,
    workflow: dict,
    loop: LoopControl,
) -> dict[str, Any]:
    run_id = str(uuid.uuid4())
    run_root = Path(settings.agent_runtime_dir).resolve() / "runs" / run_id
    run_root.mkdir(parents=True, exist_ok=True)
    state_path = run_root / "state.json"
    state_path.write_text(json.dumps(loop.initial_globals, ensure_ascii=False), encoding="utf-8")

    judge = registry.get(loop.judge_skill)
    iterations_executed = 0
    stopped_by_judge = False
    last_result: dict[str, Any] = {}
    finalize_outputs: dict[str, Any] = {}

    try:
        for i in range(loop.max_iterations):
            judge_inputs = dict(loop.judge_inputs)
            if "state_path" in (judge.define().get("inputs") or {}):
                judge_inputs["state_path"] = str(state_path)
            if "iteration" in (judge.define().get("inputs") or {}):
                judge_inputs["iteration"] = i
            decision = await judge.execute(judge_inputs)
            if not bool(decision.get("continue", False)):
                stopped_by_judge = True
                break
            last_result = _run_workflow_subprocess(workflow, state_path, i)
            iterations_executed += 1

        if loop.finalize_skill and state_path.exists():
            finalize_outputs = await _run_finalize(registry, loop, state_path)
    finally:
        shutil.rmtree(run_root, ignore_errors=True)

    return {
        "status": "completed",
        "workflow": workflow_name,
        "run_id": run_id,
        "iterations_executed": iterations_executed,
        "stopped_by_judge": stopped_by_judge,
        "outputs": (last_result.get("outputs") or {}),
        "finalize_outputs": finalize_outputs,
    }
