"""Local-only OpenAI-compatible fixture for Java → Agent → Tool end-to-end tests."""

import json

from fastapi import FastAPI, Request
from fastapi.responses import StreamingResponse

app = FastAPI()


@app.post("/v1/chat/completions")
async def complete(request: Request):
    body = await request.json()
    content = "已根据你的偏好整理岗位，请查看推荐卡片。"
    if body.get("stream"):

        async def chunks():
            for delta, finish in [(content, None), ("", "stop")]:
                yield (
                    "data: "
                    + json.dumps(
                        {
                            "id": "local-test",
                            "object": "chat.completion.chunk",
                            "created": 0,
                            "model": "test-model",
                            "choices": [{"index": 0, "delta": {"content": delta}, "finish_reason": finish}],
                        },
                        ensure_ascii=False,
                    )
                    + "\n\n"
                )
            yield "data: [DONE]\n\n"

        return StreamingResponse(chunks(), media_type="text/event-stream")
    if body.get("response_format"):
        try:
            data = json.loads(body["messages"][-1]["content"])
            jobs = data.get("jobs", [])
            content = json.dumps({
                "order": [j["id"] for j in jobs],
                "reasons": {str(j["id"]): "岗位方向与当前偏好一致。" for j in jobs},
            }, ensure_ascii=False)
        except (ValueError, KeyError):
            content = "{}"
    return {
        "id": "local-test",
        "object": "chat.completion",
        "created": 0,
        "model": "test-model",
        "choices": [{"index": 0, "message": {"role": "assistant", "content": content}, "finish_reason": "stop"}],
        "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20},
    }
