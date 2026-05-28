"""WorkflowEngine 执行语义"""

import asyncio
from pathlib import Path

import pytest

from app.core.base_skill import BaseSkill
from app.engine import WorkflowEngine, WorkflowNodeError
from app.catalog.catalog import DataClassCatalog
from app.core.registry import SkillRegistry
from app.runtime.paths import RuntimePaths


@pytest.fixture
def engine_ctx(tmp_path: Path):
    paths = RuntimePaths(tmp_path / "runtime")
    paths.ensure_dirs()
    catalog = DataClassCatalog(paths)
    catalog.seed_if_empty()
    if not catalog.list_all():
        catalog.load_from_disk()
    registry = SkillRegistry()
    registry.auto_discover("app.skills.platform")
    registry.auto_discover("app.skills.business")
    return WorkflowEngine(registry, catalog), registry


def test_run_success(engine_ctx):
    engine, registry = engine_ctx
    skill = registry.get("insert_resume")

    async def ok(_inputs):
        return {"result": {"success": True, "message": "ok"}}

    skill.execute = ok  # type: ignore[method-assign]

    wf = {
        "name": "t",
        "nodes": {
            "n1": {
                "skill": "insert_resume",
                "inputs": {
                    "user_id": {"value": 1},
                    "resume_data": {"value": {"personalIntro": "hi"}},
                },
            }
        },
    }
    assert engine.validate(wf, allow_literals=True) == []
    out = asyncio.run(engine.run(wf))
    assert "n1.result" in out


def test_run_fail_fast(engine_ctx):
    engine, registry = engine_ctx
    skill = registry.get("insert_resume")

    async def boom(_inputs):
        raise RuntimeError("backend down")

    skill.execute = boom  # type: ignore[method-assign]

    wf = {
        "name": "t",
        "nodes": {
            "n1": {
                "skill": "insert_resume",
                "inputs": {
                    "user_id": {"value": 1},
                    "resume_data": {"value": {"personalIntro": "x"}},
                },
            }
        },
    }
    with pytest.raises(WorkflowNodeError) as exc:
        asyncio.run(engine.run(wf))
    assert exc.value.root_node == "n1"
    assert exc.value.failure_kind == "execute"


def test_failed_node_chain_upstream_not_child(engine_ctx):
    """上游 n_bad 失败时，不应把 failed_node 记为下游 n_child。"""
    engine, registry = engine_ctx

    async def boom(_inputs):
        raise RuntimeError("upstream failed")

    registry.get("string_concat").execute = boom  # type: ignore[method-assign]

    wf = {
        "name": "chain",
        "nodes": {
            "source": {
                "skill": "input_text",
                "inputs": {"text": {"value": "hello"}},
            },
            "in_bad_right": {
                "skill": "input_text",
                "inputs": {"text": {"value": "bad"}},
            },
            "n_bad": {
                "skill": "string_concat",
                "inputs": {
                    "left": {"from": "source.text"},
                    "right": {"from": "in_bad_right.text"},
                },
            },
            "in_child_right": {
                "skill": "input_text",
                "inputs": {"text": {"value": "child"}},
            },
            "n_child": {
                "skill": "string_concat",
                "inputs": {
                    "left": {"from": "n_bad.text"},
                    "right": {"from": "in_child_right.text"},
                },
            },
        },
    }
    assert engine.validate(wf, allow_source_literals_only=True) == []
    with pytest.raises(WorkflowNodeError) as exc:
        asyncio.run(engine.run(wf))
    assert exc.value.root_node == "n_bad"
    assert exc.value.root_node != "n_child"


def test_failed_node_parallel_fanout(engine_ctx):
    """并行扇出：仅 n1 失败时 failed_node 为 n1，而非 n2。"""
    engine, registry = engine_ctx

    async def boom(_inputs):
        raise RuntimeError("n1 failed")

    async def ok_profile(_inputs):
        return {"result": {"success": True, "message": "ok"}}

    registry.get("insert_resume").execute = boom  # type: ignore[method-assign]
    registry.get("insert_user_profile").execute = ok_profile  # type: ignore[method-assign]

    wf = {
        "name": "fanout",
        "nodes": {
            "input_uid": {
                "skill": "input_int",
                "inputs": {"int": {"value": 1}},
            },
            "n1": {
                "skill": "insert_resume",
                "inputs": {
                    "user_id": {"from": "input_uid.int"},
                    "resume_data": {"value": {"personalIntro": "x"}},
                },
            },
            "n2": {
                "skill": "insert_user_profile",
                "inputs": {
                    "user_id": {"from": "input_uid.int"},
                    "profile_data": {
                        "value": {
                            "realName": "",
                            "gender": 1,
                            "major": "",
                            "eduLevel": 2,
                            "grade": "",
                            "mindset": 2,
                        }
                    },
                },
            },
        },
    }
    assert engine.validate(wf, allow_literals=True) == []
    with pytest.raises(WorkflowNodeError) as exc:
        asyncio.run(engine.run(wf))
    assert exc.value.root_node == "n1"


def test_failed_node_dual_parallel_deterministic(engine_ctx):
    """两路无依赖节点同时失败：根因节点按 id 字典序取最小。"""
    engine, registry = engine_ctx

    async def boom(_inputs):
        raise RuntimeError("parallel failed")

    registry.get("insert_resume").execute = boom  # type: ignore[method-assign]

    wf = {
        "name": "dual",
        "nodes": {
            "n_b": {
                "skill": "insert_resume",
                "inputs": {
                    "user_id": {"value": 1},
                    "resume_data": {"value": {"personalIntro": "b"}},
                },
            },
            "n_a": {
                "skill": "insert_resume",
                "inputs": {
                    "user_id": {"value": 2},
                    "resume_data": {"value": {"personalIntro": "a"}},
                },
            },
        },
    }
    assert engine.validate(wf, allow_literals=True) == []
    with pytest.raises(WorkflowNodeError) as exc:
        asyncio.run(engine.run(wf))
    assert exc.value.root_node == "n_a"


def test_real_skill_string_concat_without_mock(engine_ctx):
    """使用真实 string_concat Skill（不 mock execute）验证主流程可用。"""
    engine, _registry = engine_ctx

    wf = {
        "name": "real_concat",
        "nodes": {
            "left": {
                "skill": "input_text",
                "inputs": {"text": {"value": "hello"}},
            },
            "right": {
                "skill": "input_text",
                "inputs": {"text": {"value": "world"}},
            },
            "concat": {
                "skill": "string_concat",
                "inputs": {
                    "left": {"from": "left.text"},
                    "right": {"from": "right.text"},
                },
            },
        },
    }
    assert engine.validate(wf, allow_source_literals_only=True) == []
    outputs = asyncio.run(engine.run(wf))
    assert outputs["concat.text"] == "helloworld"


class RetryableBoom(Exception):
    pass


class NonRetryableBoom(Exception):
    pass


class FlakyEchoSkill(BaseSkill):
    """真实测试 Skill：前两次抛可重试异常，第三次成功。"""

    def __init__(self) -> None:
        self.calls = 0

    def define(self) -> dict:
        return {
            "name": "flaky_echo",
            "description": "flaky for retry integration test",
            "inputs": {"text": "text"},
            "outputs": {"text": "text"},
            "retry_policy": {
                "enabled": True,
                "max_retries": 3,
                "retry_on": ["RetryableBoom"],
                "backoff_seconds": 0,
            },
        }

    async def execute(self, inputs: dict[str, str]) -> dict[str, str]:
        self.calls += 1
        if self.calls < 3:
            raise RetryableBoom("flaky network")
        return {"text": inputs["text"]}


def test_retryable_exception_eventually_success(engine_ctx):
    engine, registry = engine_ctx
    skill = registry.get("string_concat")
    attempts = {"count": 0}

    orig_define = skill.define

    def define_with_retry():
        data = orig_define()
        data["retry_policy"] = {
            "enabled": True,
            "max_retries": 3,
            "retry_on": ["RetryableBoom"],
            "backoff_seconds": 0,
        }
        return data

    async def flaky(_inputs):
        attempts["count"] += 1
        if attempts["count"] < 3:
            raise RetryableBoom("transient")
        return {"text": "ok"}

    skill.define = define_with_retry  # type: ignore[method-assign]
    skill.execute = flaky  # type: ignore[method-assign]

    wf = {
        "name": "retry_success",
        "nodes": {
            "src_left": {"skill": "input_text", "inputs": {"text": {"value": "a"}}},
            "src_right": {"skill": "input_text", "inputs": {"text": {"value": "b"}}},
            "n1": {
                "skill": "string_concat",
                "inputs": {"left": {"from": "src_left.text"}, "right": {"from": "src_right.text"}},
            },
        },
    }
    assert engine.validate(wf, allow_source_literals_only=True) == []
    out = asyncio.run(engine.run(wf))
    assert out["n1.text"] == "ok"
    assert attempts["count"] == 3


def test_real_skill_with_built_in_retry_policy(engine_ctx):
    """注册真实 Skill（非 monkeypatch）验证重试生效。"""
    engine, registry = engine_ctx
    flaky = FlakyEchoSkill()
    registry.register(flaky)

    wf = {
        "name": "real_retry_skill",
        "nodes": {
            "src": {"skill": "input_text", "inputs": {"text": {"value": "resume"}}},
            "n1": {"skill": "flaky_echo", "inputs": {"text": {"from": "src.text"}}},
        },
    }
    assert engine.validate(wf, allow_source_literals_only=True) == []
    out = asyncio.run(engine.run(wf))
    assert out["n1.text"] == "resume"
    assert flaky.calls == 3


def test_non_retryable_exception_fail_fast(engine_ctx):
    engine, registry = engine_ctx
    skill = registry.get("string_concat")
    attempts = {"count": 0}

    orig_define = skill.define

    def define_with_retry():
        data = orig_define()
        data["retry_policy"] = {
            "enabled": True,
            "max_retries": 3,
            "retry_on": ["RetryableBoom"],
            "backoff_seconds": 0,
        }
        return data

    async def boom(_inputs):
        attempts["count"] += 1
        raise NonRetryableBoom("fatal")

    skill.define = define_with_retry  # type: ignore[method-assign]
    skill.execute = boom  # type: ignore[method-assign]

    wf = {
        "name": "retry_non_retryable",
        "nodes": {
            "src_left": {"skill": "input_text", "inputs": {"text": {"value": "a"}}},
            "src_right": {"skill": "input_text", "inputs": {"text": {"value": "b"}}},
            "n1": {
                "skill": "string_concat",
                "inputs": {"left": {"from": "src_left.text"}, "right": {"from": "src_right.text"}},
            },
        },
    }
    assert engine.validate(wf, allow_source_literals_only=True) == []
    with pytest.raises(WorkflowNodeError) as exc:
        asyncio.run(engine.run(wf))
    assert exc.value.root_node == "n1"
    assert exc.value.attempts_used == 1
    assert attempts["count"] == 1


def test_retry_exhausted_includes_attempts_and_snapshot(engine_ctx):
    engine, registry = engine_ctx
    skill = registry.get("string_concat")
    attempts = {"count": 0}

    orig_define = skill.define

    def define_with_retry():
        data = orig_define()
        data["retry_policy"] = {
            "enabled": True,
            "max_retries": 2,
            "retry_on": ["RetryableBoom"],
            "backoff_seconds": 0,
        }
        return data

    async def always_fail(_inputs):
        attempts["count"] += 1
        raise RetryableBoom("still failing")

    skill.define = define_with_retry  # type: ignore[method-assign]
    skill.execute = always_fail  # type: ignore[method-assign]

    wf = {
        "name": "retry_exhausted",
        "nodes": {
            "src_left": {"skill": "input_text", "inputs": {"text": {"value": "left"}}},
            "src_right": {"skill": "input_text", "inputs": {"text": {"value": "right"}}},
            "n1": {
                "skill": "string_concat",
                "inputs": {"left": {"from": "src_left.text"}, "right": {"from": "src_right.text"}},
            },
        },
    }
    assert engine.validate(wf, allow_source_literals_only=True) == []
    with pytest.raises(WorkflowNodeError) as exc:
        asyncio.run(engine.run(wf))
    assert exc.value.root_node == "n1"
    assert exc.value.attempts_used == 3
    assert exc.value.retry_policy_applied["max_retries"] == 2
    assert "left" in exc.value.input_snapshot
    assert attempts["count"] == 3


def test_node_timeout_fail_fast(engine_ctx):
    engine, registry = engine_ctx
    skill = registry.get("string_concat")

    async def slow(_inputs):
        await asyncio.sleep(0.03)
        return {"text": "late"}

    skill.execute = slow  # type: ignore[method-assign]

    wf = {
        "name": "timeout_once",
        "nodes": {
            "src_left": {"skill": "input_text", "inputs": {"text": {"value": "a"}}},
            "src_right": {"skill": "input_text", "inputs": {"text": {"value": "b"}}},
            "n1": {
                "skill": "string_concat",
                "timeout_seconds": 0.005,
                "inputs": {"left": {"from": "src_left.text"}, "right": {"from": "src_right.text"}},
            },
        },
    }
    assert engine.validate(wf, allow_source_literals_only=True) == []
    with pytest.raises(WorkflowNodeError) as exc:
        asyncio.run(engine.run(wf))
    assert exc.value.root_node == "n1"
    assert exc.value.timeout_seconds == 0.005
    assert "超时" in str(exc.value.cause)


def test_node_timeout_retry_then_success(engine_ctx):
    engine, registry = engine_ctx
    skill = registry.get("string_concat")
    attempts = {"count": 0}
    orig_define = skill.define

    def define_with_timeout_retry():
        data = orig_define()
        data["retry_policy"] = {
            "enabled": True,
            "max_retries": 2,
            "retry_on": ["TimeoutError"],
            "backoff_seconds": 0,
        }
        return data

    async def flaky_timeout(_inputs):
        attempts["count"] += 1
        if attempts["count"] < 3:
            await asyncio.sleep(0.02)
            return {"text": "late"}
        return {"text": "ok"}

    skill.define = define_with_timeout_retry  # type: ignore[method-assign]
    skill.execute = flaky_timeout  # type: ignore[method-assign]

    wf = {
        "name": "timeout_retry_success",
        "nodes": {
            "src_left": {"skill": "input_text", "inputs": {"text": {"value": "a"}}},
            "src_right": {"skill": "input_text", "inputs": {"text": {"value": "b"}}},
            "n1": {
                "skill": "string_concat",
                "timeout_seconds": 0.005,
                "inputs": {"left": {"from": "src_left.text"}, "right": {"from": "src_right.text"}},
            },
        },
    }
    assert engine.validate(wf, allow_source_literals_only=True) == []
    out = asyncio.run(engine.run(wf))
    assert out["n1.text"] == "ok"
    assert attempts["count"] == 3


def test_validate_timeout_seconds_must_be_positive(engine_ctx):
    engine, _registry = engine_ctx
    wf = {
        "name": "bad_timeout",
        "nodes": {
            "n1": {
                "skill": "input_text",
                "timeout_seconds": 0,
                "inputs": {"text": {"value": "x"}},
            }
        },
    }
    errors = engine.validate(wf, allow_source_literals_only=True)
    assert any("timeout_seconds 必须大于 0" in e for e in errors)
