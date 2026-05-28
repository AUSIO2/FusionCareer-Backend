"""APScheduler — 触发工作流执行"""

from __future__ import annotations

import copy
import json
import logging
from typing import Any

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger
from apscheduler.triggers.interval import IntervalTrigger

from app.catalog.workflow_catalog import WorkflowCatalog
from app.engine.runner import WorkflowEngine, WorkflowNodeError
from app.runtime.paths import RuntimePaths
from app.scheduler.models import ScheduleBody, ScheduleTrigger
from app.scheduler.store import ScheduleStore

logger = logging.getLogger(__name__)


class SchedulerService:
    def __init__(
        self,
        engine: WorkflowEngine,
        workflow_catalog: WorkflowCatalog,
        paths: RuntimePaths,
        *,
        timezone: str = "Asia/Shanghai",
    ) -> None:
        self._engine = engine
        self._workflow_catalog = workflow_catalog
        self._store = ScheduleStore(paths)
        self._scheduler = AsyncIOScheduler(timezone=timezone)
        self._last_errors: dict[str, str] = {}

    @property
    def store(self) -> ScheduleStore:
        return self._store

    def start(self) -> None:
        self._store.load_all()
        for record in self._store.list_all():
            if record.enabled:
                self._register_job(record)
        if not self._scheduler.running:
            self._scheduler.start()
        logger.info("SchedulerService: 已启动，%d 个任务", len(self._store.list_all()))

    def shutdown(self) -> None:
        if self._scheduler.running:
            self._scheduler.shutdown(wait=False)

    def reload_all(self) -> None:
        for job in self._scheduler.get_jobs():
            self._scheduler.remove_job(job.id)
        self._store.load_all()
        for record in self._store.list_all():
            if record.enabled:
                self._register_job(record)

    def upsert(self, body: ScheduleBody) -> None:
        if not self._workflow_catalog.has(body.workflow):
            raise ValueError(f"工作流 '{body.workflow}' 不存在")
        self._store.put(body)
        job_id = self._job_id(body.id)
        if self._scheduler.get_job(job_id):
            self._scheduler.remove_job(job_id)
        if body.enabled:
            self._register_job(body)

    def delete(self, schedule_id: str) -> None:
        job_id = self._job_id(schedule_id)
        if self._scheduler.get_job(job_id):
            self._scheduler.remove_job(job_id)
        self._store.delete(schedule_id)
        self._last_errors.pop(schedule_id, None)

    def last_error(self, schedule_id: str) -> str | None:
        return self._last_errors.get(schedule_id)

    @staticmethod
    def _job_id(schedule_id: str) -> str:
        return f"schedule:{schedule_id}"

    def _register_job(self, record: ScheduleBody) -> None:
        trigger = self._build_trigger(record.trigger)
        self._scheduler.add_job(
            self._run_scheduled,
            trigger=trigger,
            id=self._job_id(record.id),
            kwargs={"schedule_id": record.id},
            replace_existing=True,
        )

    @staticmethod
    def _build_trigger(spec: ScheduleTrigger):
        if spec.type == "cron":
            return CronTrigger.from_crontab(spec.cron or "")
        kwargs: dict[str, Any] = {}
        if spec.minutes is not None:
            kwargs["minutes"] = spec.minutes
        if spec.seconds is not None:
            kwargs["seconds"] = spec.seconds
        return IntervalTrigger(**kwargs)

    async def _run_scheduled(self, schedule_id: str) -> None:
        try:
            record = self._store.get(schedule_id)
        except KeyError:
            logger.error("定时任务 %s 不存在", schedule_id)
            return

        try:
            workflow = copy.deepcopy(self._workflow_catalog.get(record.workflow))
            for key, val in record.overrides.items():
                parts = key.split(".", 1)
                if len(parts) != 2:
                    continue
                nid, slot = parts
                node = workflow.get("nodes", {}).get(nid)
                if node and slot in node.get("inputs", {}):
                    node["inputs"][slot] = {"value": val}

            errors = self._engine.validate(workflow, allow_source_literals_only=True)
            if errors:
                msg = "; ".join(errors[:5])
                self._last_errors[schedule_id] = msg
                logger.error("定时任务 %s 校验失败: %s", schedule_id, msg)
                return

            await self._engine.run(workflow)
            self._last_errors.pop(schedule_id, None)
            logger.info("定时任务 %s 执行完成 workflow=%s", schedule_id, record.workflow)
        except WorkflowNodeError as e:
            self._last_errors[schedule_id] = (
                f"{e.root_node}({e.skill}) "
                f"attempt={e.attempts_used} timeout={e.timeout_seconds}: {e.cause}"
            )
            logger.exception("定时任务 %s 执行失败", schedule_id)
        except Exception as e:
            self._last_errors[schedule_id] = f"{type(e).__name__}: {e}"
            logger.exception("定时任务 %s 执行失败", schedule_id)
