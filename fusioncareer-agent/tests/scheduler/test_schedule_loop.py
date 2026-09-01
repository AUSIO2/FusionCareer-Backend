"""ScheduleBody 支持 loop 字段序列化。"""

from __future__ import annotations

from app.scheduler.models import ScheduleBody, ScheduleTrigger


def test_schedule_body_with_loop():
    body = ScheduleBody(
        id="wechat-daily",
        workflow="wechat_daily_body",
        trigger=ScheduleTrigger(type="cron", cron="0 17 * * *"),
        loop={
            "judge_skill": "wechat_judge_accounts",
            "max_iterations": 100,
            "finalize_skill": "wechat_finalize_daily",
            "finalize_inputs": {"paths": {"config_root": "/data/wechat"}},
        },
    )
    disk = body.to_disk()
    restored = ScheduleBody.from_disk(disk)
    assert restored.loop is not None
    assert restored.loop["judge_skill"] == "wechat_judge_accounts"
