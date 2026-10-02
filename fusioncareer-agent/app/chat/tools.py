"""Fixed read and reversible-proposal Tool registry for the career assistant."""

import json
from dataclasses import dataclass
from typing import Any

import httpx

from app.integrations.backend import BackendApiError, BackendClient

MAX_TOOL_RESULT_CHARS = 8_192

READ_TOOLS: list[dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": "get_my_space",
            "description": "读取当前用户个人空间各分区摘要。",
            "parameters": {"type": "object", "properties": {}, "additionalProperties": False},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_my_account",
            "description": "读取当前用户账号的安全投影。",
            "parameters": {"type": "object", "properties": {}, "additionalProperties": False},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_my_profile",
            "description": "读取当前用户完整个人资料。",
            "parameters": {"type": "object", "properties": {}, "additionalProperties": False},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_my_resume",
            "description": "读取当前用户结构化简历。",
            "parameters": {"type": "object", "properties": {}, "additionalProperties": False},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_my_files",
            "description": "分页列出当前用户未删除文件的安全元数据。",
            "parameters": {
                "type": "object",
                "properties": {
                    "page": {"type": "integer", "minimum": 1},
                    "size": {"type": "integer", "minimum": 1, "maximum": 20},
                },
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_my_file",
            "description": "读取一个当前用户自有文件的安全元数据，不返回文件路径或二进制。",
            "parameters": {
                "type": "object",
                "properties": {"fileId": {"type": "string", "pattern": "^[0-9]+$"}},
                "required": ["fileId"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_my_file_quota",
            "description": "读取当前用户文件数量和存储配额。",
            "parameters": {"type": "object", "properties": {}, "additionalProperties": False},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_my_memory",
            "description": "读取当前用户白名单长期记忆。",
            "parameters": {"type": "object", "properties": {}, "additionalProperties": False},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_my_applications",
            "description": "分页读取当前用户自己的问卷草稿和投递。",
            "parameters": {
                "type": "object",
                "properties": {
                    "status": {
                        "type": "string",
                        "enum": ["DRAFT", "SUBMITTED", "REVIEWED", "WITHDRAWN"],
                    },
                    "page": {"type": "integer", "minimum": 1},
                    "size": {"type": "integer", "minimum": 1, "maximum": 10},
                },
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_my_application",
            "description": "读取当前用户对指定岗位的草稿或投递。",
            "parameters": {
                "type": "object",
                "properties": {"jobPostId": {"type": "string", "pattern": "^[0-9]+$"}},
                "required": ["jobPostId"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "search_jobs",
            "description": "搜索当前用户可见且未截止的公开岗位。",
            "parameters": {
                "type": "object",
                "properties": {
                    "keyword": {"type": "string", "maxLength": 100},
                    "jobCategory": {"type": "string"},
                    "recruitType": {"type": "string"},
                    "workMode": {"type": "string"},
                    "workProvince": {"type": "string", "maxLength": 32},
                    "workCity": {"type": "string", "maxLength": 32},
                    "page": {"type": "integer", "minimum": 1},
                    "size": {"type": "integer", "minimum": 1, "maximum": 10},
                    "sort": {"type": "string"},
                },
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_job",
            "description": "读取一个当前仍可见的公开岗位详情。",
            "parameters": {
                "type": "object",
                "properties": {"jobId": {"type": "string", "pattern": "^[0-9]+$"}},
                "required": ["jobId"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_job_questionnaire",
            "description": "读取一个当前可见岗位的动态问卷题目。",
            "parameters": {
                "type": "object",
                "properties": {"jobPostId": {"type": "string", "pattern": "^[0-9]+$"}},
                "required": ["jobPostId"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_my_changes",
            "description": "分页读取当前用户个人空间安全变更历史。",
            "parameters": {
                "type": "object",
                "properties": {
                    "beforeId": {"type": "string", "pattern": "^[0-9]+$"},
                    "size": {"type": "integer", "minimum": 1, "maximum": 20},
                },
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_my_change",
            "description": "读取一条属于当前用户的安全变更详情，不返回加密快照。",
            "parameters": {
                "type": "object",
                "properties": {"actionId": {"type": "string", "pattern": "^[0-9]+$"}},
                "required": ["actionId"],
                "additionalProperties": False,
            },
        },
    },
]

PROFILE_FIELDS = [
    "realName", "gender", "birthDate", "politicalStatus", "phone", "email", "wechat",
    "hometown", "grade", "major", "eduLevel", "supervisor", "intentionOrder",
    "intentionCity", "intentionDream", "mindset",
]
RESUME_FIELDS = [
    "personalIntro", "basicInfo", "education", "internship", "campus", "awards",
    "skills", "portfolio", "remark",
]
MEMORY_FIELDS = [
    "responseStyle", "currentGoal", "targetCities", "targetIndustries",
    "preferredWorkModes", "temporaryConstraints",
]


def proposalTool(readName: str, readDescription: str, readFields: list[str]) -> dict[str, Any]:
    return {
        "type": "function",
        "function": {
            "name": readName,
            "description": readDescription,
            "parameters": {
                "type": "object",
                "properties": {
                    "changes": {
                        "type": "array",
                        "minItems": 1,
                        "maxItems": len(readFields),
                        "items": {
                            "type": "object",
                            "properties": {
                                "field": {"type": "string", "enum": readFields},
                                "operation": {"type": "string", "enum": ["SET", "CLEAR"]},
                                "value": {},
                            },
                            "required": ["field", "operation"],
                            "additionalProperties": False,
                        },
                    },
                    "reason": {"type": "string", "minLength": 1, "maxLength": 256},
                },
                "required": ["changes", "reason"],
                "additionalProperties": False,
            },
        },
    }


def questionnaireTool(readName: str, readDescription: str) -> dict[str, Any]:
    return {
        "type": "function",
        "function": {
            "name": readName,
            "description": readDescription,
            "parameters": {
                "type": "object",
                "properties": {
                    "jobPostId": {"type": "string", "pattern": "^[0-9]+$"},
                    "answers": {
                        "type": "array",
                        "maxItems": 100,
                        "items": {
                            "type": "object",
                            "properties": {
                                "questionId": {"type": "string", "pattern": "^[0-9]+$"},
                                "value": {},
                            },
                            "required": ["questionId", "value"],
                            "additionalProperties": False,
                        },
                    },
                    "reason": {"type": "string", "minLength": 1, "maxLength": 256},
                },
                "required": ["jobPostId", "answers", "reason"],
                "additionalProperties": False,
            },
        },
    }


WRITE_TOOLS: list[dict[str, Any]] = [
    proposalTool(
        "propose_profile_patch",
        "仅当用户当前消息明确要求保存或修改资料时，创建待用户确认的资料修改提案。"
        "SET 的枚举值使用英文枚举名；intentionCity 使用字符串数组。不会直接修改资料。",
        PROFILE_FIELDS,
    ),
    proposalTool(
        "propose_resume_patch",
        "仅当用户当前消息明确要求保存或修改结构化简历时，创建待用户确认的修改提案。"
        "不会直接修改简历。",
        RESUME_FIELDS,
    ),
    proposalTool(
        "propose_memory_patch",
        "仅当用户当前消息明确要求记住、修改或忘记长期偏好时，创建待用户确认的记忆提案。"
        "responseStyle/currentGoal 使用字符串，其余字段使用字符串数组。不会直接修改记忆。",
        MEMORY_FIELDS,
    ),
    {
        "type": "function",
        "function": {
            "name": "propose_file_delete",
            "description": (
                "仅当用户当前消息明确要求删除自己的文件时，创建移入回收站的待确认提案。"
                "不会删除 blob，也不会直接改变文件状态。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "fileId": {"type": "string", "pattern": "^[0-9]+$"},
                    "reason": {"type": "string", "minLength": 1, "maxLength": 256},
                },
                "required": ["fileId", "reason"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "parse_resume_file",
            "description": (
                "仅当用户当前消息明确要求解析并应用自己的简历文件时，解析文件并创建"
                "Profile + Resume 跨资源待确认提案。不会直接修改资料。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "fileId": {"type": "string", "pattern": "^[0-9]+$"},
                    "reason": {"type": "string", "minLength": 1, "maxLength": 256},
                },
                "required": ["fileId", "reason"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "propose_file_restore",
            "description": (
                "仅当用户当前消息明确要求恢复自己的回收站文件时，创建待确认恢复提案。"
                "不会直接改变文件状态。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "fileId": {"type": "string", "pattern": "^[0-9]+$"},
                    "reason": {"type": "string", "minLength": 1, "maxLength": 256},
                },
                "required": ["fileId", "reason"],
                "additionalProperties": False,
            },
        },
    },
    questionnaireTool(
        "propose_questionnaire_draft",
        "仅当用户当前消息明确要求保存指定岗位的问卷草稿时，创建待确认提案。"
        "先用 get_job_questionnaire 获取题目；允许只提供部分答案。不会直接保存。",
    ),
    questionnaireTool(
        "propose_questionnaire_submit",
        "仅当用户当前消息明确要求正式提交指定岗位问卷时，创建待确认提案。"
        "先用 get_job_questionnaire 获取题目；所有必填题必须完整。不会直接提交。",
    ),
]

READ_TOOLS.append({
    "type": "function",
    "function": {
        "name": "recommend_jobs",
        "description": "根据当前用户本人资料和明确偏好推荐有效岗位。普通精确检索使用 search_jobs；不要猜测招聘类型。",
        "parameters": {
            "type": "object", "additionalProperties": False,
            "properties": {
                "jobCategories": {"type": "array", "maxItems": 5, "items": {
                    "type": "string", "enum": ["MEDIA", "ENTERPRISE", "GOVERNMENT", "ACADEMIC", "OTHER"]}},
                "workCities": {"type": "array", "maxItems": 20, "items": {"type": "string", "maxLength": 100}},
                "keywords": {"type": "array", "maxItems": 20, "items": {"type": "string", "maxLength": 100}},
                "recruitType": {"type": "string", "enum": ["BIG_INTERNSHIP", "SMALL_INTERNSHIP",
                    "DAILY_INTERNSHIP", "CAMPUS_RECRUITMENT", "CAMPUS_SCREENING", "BOTH_INTERNSHIP", "OTHER"]},
                "text": {"type": "string", "maxLength": 1000},
            },
        },
    },
})

TOOLS = READ_TOOLS + WRITE_TOOLS
TOOL_NAMES = {readTool["function"]["name"] for readTool in TOOLS}
READ_TOOL_NAMES = {readTool["function"]["name"] for readTool in READ_TOOLS}
PROPOSAL_TOOL_NAMES = {readTool["function"]["name"] for readTool in WRITE_TOOLS}


@dataclass(frozen=True)
class ToolResult:
    content: str
    proposed_action: dict[str, Any] | None = None
    succeeded: bool = True
    presentation: dict[str, Any] | None = None


async def runTool(
    readBackend: BackendClient,
    readContext: str,
    readName: str,
    readArguments: str,
    readCallId: str,
    readAllowedNames: set[str] | None = None,
) -> ToolResult:
    if readName not in TOOL_NAMES or (
        readAllowedNames is not None and readName not in readAllowedNames
    ):
        return ToolResult(
            json.dumps({"error": "UNKNOWN_TOOL"}, ensure_ascii=False), succeeded=False,
        )
    try:
        readArgs = json.loads(readArguments or "{}")
        if not isinstance(readArgs, dict):
            raise TypeError("tool arguments must be an object")
    except (json.JSONDecodeError, TypeError):
        return ToolResult(
            json.dumps({"error": "INVALID_TOOL_ARGUMENTS"}, ensure_ascii=False),
            succeeded=False,
        )
    if readName == "recommend_jobs":
        from pydantic import ValidationError

        from app.chat.recommendations import recommend_jobs
        try:
            result = await recommend_jobs(readBackend, readContext, readArgs, readCallId)
            summary = {**result, "jobs": [{k: j.get(k) for k in (
                "id", "positionName", "companyName", "workCity", "jobCategory", "recruitType",
                "recommendReason",
            )} for j in result["jobs"]]}
            reasons = {str(j["id"]): j["recommendReason"] for j in result["jobs"]
                       if isinstance(j.get("recommendReason"), str) and j["recommendReason"].strip()}
            presentation = {"schemaVersion": 1, "type": "job_results",
                            "jobIds": [str(j["id"]) for j in result["jobs"]],
                            "reasons": reasons,
                            "filters": result["filters"], "method": result["method"],
                            "degraded": result["degraded"], "candidateCount": result["candidateCount"],
                            "algorithmVersion": result["algorithmVersion"]}
            return ToolResult(json.dumps({"untrustedData": summary}, ensure_ascii=False),
                              presentation=presentation)
        except ValidationError:
            return toolError("VALIDATION_ERROR", "推荐筛选条件格式无效", False)
        except BackendApiError as error:
            return safeBackendError(error)
        except Exception:  # noqa: BLE001 - recommendation failures are model-safe data
            return toolError("TOOL_UNAVAILABLE", "推荐服务暂时不可用", True)
    readAttempts = 2 if readName in READ_TOOL_NAMES else 1
    for readAttempt in range(readAttempts):
        try:
            readResult = await readBackend.run_agent_tool(
                readName, readArgs, readContext, tool_call_id=readCallId,
            )
            writeResult = json.dumps(
                {"untrustedData": readResult},
                ensure_ascii=False,
                separators=(",", ":"),
                default=str,
            )
            if len(writeResult) > MAX_TOOL_RESULT_CHARS:
                writeResult = json.dumps({
                    "untrustedData": writeResult[:6_000],
                    "truncated": True,
                }, ensure_ascii=False, separators=(",", ":"))
            readAction = readResult if (
                readName in PROPOSAL_TOOL_NAMES
                and isinstance(readResult, dict)
                and readResult.get("status") == "PENDING"
            ) else None
            return ToolResult(writeResult, readAction)
        except BackendApiError as readError:
            return safeBackendError(readError)
        except httpx.TransportError:
            if readAttempt + 1 < readAttempts:
                continue
            return toolError("TOOL_UNAVAILABLE", "查询服务暂时不可用", True)
        except Exception:  # noqa: BLE001 - Tool errors become model-safe data
            return toolError("TOOL_UNAVAILABLE", "工具暂时不可用", True)
    return toolError("TOOL_UNAVAILABLE", "工具暂时不可用", True)


def safeBackendError(readError: BackendApiError) -> ToolResult:
    readCode = readError.code
    if readCode == 400:
        return toolError("VALIDATION_ERROR", readError.message, False)
    if readCode == 403:
        return toolError("CONTEXT_INVALID", "工具权限已失效", False)
    if readCode == 404:
        return toolError("RESOURCE_NOT_FOUND", readError.message, False)
    if readCode == 409:
        return toolError("CONFLICT", readError.message, False)
    return toolError("TOOL_UNAVAILABLE", "工具暂时不可用", True)


def toolError(readCode: str, readMessage: str, readRetryable: bool) -> ToolResult:
    readSafeMessage = " ".join(str(readMessage).split())[:256]
    return ToolResult(
        json.dumps({
            "error": readCode,
            "message": readSafeMessage,
            "retryable": readRetryable,
        }, ensure_ascii=False, separators=(",", ":")),
        succeeded=False,
    )
