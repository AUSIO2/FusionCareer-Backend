"""定时任务 JSON 模型"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field, model_validator


class ScheduleTrigger(BaseModel):
    type: Literal["cron", "interval"]
    cron: str | None = None
    minutes: float | None = None
    seconds: float | None = None

    @model_validator(mode="after")
    def check_trigger_fields(self) -> ScheduleTrigger:
        if self.type == "cron":
            if not self.cron:
                raise ValueError("cron 触发器需要 cron 字段")
        elif self.type == "interval":
            if self.minutes is None and self.seconds is None:
                raise ValueError("interval 触发器需要 minutes 或 seconds")
        return self


class ScheduleBody(BaseModel):
    id: str
    workflow: str
    enabled: bool = True
    trigger: ScheduleTrigger
    overrides: dict[str, Any] = Field(default_factory=dict)
    description: str = ""

    def to_disk(self) -> dict[str, Any]:
        return self.model_dump()

    @classmethod
    def from_disk(cls, data: dict[str, Any]) -> ScheduleBody:
        return cls.model_validate(data)
