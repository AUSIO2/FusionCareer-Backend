# 算法仓库完整接入工作流引擎

基线：`chenxin1209/FusionCareer-Algorithm@72ff6b2b44520f78090958f0c3a7e74959189646`。
本说明取代旧集成计划中“只选择性迁入六月版本”的范围约定。

## 执行关系

```text
Java /api/internal/job/structure
  → job_structure 工作流
  → input → upstream_algorithm(job_structure) → normalize_job_result

Java /api/internal/resume/parse
  → resume_parse 工作流
  → input → download_resume → upstream_algorithm(resume_parse) → normalize_resume_result

管理员 /api/workflows/{name}/run 或 Scheduler
  → 同一个 WorkflowEngine → upstream_algorithm → 原仓库函数
```

job_structure 根据 sourceType 选择管理员短提示词或 Markdown 抽取。
官网/公众号现有采集工作流的岗位结构化步骤也进入 job_structure。
共享采集模块位于 `app/skills/business/crawlers/`，官网和公众号分别位于其 `official/`、`wechat/` 子目录；
目录配置优先使用 CRAWL_CONFIG_ROOT，旧 WECHAT_CONFIG_ROOT 仍兼容。
Java 接口鉴权、请求和响应格式兼容；简历归属检查仍通过 BackendClient。
预设来自 WorkflowCatalog，支持 runtime 同名覆盖；每次执行复制预设，
不会把用户正文写回共享预设。API 预设约定入口 input.json_obj、出口 result.result。

## 完整能力映射

Skill：`upstream_algorithm`，输入 operation:text、request:json_obj，输出 result:json_obj。
下表除 job_structure 外，预设名称为 `algorithm_` 加 operation。

| operation | 上游功能 | request 主要字段 |
|---|---|---|
| job_structure | 按 PLATFORM/CRAWL 分流 | text, sourceType, sourceUrl |
| job_admin | 管理员短提示词、学院内推、大/小实习、日期补年、表单回填、多岗 | text |
| job_markdown | Markdown 抽取、标题过滤、汇总拆岗、映射、篇内/CSV去重 | text + sourceUrl，或 file |
| job_batch | 递归批量 Markdown、共享去重索引 | directory，或 documents 字符串数组 |
| job_deduplicate | 存量 CSV 去重 | workspace |
| job_export_json | CSV → JSON | workspace |
| job_export_xlsx | CSV → Excel，表头/冻结/列宽 | workspace |
| job_upload | 文件/远端/本地索引去重、分批上传 | workspace, json/csv, dry_run, fetch_existing |
| resume_parse | 文本/PDF/DOCX/图片/OCR、清洗、提取、token/耗时指标 | raw_text，或 file/file_base64/file_url；filename 可指定扩展名 |
| resume_batch | 简历批量 CSV | workspace, files, output |
| wechat_bootstrap | 每号最新 N 条、Markdown、history | workspace, accounts |
| wechat_daily | 增量、日期镜像、日报 JSONL 和 Markdown | workspace, accounts |
| wechat_archive | 一次完整存档检查 | workspace, accounts |
| wechat_sync_session | 从已登录浏览器通过 CDP 同步并校验凭证 | workspace, cdp_url |

`algorithm_job_pipeline` 串联批量抽取 → 去重 → JSON → Excel。
公众号 watch 由 Scheduler 重复执行 algorithm_wechat_archive；原 shell 的
install-cron 由 Scheduler cron trigger 承担。交互式扫码在操作员浏览器完成，
节点通过 CDP 同步，不在服务端等待终端 Enter。

原仓库的 JSON 修复、公司名清洗、枚举映射、日期补年、LLM 完整输入输出日志、
token/耗时指标均由原函数执行。

## 调用示例

请求都使用现有 `X-Agent-Admin-Token` 请求头。

`POST /api/workflows/algorithm_job_admin/run`：

```json
{"overrides":{"input.json_obj":{"workspace":"admin-review","text":"学院内推：某公司招聘编辑实习生，12月31日截止。"}}}
```

`POST /api/workflows/algorithm_job_pipeline/run`：

```json
{"overrides":{"input.json_obj":{"workspace":"job-import","documents":["# 招聘岗位汇总\n某公司招聘编辑、记者……"]}}}
```

`POST /api/workflows/algorithm_job_upload/run`：

```json
{"overrides":{"input.json_obj":{"workspace":"job-import","dry_run":true}}}
```

需要写入时设置 dry_run:false。默认预览不写远端或保存上传索引；其 uploaded
计数沿用上游含义，表示模拟可上传条数。实际上传强制 OFFLINE 草稿。

`POST /api/workflows/algorithm_resume_parse/run`：

```json
{"overrides":{"input.json_obj":{"raw_text":"张同学，新闻学，掌握 Python。"}}}
```

该接口返回上游原始 record 和 metrics；Java 原 /api/internal/resume/parse
仍只接收 userId/fileId，并返回规范化补丁，不开放任意 URL 下载。

公众号请求（同步会话和采集必须使用同一个 workspace）：

```json
{"overrides":{"input.json_obj":{"workspace":"wechat-daily","accounts":[{"fakeid":"实际fakeid","name":"就业公众号"}],"config":{"bootstrap_article_limit":10}}}}
```

也可用 WECHAT_TOKEN、WECHAT_COOKIE 环境变量提供凭证。
Scheduler 示例（PUT /api/admin/schedules/upstream-wechat-daily）：

```json
{"id":"upstream-wechat-daily","workflow":"algorithm_wechat_daily","enabled":true,"trigger":{"type":"cron","cron":"0 17 * * *"},"overrides":{"input.json_obj":{"workspace":"wechat-daily","accounts":[{"fakeid":"实际fakeid","name":"就业公众号"}]}}}
```

示例不会自动创建。watch 将 workflow 改成 algorithm_wechat_archive，trigger
改为 `{"type":"interval","minutes":60}`。

## 文件、状态、隔离

- 文件位于 `$AGENT_RUNTIME_DIR/algorithm/<workspace>/`；未指定 workspace 时生成独立 ID。
- 批处理、导出、上传、采集需复用同一 workspace；通用预设默认 workspace=default。
- 文件参数必须位于当前 workspace 内；简历最大 20 MB。
- 岗位 CSV/JSON/XLSX 在 data/output，日志在 logs，公众号状态在 workspace 根目录。
- 密钥通过子进程 stdin 注入，不保存到预设、命令行或岗位配置文件。
- 独立进程隔离上游全局路径、同步客户端和 OCR 单例；同 workspace 文件锁串行，其他可并行。
- 总超时默认 1800 秒，可用 ALGORITHM_TIMEOUT_SECONDS 配置；引擎节点可设更短 timeout_seconds。
  取消/超时终止 worker，不让后台继续写入。
- OCR 每个 worker 首次加载模型，有冷启动开销；下载缓存复用，批量简历在单 worker 内复用模型。
- 完整模型输入输出日志沿用上游行为，存于私有运行目录；删除旧 workspace 可清理日志/产物。

## 适配边界

1. 上游 tracked 文件保持原样，PROVENANCE.json 记录完整校验哈希。
2. HTTP 服务只有 Agent，上游独立 HTTP/CLI 的功能通过工作流暴露。
3. Java 字段沿用规范化层；applyDeadline → applicationDeadline；上传强制草稿并附服务间 Token。
4. 上游岗位 LLM 返回 None/无效 JSON 改为节点失败；公众号正常下载异常恢复 history/wx_poc。
   岗位查重与写入统一使用清洗后的公司/岗位名，避免招聘后缀造成重跑重复；CSV 写入失败也会使节点失败。
5. 强制结束进程/掉电不保证多文件事务，需要重跑并核对采集状态。
6. 六月实现保留给显式注入 Python LLMClient 的兼容调用/旧测试；生产 HTTP 和默认采集走新工作流。

## 构建验证

Python 3.11+；新增 openpyxl、playwright。生产镜像仍以 `.[ocr]` 安装 Paddle。
CDP 同步使用已运行浏览器，不要求容器安装 Chromium。若单独运行上游桌面交互脚本，
按其文档安装 Chromium。

```bash
cd fusioncareer-agent
python -m pip install '.[dev]'
python -m pytest -q tests
```

测试覆盖真实引擎、上游子进程、模拟 HTTP、Java 契约、日期/内推提示词、简历文件和文本、
批量 CSV、JSON/Excel、上传去重和鉴权、公众号状态恢复和源码哈希。
真实大模型效果、生产微信会话、真实 Paddle 推理仍需部署环境联调。
