# 算法服务联调与上线手册

本文对应 `ALGORITHM_INTEGRATION_PLAN.md` 的 C1–C15。生产拓扑是：浏览器只访问 Python 机 Nginx；业务 API 转发至 Java；Java 与 Agent 通过带 `X-Internal-Token` 的内网接口互调。Agent 是唯一的算法和公众号处理进程。

## 1. 本地一次验收

准备 Docker、Java、Node.js，以及两个同级仓库。首次安装：

```bash
cd fusioncareer-agent
python3.11 -m venv .venv
.venv/bin/pip install -e '.[dev]'
cd ../../FusionCareer-View/ui_kits/student
npm ci
```

执行完整门禁（假 LLM 端到端、Python、Java/MySQL、OpenAPI、前端构建、Nginx、Compose、`linux/amd64` OCR 镜像）：

```bash
FRONTEND_ROOT=/absolute/path/FusionCareer-View \
  ./deploy/scripts/test-algorithm.sh
```

假服务只监听 `127.0.0.1:18888`，Agent 只监听 `127.0.0.1:18901`；脚本退出时自动关闭。测试数据包含隐私标记，并断言标记不进入 Agent 日志、简历临时目录已清理。任何一步失败都阻止发布。

## 2. 生产配置

Java 机从 `deploy/env.java.example` 生成 `.env.production`；Python 机从 `deploy/env.agent.example` 生成 `fusioncareer-agent/.env.agent`。两机的 `INTERNAL_SERVICE_TOKEN` 必须一致，`AGENT_ADMIN_TOKEN` 必须是另一条随机值。

必须通过服务器环境或仅管理员可读的 env 文件配置：

- `BACKEND_BASE_URL`：Java 内网地址；
- `PYTHON_SERVICE_BASE_URL`：Agent 内网地址；
- `INTERNAL_SERVICE_TOKEN`：Java/Python 双向鉴权；
- `AGENT_ADMIN_TOKEN`：Agent 管理接口鉴权；
- `LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL`；
- `WECHAT_TOKEN`、`WECHAT_COOKIE`。

禁止提交 `.env.agent`、`.env.production`、微信 cookie、真实 LLM Key。日志只记录模型名、长度、状态和耗时，不记录简历正文、岗位原文、cookie 或 Token。

## 3. 发布顺序

1. 备份 Python 持久卷：`agent_runtime`、`wechat_data`、`paddle_cache`；备份 Java 数据库和上传目录。
2. 运行完整门禁，记录通过的 Java、前端和 Agent commit。
3. 构建并推送/传输 `linux/amd64` Agent 镜像。
4. 先更新 Agent，保持微信 Scheduler 禁用；检查容器 `healthy`。
5. 在 Python 机执行 `curl -fsS http://127.0.0.1:8900/api/health`。
6. 更新 Java，验证 Java `/sys/health`，再验证简历解析与岗位结构化 API。
7. 更新前端；人工验收学生上传的默认不勾选、勾选更新/失败重试，以及管理员“智能识别→编辑→保存/发布”。
8. 手工执行一次公众号 bootstrap 和 daily，确认只生成不重复的 `OFFLINE` 草稿。
9. 启用 Scheduler，观察 24 小时的 LLM 错误率、延迟、抓取新增数、重复草稿和磁盘使用。

Python 机启动：

```bash
docker compose -f deploy/docker-compose.agent.yml \
  --env-file fusioncareer-agent/.env.agent up -d --build
docker compose -f deploy/docker-compose.agent.yml ps
docker compose -f deploy/docker-compose.agent.yml logs --tail=100 agent
```

## 4. 生产 smoke

从内网执行；Token 用环境变量传入，不写命令历史：

```bash
curl -fsS http://127.0.0.1:8900/api/health
curl -fsS -H "X-Internal-Token: $INTERNAL_SERVICE_TOKEN" \
  http://127.0.0.1:8900/api/internal/health
curl -fsS http://127.0.0.1:9100/sys/health
```

外网必须返回 404：

```bash
curl -o /dev/null -sS -w '%{http_code}\n' \
  https://fusioncareer.fudan.edu.cn/agent/api/internal/health
curl -o /dev/null -sS -w '%{http_code}\n' \
  https://fusioncareer.fudan.edu.cn/api/internal/resume-file/1/list
```

再用专用测试账号上传一份无真实隐私的 PDF/DOCX：先不勾选，确认零算法请求；再勾选，确认只合并非空字段。管理员粘贴一段双岗位文本，确认可切换草稿且未自动入库/发布。

## 5. 微信会话更新

公众号后台会话过期时，只更新 Python 机 `.env.agent` 中的 `WECHAT_TOKEN` 和 `WECHAT_COOKIE`，然后重建 Agent 容器：

```bash
docker compose -f deploy/docker-compose.agent.yml \
  --env-file fusioncareer-agent/.env.agent up -d --force-recreate agent
```

先手工跑单账号/小批量验证，再恢复 Scheduler。账号元数据、文章、run、checkpoint 都在 SQLite/持久卷中；不要再维护按行对齐的公众号名称文件。

## 6. 故障处理与重试

- 简历解析失败：文件应仍存在；修复 Agent/LLM 后从用户端点击重试。不要重新上传或人工复制简历正文到日志。
- 岗位结构化失败：管理员原文和表单保持可编辑；重试只重新识别，不自动保存。
- 抓取或结构化失败：保留 Markdown 和失败 run；恢复依赖后重跑，成功后才标记完成。去重键保证不会重复创建草稿。
- 401/403：核对两机 `INTERNAL_SERVICE_TOKEN` 是否一致，以及请求是否走内网路径；不要临时取消鉴权。
- OCR 首次慢：检查 `paddle_cache` 是否挂载、磁盘是否充足；不要把模型缓存打进 Git。

## 7. 回滚

1. 先禁用 Scheduler，避免回滚期间继续抓取。
2. 前端回退到上一镜像/commit；原上传和手工建岗路径不依赖 AI。
3. Java 回退到上一镜像；`updateProfile=false` 路径保持兼容。
4. Agent 回退到上一镜像，但保留三个持久卷，不执行 `docker compose down -v`。
5. 算法生成岗位均为 `OFFLINE`，按本次 run/来源核对后删除；不要批量删除已人工发布岗位。
6. 验证 Java、Agent health 和外网 internal 404，再恢复流量；确认版本稳定后才恢复 Scheduler。

若资料字段需要回滚，使用受控审计记录中的字段名和旧值恢复；审计数据不得保存简历全文。
