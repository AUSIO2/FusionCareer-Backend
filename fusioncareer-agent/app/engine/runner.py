"""工作流引擎 — 并行 DAG；任一节点失败则全图失败"""

from __future__ import annotations

import asyncio
import copy
import logging
from dataclasses import dataclass
from typing import Any, Literal

from app.catalog.catalog import DataClassCatalog
from app.core.base_skill import BaseSkill
from app.core.registry import SkillRegistry
from app.engine.validator import WorkflowValidator

logger = logging.getLogger(__name__)

FailureKind = Literal["execute", "dependency"]


class WorkflowNodeError(Exception):
    """单个节点执行失败"""

    def __init__(
        self,
        node_id: str,
        skill: str,
        cause: Exception,
        partial_outputs: dict[str, Any],
        *,
        failure_kind: FailureKind = "execute",
        root_node_id: str | None = None,
        attempts_used: int = 1,
        retry_policy_applied: dict[str, Any] | None = None,
        input_snapshot: dict[str, Any] | None = None,
        timeout_seconds: float | None = None,
    ):
        super().__init__(str(cause))
        self.node_id = node_id
        self.skill = skill
        self.cause = cause
        self.partial_outputs = partial_outputs
        self.failure_kind = failure_kind
        self.root_node_id = root_node_id if root_node_id is not None else node_id
        self.attempts_used = attempts_used
        self.retry_policy_applied = retry_policy_applied or {
            "enabled": False,
            "max_retries": 0,
            "retry_on": [],
            "backoff_seconds": 0.0,
        }
        self.input_snapshot = input_snapshot or {}
        self.timeout_seconds = timeout_seconds

    @property
    def root_node(self) -> str:
        """对外报告用的根因节点（与 failed_node 一致）。"""
        return self.root_node_id


def _collect_workflow_node_errors(exc: BaseException) -> list[WorkflowNodeError]:
    if isinstance(exc, WorkflowNodeError):
        return [exc]
    if isinstance(exc, ExceptionGroup):
        found: list[WorkflowNodeError] = []
        for sub in exc.exceptions:
            found.extend(_collect_workflow_node_errors(sub))
        return found
    return []


def _pick_root_workflow_error(errors: list[WorkflowNodeError]) -> WorkflowNodeError | None:
    if not errors:
        return None
    execute_errors = [e for e in errors if e.failure_kind == "execute"]
    candidates = execute_errors if execute_errors else errors
    return min(candidates, key=lambda e: e.root_node)


@dataclass(frozen=True)
class RetryPolicy:
    enabled: bool = False
    max_retries: int = 0
    retry_on: tuple[str, ...] = ()
    backoff_seconds: float = 0.0

    def to_dict(self) -> dict[str, Any]:
        return {
            "enabled": self.enabled,
            "max_retries": self.max_retries,
            "retry_on": list(self.retry_on),
            "backoff_seconds": self.backoff_seconds,
        }


def _parse_retry_policy(defn: dict[str, Any]) -> RetryPolicy:
    raw = defn.get("retry_policy")
    if not isinstance(raw, dict):
        return RetryPolicy()
    enabled = bool(raw.get("enabled", False))
    max_retries = raw.get("max_retries", 0)
    retry_on = raw.get("retry_on", [])
    backoff = raw.get("backoff_seconds", 0.0)
    if not isinstance(max_retries, int) or max_retries < 0:
        max_retries = 0
    if not isinstance(retry_on, list):
        retry_on = []
    retry_on_names = tuple(v for v in retry_on if isinstance(v, str))
    if not isinstance(backoff, (int, float)) or backoff < 0:
        backoff = 0.0
    return RetryPolicy(
        enabled=enabled and max_retries > 0,
        max_retries=max_retries,
        retry_on=retry_on_names,
        backoff_seconds=float(backoff),
    )


def _is_retryable(exc: Exception, policy: RetryPolicy) -> bool:
    if not policy.enabled:
        return False
    if not policy.retry_on:
        return True
    exc_name = type(exc).__name__
    full_name = f"{type(exc).__module__}.{exc_name}"
    return exc_name in policy.retry_on or full_name in policy.retry_on


def _snapshot_value(value: Any, depth: int = 0) -> Any:
    if depth >= 2:
        return {"type": type(value).__name__}
    if value is None or isinstance(value, (bool, int, float)):
        return value
    if isinstance(value, str):
        preview = value[:120]
        return {"type": "str", "len": len(value), "preview": preview}
    if isinstance(value, list):
        return {
            "type": "list",
            "len": len(value),
            "items": [_snapshot_value(v, depth + 1) for v in value[:3]],
        }
    if isinstance(value, dict):
        keys = list(value.keys())
        sample = keys[:5]
        return {
            "type": "dict",
            "len": len(value),
            "keys": sample,
            "sample": {k: _snapshot_value(value[k], depth + 1) for k in sample},
        }
    return {"type": type(value).__name__}


def _snapshot_inputs(inputs: dict[str, Any]) -> dict[str, Any]:
    return {slot: _snapshot_value(value) for slot, value in inputs.items()}


def _parse_node_timeout(node: dict[str, Any]) -> float | None:
    raw = node.get("timeout_seconds")
    if raw is None:
        return None
    if isinstance(raw, (int, float)) and raw > 0:
        return float(raw)
    return None


class WorkflowEngine:
    def __init__(self, registry: SkillRegistry, catalog: DataClassCatalog):
        self._registry = registry
        self._catalog = catalog
        self._validator = WorkflowValidator(registry, catalog)

    @property
    def validator(self) -> WorkflowValidator:
        return self._validator

    def validate(
        self,
        workflow: dict,
        *,
        allow_literals: bool = False,
        allow_source_literals_only: bool = False,
    ) -> list[str]:
        return self._validator.validate(
            workflow,
            allow_literals=allow_literals,
            allow_source_literals_only=allow_source_literals_only,
        )

    async def run(self, workflow: dict) -> dict[str, Any]:
        """
        执行工作流。成功返回 output cache；失败抛出 WorkflowNodeError（含 partial_outputs）。
        """
        workflow = copy.deepcopy(workflow)
        nodes = workflow["nodes"]
        cache: dict[str, Any] = {}
        futures: dict[str, asyncio.Future[None]] = {}
        loop = asyncio.get_running_loop()
        for nid in nodes:
            futures[nid] = loop.create_future()

        def _has_dependents(nid: str) -> bool:
            prefix = f"{nid}."
            for other_id, other in nodes.items():
                if other_id == nid:
                    continue
                for spec in (other.get("inputs") or {}).values():
                    if isinstance(spec, dict):
                        ref = spec.get("from", "")
                        if isinstance(ref, str) and ref.startswith(prefix):
                            return True
            return False

        def _fail(
            nid: str,
            skill_name: str,
            exc: Exception,
            *,
            attempts_used: int,
            retry_policy: RetryPolicy,
            input_snapshot: dict[str, Any] | None,
            timeout_seconds: float | None,
        ) -> None:
            err = WorkflowNodeError(
                nid,
                skill_name,
                exc,
                dict(cache),
                failure_kind="execute",
                root_node_id=nid,
                attempts_used=attempts_used,
                retry_policy_applied=retry_policy.to_dict(),
                input_snapshot=input_snapshot,
                timeout_seconds=timeout_seconds,
            )
            if _has_dependents(nid) and not futures[nid].done():
                futures[nid].set_exception(err)
            raise err

        async def _run_node(nid: str) -> None:
            node = nodes[nid]
            skill_name = node["skill"]
            skill: BaseSkill = self._registry.get(skill_name)
            defn = skill.define()
            retry_policy = _parse_retry_policy(defn)
            timeout_seconds = _parse_node_timeout(node)

            try:
                deps: set[str] = set()
                for spec in node.get("inputs", {}).values():
                    if isinstance(spec, dict) and "from" in spec:
                        deps.add(spec["from"].split(".", 1)[0])

                if deps:
                    logger.debug("[%s] 等待上游: %s", nid, deps)
                    await asyncio.gather(*(futures[d] for d in deps))

                inputs: dict[str, Any] = {}
                for slot, spec in node.get("inputs", {}).items():
                    if not isinstance(spec, dict):
                        raise ValueError(f"节点 {nid}: 输入 '{slot}' 规格无效")
                    if "value" in spec:
                        inputs[slot] = spec["value"]
                    elif "from" in spec:
                        ref = spec["from"]
                        if ref not in cache:
                            raise ValueError(f"节点 {nid}: 输入 '{slot}' 引用 '{ref}' 不存在")
                        inputs[slot] = cache[ref]
                    else:
                        raise ValueError(f"节点 {nid}: 输入 '{slot}' 缺少 value 或 from")

                for slot in defn.get("inputs", {}):
                    if slot not in inputs:
                        raise ValueError(f"节点 {nid}: 缺少必需输入 '{slot}'")

                input_snapshot = _snapshot_inputs(inputs)
                attempt = 0
                while True:
                    attempt += 1
                    try:
                        logger.info("[%s] 执行 %s attempt=%d", nid, skill_name, attempt)
                        if timeout_seconds is None:
                            result = await skill.execute(inputs)
                        else:
                            async with asyncio.timeout(timeout_seconds):
                                result = await skill.execute(inputs)
                        break
                    except TimeoutError as e:
                        timeout_exc = TimeoutError(
                            f"节点 {nid} 执行超时（>{timeout_seconds}s）"
                        )
                        retryable = _is_retryable(timeout_exc, retry_policy)
                        should_retry = retryable and attempt <= retry_policy.max_retries
                        if should_retry:
                            logger.warning(
                                "[%s] %s 超时将重试 attempt=%d/%d",
                                nid,
                                skill_name,
                                attempt,
                                retry_policy.max_retries,
                            )
                            if retry_policy.backoff_seconds > 0:
                                await asyncio.sleep(retry_policy.backoff_seconds * attempt)
                            continue
                        raise WorkflowNodeError(
                            nid,
                            skill_name,
                            timeout_exc,
                            dict(cache),
                            failure_kind="execute",
                            root_node_id=nid,
                            attempts_used=attempt,
                            retry_policy_applied=retry_policy.to_dict(),
                            input_snapshot=input_snapshot,
                            timeout_seconds=timeout_seconds,
                        ) from e
                    except Exception as e:
                        retryable = _is_retryable(e, retry_policy)
                        should_retry = retryable and attempt <= retry_policy.max_retries
                        if should_retry:
                            logger.warning(
                                "[%s] %s 失败将重试 attempt=%d/%d: %s",
                                nid,
                                skill_name,
                                attempt,
                                retry_policy.max_retries,
                                e,
                            )
                            if retry_policy.backoff_seconds > 0:
                                await asyncio.sleep(retry_policy.backoff_seconds * attempt)
                            continue
                        raise WorkflowNodeError(
                            nid,
                            skill_name,
                            e,
                            dict(cache),
                            failure_kind="execute",
                            root_node_id=nid,
                            attempts_used=attempt,
                            retry_policy_applied=retry_policy.to_dict(),
                            input_snapshot=input_snapshot,
                            timeout_seconds=timeout_seconds,
                        ) from e

                out_def = defn.get("outputs") or {}
                if len(out_def) == 1:
                    only_slot = next(iter(out_def))
                    if only_slot not in result and len(result) == 1:
                        result = {only_slot: next(iter(result.values()))}
                    elif only_slot not in result:
                        raise ValueError(
                            f"节点 {nid}: execute 须返回 output 槽 '{only_slot}'，"
                            f"实际 {list(result)}"
                        )

                for slot, val in result.items():
                    if slot in out_def:
                        cache[f"{nid}.{slot}"] = val
                if not futures[nid].done():
                    futures[nid].set_result(None)
                logger.info("[%s] %s 完成", nid, skill_name)
            except WorkflowNodeError:
                raise
            except Exception as e:
                logger.error("[%s] %s 失败: %s", nid, skill_name, e)
                _fail(
                    nid,
                    skill_name,
                    e,
                    attempts_used=1,
                    retry_policy=retry_policy,
                    input_snapshot=None,
                    timeout_seconds=timeout_seconds,
                )

        try:
            async with asyncio.TaskGroup() as tg:
                for nid in nodes:
                    tg.create_task(_run_node(nid))
        except ExceptionGroup as eg:
            nested = _collect_workflow_node_errors(eg)
            root = _pick_root_workflow_error(nested)
            if root is not None:
                raise root from eg
            raise WorkflowNodeError(
                "unknown",
                "",
                eg.exceptions[0],
                dict(cache),
                failure_kind="execute",
                root_node_id="unknown",
            ) from eg.exceptions[0]
        except WorkflowNodeError:
            raise

        return cache
