"""Skill define() 与数据类 role 门禁（供后续 Skill 上传使用）"""

from __future__ import annotations

from app.catalog.catalog import DataClassCatalog
from app.catalog.errors import CatalogError
from app.catalog.models import DataClassRole


def validate_skill_type_names(
    catalog: DataClassCatalog,
    input_types: set[str],
    output_types: set[str],
) -> None:
    if "any" in input_types or "any" in output_types:
        raise CatalogError("类型名 any 已禁用", code="forbidden_type_name", status_code=422)

    for type_name in input_types:
        catalog.assert_usable_as_input(type_name)

    for type_name in output_types:
        record = catalog.get(type_name)
        if record.role not in (DataClassRole.O, DataClassRole.IO):
            raise CatalogError(
                f"类型 '{type_name}' 的 role 无效",
                code="invalid_role",
                status_code=422,
            )
