"""Production safety adaptations for the byte-pinned upstream 261013 parser."""

from __future__ import annotations

import copy
import json
import re
import unicodedata
from collections.abc import Callable
from typing import Any

RESUME_FIELDS = frozenset({
    "real_name", "gender", "birth_date", "political_status", "phone", "email",
    "wechat", "hometown", "grade", "major", "edu_level", "supervisor",
    "intention_order", "intention_city", "intention_dream", "mindset",
    "personal_intro", "basic_info", "education", "internship", "campus", "awards",
    "skills", "portfolio", "remark",
})
NESTED_FIELDS = frozenset({"fc_user_profile", "fc_resume"})


def semantic_canonical(read_value: Any) -> str:
    """Keep language symbols that carry meaning while normalizing display noise."""
    text = unicodedata.normalize("NFKC", str(read_value or "")).casefold()
    text = text.replace("ielts", "雅思").replace("toefl", "托福")
    text = re.sub(r"(?:cet|大学英语)\s*[-_]?\s*6|大学英语六级", "英语六级", text)
    text = re.sub(r"(?:cet|大学英语)\s*[-_]?\s*4|大学英语四级", "英语四级", text)
    return re.sub(r"[^0-9a-z\u4e00-\u9fff+#./-]+", "", text)


def conservative_same_experience(read_left: str, read_right: str) -> bool:
    """Prefer retaining a possible duplicate over deleting a distinct experience."""
    left_key = semantic_canonical(read_left)
    right_key = semantic_canonical(read_right)
    if not left_key or not right_key:
        return False
    if left_key == right_key:
        return True
    left_dates = set(re.findall(r"(?:19|20)\d{2}(?:[./年-]\d{1,2})?", read_left))
    right_dates = set(re.findall(r"(?:19|20)\d{2}(?:[./年-]\d{1,2})?", read_right))
    if left_dates and right_dates and left_dates.isdisjoint(right_dates):
        return False
    return min(len(left_key), len(right_key)) >= 16 and (
        left_key in right_key or right_key in left_key
    )


def _flatten_legacy_wrapper(read_raw: dict[str, Any]) -> dict[str, Any]:
    if set(read_raw) & (RESUME_FIELDS | NESTED_FIELDS):
        return read_raw
    flattened: dict[str, Any] = {}
    for read_value in read_raw.values():
        if not isinstance(read_value, dict):
            continue
        for read_key, nested_value in read_value.items():
            if read_key in RESUME_FIELDS | NESTED_FIELDS and read_key not in flattened:
                flattened[read_key] = nested_value
    return flattened or read_raw


def _text(read_value: Any) -> str:
    if isinstance(read_value, list):
        return "；".join(str(item).strip() for item in read_value if str(item).strip())
    return str(read_value or "").strip()


def _experience_item(read_value: Any) -> Any:
    if not isinstance(read_value, dict) or isinstance(read_value.get("text"), str):
        return read_value
    organization = _text(read_value.get("organization"))
    role = _text(read_value.get("role"))
    time_value = _text(read_value.get("time"))
    detail = "；".join(filter(None, (_text(read_value.get("tasks")), _text(read_value.get("results")))))
    header = "｜".join(filter(None, (organization, role, time_value)))
    if header and detail:
        return f"{header}：{detail}"
    return header or detail or read_value


def _experience_values(read_value: Any) -> Any:
    if isinstance(read_value, list):
        return [_experience_item(item) for item in read_value]
    return _experience_item(read_value)


def _remark_values(read_value: Any) -> Any:
    if read_value in (None, ""):
        return read_value
    if isinstance(read_value, str) and read_value.lstrip().startswith("["):
        try:
            decoded = json.loads(read_value)
        except json.JSONDecodeError:
            decoded = read_value
        if isinstance(decoded, list):
            read_value = decoded
    values = read_value if isinstance(read_value, list) else [read_value]
    return [
        part.strip()
        for value in values
        for part in re.split(r"(?:\n+|[；;,，]+)", str(value))
        if part.strip()
    ]


def prepare_model_output(read_raw: dict[str, Any]) -> dict[str, Any]:
    update_raw = _flatten_legacy_wrapper(copy.deepcopy(read_raw))
    containers = [update_raw]
    nested_resume = update_raw.get("fc_resume")
    if isinstance(nested_resume, dict):
        containers.append(nested_resume)
    for container in containers:
        for field in ("internship", "campus"):
            if field in container:
                container[field] = _experience_values(container[field])
        if "remark" in container:
            container["remark"] = _remark_values(container["remark"])
    return update_raw


def adapt_resume_parser_261013(update_parser: Any, update_module: Any) -> Any:
    """Install isolated-process guards without modifying vendored source bytes."""
    update_module._canonical = semantic_canonical
    update_module._same_experience = conservative_same_experience
    parse_model: Callable[..., dict[str, Any]] = update_parser._llm.parse_resume_to_dict

    def compatible_parse(*args: Any, **kwargs: Any) -> dict[str, Any]:
        return prepare_model_output(parse_model(*args, **kwargs))

    update_parser._llm.parse_resume_to_dict = compatible_parse
    return update_parser
