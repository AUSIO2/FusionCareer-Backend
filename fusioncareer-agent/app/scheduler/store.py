"""定时任务磁盘存储"""

from __future__ import annotations

import json
import logging

from app.runtime.paths import RuntimePaths, atomic_write_json
from app.scheduler.models import ScheduleBody

logger = logging.getLogger(__name__)


class ScheduleStore:
    def __init__(self, paths: RuntimePaths) -> None:
        self._paths = paths
        self._schedules: dict[str, ScheduleBody] = {}

    def load_all(self) -> None:
        self._schedules.clear()
        self._paths.schedules.mkdir(parents=True, exist_ok=True)
        for path in sorted(self._paths.schedules.glob("*.json")):
            if path.name.startswith("_"):
                continue
            data = json.loads(path.read_text(encoding="utf-8"))
            record = ScheduleBody.from_disk(data)
            self._schedules[record.id] = record
        logger.info("ScheduleStore: 已加载 %d 个定时任务", len(self._schedules))

    def get(self, schedule_id: str) -> ScheduleBody:
        if schedule_id not in self._schedules:
            raise KeyError(schedule_id)
        return self._schedules[schedule_id]

    def list_all(self) -> list[ScheduleBody]:
        return [self._schedules[k] for k in sorted(self._schedules)]

    def put(self, body: ScheduleBody) -> None:
        path = self._paths.schedules / f"{body.id}.json"
        atomic_write_json(path, body.to_disk())
        self._schedules[body.id] = body

    def delete(self, schedule_id: str) -> None:
        if schedule_id not in self._schedules:
            raise KeyError(schedule_id)
        path = self._paths.schedules / f"{schedule_id}.json"
        if path.is_file():
            path.unlink()
        del self._schedules[schedule_id]
