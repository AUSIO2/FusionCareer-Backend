"""管理员 API — 数据类 / 工作流 / Skill / 定时任务"""

import asyncio
import logging
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel

from app.api.deps.admin_auth import require_agent_admin
from app.catalog.catalog import DataClassCatalog
from app.catalog.errors import CatalogError
from app.catalog.models import DataClassUpsertBody
from app.catalog.ref_index import DataClassRefIndex
from app.catalog.workflow_catalog import WorkflowCatalog, WorkflowCatalogError
from app.core.registry import SkillRegistry
from app.core.skill_installer import SkillInstallError, delete_skill, install_skill
from app.engine import WorkflowEngine
from app.scheduler.models import ScheduleBody
from app.scheduler.service import SchedulerService
from app.config import settings
from app.skills.business.wechat.paths import WechatPaths
from app.skills.business.wechat.store import WechatStore
from app.skills.business.wechat.structure_articles import drainPendingArticles

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/api/admin",
    tags=["admin"],
    dependencies=[Depends(require_agent_admin)],
)


class SkillUploadBody(BaseModel):
    source: str
    introduces: dict[str, Any] | None = None


def _catalog(request: Request) -> DataClassCatalog:
    return request.app.state.data_class_catalog


def _ref_index(request: Request) -> DataClassRefIndex:
    return request.app.state.data_class_ref_index


def _workflow_catalog(request: Request) -> WorkflowCatalog:
    return request.app.state.workflow_catalog


def _registry(request: Request) -> SkillRegistry:
    return request.app.state.skill_registry


def _engine(request: Request) -> WorkflowEngine:
    return request.app.state.workflow_engine


def _scheduler(request: Request) -> SchedulerService:
    return request.app.state.scheduler_service


def _structure_paths() -> WechatPaths:
    if not settings.wechat_config_root:
        raise HTTPException(status_code=503, detail="WECHAT_CONFIG_ROOT 未配置")
    return WechatPaths(Path(settings.wechat_config_root))


def _structure_status(request: Request) -> dict[str, Any]:
    state = dict(request.app.state.structure_drain_state)
    state["pendingCount"] = WechatStore(_structure_paths().database_file).countPendingArticles()
    return state


async def _run_structure_drain(app) -> None:
    state = app.state.structure_drain_state
    try:
        state["result"] = await drainPendingArticles(
            _structure_paths(), app.state.backend_client
        )
        state["status"] = "COMPLETED"
        state["error"] = None
    except asyncio.CancelledError:
        state["status"] = "CANCELLED"
        raise
    except Exception as readError:  # noqa: BLE001 - expose background failure via status API
        state["status"] = "FAILED"
        state["error"] = f"{type(readError).__name__}: {str(readError)[:300]}"
        logger.exception("manual structure drain failed")
    finally:
        state["finishedAt"] = datetime.now(timezone.utc).isoformat()


def _start_structure_drain(request: Request) -> dict[str, Any]:
    readTask = request.app.state.structure_drain_task
    if readTask is None or readTask.done():
        request.app.state.structure_drain_state = {
            "status": "RUNNING",
            "startedAt": datetime.now(timezone.utc).isoformat(),
            "finishedAt": None,
            "result": None,
            "error": None,
        }
        request.app.state.structure_drain_task = asyncio.create_task(
            _run_structure_drain(request.app)
        )
    return _structure_status(request)


# ── 数据类 ──


@router.get("/data-classes")
async def list_data_classes(request: Request):
    catalog = _catalog(request)
    ref_index = _ref_index(request)
    return {"data_classes": catalog.list_summaries(ref_index)}


@router.get("/data-classes/{name}")
async def get_data_class(name: str, request: Request):
    catalog = _catalog(request)
    ref_index = _ref_index(request)
    try:
        record = catalog.get(name)
    except CatalogError as e:
        raise _http_from_catalog(e) from e
    return {
        "data_class": record.to_disk(),
        "locked": ref_index.is_locked(name),
        "referrers": ref_index.referrers(name),
    }


@router.put("/data-classes/{name}")
async def put_data_class(name: str, body: DataClassUpsertBody, request: Request):
    catalog = _catalog(request)
    ref_index = _ref_index(request)
    try:
        record, status = catalog.upsert(name, body, ref_index)
    except CatalogError as e:
        raise _http_from_catalog(e) from e
    return {
        "data_class": record.to_disk(),
        "status": status,
        "locked": ref_index.is_locked(name),
        "referrers": ref_index.referrers(name),
    }


@router.delete("/data-classes/{name}")
async def delete_data_class(name: str, request: Request):
    catalog = _catalog(request)
    ref_index = _ref_index(request)
    try:
        catalog.delete(name, ref_index)
    except CatalogError as e:
        raise _http_from_catalog(e) from e
    return {"deleted": name}


# ── 工作流 ──


@router.get("/workflows")
async def list_workflows_admin(request: Request):
    return {"workflows": _workflow_catalog(request).list_entries()}


@router.get("/workflows/{name}")
async def get_workflow(name: str, request: Request):
    catalog = _workflow_catalog(request)
    try:
        wf = catalog.get(name)
        return {"workflow": wf, "source": catalog.source(name)}
    except WorkflowCatalogError as e:
        raise _http_from_workflow(e) from e


@router.put("/workflows/{name}")
async def put_workflow(name: str, body: dict, request: Request):
    catalog = _workflow_catalog(request)
    engine = _engine(request)
    try:
        status = catalog.put(name, body, engine.validator)
    except WorkflowCatalogError as e:
        raise _http_from_workflow(e) from e
    return {"name": name, "status": status, "source": catalog.source(name)}


@router.delete("/workflows/{name}")
async def delete_workflow(name: str, request: Request):
    catalog = _workflow_catalog(request)
    try:
        catalog.delete(name)
    except WorkflowCatalogError as e:
        raise _http_from_workflow(e) from e
    return {"deleted": name}


# ── Skill 插件 ──


@router.get("/skills")
async def list_skills_admin(request: Request):
    return {"skills": _registry(request).list_entries()}


@router.put("/skills/{name}")
async def put_skill(name: str, body: SkillUploadBody, request: Request):
    try:
        skill = install_skill(
            name,
            body.source,
            paths=request.app.state.runtime_paths,
            catalog=_catalog(request),
            ref_index=_ref_index(request),
            registry=_registry(request),
            introduces=body.introduces,
        )
    except SkillInstallError as e:
        raise _http_from_skill(e) from e
    return {"skill": skill.define(), "source": "runtime"}


@router.delete("/skills/{name}")
async def delete_skill_route(name: str, request: Request):
    try:
        delete_skill(
            name,
            paths=request.app.state.runtime_paths,
            registry=_registry(request),
            ref_index=_ref_index(request),
        )
    except SkillInstallError as e:
        raise _http_from_skill(e) from e
    return {"deleted": name}


# ── 定时任务 ──


@router.get("/wechat/structure-pending")
async def get_structure_pending(request: Request):
    return _structure_status(request)


@router.post("/wechat/structure-pending", status_code=202)
async def start_structure_pending(request: Request):
    return _start_structure_drain(request)


@router.get("/schedules")
async def list_schedules(request: Request):
    store = _scheduler(request).store
    items = []
    for rec in store.list_all():
        items.append(
            {
                **rec.to_disk(),
                "last_error": _scheduler(request).last_error(rec.id),
            }
        )
    return {"schedules": items}


@router.put("/schedules/{schedule_id}")
async def put_schedule(schedule_id: str, body: ScheduleBody, request: Request):
    if body.id != schedule_id:
        raise HTTPException(
            status_code=422,
            detail={"code": "id_mismatch", "message": "路径 id 与 body.id 不一致"},
        )
    try:
        _scheduler(request).upsert(body)
    except ValueError as e:
        raise HTTPException(status_code=422, detail={"code": "invalid_schedule", "message": str(e)}) from e
    return {"schedule": body.to_disk()}


@router.delete("/schedules/{schedule_id}")
async def delete_schedule(schedule_id: str, request: Request):
    try:
        _scheduler(request).delete(schedule_id)
    except KeyError as e:
        raise HTTPException(
            status_code=404,
            detail={"code": "schedule_not_found", "message": f"定时任务 '{schedule_id}' 不存在"},
        ) from e
    return {"deleted": schedule_id}


# ── 全量 reload ──


@router.post("/reload")
async def reload_all(request: Request):
    paths = request.app.state.runtime_paths
    registry = _registry(request)
    ref_index = _ref_index(request)
    workflow_catalog = _workflow_catalog(request)
    scheduler = _scheduler(request)

    registry.reload_plugins(paths)
    ref_index.rebuild_from_registry(registry)
    ref_index.save_to_disk(paths)
    workflow_catalog.load_all()
    scheduler.reload_all()

    return {
        "reloaded": {
            "skills": len(registry.list_all()),
            "workflows": len(workflow_catalog.list_entries()),
            "schedules": len(scheduler.store.list_all()),
            "data_classes": len(_catalog(request).list_all()),
        }
    }


def _http_from_catalog(exc: CatalogError):
    detail = {"code": exc.code, "message": exc.message}
    if exc.referrers:
        detail["referrers"] = exc.referrers
    return HTTPException(status_code=exc.status_code, detail=detail)


def _http_from_workflow(exc: WorkflowCatalogError):
    detail: dict[str, Any] = {"code": exc.code, "message": exc.message}
    if exc.errors:
        detail["errors"] = exc.errors
    return HTTPException(status_code=exc.status_code, detail=detail)


def _http_from_skill(exc: SkillInstallError):
    return HTTPException(
        status_code=exc.status_code,
        detail={"code": exc.code, "message": exc.message},
    )
