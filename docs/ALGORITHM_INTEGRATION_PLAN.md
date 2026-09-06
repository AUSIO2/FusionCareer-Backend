# FusionCareer 算法、爬虫与前后端集成计划

## 1. 目标

本计划完成三条业务闭环：

1. 学生上传简历时可选“用解析结果更新我的资料”，系统解析 PDF/图片简历并选择性更新个人资料与在线简历。
2. 管理员粘贴岗位原文，系统返回标准 `JobPostRequest` 字段，管理员编辑后使用已有草稿/发布流程。
3. 微信公众号每日增量抓取 Markdown，使用同一岗位结构化能力生成待审核岗位草稿，经 Java 写库后由管理员发布。

完成时，服务器只运行一个 `fusioncareer-agent` Python 服务；不部署桌面 `pipeline.sh`，不额外部署算法仓库自带的 FastAPI。

## 2. 已审计的源码基线

### 2.1 算法仓库

- 仓库：`https://github.com/chenxin1209/FusionCareer-Algorithm`
- 审计提交：`4dc20862f79888e7e84351217767fa161a53ae83`
- 许可证：MIT；迁入时保留版权和上游提交记录。
- 岗位结构化核心：`job_structuring/engine.py`，1363 行。
- 岗位上传工具：`job_structuring/upload_backend.py`，521 行。
- 简历解析核心：`resume_parser/parser.py`，272 行。
- 简历 HTTP 路由：`resume_parser/routers/internal.py`，当前为空文件。
- 仓库当前无自动化测试。

### 2.2 爬虫源码

爬虫没有独立仓库，唯一源码为：

- `/Users/xiong/Desktop/wechat_crawler.py.py`
- `/Users/xiong/Desktop/pipeline.sh`

桌面 Python 文件约 899 行，提供 bootstrap、daily 和 watch。当前 `fusioncareer-agent/app/skills/business/wechat/` 已有未提交的迁移版，已拆出 HTTP、IO、路径、单账号处理、循环和 Scheduler。实施必须保留这些现有改动，不用桌面文件整体覆盖。

## 3. 代码审计结论

### 3.1 可直接复用

#### 岗位结构化

- LLM 中文提示词和一文多岗规则。
- 中文枚举到 Java 枚举常量的映射。
- 大类/子类修复、简单启发式补全。
- JSON 容错、一文多岗拆分和篇内去重。
- `(sourceUrl, companyName, positionName)` 去重键。
- 对齐 `JobPostRequest` 的 camelCase 字段。

#### 简历解析

- PDF 正文和表格提取。
- DOCX 段落、表格、页眉页脚和文本框提取。
- 图片缩放和 PaddleOCR 调用。
- OCR 文本清洗。
- 简历提取提示词。
- 模型 JSON 容错与字段白名单。

### 3.2 不应直接迁入

- 算法仓库自带 `resume_parser/main.py`：会创建第二个 FastAPI，且导入路径与当前 Agent 不一致。
- 空的 `resume_parser/routers/internal.py`：不能被认为已实现 HTTP 服务。
- `DeepSeekClient`：与已有异步 `LLMClient` 重复。
- `config.json`：与 Pydantic Settings 重复，且容易存放密钥。
- CSV/XLSX CLI 与 `openpyxl`：互动功能不需要中间 Excel 文件。
- `upload_backend.py` 的独立 HTTP 客户端：与已有 `BackendClient` 重复。
- `aiohttp`、第二份 FastAPI/uvicorn 依赖：当前 Agent 已提供对应能力。
- 桌面 `pipeline.sh` 的 venv、cron 和日志管理：已由 Docker、Scheduler 和 logging 提供。

### 3.3 必须修正的契约问题

1. 简历算法输出 snake_case，Agent/Java 使用 camelCase。
2. 简历算法输出数字枚举；Java Jackson 应接收 `MALE`、`PARTY_MEMBER`、`ACADEMIC_MASTER` 等常量名，不能直接传数字。
3. `birth_date` 输出 `YYYY-MM`，Java `LocalDate` 需要 `YYYY-MM-DD`；缺日时统一补 `01`。
4. `intention_city` 是 Python list，当前 Java DTO/数据库链路使用 JSON 字符串。
5. 算法岗位默认 `PUBLISHED`；自动抓取数据必须默认 `OFFLINE`，经管理员确认后才发布。
6. 岗位核心是同步 `requests` 调 LLM；接入 FastAPI 时应复用异步 `LLMClient`，避免阻塞事件循环。
7. 上游简历 API 模型允许任意 `file_url`，存在 SSRF 风险；集成 API 只接收经 Java 验证的 `userId + fileId`。
8. PaddleOCR 依赖体积大，且上游锁定 Paddle/PaddleX 版本；必须先在 `linux/amd64` 生产镜像完成构建试验。
9. Java `/internal/**` 当前只依赖网络隔离，缺少服务间 Token。
10. 微信迁移版仍使用 `gzh.txt + 公众号名字 + history.json + wx_poc.txt`，需在接算法前先稳定状态模型。

## 4. 总体架构决策

```text
学生浏览器
  → Java 上传简历
  → Python Agent 解析简历
  → Java 校验并更新 profile/resume

管理员浏览器
  → Java 角色鉴权
  → Python Agent 结构化岗位文本
  → Java 返回标准字段
  → 管理员编辑并使用已有发布接口

Agent Scheduler
  → WeChat Skill 抓取增量 Markdown
  → 同一岗位结构化核心
  → Python BackendClient
  → Java /internal/job-post/batch (OFFLINE)
```

核心原则：

- Java 是身份、权限、文件所有权、业务校验和数据库的唯一负责方。
- Python 不直连 MySQL，不直接替用户决定发布岗位。
- 前端不直连 Python；所有用户请求先经 Java 鉴权。
- 只运行已有 Agent FastAPI，算法作为 Python 包/能力迁入。
- 所有抓取岗位先成为草稿，不自动面向学生发布。

## 5. Python 仓库接入方案

### 5.1 迁入目录

```text
fusioncareer-agent/app/algorithms/
├── job_structuring/
│   ├── prompt.py
│   ├── normalize.py
│   ├── dedup.py
│   └── service.py
└── resume_parser/
    ├── extractors/
    ├── ocr/
    ├── prompt.py
    ├── normalize.py
    └── service.py
```

不迁入上游 CLI、第二个 FastAPI、空 router、CSV/XLSX 中间流程和重复的 HTTP/LLM 客户端。

增加 `fusioncareer-agent/UPSTREAM.md`，记录：

```text
source: chenxin1209/FusionCareer-Algorithm
commit: 4dc20862f79888e7e84351217767fa161a53ae83
license: MIT
imported modules: job_structuring core, resume_parser core
excluded modules: CLI, standalone FastAPI, uploader, generated data
```

### 5.2 复用现有 Agent 能力

- LLM 调用统一使用 `app.integrations.llm.LLMClient`。
- Java 调用统一使用 `app.integrations.backend.BackendClient`。
- 定时任务使用已有 `SchedulerService`。
- 算法工作流使用已有 Skill/Workflow 注册机制。
- 运行时文件使用 `RuntimePaths` 和原子写入。
- 不再使用算法仓库 `config.json`，统一使用 Pydantic Settings/环境变量。

### 5.3 算法内部 API

Python 新增仅服务间可访问的 router：

#### `POST /api/internal/job/structure`

Header：

```text
X-Internal-Token: <shared-secret>
```

Request：

```json
{
  "text": "岗位原文",
  "sourceUrl": "https://example.test/source",
  "sourceType": "PLATFORM",
  "defaultStatus": "OFFLINE"
}
```

Response：

```json
{
  "jobs": [
    {
      "companyName": "...",
      "positionName": "...",
      "jobCategory": "MEDIA",
      "recruitType": "CAMPUS_RECRUITMENT",
      "status": "OFFLINE"
    }
  ],
  "warnings": []
}
```

规则：

- 允许一文多岗，始终返回数组。
- 至少要求 `companyName + positionName`。
- 枚举必须是 Java 常量名。
- 未识别值返回 `null`/空字符串并写入 `warnings`，不伪造。
- 交互式请求不写 Java，只返回编辑草稿。

#### `POST /api/internal/resume/parse`

Request：

```json
{
  "userId": "2094674091431800833",
  "fileId": "2094675000000000000"
}
```

Response：

```json
{
  "profilePatch": {
    "realName": "...",
    "gender": "FEMALE",
    "politicalStatus": "LEAGUE_MEMBER",
    "eduLevel": "ACADEMIC_MASTER",
    "birthDate": "2001-01-01",
    "intentionCity": "[\"上海\",\"北京\"]"
  },
  "resumePatch": {
    "education": "...",
    "internship": "...",
    "skills": "..."
  },
  "warnings": []
}
```

规则：

- Python 通过带服务 Token 的 Java Internal API 下载文件。
- 不接受任意 URL、本地路径或无上限 base64。
- 空字段不进入 patch。
- 响应不包含原始简历全文。
- 日志不记录姓名、电话、邮箱、简历文本和 LLM 原始响应。

### 5.4 内部服务 Token

新增环境变量：

```text
INTERNAL_SERVICE_TOKEN=<random-secret>
```

- Java 访问 Python 带 `X-Internal-Token`。
- Python 访问 Java `/internal/**` 带同一 Header。
- Java 对全部 `/internal/**` 校验 Token。
- Python `/api/internal/**` 使用 `secrets.compare_digest`。
- Nginx 不向浏览器暴露 Python internal API。
- Token 只存在服务器 `.env`/密钥管理，不写 Git、日志或前端。

## 6. 公众号爬虫重构与岗位管道

### 6.1 账号模型

删除按行关联的 `gzh.txt + 公众号名字`。使用 Python 标准库 SQLite：

```text
accounts
- fakeid TEXT PRIMARY KEY
- name TEXT
- enabled INTEGER
- last_article_url TEXT
- updated_at TEXT

articles
- url TEXT PRIMARY KEY
- fakeid TEXT
- title TEXT
- published_at TEXT
- markdown_path TEXT
- content_hash TEXT
- structured INTEGER

runs
- id TEXT PRIMARY KEY
- mode TEXT
- started_at TEXT
- finished_at TEXT
- status TEXT
- added_count INTEGER
- error TEXT
```

公众号名称从第一篇文章 HTML 的 `nickname`/`profile_meta_nickname` 自动识别，写回 `accounts`。人工名称作为覆盖值。未识别时使用 `account_<fakeid末8位>`，不再使用可冲突的 `Unknown_Account`。

### 6.2 统一抓取函数

用一个 `crawlAccount` 取代重复的 bootstrap/daily/archive 主体：

```text
crawlAccount(account, limit)
```

- 无 checkpoint：抓取最新 `limit` 篇并建立状态。
- 有 checkpoint：翻页到已知 URL 后停止。
- 无限 watch 由 Scheduler 取代，不在 Skill 内 `while True`。
- 全量 archive 不进入首期生产功能；确有历史数据需求时才加 `limit=None`。

每篇文章的原子顺序：

1. 下载文章。
2. 转换 Markdown。
3. 原子写入 Markdown。
4. 写入 `articles`。
5. 更新 account checkpoint。
6. 调用岗位结构化。
7. 结构化成功后标记 `structured=1`。

在文件写入/状态提交失败时不推进 checkpoint，防止永久漏文。

### 6.3 自动岗位写入

- 结构化结果统一设置 `sourceType=CRAWL`、`status=OFFLINE`。
- Python 复用 `BackendClient.createJobPosts`调用 Java batch API。
- 上传前使用 `(sourceUrl, companyName, positionName)` 与 Java 现有岗位比对。
- 单 Scheduler/单 Agent 是首期上限；如未来多实例并行，再在 Java 增加持久化 fingerprint 唯一约束。
- 结构化失败不影响 Markdown 存档，保留重试状态。

## 7. Java 接入计划

### 7.1 Python 客户端

扩展已有 `PythonServiceClient`：

```text
structureJob
parseResume
readHealth
```

加入的 DTO：

```text
JobStructureRequest
JobStructureResponse
ResumeParseRequest
ResumeParseResponse
ResumeUploadResponse
```

RestClient 统一添加 `X-Internal-Token`，读取超时首期设为 120 秒。

### 7.2 简历上传流程

调整：

```text
POST /user/resume/file/upload
multipart:
- file
- updateProfile: true|false
```

Response：

```json
{
  "file": { "id": "...", "originalName": "resume.pdf" },
  "parseStatus": "SUCCESS",
  "updatedProfileFields": ["realName", "major", "eduLevel"],
  "updatedResumeFields": ["education", "internship", "skills"],
  "message": "已上传并更新资料"
}
```

增加重试：

```text
POST /user/resume/file/{fileId}/parse
```

业务规则：

- Java 先校验文件所有权，再调 Python。
- 不勾选时只上传，不调算法。
- 只将 Python 非空 patch 合并到现有记录，未识别字段保持不变。
- 识别到的字段覆盖旧值；响应明确返回已更新字段。
- Python/LLM 失败时保留已上传文件，`parseStatus=FAILED`，用户可重试；不回滚或删除文件。
- profile 与 resume patch 在 Java 内一个数据库事务中更新。
- 枚举、日期、城市 JSON 由 Java DTO 再校验。

首期使用同步请求和前端 loading；实测 P95 超过 30 秒或经常超时时，再增加异步任务表/轮询，首期不建任务队列。

### 7.3 管理员岗位结构化

新增角色保护接口：

```text
POST /admin/job-post/structure
```

Request：

```json
{
  "text": "粘贴的岗位描述",
  "sourceUrl": ""
}
```

Response：`R<List<JobPostRequest>>`。

该接口不写库。管理员编辑结果后再使用已有：

```text
POST /admin/job-post       # 保存草稿/创建
PUT  /admin/job-post/{id}  # 编辑/发布
```

一文多岗时返回多条，前端允许切换待编辑条目。

## 8. 前端接入计划

### 8.1 学生简历上传

在已有上传区增加：

```text
☑ 使用简历解析结果更新我的资料和在线简历
```

行为：

- 默认不勾选，避免无意覆盖。
- 勾选后明确告知简历内容将发送给配置的 LLM 服务用于解析。
- 上传与解析期间禁用重复提交。
- 成功后刷新资料、在线简历、文件列表和配额。
- 提示实际更新字段数。
- 解析失败但文件上传成功时，显示“文件已保存，资料更新失败”及重试入口。

### 8.2 管理员岗位粘贴

在新建岗位页面顶部增加一个原文文本框和按钮：

```text
[粘贴岗位原文........................]
[智能识别并填充]
```

行为：

- 调用结构化接口后填充已有 `nj` 表单，不新建另一套岗位表单。
- 不自动保存或发布。
- 多岗位时显示“已识别 N 个岗位”和切换器，切换前保留编辑。
- 对未识别和高风险枚举显示 warning。
- 管理员仍必须点击“保存草稿”或“发布”。

## 9. 依赖与镜像计划

### 9.1 直接复用已有依赖

- `fastapi`
- `uvicorn`
- `httpx`
- `openai`
- `pydantic`
- `pydantic-settings`
- `pdfplumber`
- `requests`
- `apscheduler`

### 9.2 需增加

- `python-docx`
- `lxml`
- `Pillow`
- `numpy`
- `paddlepaddle==3.1.1`
- `paddleocr==3.1.0`
- `paddlex>=3.1.0,<3.2.0`
- `python-multipart`（仅当 Python 直接收 multipart 时；推荐 fileId 流程后可不加）

不增加 `openpyxl`、`aiohttp`或第二套 Web 框架。

### 9.3 Python 版本

统一使用 Python 3.11，同时满足当前 Agent `>=3.11` 和算法仓库 `>=3.10` 要求，优先使用 Paddle 有完整 wheel 支持的版本。

镜像构建门禁：

```text
linux/amd64 build success
import paddleocr success
OCR 小样本 success
PDF 文本提取 success
Agent /api/health success
```

如 Paddle 使基础镜像过大，先保留同一服务，通过 OCR extra/多阶段构建减少重复；不立即拆第二个微服务。

## 10. 数据与隐私

- 简历包含个人信息，勾选 AI 解析前显示简明告知。
- 只将用户选择解析的简历内容发送给 LLM。
- 不在 Agent workflow `input_snapshot`、日志、错误响应或调试报告中写入简历全文。
- 临时文件使用独立目录并在 `finally` 删除。
- 限制解析文本长度、页数、图片像素和并发数。
- 不信任简历或岗位文本中的指令；提示词要求模型只抽取事实。
- 微信 cookie/token 放挂载密钥文件或环境变量，不进 SQLite、Git 或日志。

## 11. 测试策略

### 11.1 Python 单元测试（不访问网络）

#### 岗位

- 示例 Markdown 一文两岗。
- 空数组表示非招聘文章。
- 中文/英文枚举归一化。
- 大类/子类修复。
- 必填字段缺失 warning。
- 篇内与跨文去重。
- 默认为 `OFFLINE`。
- LLM 非法 JSON、超时和限流。

#### 简历

- 可提取文本 PDF。
- 纯图 PDF/图片 OCR。
- 非法扩展名和超限文件。
- snake_case 转 camelCase。
- 数字枚举转 Java 常量。
- `YYYY-MM` 转 `YYYY-MM-01`。
- 空字段不进入 patch。
- LLM 非法 JSON、超时和敏感信息日志检查。

#### 爬虫

- 账号名自动识别与人工覆盖。
- 翻页遇 checkpoint 停止。
- 重复 URL 不再下载。
- Markdown 写入失败不推进 checkpoint。
- 会话过期返回明确错误。
- Scheduler 重启后任务恢复。

### 11.2 Java 测试

使用 MockBean/本地假 Python Client，不调用真实 LLM：

- 未勾选时不调 Python。
- 勾选时校验文件属于当前用户。
- 非空 patch 合并，空字段不覆盖。
- profile/resume 事务回滚。
- Python 失败时文件仍存在且可重试。
- 普通用户不能调管理岗位结构化。
- 结构化只返回草稿，不写数据库。
- 错误/缺失 Internal Token 返回 401/403。

### 11.3 前端验证

- `npm run build`。
- 不勾选只上传。
- 勾选成功后资料刷新。
- 解析失败时正确区分“文件已上传”和“资料未更新”。
- 管理员可编辑结构化结果，不会自动发布。
- 多岗位结果切换正确。

### 11.4 本地端到端

1. 启动隔离 MySQL、Java dev、Agent dev 和 Vite。
2. 使用假 LLM 返回固定 JSON，不消耗真实 Key。
3. 学生上传样例 PDF 并更新资料。
4. 管理员粘贴样例岗位、编辑、保存草稿并发布。
5. 模拟公众号新文章，验证生成 `OFFLINE` 岗位。
6. 验证所有 ID 在 JSON 中保持字符串。

CI 禁止访问真实微信、DeepSeek、生产 Java 或生产文件。

## 12. 15 次原子提交计划

### C1 `test(agent): establish algorithm contract baseline`

- 固定上游提交和 MIT 来源记录。
- 迁入无敏感信息的 Markdown、PDF、LLM JSON 样本。
- 建立岗位和简历期望契约测试。
- 不迁入业务代码。

验收：当前 Agent 测试可重复执行，测试无网络。

### C2 `refactor(wechat): replace positional account files`

- 增加 SQLite `accounts`。
- 从现有两个文件提供一次性导入，之后不再读取。
- 自动解析和缓存公众号名称。
- 保留人工覆盖。

验收：fakeid/名称不会因行数错位，未知名不冲突。

### C3 `refactor(wechat): unify crawl state and scheduler flow`

- 增加 `articles/runs` 状态。
- 合并 bootstrap/daily 单账号主体。
- 删除 watch/cron 生产需求。
- 实现写文件后才提交 checkpoint。
- 保留已有 Scheduler/loop Skill。

验收：中断后重试不漏文、不重复。

### C4 `feat(agent): integrate job structuring core`

- 迁入提示词、映射、修复和去重核心。
- 改为内存 list 输入/输出。
- 复用异步 `LLMClient`。
- 默认 `OFFLINE`。
- 不带 CSV/XLSX/CLI/uploader。

验收：上游示例一文两岗测试通过。

### C5 `feat(agent): expose protected job structure API`

- 增加 `/api/internal/job/structure`。
- Token 鉴权、Pydantic 输入限制和响应 DTO。
- 返回 jobs + warnings，不写 Java。

验收：正常、非招聘、非法 JSON、缺 Token 全覆盖。

### C6 `feat(agent): integrate resume parser core`

- 迁入 PDF/图片/DOCX 提取器、OCR、提示词和归一化。
- 复用 `LLMClient`。
- 增加 snake_case/camelCase、枚举、日期、城市适配。
- 去除空值。

验收：PDF 样本和假 LLM 结果可生成 Java patch。

### C7 `feat(agent): expose protected resume parse API`

- 增加 `/api/internal/resume/parse`。
- 通过 BackendClient 下载指定用户文件。
- 限制文件大小/页数/像素，清理临时文件。

验收：跨用户 fileId、任意 URL、超限文件均被拒绝。

### C8 `feat(agent): create offline job drafts from crawl manifests`

- 微信新文章调用结构化核心。
- 去重后调 Java batch API。
- 写入成功才标记 structured。
- 只创建 `OFFLINE` 岗位。

验收：重跑不重复，结构化/上传失败可重试。

### C9 `fix(internal): authenticate Java Python service traffic`

- Java `/internal/**` 校验 `X-Internal-Token`。
- Python internal router 校验相同 Token。
- Java RestClient/Python BackendClient 添加 Header。
- test/dev/prod 配置分离。

验收：缺失/错误 Token 被拒绝，正确 Token 通过。

### C10 `feat(resume): parse upload and merge user data`

- 扩展上传接口 `updateProfile`。
- 增加解析重试接口。
- 校验文件所有权。
- 非空 patch 事务合并。
- 算法失败保留文件。

验收：Java/MySQL/JUnit 覆盖不勾选、成功、失败、重试和跨用户。

### C11 `feat(admin): bridge job structuring service`

- 增加 `/admin/job-post/structure`。
- ADMIN 角色保护。
- 调 Python 并二次校验 JobPost DTO。
- 不写库。

验收：普通用户 403，管理员得到可编辑草稿。

### C12 `feat(profile): opt into resume-driven data update`

- 增加默认不勾选的 checkbox。
- 上传 loading、隐私告知、成功字段和失败重试。
- 成功后刷新资料/简历/文件/配额。

验收：生产构建通过，不勾选时零算法请求。

### C13 `feat(admin): structure pasted job descriptions`

- 增加原文框和智能识别按钮。
- 复用已有岗位表单。
- 支持多岗位切换和 warnings。
- 不自动保存/发布。

验收：编辑后可使用原有草稿/发布流程。

### C14 `chore(deploy): ship algorithm runtime`

- Python 3.11 `linux/amd64` 镜像。
- 算法/OCR 依赖。
- Agent runtime、微信文章、SQLite、OCR 模型缓存持久卷。
- LLM、Internal Token、Java URL、微信会话配置。
- Nginx 取消公网 Agent internal 暴露。
- healthcheck 与资源限制。

验收：Agent 重建/重启不丢状态，Java/Python 双向调用通过。

### C15 `test(integration): add algorithm smoke tests and runbooks`

- 本地假 LLM 端到端脚本。
- 生产发布前 smoke。
- 简历隐私、密钥、日志和临时文件审计。
- 部署、会话更新、失败重试、回滚手册。

验收：所有完成标准由一个可重复命令验证。

## 13. 部署顺序

1. 备份 Python runtime/微信文章目录；不备份/上传 LLM Key 和 cookie 到 Git。
2. 在 Mac/CI 构建 `linux/amd64` Agent 镜像，运行 OCR/PDF/岗位样本。
3. 先部署 Python Agent，但不启用微信 Scheduler。
4. Python 机本地验证 `/api/health`和两个 internal API（假 LLM/小样本）。
5. 部署 Java Internal Token 与 Python Client，验证双向调用。
6. 部署简历/管理接口，先用 API smoke，不部署前端。
7. 部署前端，完成简历和岗位人工验收。
8. 手工运行一次微信 bootstrap 和一次 daily，检查草稿去重。
9. 最后启用 Scheduler。
10. 观察 24 小时的 LLM 错误、耗时、抓取新增、重复草稿和磁盘使用。

## 14. 回滚方案

- 前端回滚：去掉 AI 入口不影响原上传和手工新建岗位。
- Java 回滚：`updateProfile=false` 路径保持原上传行为；Python 不可用时不影响核心 Java API。
- Scheduler 回滚：先禁用 schedule，不删 SQLite/Markdown。
- Python 回滚：回退旧镜像并保留持久卷。
- 岗位回滚：算法自动生成的岗位默认 OFFLINE，可批量删除且不影响学生。
- 资料回滚：首期在更新前记录字段名和原值到受控审计数据（不记原简历全文）；如不建审计表，则 UI 必须明确覆盖风险。

## 15. 完成标准

- 桌面 `wechat_crawler.py.py` 和 `pipeline.sh` 不进生产镜像。
- Python Agent 是唯一算法/爬虫服务。
- 学生不勾选时不调用 LLM。
- 学生勾选后可解析简历，非空字段更新成功，失败可重试。
- 管理员可将粘贴文本转为可编辑岗位，算法不自动发布。
- 公众号新文章会生成不重复的 OFFLINE 岗位草稿。
- 微信账号不再依赖两个文件的行号对齐。
- Java/Python internal API 双向鉴权。
- 简历全文、cookie、LLM Key 不出现在日志、Git 和错误响应中。
- Python 单测、Java/JUnit/MySQL、前端构建、假 LLM 端到端和生产 smoke 全部通过。
- Agent/Java 重启后爬虫状态、Markdown、SQLite、调度和 OCR 缓存不丢失。

## 16. 实施前需固定的产品假设

本计划默认：

1. “更新我的资料”同时更新 `fc_user_profile` 和 `fc_resume`，但只覆盖算法识别到的非空字段。
2. AI 解析默认不勾选。
3. 管理员粘贴的一段文本可能包含多个岗位。
4. 抓取岗位默认 OFFLINE，必须人工审核。
5. 首期单 Agent 实例，不建分布式队列。
6. 首期同步 LLM 请求；用真实延迟数据决定是否异步化。
7. Java 现有上传格式 PDF/JPG/JPEG/PNG 是首期范围；DOCX 提取器可迁入，但不在前端开放直到文件白名单一致。

如上述任一假设改变，应在实现对应提交前修订本文档，不在代码中猜测。
