"""全局数据类目录 — 磁盘 + 内存"""

from __future__ import annotations

import json
import logging
import shutil
from pathlib import Path

from jsonschema.exceptions import SchemaError

from app.runtime.paths import RuntimePaths, atomic_write_json
from app.catalog.errors import CatalogError
from app.catalog.models import DataClassRecord, DataClassRole, DataClassUpsertBody
from app.catalog.ref_index import DataClassRefIndex
from app.catalog.subschema import check_schema_valid, schema_fingerprint

logger = logging.getLogger(__name__)

FORBIDDEN_NAME = "any"
SEED_PACKAGE = Path(__file__).parent / "seed"


class DataClassCatalog:
    def __init__(self, paths: RuntimePaths) -> None:
        self._paths = paths
        self._records: dict[str, DataClassRecord] = {}

    @property
    def paths(self) -> RuntimePaths:
        return self._paths

    def load_from_disk(self) -> None:
        self._records.clear()
        if not self._paths.data_classes.is_dir():
            return
        for path in sorted(self._paths.data_classes.glob("*.json")):
            if path.name.startswith("_"):
                continue
            data = json.loads(path.read_text(encoding="utf-8"))
            record = DataClassRecord.from_disk(data)
            self._records[record.name] = record
        logger.info("DataClassCatalog: 已加载 %d 个数据类", len(self._records))

    def seed_if_empty(self) -> bool:
        """目录为空时从镜像 seed 复制。返回是否执行了 seed。"""
        self._paths.ensure_dirs()
        existing = list(self._paths.data_classes.glob("*.json"))
        if existing:
            return False
        if not SEED_PACKAGE.is_dir():
            logger.warning("未找到 seed 目录: %s", SEED_PACKAGE)
            return False
        for src in SEED_PACKAGE.glob("*.json"):
            shutil.copy2(src, self._paths.data_classes / src.name)
        logger.info("DataClassCatalog: 已从 seed 初始化 %s", self._paths.data_classes)
        self.load_from_disk()
        return True

    def has(self, name: str) -> bool:
        return name in self._records

    def get(self, name: str) -> DataClassRecord:
        if name not in self._records:
            raise CatalogError(
                f"数据类 '{name}' 不存在",
                code="data_class_not_found",
                status_code=404,
            )
        return self._records[name]

    def list_all(self) -> list[DataClassRecord]:
        return [self._records[k] for k in sorted(self._records)]

    def list_summaries(self, ref_index: DataClassRefIndex) -> list[dict]:
        items = []
        for record in self.list_all():
            refs = ref_index.referrers(record.name)
            items.append(
                {
                    "name": record.name,
                    "role": record.role.value,
                    "locked": bool(refs),
                    "referrers": refs,
                }
            )
        return items

    def upsert(
        self,
        name: str,
        body: DataClassUpsertBody,
        ref_index: DataClassRefIndex,
    ) -> tuple[DataClassRecord, str]:
        """
        登记或更新数据类。返回 (record, status)。
        status: created | updated | idempotent
        """
        if name == FORBIDDEN_NAME:
            raise CatalogError("类型名 any 已禁用", code="forbidden_type_name", status_code=422)

        self._validate_schema(body)

        new_record = DataClassRecord(name=name, role=body.role, schema=body.type_schema)
        new_fp = schema_fingerprint(new_record.to_disk())

        existing_path = self._paths.data_class_file(name)
        if name in self._records:
            old = self._records[name]
            old_fp = schema_fingerprint(old.to_disk())
            if new_fp == old_fp:
                return old, "idempotent"

            if ref_index.is_locked(name):
                refs = ref_index.referrers(name)
                raise CatalogError(
                    f"数据类 '{name}' 已被 Skill 引用，禁止修改: {', '.join(refs)}",
                    code="data_class_locked",
                    status_code=409,
                    referrers=refs,
                )
            status = "updated"
        else:
            status = "created"

        atomic_write_json(existing_path, new_record.to_disk())
        self._records[name] = new_record
        return new_record, status

    def delete(self, name: str, ref_index: DataClassRefIndex) -> None:
        if name not in self._records:
            raise CatalogError(
                f"数据类 '{name}' 不存在",
                code="data_class_not_found",
                status_code=404,
            )
        if ref_index.is_locked(name):
            refs = ref_index.referrers(name)
            raise CatalogError(
                f"数据类 '{name}' 已被 Skill 引用，禁止删除: {', '.join(refs)}",
                code="data_class_locked",
                status_code=409,
                referrers=refs,
            )
        path = self._paths.data_class_file(name)
        if path.is_file():
            path.unlink()
        del self._records[name]

    def assert_usable_as_input(self, type_name: str) -> None:
        record = self.get(type_name)
        if record.role != DataClassRole.IO:
            raise CatalogError(
                f"类型 '{type_name}' 为 {record.role.value}，不能用于 Skill input（仅 IO 可以）",
                code="role_not_input",
                status_code=422,
            )

    def assert_usable_as_output(self, type_name: str) -> None:
        self.get(type_name)

    @staticmethod
    def _validate_schema(body: DataClassUpsertBody) -> None:
        try:
            check_schema_valid(body.type_schema)
        except SchemaError as e:
            raise CatalogError(
                f"JSON Schema 不合法: {e.message}",
                code="invalid_schema",
                status_code=422,
            ) from e
