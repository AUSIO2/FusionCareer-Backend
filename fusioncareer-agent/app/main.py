"""FusionCareer AI Agent — FastAPI 入口"""

import copy
import logging
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from fastapi import Depends, FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from app.api.deps.admin_auth import require_agent_admin
from app.api.exceptions import register_exception_handlers
from app.api.routers import admin as admin_router
from app.catalog.catalog import DataClassCatalog
from app.catalog.ref_index import DataClassRefIndex
from app.catalog.workflow_catalog import WorkflowCatalog
from app.config import settings
from app.core.registry import SkillRegistry
from app.core.source_skills import is_source_node
from app.engine import WorkflowEngine, WorkflowNodeError
from app.engine.loop_runner import LoopControl, run_with_loop, validate_loop
from app.integrations.backend import BackendClient
from app.runtime.paths import RuntimePaths
from app.scheduler.service import SchedulerService
from app.skills.business.insert_resume import set_backend_client as set_insert_resume_backend
from app.skills.business.insert_user_profile import (
    set_backend_client as set_insert_user_profile_backend,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-5s [%(name)s] %(message)s",
)
logger = logging.getLogger(__name__)

backend_client = BackendClient()
registry = SkillRegistry()
engine: WorkflowEngine | None = None
workflow_catalog: WorkflowCatalog | None = None
scheduler_service: SchedulerService | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global engine, workflow_catalog, scheduler_service

    set_insert_resume_backend(backend_client)
    set_insert_user_profile_backend(backend_client)

    runtime_paths = RuntimePaths(Path(settings.agent_runtime_dir).resolve())
    runtime_paths.ensure_dirs()

    data_class_catalog = DataClassCatalog(runtime_paths)
    if not data_class_catalog.seed_if_empty():
        data_class_catalog.load_from_disk()

    data_class_ref_index = DataClassRefIndex()
    data_class_ref_index.load_from_disk(runtime_paths)

    registry.auto_discover("app.skills.platform")
    registry.auto_discover("app.skills.business")
    try:
        registry.reload_plugins(runtime_paths)
    except Exception:
        logger.warning("runtime 插件加载失败，仅使用内置 Skill", exc_info=True)

    data_class_ref_index.rebuild_from_registry(registry)
    data_class_ref_index.save_to_disk(runtime_paths)

    workflow_catalog = WorkflowCatalog(runtime_paths)
    workflow_catalog.seed_runtime_if_empty()
    workflow_catalog.load_all()

    engine = WorkflowEngine(registry, data_class_catalog)
    scheduler_service = SchedulerService(
        engine,
        workflow_catalog,
        runtime_paths,
        registry,
        timezone=settings.schedule_timezone,
    )
    scheduler_service.start()

    app.state.runtime_paths = runtime_paths
    app.state.data_class_catalog = data_class_catalog
    app.state.data_class_ref_index = data_class_ref_index
    app.state.skill_registry = registry
    app.state.workflow_catalog = workflow_catalog
    app.state.workflow_engine = engine
    app.state.scheduler_service = scheduler_service

    logger.info(
        "启动完成: %d Skill, %d 工作流, %d 数据类, %d 定时任务",
        len(registry.list_all()),
        len(workflow_catalog.list_entries()),
        len(data_class_catalog.list_all()),
        len(scheduler_service.store.list_all()),
    )

    yield

    scheduler_service.shutdown()
    await backend_client.close()


app = FastAPI(
    title="FusionCareer AI Agent",
    description="节点式工作流微服务 — 可插拔 Skill 插件",
    version="0.2.0",
    lifespan=lifespan,
)

register_exception_handlers(app)
app.include_router(admin_router.router)


class RunWorkflowRequest(BaseModel):
    name: str = "inline"
    nodes: dict
    loop: dict | None = None


class RunPresetRequest(BaseModel):
    overrides: dict = Field(default_factory=dict)
    loop: dict | None = None


def _validate_overrides(workflow: dict, overrides: dict) -> list[str]:
    errors: list[str] = []
    nodes = workflow.get("nodes") or {}
    for key in overrides:
        parts = key.split(".", 1)
        if len(parts) != 2:
            errors.append(f"overrides 键 '{key}' 格式应为 node_id.slot")
            continue
        nid, slot = parts
        if nid not in nodes:
            errors.append(f"overrides 节点 '{nid}' 不存在")
            continue
        if slot not in (nodes[nid].get("inputs") or {}):
            errors.append(f"overrides 槽位 '{key}' 不存在")
            continue
        if not is_source_node(nodes, nid, registry):
            errors.append(f"overrides 仅允许源节点（input_*），'{key}' 为业务节点")
    return errors


def _validate_or_422(workflow: dict, *, overrides: dict | None = None) -> None:
    if overrides:
        override_errors = _validate_overrides(workflow, overrides)
        if override_errors:
            raise HTTPException(status_code=422, detail={"errors": override_errors})
    errors = engine.validate(workflow, allow_source_literals_only=True)
    if errors:
        raise HTTPException(status_code=422, detail={"errors": errors})


def _validate_loop_or_422(loop: LoopControl | None) -> None:
    errors = validate_loop(registry, loop)
    if errors:
        raise HTTPException(status_code=422, detail={"errors": errors})


async def _run_response(
    workflow_name: str,
    workflow: dict,
    *,
    overrides: dict | None = None,
    loop: LoopControl | None = None,
):
    _validate_or_422(workflow, overrides=overrides)
    _validate_loop_or_422(loop)
    try:
        if loop is not None:
            return await run_with_loop(registry, workflow_name, workflow, loop)
        outputs = await engine.run(workflow)
        return {
            "status": "completed",
            "workflow": workflow_name,
            "outputs": outputs,
        }
    except WorkflowNodeError as e:
        root = e.root_node
        return JSONResponse(
            status_code=200,
            content={
                "status": "failed",
                "workflow": workflow_name,
                "failed_node": root,
                "root_node": root,
                "skill": e.skill,
                "attempts_used": e.attempts_used,
                "retry_policy": e.retry_policy_applied,
                "input_snapshot": e.input_snapshot,
                "timeout_seconds": e.timeout_seconds,
                "error": {"type": type(e.cause).__name__, "message": str(e.cause)},
                "outputs": e.partial_outputs,
            },
        )


def _apply_overrides(workflow: dict, overrides: dict) -> dict:
    wf = copy.deepcopy(workflow)
    for key, val in overrides.items():
        parts = key.split(".", 1)
        if len(parts) != 2:
            continue
        nid, slot = parts
        node = wf.get("nodes", {}).get(nid)
        if node and slot in node.get("inputs", {}):
            node["inputs"][slot] = {"value": val}
    return wf


@app.get("/api/health")
async def health():
    return {"status": "ok", "backend": settings.backend_base_url}


@app.get("/api/skills")
async def list_skills():
    return {"skills": registry.list_all()}


@app.post("/api/run", dependencies=[Depends(require_agent_admin)])
async def run_workflow(req: RunWorkflowRequest):
    workflow = {"name": req.name, "nodes": req.nodes}
    loop = LoopControl(**req.loop) if req.loop else None
    return await _run_response(req.name, workflow, loop=loop)


@app.get("/api/workflows")
async def list_workflows():
    return {"workflows": workflow_catalog.list_entries()}


@app.post("/api/workflows/{name}/run", dependencies=[Depends(require_agent_admin)])
async def run_preset_workflow(name: str, req: RunPresetRequest = RunPresetRequest()):
    if not workflow_catalog.has(name):
        raise HTTPException(status_code=404, detail=f"工作流 '{name}' 不存在")

    base = workflow_catalog.get(name)
    workflow = _apply_overrides(base, req.overrides)
    loop = LoopControl(**req.loop) if req.loop else None
    return await _run_response(name, workflow, overrides=req.overrides, loop=loop)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app.main:app", host="0.0.0.0", port=settings.agent_port, reload=True)
