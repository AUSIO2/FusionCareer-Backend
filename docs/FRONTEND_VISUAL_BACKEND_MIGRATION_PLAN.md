# FusionCareer 前端视觉与 Java/Python 能力整合迁移计划

日期：2026-09-30，算法基线于 2026-10-02 更新。视觉与首版 Agent 已部署，生产验收见 [部署记录](DEPLOYMENT_VISUAL_AGENT_2026-09-30.md)；最新算法仍处于发布准备阶段，实现细节见 [迁移实施说明](VISUAL_MIGRATION_IMPLEMENTATION.md)。

## 1. 目标与迁移边界

采用代馨彤前端的视觉设计，在当前正式工程中接通已有 Java 业务和 Python Agent，并按已确定方向把算法仓库能力封装为增强 Tool。

前端迁入范围是布局、样式、图标、动效、组件视觉及展示交互。展开、收起、拖动、标签搜索、键盘操作等纯 UI 行为可以复用；登录、API、角色判断、数据保存、会话、推荐排序和发布策略以当前后端为准。

不以替换整个仓库、整页覆盖或整体合并旧目录的方式实施。收藏的本地存储逻辑、固定三步推荐状态机、上游 API 地址、演示登录和客户端业务规则不随视觉迁移进入正式工程。第三层偏好词表也不自动成为新的业务分类。

本计划同时覆盖之前已讨论的算法 main 升级；后台采集能力与学生界面分别验收、分别启用。收藏服务、全新偏好数据模型属于另外的业务扩展，本次不因前端出现对应按钮而自动新增。

## 2. 固定基线与事实依据

| 用途 | 仓库或工程 | 本次核对的基线 |
|---|---|---|
| 前端视觉来源 | `daixintongtt-code/FusionCareer-View-1` | `main@7b68fea40b730a143481956b61fc4b2973e910d0` |
| 9/29 分层偏好来源 | 同上 | `719e3fe96aaea01442c316e54b931cf6e0d01bcb` |
| 9/29 推荐界面来源 | 同上 | `7b68fea` |
| 9/28 悬浮球、收藏视觉来源 | 同上 | `845b272996473cfd906059b60d1ffc0f8b0ed0eb` |
| 正式前端迁入目标 | `FusionCareer-View/ui_kits/student` | 本地集成分支 `32f8730` 及已登记的未提交修改 |
| Java/Python 能力来源 | 当前 `FusionCareer-Backend` | 本地 `database@18b1d1d` |
| 算法迁入目标 | `chenxin1209/FusionCareer-Algorithm` | `main@1f3d8a7a7d244487294a3793658e625d4d9086f1` |
| 当前 vendor 基线 | `fusioncareer-agent/app/vendor/fusioncareer_algorithm` | `1f3d8a7a7d244487294a3793658e625d4d9086f1` |

以上是本地源码与远端提交核对结果，不等于生产服务器已部署版本。发布前必须分别记录 Java、Agent、前端线上镜像/产物版本和数据库迁移版本。

前端视觉源在 `Desktop/FusionCareer Design System_V4/`，目标在 `ui_kits/student/`。9/29 共两个提交；9/28 的悬浮球是它们的依赖。当前目标已经有 `JobCategoryIcon.vue`、多城市/学历显示、姓名修复和复旦红色校徽，按差异迁入，保留现有登录页和品牌成果。

当前 Java 已实现单用户单 Session、消息、摘要、租约恢复、Memory、版本化个人空间、确认/拒绝提案、跨资源简历解析提案、文件恢复、冲突字段回退及结构化岗位消息。Python 已实现有超时和并发限制的 Tool Loop、流式回复、增强推荐 Tool、算法子进程、工作流和 Scheduler。

## 3. 最终职责

| 层 | 负责内容 | 状态归属 |
|---|---|---|
| Vue 展示组件 | 悬浮球、面板、气泡、输入框、标签、结果卡、确认卡、加载/错误展示 | 开关、拖动位置、尚未提交的表单草稿 |
| 前端 composable/API adapter | 调用当前 Java API、解析 SSE、刷新资源、显示冲突 | 已获取数据的可丢弃缓存 |
| Java | 用户身份、权限、候选岗位查询、个人空间、消息和展示数据保存、提案执行 | MySQL 中的业务事实和用户会话 |
| Python Agent | 单一对话编排、关键词理解、Tool 调用、模型回复 | 请求内临时上下文 |
| Python 算法/Workflow | 预筛、岗位结构化、简历解析、排序、采集、统计、导出 | 现有 runtime、CrawlStore、持久卷 |

学生请求链路：`展示组件 → Java Assistant SSE → Python Agent → recommend_jobs → Java 受控候选查询 → Python 算法排序 → Java 校验并保存岗位卡 → 展示组件`。

`recommend_jobs` 的 Python 编排直接调用受控 Java 查询和现有算法执行器；避免在 Java 的同名 Tool 处理中再同步回调同一条 Python 对话请求。数据库仍只由 Java 访问。

## 4. 前端视觉迁入清单

| 来源 | 迁入内容 | 接线方式 |
|---|---|---|
| `AIAssistant.vue` 悬浮入口 | 外观、拖拽、阴影、面板定位、开合动效 | 一个应用级 Assistant 状态实例；首页、详情、个人中心共享 |
| 推荐选择区 | 胶囊按钮、城市搜索、多选、已选标签、加载和重试 | 作为一次推荐请求的草稿输入；提交进入正式 Assistant 的同一 Session |
| 岗位结果卡 | 排版、公司/地点、原因区域、详情入口 | 接收后端结构化安全岗位数据；点击时再次由后端检查可见性 |
| `MultiLevelTagPicker.vue` | 分层导航、搜索、标签、选择/删除、响应式样式 | `props + emits`；选项和值转换由正式业务 adapter 提供 |
| 收藏按钮与列表 | 心形按钮、列表卡片、空态的组件样式 | 可以保存为组件；当前无收藏服务，生产入口不启用，不迁入 `favorites.js` |
| 岗位列表、详情、个人中心 | 与上述组件相关的布局和视觉增量 | 局部替换 template/CSS；复用现有岗位、问卷、简历 API |
| 图标和版权说明 | 新增且未迁入的 SVG/版权材料 | 与已有 `JobCategoryIcon.vue` 去重，保留来源说明 |

不把源仓库 `App.vue`、router、auth、api、`ProfileView.vue` 的保存函数和全局 CSS 整体覆盖目标。新增 CSS 使用组件作用域或明确前缀，沿用目标 CSS tokens，防止影响管理员页面和登录页。

建议组件拆分为 `AssistantLauncher`、`AssistantPanel`、`AssistantMessages`、`AssistantComposer`、`RecommendationPreferences`、`JobResultCard`、`ActionConfirmationCard`。组件只展示输入、发出事件，业务请求统一交给 `useAssistant`、`usePersonalSpace` 和 API 模块；不引入新的状态框架作为前置条件。

### 4.1 偏好选择的兼容规则

当前 PersonalSpace 的 `intentionOrder` 是最多 64 字符的字符串，数据库为 `VARCHAR(64)`；新的 PATCH 契约中 `intentionCity` 接收数组。旧 `/user/profile/save` 使用的 JSON 字符串不能直接照搬到新 PATCH 请求。

本次保留分层选择器视觉，永久保存的值仍符合现有字段含义：

- 就业意向按现有大类及顺序保存；保持已有顺序，前端显示超限错误，不截断值。
- 城市保存兼容现有意向地区值。省/市层级是导航；默认选叶子城市。若要支持省级范围筛选，必须明确映射为省份条件，不能把省份当作一座城市。
- 新增的细分岗位标签可作为本轮推荐关键词，但不自动扩充用户资料数据模型，也不把父/子项压成不受限制的长字符串。
- 读取兼容历史 JSON 字符串、数组和已有分隔文本；不识别的历史项原样显示、保留，等待用户明确编辑，不自动删除或改写。
- “都可以”在本轮推荐表示该维度无约束；空选、未填写与明确清空在保存时分别处理，使用 `set/clear` 表达意图。
- 本轮筛选、Profile 长期意向、Agent Memory 是三个不同用途。点击推荐不修改 Profile/Memory；只有点击保存或明确要求 Agent 记住时才走相应写入口。

若后续决定永久保存多级业务分类，需要另加有版本的偏好 schema、数据迁移和兼容规则；这不作为本次视觉迁移的隐含要求。

## 5. 已有 Java 能力如何完整展示

以下路径是应用路径，浏览器仍通过目标工程的 `/api` 网关前缀和 `Fusion-Token` 访问。

| 界面能力 | 已有接口 |
|---|---|
| 启动能力探测 | `GET /personal-space/assistant/capabilities` |
| 恢复唯一会话、分页历史 | `GET /personal-space/assistant/session`、`GET /personal-space/assistant/messages` |
| 发送文本和附件引用 | `POST /personal-space/assistant/messages/stream` |
| 停止、清空、重置 | `POST .../run/cancel`、`POST .../session/clear`、`DELETE .../session` |
| 个人资料、在线简历 | `GET/PATCH /personal-space/profile`、`GET/PATCH /personal-space/resume` |
| 文件和回收站 | `GET /personal-space/documents`、`GET .../documents/deleted`、`DELETE .../documents/{id}`、`POST .../documents/{id}/restore` |
| 上传、下载 | 继续复用 `/user/resume/file/upload`、`/user/resume/file/{id}/download` |
| 投递记录 | `GET /personal-space/applications`；答题/保存/提交复用现有问卷服务 |
| 长期记忆 | `/personal-space/memory` 及 `/memory/{key}` 的现有读写/删除接口 |
| 变更历史、安全确认卡 | `GET /personal-space/actions`、`GET .../actions/{id}`、`GET .../actions/{id}/confirmation` |
| 确认、拒绝、回退与解决冲突 | `POST .../actions/{id}/confirm`、`/reject`、`/revert`、`/revert/resolve` |

前端按 `capabilities` 渲染写工具入口和限制，服务端仍是权限判断方。`configured` 当前只反映部分配置存在，不能当作 LLM 连通或端到端健康证明；Java/Python 写开关必须在上线门禁中核对一致。

资料/简历编辑通过 `expectedVersion + set/clear` 保存，只提交用户修改字段；遇到版本冲突重新读取并展示差异。保留完整 16 个 Profile 可写字段、9 个 Resume 字段、文件配额和投递状态，不因为源页面字段较少而丢失能力。

Agent 发起的修改继续先生成 PENDING 提案；确认卡从 Java confirmation 接口读取 `beforeDisplay/afterDisplay` 等安全投影。口头“确认”、选择城市、关闭面板都不等于执行修改。确认/拒绝后刷新对应 Profile、Resume、文件、Memory 或投递区域。

清空对话继续增加 epoch、保留 Memory 和已应用的变更历史；删除/恢复文件及回退继续走现有领域规则。文档上传后的直接解析入口和 Agent 的解析提案入口保持各自现有语义，不能因 UI 接线混淆为自动更新。

## 6. 对话接线与结构化岗位卡

### 6.1 先接现有协议

使用 POST fetch 读取 SSE，继续发送 `clientRequestId/content/fileIds`。前端处理当前 Java 暴露的 `start`、`delta`、`tool_status`、`action_proposed`、`ping`、`done`、`error`、`cancelled`。

- SSE 解析支持网络分片、多行 data、UTF-8 分片和终止事件；接口出错时处理 JSON 错误，而不是把所有响应强行视为流。
- Java 的 `done` 当前不带完整正文，前端用累积 delta 展示、用 messages API 校准最终状态；`delta.snapshot=true` 替换内容而不是重复追加。
- 消息/岗位/文件 ID 始终保留字符串精度，不转为 JS Number。
- `clientRequestId` 同一逻辑请求重试复用，编辑后的新请求生成新 ID；服务端已有幂等/租约语义继续生效。
- 刷新通过 Session 和历史恢复，不另存浏览器聊天历史；网络断流后先查询运行状态，避免重复开启同一轮。
- 折叠悬浮面板仅改变展示；明确点击停止调用 cancel API；清空、退出账号、epoch 变化后丢弃旧流事件。
- 页面导航共享同一会话；账户切换清除前端缓存。只允许持久化拖拽位置等非业务偏好。

### 6.2 推荐快捷输入

为现有发送消息 DTO 增加可选、带版本的 `interaction`，只开放明确的 `job_recommendation` 类型，包含允许的 `jobCategories/workCities/keywords/recruitType` 等结构化字段。`content` 仍是用户可见请求文本；不接收前端指定的 userId、候选岗位或任意 Tool 名。

拟定请求示例（新增字段，当前接口尚未实现）：

```json
{
  "clientRequestId": "recommend-unique-request-id",
  "content": "帮我推荐上海、杭州的媒体或企业岗位，方向是新媒体。",
  "fileIds": [],
  "interaction": {
    "schemaVersion": 1,
    "type": "job_recommendation",
    "preferences": {
      "jobCategories": ["MEDIA", "ENTERPRISE"],
      "workCities": ["上海", "杭州"],
      "keywords": ["新媒体"]
    }
  }
}
```

点击“为我推荐”由同一 Assistant 运行消费该请求，校验参数后执行同一个 `recommend_jobs` Tool；自然语言推荐也调用此 Tool。源前端 `recommendStep` 最多用于组织未提交的输入面板，不作为服务端对话进度。详情页的岗位上下文采用可选 `jobId`，由 Java 重新读取当前可见岗位。

`interaction`、详情上下文和附件引用一起纳入请求持久化及幂等比较；同一 requestId 内容不同必须冲突。旧请求未带新增字段时完全兼容。

### 6.3 结果卡持久化

新增一个固定类型的 `job_results` 展示事件，并在 `fc_ai_message` 增加可空、带 schemaVersion 的展示 JSON；消息历史响应同步添加可选 `presentation`。展示数据只保存岗位 ID、安全摘要、规则依据及算法版本，不保存原始 Tool 输出、内部字段、模型任意 HTML 或用户简历。

Python ToolResult 中区分给模型的摘要与给前端的结构化结果。Java 校验 jobIds 的当前可见性、重建安全岗位字段，再按 `userId + epoch + runId + messageId` 条件保存并发送结果；显示卡片与完成本轮使用一致的状态约束。取消/失败后清除临时展示，迟到结果不得写回失效 run。

消息重放、刷新和历史分页都能恢复结果卡。读取历史卡时批量复查岗位状态；已截止、下线或移入回收站的岗位显示失效提示，不能仍表现为可申请岗位。确认提案卡继续以 actionId 和现有 Action 数据为事实源；若需在聊天历史准确恢复其位置，保存 actionId 引用，不复制变更值。

保留源卡片的徽标位置，但不显示无依据的“匹配 86%”：上游目前未输出经过校准的匹配概率，也未把提示词中的 reasons 返回给调用者。v1 使用“命中意向城市”“学院推荐”等可验证事实标签；没有可靠理由时省略。后续若暴露规则分，明确命名为规则分，不转成概率。

## 7. 增强推荐 Tool

### 7.1 接口与数据流

保留 `search_jobs/get_job` 的普通查询能力，新增只读 `recommend_jobs`。Python 内部完成受控候选查询和算法执行；只向模型返回精简的前若干条摘要，完整卡片走结构化通道，避免现有 Tool 结果长度限制截断岗位列表。

Java 增加专用候选查询服务及内部入口，使用 `X-Internal-Token + AgentContext` 校验用户与 scope。复用可见性规则和字段投影，不把现有供后台使用的 `/internal/job-post/list` 直接暴露为学生推荐源。

内部入口建议为 `POST /internal/agent/tools/recommendation_candidates`，在 Java 白名单中注册独立的 `job:recommend` scope，返回候选岗位与本人所需资料投影。它是 `recommend_jobs` 的内部依赖，不加入模型的可调用 Tool 列表。Python Tool 注册表、Java scope 签发/校验和 capabilities 必须同步，capabilities 对外报告可用的 `recommend_jobs`，不误把辅助入口暴露成用户工具。

用户按钮提交的结构化字段必须经过与自然语言 Tool 参数相同的校验和归一化；按现有枚举白名单、数组长度与文本长度设上限。明确的推荐 interaction 可直接触发这一固定只读 Tool，结果再进入正常回答流程，计入同一轮工具预算。

v1 默认候选窗口 80 条，展示最多 10 条，LLM 重排最多 15 条。算法在候选内过滤技术岗并按公司去重；候选窗口是有界检索结果，不宣称全库最优。

### 7.2 查询与排序规则

- Java 硬过滤 `PUBLISHED`，同时检查申请截止、工作结束日期；算法缺少 status 默认保留的逻辑不能成为权限依据。
- 多城市在岗位 `workCities`、兼容单值城市等字段上 OR；归一化“上海/上海市”。岗位所有城市都应进入算法的适配输入，避免上游只读取 `workCity` 漏掉非首个城市。
- 关键词在岗位名、描述、技能/其他要求等约定字段 OR；同维度 OR、不同硬条件 AND，不把“主关键词”和关键词数组再重复 AND。
- `recommended` 映射成上游识别的 `weeklyRecommend`；v1 沿用现有推荐标记，不自行引入每周自动到期逻辑。
- 单类别保持上游行为，特别保留 MEDIA 使用关键词而非稀疏大类硬筛的处理。
- 多类别作为适配层扩展：分别取得上游单类别过滤描述，在同一查询内合并分类分支并去重。MEDIA 的关键词只作用于其分支，不影响企业等其他分支。排序输入的单值 jobCategory 留空，表示各已选类别等权，避免擅自取第一个类别；其余原评分规则照用。此差异记录为适配规则并单独测试。
- 资料仅取完成任务所需字段，由 Java 从本人 Profile/Resume 获取；姓名、学号、电话等不发送给排序模型。
- 用户只说“实习”不能自动判为“日常实习”；信息不足时保持不限或由现有 Agent 追问。

### 7.3 模型调用与可靠性

城市/词表识别先用规则；结构化按钮输入无需再调用关键词 LLM。可选关键词提取与排序沿用上游函数，记录新增模型调用的耗时和 token；最终回复使用真实已检索结果，不二次生成岗位排序或虚构分数。

Tool 超时采用环境配置：`AI_CHAT_TOOL_TIMEOUTS` 按工具名称覆盖，其次是解析/推荐专用默认值和读/写分类默认值。推荐默认 30 秒、重排默认 15 秒，均可配置；单轮总时限独立控制。Java 上游 HTTP、SSE 和运行租约同样支持配置，实际设置需给最终回复和跨服务请求预留时间。

只在重排失败、超时或非法结果时回退真实候选的规则排序，返回明确的降级标记；候选查询失败不能伪装为空列表。校验模型返回的 ID、去重、剔除越界 ID，不能信任上游 `method=rules+llm` 就认定模型确实产生有效排序。取消/超时传播到算法 worker，限制并发和队列，避免重排占满交互请求。

## 8. Python 全量能力升级及保留

### 8.1 同步原则

更新 vendor 到固定 `1f3d8a7`，上游 tracked 文件逐项校验，记录 PROVENANCE 和 MIT 来源。词表、JSON、XLSX、统计脚本和 WeRead 包必须进入安装包与生产镜像。

适配逻辑放在 vendor 外；保留当前子进程隔离、workspace 锁、路径限制、Token 注入、失败重试和取消机制。原 `dialogue.py/serve.py` 源码保留以便对照；学生端由当前 Agent 统一编排。上游对话能力可以作为管理员工作流调用，不另起学生会话。

### 8.2 能力去向

| 能力 | 接入位置 | 验收要求 |
|---|---|---|
| 城市、关键词、过滤、规则/LLM排序 | `recommend_jobs` 的算法 adapter | 单类别与固定上游样例等价，多类别扩展规则可解释 |
| 抽岗预筛 | 当前 Markdown/批处理/官网与公众号结构化入口 | 相同词表和阈值；未命中不调 LLM；管理员原文解析不误过滤 |
| 管理员拆岗、日期补年、去重、上传 | 已有工作流 | 原功能回归；入库状态仍由正式业务边界决定 |
| PDF/DOCX/图片/OCR 与批量简历解析 | 已有简历工作流、parse_resume_file Tool | 文件归属、字段补丁、跨资源提案/确认、OCR运行一致 |
| CSV/JSON/XLSX 导出与批处理 | Workflow/管理员路径 | 可以运行、下载，路径位于受控 runtime |
| 源统计 | `algorithm_job_source_stats` 等管理员工作流 | 固定目录 fixture 与实际 CrawlStore/CSV 口径一致，避免镜像目录重复计数 |
| WeRead 登录、bootstrap、daily | 新增管理员工作流与 Scheduler 配置 | 会话持久化、二维码可获取、限流/过期可识别，采集产物能进入现有处理管道 |
| 旧公众号、官网采集、积压处理 | 保留当前工作流 | 不重置账号、游标、去重记录；保持现有故障重试 |

新预筛是“抽取前文章判断”，现有 `app/algorithms/job_filter.py` 是“抽取后岗位判断”。两者并存，先通过样本对照评估新增漏检，保留现有不相关岗位进入回收站、成功岗位为草稿的处理。跳过文章保存原因和词表版本，可在规则升级后有选择重跑，不能只标成已完成后永久不可追溯。

worker 当前会重设 `PROJECT_ROOT` 到 workspace，新词表使用相对路径。必须显式定位/准备只读词表和 XLSX；若词表没有加载，应报告配置错误，不能以零词条运行后跳过全部文章。关键词模块同样检查这一点。

WeRead 的二维码展示改由管理员控制的运行入口提供，登录凭证只保存在私有持久卷；上游有限的抓取窗口如实记录，不宣称历史全量完整。原公共平台和官网数据源继续保留，跨源以原 URL/岗位键去重。Scheduler 一次只启用已验证的任务，防止重复抓取。

### 8.3 依赖和日志

WeRead 新依赖包含 `qrcode[pil]`，还声明 `urllib3<2`。必须与当前 requests/OCR 等依赖做解析及镜像验证；不要直接覆盖全项目 requirements。若确实冲突，在同一 Agent 部署中隔离该工作流运行依赖，保留健康检查和执行接口。

交互式推荐不得照搬完整 LLM 输入输出落盘。适配层控制日志入口和 `latest.json`，只记录模型、耗时、数量、token、错误码；已有受控后台调试日志继续按其权限和留存规则管理。日志策略在迁移文档中明确，不悄悄取消既有算法调试能力。

runtime 的同名 workflow 会覆盖新 preset：部署前列出覆盖项并生成差异，按版本备份和升级，验证线上实际执行的 operation，避免只更新镜像却仍运行旧流程。

## 9. 数据与协议改动清单

| 改动 | 必要性 | 兼容方式 |
|---|---|---|
| 消息请求可选 interaction/context | 选择按钮及当前岗位上下文接入同一 Agent | 旧纯文本请求继续可用，幂等比较包括新字段 |
| 用户消息输入元数据 | 重试与刷新时保留结构化请求 | 可空 JSON，沿用原消息主键/唯一约束 |
| 助手消息 presentation | 持久化岗位卡、提案引用 | 可空且带版本；旧消息仅显示文本，未知组件按文本回退 |
| SSE `job_results` | 实时展示经过校验的岗位卡 | 旧客户端忽略未知事件；最终文本保留岗位摘要/链接 |
| capability 可选字段 | 声明增强推荐与支持的展示版本 | 缺省按旧版处理；不删改现有字段 |
| Java 推荐候选 DTO/查询与内部入口 | 多条件候选检索且维护权限 | 新入口，不破坏现有列表/管理员查询含义 |
| vendor/operation/配置升级 | 算法全量能力进入现有执行框架 | 固定版本、保留旧入口和工作流 |

不为视觉迁移新增 Session 表、向量库、Redis 或第二个 Agent；不新建收藏业务表；不批量改写 Profile 数据，不改岗位表发布默认值。需要的 DDL 都采用增量迁移，先扩展 schema、后部署消费者，回滚时保留新列。

## 10. 分阶段实施与交付门禁

| 阶段 | 工作内容 | 可检查交付/完成标准 |
|---|---|---|
| P0 基线和契约 | 固定源 SHA、登记两仓未提交改动、记录线上版本；列出现有和新增 DTO；截取源组件参考图 | 基线清单、接口 fixture、桌面/手机视觉参照 |
| P1 视觉组件 | 在目标目录迁入样式和组件，使用本地 fixture 预览；保留现有品牌/姓名修复 | 视觉对照通过，原页面样式无污染；fixture 不参与生产业务 |
| P2 现有 Agent 与个人空间接线 | SSE、历史、Session、附件、Memory、确认/拒绝、回退/冲突、文件回收站和版本化表单 | 可用已有后端完成真实业务闭环，不依赖推荐新功能 |
| P3 算法与后台迁移 | vendor 更新、资源路径、依赖、日志、operation、预筛、统计、WeRead；保留原后台流程 | 哈希一致、原工作流回归、新工作流可运行；调度默认不自动启用 |
| P4 增强推荐 | 专用候选查询、本人资料投影、Python组合 Tool、预算/降级、筛选统一 | 相同筛选可重复查得真实可见岗位；多城市与 MEDIA 分支正确 |
| P5 卡片闭环 | interaction、展示字段DDL、job_results、历史重放、提案引用；把选择区连到同一会话 | 点击和自然语言推荐共用 Tool；刷新/取消/清空无残留错位 |
| P6 联调与交付 | 真实 Java/Python 前后端联调、生产镜像测试、视觉回归、文档/发布包 | 全链路验收报告、版本对应表、灰度和回滚操作清单 |

依赖：`P0 → P1 → P2`，`P0 → P3 → P4`，两条都完成后 `P5 → P6`。按能力拆成可审核提交，后端协议与前端消费者分别提交；不得直接挑选覆盖整个旧 AdminView/ProfileView 的提交。

P0 若需要隔离，先检查并复用合适 checkout；必须保留当前工作区的姓名、部署脚本、个人空间及测试改动。以当前已确认能力为基线，不从过旧远端 main 开始而丢掉本地已完成能力。

## 11. 验收矩阵

### 11.1 视觉与交互

- 对照源组件，在 1440、1024、390、320 宽度检查悬浮球、面板、标签、城市菜单和岗位卡；参考图来自固定源版本，使用相同测试数据。
- 长岗位名、多城市、空列表、错误、流式增长、键盘焦点、减少动态效果和手机软键盘均可用。
- 暂时没有后端支持的收藏/概率指标不显示成可用功能；不出现 disabled 的假聊天输入。
- 保留复旦红色校徽、现有登录页、名字/学号区分、原管理员页面和响应式表现。

### 11.2 前后端业务闭环

- 一个用户一个 Session；刷新、翻历史、断流重连、重复发送和多标签页不会重复消息/提案。
- 取消、清空、重置后旧流/岗位卡不能回写；账号切换没有旧用户内容。
- 自然语言搜索、推荐按钮及详情页咨询共用当前用户权限；不存在客户端提交 userId 绕过身份的路径。
- 已知 requestId 参数变化冲突；20 位 ID 往返不失真；版本冲突不会静默覆盖用户新修改。
- 资料、简历、Memory、文件、问卷提案：确认前不修改；确认后刷新；拒绝不修改；恢复/回退冲突有现有明确处理入口。
- 中文路径、省份、城市、空选择、未知历史偏好和 64 字符限制均有契约测试；不能直接把上游“山东-威海”当作纯城市等值查询。

### 11.3 算法和后台

- 固定输入、固定 LLM mock、相同配置下验证上游规则/输出等价；真实模型非确定性结果用人工样例验收，不要求逐字一致。
- 单/多城市、单/多类别、MEDIA 分支、全文关键词、截止/回收站岗位、缺失资料、非法排序ID及降级均覆盖。
- 预筛词表缺失必须报错；预筛跳过可追踪；抽取后原岗位过滤、去重、草稿/回收站处理无回归。
- PDF、DOCX、图片/OCR、批量导出、上传、统计、旧采集和 WeRead 均有对应工作流测试；真实登录验证单独记录。
- 日志扫描确认交互式推荐未写入用户正文/简历，取消后没有遗留 worker。

执行目标前端自身的干净安装、测试和生产构建，并记录 Node/npm 版本。此前源项目 `npm ci --prefix` 曾失败、借用现有依赖后构建通过，这不等于已证明 lock 文件损坏；本次以目标目录干净环境复现定位，按证据修复，不迁入源 `node_modules` 或盲目重写 lock。

Java 执行相关集成测试，Python 执行契约/算法/工作流/聊天测试，最后运行现有 `deploy/scripts/test-algorithm.sh` 及新的 Assistant 端到端用例。源码测试通过不代替真实网关、OCR 镜像和模型链路验收。

## 12. 发布与回滚

发布前备份数据库、上传目录、runtime、CrawlStore、采集会话和 workflow 覆盖配置，记录三个部署产物的确切版本。当前本地 HEAD 与线上版本需要单独比对。

发布顺序：

1. 应用增量 DDL，验证旧版本仍可读取。
2. 部署兼容现有请求的 Agent 算法/Tool 与内部 API，验证版本能力。
3. 部署兼容旧前端的 Java 推荐查询、消息扩展和卡片校验。
4. 先用测试账号完成文本 Agent、确认卡和规则推荐验收，再启用岗位结果卡。
5. 发布保留源视觉的新前端，按 capability 显示可用能力；验证真实网关 SSE、登录和错误反馈。
6. 启用可选 LLM 排序，检查总延迟和调用量；预筛先对照样本，再单独启用。
7. WeRead 在真实会话、采集窗口、去重和持久化验证通过后再启用 Scheduler。观察运行结果后逐步扩大。

建议新增推荐、LLM 重排、预筛、展示版本等能力开关；已有 `AI_CHAT_WRITE_ENABLED` 继续控制写提案。开关默认与部署能力一致，不由前端单方面假定可用。

回滚先关闭新增推荐/预筛/采集调度入口，按版本组合回退前端、Java、Agent；保留新增列与持久卷，不执行删除数据卷或批量删除已发布岗位。已有文本聊天、手工岗位管理、文件与问卷接口仍可使用。词表/工作流升级产生的跳过记录与游标按版本恢复或重跑。

## 13. 最终交付与完成定义

- 前端组件来源/差异清单及固定版本视觉对照。
- Java/Python/前端契约文档、兼容规则、DDL 和发布版本映射。
- vendor 源码校验、完整 operation 清单和可用的后台工作流。
- Agent 文本、增强 Tool、岗位卡、确认卡、个人空间、文件恢复和回退的端到端验收结果。
- 可复现构建、生产 smoke、能力开关及回滚手册。

完成时：源前端视觉进入正式目录，用户实际操作均由正式 Java/Python 能力承接；视觉迁移没有引入第二套身份、推荐会话、浏览器业务数据库或模型编排。算法固定版本已纳入现有框架，新增能力有调用入口和验证，保留原有业务语义。

## 14. 本地代码依据

- `fusioncareer-biz/src/main/java/com/fusioncareer/controller/PersonalSpaceAssistantController.java`
- `fusioncareer-biz/src/main/java/com/fusioncareer/controller/PersonalSpaceController.java`
- `fusioncareer-biz/src/main/java/com/fusioncareer/service/AiStreamService.java`
- `fusioncareer-biz/src/main/java/com/fusioncareer/service/AgentToolService.java`
- `fusioncareer-biz/src/main/java/com/fusioncareer/service/PersonalSpaceMutationService.java`
- `fusioncareer-api/src/main/java/com/fusioncareer/dto/req/AiMessageRequest.java`
- `fusioncareer-api/src/main/java/com/fusioncareer/dto/req/PersonalSpacePatchRequest.java`
- `fusioncareer-agent/app/api/routers/chat.py`
- `fusioncareer-agent/app/chat/tools.py`
- `fusioncareer-agent/app/algorithms/upstream.py`
- `fusioncareer-agent/app/algorithms/upstream_worker.py`
- `fusioncareer-agent/app/skills/business/crawlers/structure_articles.py`
- `docs/ALGORITHM_WORKFLOWS.md`

上游来源：[前端 7b68fea](https://github.com/daixintongtt-code/FusionCareer-View-1/tree/7b68fea40b730a143481956b61fc4b2973e910d0)、[算法 1f3d8a7](https://github.com/chenxin1209/FusionCareer-Algorithm/tree/1f3d8a7a7d244487294a3793658e625d4d9086f1)。
