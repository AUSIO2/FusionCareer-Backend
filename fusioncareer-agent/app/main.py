"""FusionCareer AI Agent — FastAPI 入口"""

import json
import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from app.clients.backend import BackendClient
from app.clients.llm import LLMClient
from app.config import settings
from app.engine import WorkflowEngine
from app.registry import SkillRegistry
from app.skills.resume_writer import set_backend_client as set_resume_backend
from app.skills.profile_writer import set_backend_client as set_profile_backend

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-5s [%(name)s] %(message)s",
)
logger = logging.getLogger(__name__)

# ── 全局单例 ──
backend_client = BackendClient()
llm_client = LLMClient()
registry = SkillRegistry()
engine: WorkflowEngine | None = None

# ── 预设工作流 ──
preset_workflows: dict[str, dict] = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    global engine

    # 启动：注入依赖 → 自动发现 Skill → 加载预设工作流
    set_resume_backend(backend_client)
    set_profile_backend(backend_client)

    registry.auto_discover("app.skills")
    logger.info(f"已注册 {len(registry.list_all())} 个 Skill: "
                f"{[s['name'] for s in registry.list_all()]}")

    engine = WorkflowEngine(registry)

    # 加载 workflows/ 目录下的 JSON
    wf_dir = Path(__file__).parent / "workflows"
    if wf_dir.exists():
        for f in wf_dir.glob("*.json"):
            preset_workflows[f.stem] = json.loads(f.read_text())
            logger.info(f"加载预设工作流: {f.stem}")

    yield

    # 关闭
    await backend_client.close()


app = FastAPI(
    title="FusionCareer AI Agent",
    description="节点式工作流微服务 — 可插拔 Skill 插件",
    version="0.1.0",
    lifespan=lifespan,
)


# ── 请求模型 ──

class RunWorkflowRequest(BaseModel):
    """直接提交工作流 JSON 执行"""
    name: str = "inline"
    nodes: dict

class RunPresetRequest(BaseModel):
    """执行预设工作流，可覆盖 value 参数"""
    overrides: dict = {}


# ── 路由 ──

@app.get("/api/health")
async def health():
    return {"status": "ok", "backend": settings.backend_base_url}


@app.get("/api/skills")
async def list_skills():
    """列出所有已注册 Skill（含输入输出定义）"""
    return {"skills": registry.list_all()}


@app.post("/api/run")
async def run_workflow(req: RunWorkflowRequest):
    """提交一个 workflow JSON 执行"""
    workflow = {"name": req.name, "nodes": req.nodes}

    # 校验
    errors = engine.validate(workflow)
    if errors:
        raise HTTPException(status_code=422, detail={"errors": errors})

    # 执行
    try:
        result = engine.run(workflow)
        # 如果是协程则 await
        import asyncio
        if asyncio.iscoroutine(result):
            result = await result
        else:
            result = await result
    except Exception as e:
        logger.exception("工作流执行失败")
        raise HTTPException(status_code=500, detail={"error": str(e)})

    return {"status": "completed", "workflow": req.name, "outputs": result}


@app.get("/api/workflows")
async def list_workflows():
    """列出所有预设工作流"""
    return {
        "workflows": [
            {"name": name, "node_count": len(wf.get("nodes", {}))}
            for name, wf in preset_workflows.items()
        ]
    }


@app.post("/api/workflows/{name}/run")
async def run_preset_workflow(name: str, req: RunPresetRequest = RunPresetRequest()):
    """执行预设工作流（可通过 overrides 覆盖 value 参数）"""
    if name not in preset_workflows:
        raise HTTPException(status_code=404, detail=f"预设工作流 '{name}' 不存在")

    workflow = json.loads(json.dumps(preset_workflows[name]))  # deep copy

    # 覆盖 value 参数: overrides = {"n1.file_id": 9876}
    for key, val in req.overrides.items():
        parts = key.split(".", 1)
        if len(parts) == 2:
            nid, slot = parts
            if nid in workflow["nodes"] and slot in workflow["nodes"][nid].get("inputs", {}):
                workflow["nodes"][nid]["inputs"][slot] = {"value": val}

    errors = engine.validate(workflow)
    if errors:
        raise HTTPException(status_code=422, detail={"errors": errors})

    try:
        result = await engine.run(workflow)
    except Exception as e:
        logger.exception("工作流执行失败")
        raise HTTPException(status_code=500, detail={"error": str(e)})

    return {"status": "completed", "workflow": name, "outputs": result}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app.main:app", host="0.0.0.0", port=settings.agent_port, reload=True)
