"""Local fake OpenAI and Java endpoints for the algorithm smoke test."""

import io
import json
import time

from docx import Document
from fastapi import FastAPI, Header, HTTPException, Response


createApp = FastAPI()


def createResume() -> bytes:
    createBuffer = io.BytesIO()
    createDocument = Document()
    createDocument.add_paragraph("张同学，新闻传播学硕士，技能：Python。private-resume-marker")
    createDocument.save(createBuffer)
    return createBuffer.getvalue()


@createApp.post("/v1/chat/completions")
async def createCompletion(readBody: dict) -> dict:
    readMessages = readBody.get("messages") or []
    readPrompt = str(readMessages[0].get("content") if readMessages else "")
    if "简历信息抽取" in readPrompt:
        createData = {
            "profilePatch": {"realName": "张同学", "major": "新闻传播学"},
            "resumePatch": {"skills": "Python"},
            "warnings": [],
        }
    else:
        createData = {
            "jobs": [{
                "单位名称": "示例科技有限公司",
                "岗位名称": "后端开发工程师",
                "岗位大类": "企业公司",
                "岗位二级分类": "民企",
                "招聘类型": "应届生招聘",
            }],
            "warnings": [],
        }
    return {
        "id": "fake-completion",
        "object": "chat.completion",
        "created": int(time.time()),
        "model": "fake-model",
        "choices": [{
            "index": 0,
            "message": {"role": "assistant", "content": json.dumps(createData, ensure_ascii=False)},
            "finish_reason": "stop",
        }],
        "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
    }


@createApp.get("/internal/resume-file/{readUserId}/list")
async def readFiles(readUserId: int, x_internal_token: str = Header(default="")) -> dict:
    if x_internal_token != "smoke-internal":
        raise HTTPException(status_code=403)
    readData = [{"id": "7", "originalName": "resume.docx"}] if readUserId == 42 else []
    return {"code": 200, "message": "ok", "data": readData}


@createApp.get("/internal/resume-file/{readFileId}/download")
async def readFile(readFileId: int, x_internal_token: str = Header(default="")) -> Response:
    if x_internal_token != "smoke-internal" or readFileId != 7:
        raise HTTPException(status_code=403)
    return Response(createResume(), media_type="application/vnd.openxmlformats-officedocument.wordprocessingml.document")
