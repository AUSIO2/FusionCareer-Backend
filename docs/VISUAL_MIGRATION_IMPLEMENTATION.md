# 2026-09-30 视觉与 Agent 迁移实施说明

状态：视觉与首版 Agent 已于 2026-09-30 部署，详见 [生产部署记录](DEPLOYMENT_VISUAL_AGENT_2026-09-30.md)。当前待发布算法候选固定为 `FusionCareer-Algorithm@1f3d8a7a7d244487294a3793658e625d4d9086f1`；历史部署记录保留当时的 `81c51a6`。前端代码在同级 `FusionCareer-View/ui_kits/student`，视觉参考为 `daixintongtt-code/FusionCareer-View-1@7b68fea`。

## 已实现

- 复用源前端的悬浮球、面板、分层选择器和岗位卡视觉；保留当前正式登录、复旦校徽、姓名显示修复及既有页面功能。
- 同一 Assistant 会话接入真实 Java SSE，支持文本、附件、停止、清空、历史分页和断流状态恢复。客户端不保存聊天业务数据。
- 推荐按钮以结构化 interaction 进入同一 Agent；自然语言推荐使用 `recommend_jobs`。Java 按签名上下文查本人资料和有效岗位，Python 执行固定版本规则及可选模型排序。
- 多分类分支、多城市、多关键词全文查询；非发布、已截止和回收站岗位不进入候选。岗位所有城市传给算法，学院推荐字段适配为 upstream 的 weeklyRecommend。
- 消息持久化 input_metadata 和 presentation。结构化请求参与幂等检查；岗位卡只保存引用，读取时重新查询可见性；提案卡只保存属于当前用户/run 的 actionId。
- 前端接通安全确认卡、确认/拒绝、个人空间 Memory、修改历史、回退/冲突字段选择、文件回收站和对话重置。Profile/Resume 使用带 expectedVersion 的 PATCH。
- 预筛、推荐准备/对话/排序、源统计、WeRead 登录/初始化/增量均有算法 operation 与预设。现有解析、导出、上传及官网/旧公众号流程保留。
- CrawlStore 增加预筛原因/版本审计，可按词表版本重新排队；源统计按文章 URL 和 Java 岗位 ID 去重，避免日报/镜像目录重复计数。
- WeRead 提供异步扫码启动、状态读取和鉴权二维码下载；采集成功的文章登记到现有 CrawlStore。凭证文件不可由导出接口读取，失败恢复采集游标。

收藏业务未启用。岗位卡不显示无依据的百分比。分层选择的永久保存仍遵守既有 Profile 字段和长度约束，细分新业务模型不在本次迁移中。

## Tool 超时配置

在 Agent 环境中设置；修改后重启/重建 Agent 生效。单位均为秒，必须为有限正数。

```dotenv
# 优先级最高：指定 Tool 名称覆盖默认值
AI_CHAT_TOOL_TIMEOUTS={"recommend_jobs":110,"search_jobs":8,"parse_resume_file":90}
AI_CHAT_RECOMMEND_ENABLED=true

# 未命中名称覆盖时：专用值优先，再按读/写分类
AI_CHAT_RECOMMEND_TIMEOUT_SECONDS=110
AI_CHAT_PARSE_TIMEOUT_SECONDS=90
AI_CHAT_READ_TOOL_TIMEOUT_SECONDS=3
AI_CHAT_WRITE_TOOL_TIMEOUT_SECONDS=15

# 对话总时限独立存在，包含所有 Tool 与模型回复
AI_CHAT_RUN_TIMEOUT_SECONDS=150
RECOMMENDATION_LLM_TIMEOUT_SECONDS=15
RECOMMENDATION_LLM_ENABLED=true
# 2026-10-02 发布决定：两项均启用，先用测试账号验收冷缓存
RECOMMENDATION_COMPANY_SCORE_ENABLED=true
RECOMMENDATION_AUDIT_LOG_ENABLED=true
```

不配置 `AI_CHAT_TOOL_TIMEOUTS` 时使用专用/分类默认值；配置 `{}` 仅清除名称覆盖。一个 Tool 的整体 asyncio deadline 覆盖内部重试与子进程。Java Tool HTTP 请求使用同一 Tool 预算，避免配置长解析时仍被 Python HTTP 客户端默认 30 秒截断。

Java 对应环境变量：

```dotenv
AI_CHAT_UPSTREAM_TIMEOUT_SECONDS=180
AI_CHAT_STREAM_TIMEOUT_MS=200000
AI_CHAT_RUN_LEASE_SECONDS=240
AGENT_CONTEXT_TTL_SECONDS=300
PYTHON_SERVICE_READ_TIMEOUT_MS=60000
```

延长单轮处理时间时，同时调整 Java HTTP、SSE、租约、AgentContext 有效期和 Nginx `proxy_read_timeout`。`PYTHON_SERVICE_READ_TIMEOUT_MS` 控制 Java 对普通解析接口的等待，与对话 SSE 时限不同。不要把一个 Tool 的超时设得比单轮总时间还长并期待它生效。

Java 和 Agent 的 AI_CHAT_RECOMMEND_ENABLED 保持一致；设为 false 隐藏推荐能力并阻止内部推荐查询，普通聊天与搜索继续可用。重排失败/超时时仅降级为真实候选的规则排序；候选查询失败返回工具错误，不伪装为空结果。推荐交互不落盘完整 LLM 输入输出；上游推荐审计按本轮发布决定启用，并只保存在私有持久卷。最新算法的技术岗过滤、同公司去重和推荐理由不依赖市值或审计开关，可在异常时单独关闭两项。

## 消息协议

继续使用 `POST /personal-space/assistant/messages/stream`，经前端 `/api` 网关访问。

```json
{
  "clientRequestId": "unique-client-request",
  "content": "帮我推荐上海的媒体岗位",
  "fileIds": [],
  "interaction": {
    "schemaVersion": 1,
    "type": "job_recommendation",
    "preferences": {
      "jobCategories": ["MEDIA"],
      "workCities": ["上海"],
      "keywords": ["视频剪辑"]
    }
  }
}
```

interaction 可省略。可选 jobId 为详情页上下文，由 Java 校验当前岗位可见性。普通自然语言请求仍完全兼容。

新增 `job_results` SSE 的 data 为 `{"runId":"...","presentation":{...}}`，包含 Java 重新查询后的 jobs 安全投影。确认提案沿用 action_proposed；历史消息新增 inputMetadata/presentation。capabilities 返回 readTools 中的 recommend_jobs 及 presentationTypes。客户端正确处理 Java done 不含正文、delta.snapshot 替换快照、长整型 ID 字符串等现有契约。

## 管理员算法入口

所有以下 Agent 管理操作使用 `X-Agent-Admin-Token`，不由学生前端直接调用。

- `POST /api/admin/algorithm/{workspace}/weread-login`：启动登录并返回 202。
- `GET /api/admin/algorithm/{workspace}/weread-login`：查询状态与 qrReady。
- `GET /api/admin/algorithm/{workspace}/artifact?path=weread-login.png`：下载有效期内的二维码。
- `GET /api/admin/algorithm/{workspace}/artifact?path=data/output/source_stats/daily_source_stats.csv`：下载统计；同目录 source_totals.csv 也可下载。
- `POST /api/admin/algorithm/prefilter/requeue?version=<16位词表哈希>`：只重新排队指定版本预筛跳过的文章。

新增预设通过现有 `POST /api/workflows/{name}/run` 运行：

```text
algorithm_job_prefilter
algorithm_job_recommend_prepare
algorithm_job_recommend_turn
algorithm_job_recommend_rank
algorithm_job_source_stats
algorithm_weread_login
algorithm_weread_bootstrap
algorithm_weread_daily
```

WeRead 登录和采集使用同一 workspace，采集传 `accounts:[{"fakeid":"...","name":"..."}]`。配置 CRAWL_CONFIG_ROOT/WECHAT_CONFIG_ROOT 后，采集文档加入统一待处理库，由现有结构化工作流生成草稿/回收站记录。当前 WeRead 自身的抓取窗口限制保持上游行为，不保证补全任意长度的离线历史。

源统计默认读取配置的采集根目录，并从 Java 读取岗位；workflow 可显式传 jobs 和 workspace 内 directory 进行 fixture/离线统计。上游原始脚本仍完整保存在 vendor，生产 adapter 适配 crawl.db 和去重口径。

## 发布准备与顺序

1. 为当前线上 Java、Agent、前端和 runtime 工作流覆盖项记录版本并备份；本地分支还有其他已存在的修改，不能整目录无差别发布。
2. **已有数据库**执行 `fusioncareer-biz/src/main/resources/db/migration/V20260930__ai_message_presentation.sql` 一次。新数据库的 schema.sql 已含新列，不重复执行该 ALTER。
3. 更新 Agent，检查词表、XLSX、qrcode 和 OCR 依赖均已打包；核对同名 runtime workflow 是否覆盖新 preset。
4. 更新 Java，再发布前端。先检查 capabilities、既有解析和普通聊天，再验收推荐卡及修改确认。
5. 预筛开关为 PREFILTER_ENABLED，阈值为 PREFILTER_MIN_HITS；生产启用前对代表性文章抽检。WeRead 需真实管理员扫码验证后再配置 Scheduler。
6. 回滚先关闭新增任务/入口，再回退兼容版本；保留新列和持久卷，不删除运行数据。

源前端 node_modules、静态登录逻辑、localStorage 收藏和固定推荐会话未迁入。

## 已执行验证

- Python 全量 168 项通过，覆盖原算法/工作流、源码哈希、推荐、超时配置、管理接口边界与对话协议。
- Java 在新建隔离 MySQL 库中全量 148 项通过；随后增加并通过了提案引用归属测试，当前测试报告合计 149 项无失败。
- 前端 21 项 Node 测试通过、生产构建通过、独立临时目录 npm ci 成功。
- Playwright 使用固定数据验证推荐选项、请求体、SSE 卡片、刷新恢复、确认卡、个人空间及 1440/1024/390/320 宽度。
- 真实浏览器 → Java → Python → 推荐 worker 链路验证通过；模型由本地 fixture 替代。另验证幂等重放与结构化请求变化冲突。
- Compose 配置解析与 Nginx 配置检查通过。
- Linux amd64 生产镜像构建通过；以非 root 用户验证安装包全部 88 个上游文件哈希，OCR、QR、Agent 与 WeRead 依赖导入通过。
- 最终镜像实际启动并通过 `/api/health` 检查；容器中的 `AI_CHAT_TOOL_TIMEOUTS` 覆盖生效，`algorithm_job_prefilter` HTTP 工作流加载 128 条词并成功返回审计版本。

后续部署轮次已完成生产数据库迁移、真实模型推荐/回复、确认提案和合成图片解析验收。WeRead 真实扫码会话与长期推荐效果评估仍需后续使用验证，未新建 WeRead 调度。
