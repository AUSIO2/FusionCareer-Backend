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

### 长招聘文章结构化

生产使用 `JOB_STRUCTURE_MODE=chunked`。长文先按 Markdown 语义块识别岗位目录，再按最多 4 个岗位和
12,000 字上下文抽取详情；模型返回截断时自动缩小批次。旧的单次全文抽取仅用于紧急回滚：

```bash
JOB_STRUCTURE_MODE=legacy
```

更新镜像时还要同步持久卷中的 `/data/agent/runtime/workflows/job_structure.json`；仅修改镜像内 preset
不会覆盖已经存在的 runtime workflow。reload 后在管理员工作流列表中确认 `job_structure` 使用
`native_job_structure`。

结构化状态可通过管理员接口查看：

```bash
curl -fsS -H "X-Agent-Admin-Token: $AGENT_ADMIN_TOKEN" \
  http://127.0.0.1:8900/api/admin/wechat/structure-pending
```

失败文章按 1 小时、6 小时、24 小时退避，第三次失败进入 `QUARANTINED`。修复原因后，只重新入队明确
的 URL；批量重新入队全部隔离项必须显式传 `allQuarantined=true`：

```bash
curl -fsS -X POST \
  -H "X-Agent-Admin-Token: $AGENT_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"urls":["https://example.invalid/job"]}' \
  http://127.0.0.1:8900/api/admin/wechat/structure-requeue
```

排查调用量时关注容器日志中的 `operation=job_index|job_detail|job_single`、`finish_reason`、
`prompt_tokens` 和 `completion_tokens`。不得将完整提示词、文章正文或 API key 输出到普通运维日志。

### 261013 简历解析

代码/Compose 默认 `RESUME_PARSER_VERSION=legacy`；批准的生产 override 显式设为 `261013`，选择上游
新增的经历分段、保守重分类、跨字段去重和双栏 PDF 提取。首发只切单文件 `resume_parse`，批量解析仍走
legacy。正式 Java 契约不变，仍由 Agent 校验文件归属、格式、20 MB 上限和
75 秒 HTTP 总预算；模型调用继续禁用 SDK 网络重试，并让一次 JSON 修复共享 60 秒模型预算。

`RESUME_MODEL_IO_LOG_ENABLED=false` 是 Agent 的生产默认值。确需排障时，日志只写私有算法 workspace，
强制 `include_sensitive=false`，并应配置独立的 `RESUME_MODEL_IO_ACTOR_HMAC_SECRET`；不得复用 API Token，
不得把日志复制到普通运维输出。恢复完成后立即关闭日志，并按既有数据保留策略清理。

发布前至少验证：261013/legacy 开关、经历换行与分类、PDF 保守回退、真实工作流契约、一次 JSON 修复的
共享超时，以及岗位去重回归。回滚只需把 `RESUME_PARSER_VERSION` 改为 `legacy` 并重建 Agent，三个
持久卷和 Java/MySQL 均不变。

### 跨来源岗位去重

`JOB_RECORD_DEDUP_ENABLED=true` 控制抽岗后的岗位级去重，和整篇 Markdown 的
`ARTICLE_DEDUP_ENABLED` 相互独立。岗位级去重先保留原有
`sourceUrl + companyName + positionName` 精确键，再识别括号中的品牌/法定主体别名、规范化投递 URL
与邮箱。显式品牌—法人映射维护在 `app/algorithms/job_structuring/company_aliases.json`；删除法律后缀
得到的弱名称和“国有企业”等括号描述不能触发自动去重。只有岗位名、公司身份相同，活动年份、季节、
招聘类型、部门、城市、人数、薪资与工作形式兼容，并满足以下任一强锚点时才跨来源自动去重：同职位级
投递 URL；同活动级 URL + 同邮箱；或可解析出租户/活动身份的 ATS URL + 同招聘届别/类型。51job、
猎聘等公共列表页和飞书搜索页不属于租户活动锚点，邮箱本身也不能单独触发。每个新成员还必须与候选
簇内全部直接证据兼容，缺失字段不能作为桥梁，把北京和上海等互相冲突的记录传递合并。

不同高校转载 URL、系统默认截止日期相差一两天不再造成重复。明确不同届别/春秋招、不同部门、互不
相交的城市或职位 ID 仍保留为不同岗位；“全国”“全国主要城市”“全国 100 多个城市”等明确覆盖
范围会归一为同一全国覆盖证据，但不会成为北京/上海的通配符。只有门牌、楼栋、园区、校区或带行政
区划的道路信息才作为具体地址；薪资福利构成的标点差异不构成冲突，面议与明确数额仍冲突。同一待写
批次内的兼容重复会补齐城市及包含关系下的更完整描述。命中 Java 已有 keeper 时，必须走
`PUT /internal/job-post/{id}/merge-evidence`：Java 在行锁和版本前置条件下单调合并字段，并在同一事务
保存原 keeper 与每个 incoming 的隐藏 `RECYCLED` 证据快照。Agent 重启后按这些原始成员重建
complete-link 簇，不能把城市并集、最晚截止日或不同成员各自提供的 URL/邮箱拼成新的传递桥梁。
确定性字段冲突保留 incoming 为独立岗位；超时或 5xx 结果不明时熔断后续写入并重试。管理员编辑已
合并岗位必须携带 `expectedUpdatedAt`，旧版本返回 409，避免旧表单覆盖刚合入的证据。存量清理仍须
先生成审计计划、保存 keeper-before 快照并核对引用后执行。
LLM 抽取继续并发，最终的“匹配 → Java 写入 → 索引登记”在单次任务内串行，进程级锁和持久卷文件锁
又避免 Scheduler、管理员 drain 与一次性 runner 并行写入。Java 响应超时后会在锁内重新读取后端，
确认是否已提交；未确认的写入不占用去重键并保留文章重试。

以上保证以当前单 Agent、单 Uvicorn worker、共享同一持久卷为边界。扩展为多主机副本，或允许 Excel/
其他 Java 写入口与采集并行前，必须增加 Java 幂等 batch-upsert 和 MySQL 可空语义键唯一索引作为最终
兜底；不能把 Python 内存/文件锁当作跨主机数据库约束。

发布前至少运行：

```bash
cd fusioncareer-agent
pytest -q tests/algorithms/test_job_record_dedup.py \
  tests/skills/crawlers/test_structure_articles.py
```

若误判需要紧急回滚，可只设置 `JOB_RECORD_DEDUP_ENABLED=false`，此时仍保留原有精确三元组去重与
文章近重复去重。该开关不处理存量记录；存量清理必须先生成 dry-run keeper/loser 计划并核对引用。

## 7. 回滚

1. 先禁用 Scheduler，避免回滚期间继续抓取。
2. 前端回退到上一镜像/commit；原上传和手工建岗路径不依赖 AI。
3. Java 回退到上一镜像；`updateProfile=false` 路径保持兼容。
4. Agent 回退到上一镜像，但保留三个持久卷，不执行 `docker compose down -v`。
5. 算法生成岗位均为 `OFFLINE`，按本次 run/来源核对后删除；不要批量删除已人工发布岗位。
6. 验证 Java、Agent health 和外网 internal 404，再恢复流量；确认版本稳定后才恢复 Scheduler。

若资料字段需要回滚，使用受控审计记录中的字段名和旧值恢复；审计数据不得保存简历全文。
