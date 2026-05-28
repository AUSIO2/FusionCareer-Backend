"""数据类记录模型 — 一名一型，role 决定能否作 input/output 槽"""

from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator


class DataClassRole(str, Enum):
    IO = "IO"  # 可作 Skill input / output
    O = "O"    # 仅可作 Skill output


class DataClassRecord(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    name: str
    role: DataClassRole
    type_schema: dict[str, Any] = Field(alias="schema")

    @field_validator("name")
    @classmethod
    def name_not_any(cls, v: str) -> str:
        if v == "any":
            raise ValueError("类型名 any 已禁用")
        return v

    def to_disk(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "role": self.role.value,
            "schema": self.type_schema,
        }

    @classmethod
    def from_disk(cls, data: dict[str, Any]) -> DataClassRecord:
        if "schema" not in data:
            raise ValueError(
                f"数据类 '{data.get('name', '?')}' 必须包含 schema 字段（不再支持 input/output）"
            )
        return cls(
            name=data["name"],
            role=DataClassRole(data["role"]),
            schema=data["schema"],
        )


class DataClassUpsertBody(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    role: DataClassRole
    type_schema: dict[str, Any] = Field(alias="schema")
