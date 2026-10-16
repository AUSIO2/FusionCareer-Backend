from __future__ import annotations

import sys
from importlib import import_module
from pathlib import Path
from typing import ClassVar

import pytest

from app.algorithms.resume_parser_261013_adapter import adapt_resume_parser_261013

VENDOR = Path(__file__).parents[2] / "app/vendor/fusioncareer_algorithm"
if str(VENDOR) not in sys.path:
    sys.path.insert(0, str(VENDOR))

pdf_extractor = import_module("resume_parser.extractors.pdf_extractor_261013")
io_log = import_module("resume_parser.llm.io_log_261013")
resume_parser_module = import_module("resume_parser.parser_261013")
detect_clear_two_column_261013 = pdf_extractor.detect_clear_two_column_261013
ModelIOLogger261013 = io_log.ModelIOLogger261013
ResumeParser261013 = resume_parser_module.ResumeParser261013


class FakeResumeModel:
    last_usage: ClassVar[dict[str, int]] = {
        "prompt_tokens": 11,
        "completion_tokens": 7,
        "total_tokens": 18,
        "api_calls": 1,
    }

    def parse_resume_to_dict(self, _text, **_kwargs):
        return {
            "real_name": "张同学",
            "personal_intro": "新闻传播专业，CET-6 600分，关注内容运营。",
            "basic_info": "姓名：张同学，GPA：3.8，学校：复旦大学",
            "internship": [
                {
                    "organization": "新东方教育科技有限公司",
                    "role": "AIGC视频运营实习生",
                    "time": "2025.09-至今",
                    "tasks": ["负责AI内容创作", "撰写创意脚本"],
                },
                "复旦大学学生会｜编辑｜2024.09-2025.06：运营公众号",
            ],
            "campus": ["复旦大学学生会｜编辑｜2024.09-2025.06：运营公众号"],
            "skills": ["CET-6 600分", "Python", "C", "C++", "C#"],
            "remark": ["Python", "英语六级，计算机二级", "可接受短期出差"],
        }


def test_261013_parser_formats_and_reclassifies_experiences_without_logging(tmp_path):
    parser = adapt_resume_parser_261013(ResumeParser261013(
        llm_client=FakeResumeModel(),
        io_logger=ModelIOLogger261013(enabled=False, log_dir=tmp_path / "logs"),
    ), resume_parser_module)

    result = parser.parse_text("synthetic resume")

    assert "新东方教育科技有限公司 | AIGC视频运营实习生 | 2025.09-至今" in result["internship"]
    assert "\n负责AI内容创作；撰写创意脚本" in result["internship"]
    assert "学生会" not in result["internship"]
    assert result["campus"].count("复旦大学学生会") == 1
    assert "CET-6 600分" not in result["personal_intro"]
    assert result["basic_info"] == "GPA：3.8"
    assert all(skill in result["skills"].split("；") for skill in ("C", "C++", "C#"))
    assert result["remark"] == "计算机二级；可接受短期出差"
    assert "moved_internship_item_to_campus" in parser.last_metrics["normalization"]["normalization_warnings"]
    assert parser.last_metrics["total_tokens"] == 18
    assert not list(tmp_path.rglob("*.jsonl"))


class WrappedResumeModel:
    last_usage: ClassVar[dict[str, int]] = {}

    def parse_resume_to_dict(self, _text, **_kwargs):
        return {"data": {
            "real_name": "李同学",
            "skills": ["C", "C++"],
            "internship": [{
                "organization": "示例媒体",
                "role": "编辑实习生",
                "tasks": ["采写新闻"],
            }],
            "campus": [
                "复旦大学记者团｜记者｜2024.01-2024.06：采写校园新闻",
                "复旦大学记者团｜编辑｜2024.01-2024.06：编辑校园新闻",
            ],
        }}


def test_261013_adapter_keeps_legacy_wrappers_missing_dates_and_distinct_experiences(tmp_path):
    parser = adapt_resume_parser_261013(ResumeParser261013(
        llm_client=WrappedResumeModel(),
        io_logger=ModelIOLogger261013(enabled=False, log_dir=tmp_path / "logs"),
    ), resume_parser_module)

    result = parser.parse_text("synthetic wrapped resume")

    assert result["real_name"] == "李同学"
    assert result["skills"] == "C；C++"
    assert result["internship"] == "示例媒体 | 编辑实习生\n采写新闻"
    assert "记者团 | 记者" in result["campus"]
    assert "记者团 | 编辑" in result["campus"]


def test_261013_blank_scanned_pdf_fails_before_model_call(tmp_path, monkeypatch):
    path = tmp_path / "scan.pdf"
    path.write_bytes(b"%PDF synthetic scan")
    monkeypatch.setattr(
        resume_parser_module,
        "_extract_plain_text_261013",
        lambda _path: ("", False, {"extractor": "pdfplumber_261013"}),
    )
    parser = adapt_resume_parser_261013(ResumeParser261013(
        llm_client=FakeResumeModel(),
        io_logger=ModelIOLogger261013(enabled=False, log_dir=tmp_path / "logs"),
    ), resume_parser_module)

    with pytest.raises(ValueError, match="未从简历文件中提取到文本"):
        parser.parse(str(path))


def _column_characters() -> list[dict]:
    characters = []
    for row in range(20):
        top = 90 + row * 30
        for column_start in (60, 360):
            for offset in range(5):
                x0 = column_start + offset * 20
                characters.append({
                    "text": "文",
                    "x0": x0,
                    "x1": x0 + 10,
                    "top": top,
                    "bottom": top + 10,
                    "size": 10,
                    "upright": True,
                })
    return characters


def test_261013_pdf_layout_requires_a_clear_central_gutter():
    decision = detect_clear_two_column_261013(
        _column_characters(), page_width=600, page_height=800,
    )
    assert decision["is_two_column"] is True
    assert decision["layout_mode"] == "two_column"

    single_column = [
        {**char, "x0": 240 + index % 12 * 10, "x1": 248 + index % 12 * 10}
        for index, char in enumerate(_column_characters())
    ]
    fallback = detect_clear_two_column_261013(
        single_column, page_width=600, page_height=800,
    )
    assert fallback["is_two_column"] is False
