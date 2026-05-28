"""JSON Schema 校验与指纹"""

from __future__ import annotations

import json
from typing import Any

from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError


def check_schema_valid(schema: dict[str, Any]) -> None:
    Draft202012Validator.check_schema(schema)


def schema_fingerprint(record: dict[str, Any]) -> str:
    """规范化 role + schema 用于相等比较。"""
    payload = {
        "role": record.get("role"),
        "schema": record.get("schema"),
    }
    return json.dumps(payload, sort_keys=True, ensure_ascii=False)
