"""wechat io 与 judge skill 单测。"""

from __future__ import annotations

import json
from pathlib import Path

import asyncio

import pytest

from app.runtime.paths import atomic_write_json
from app.skills.business.wechat.io import append_jsonl, load_json_file
from app.skills.business.wechat.judge_accounts import WechatJudgeAccountsSkill


def test_judge_accounts_continue(tmp_path: Path):
    state_path = tmp_path / "state.json"
    atomic_write_json(state_path, {"total": 3})
    skill = WechatJudgeAccountsSkill()

    async def _run():
        out = await skill.execute({"state_path": str(state_path), "iteration": 1})
        assert out["continue"] is True
        out2 = await skill.execute({"state_path": str(state_path), "iteration": 3})
        assert out2["continue"] is False

    asyncio.run(_run())


def testAppendJsonl(tmp_path: Path):
    log = tmp_path / "a.jsonl"
    append_jsonl(log, {"x": 1})
    lines = log.read_text(encoding="utf-8").strip().splitlines()
    assert json.loads(lines[0])["x"] == 1
    assert load_json_file(tmp_path / "missing.json", default={}) == {}
