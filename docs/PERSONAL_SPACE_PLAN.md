# FusionCareer 个人空间实施计划

> 状态：实施中；聚合查询、版本化 Mutation、轻量 Memory、AES-GCM History、单资源及 Profile + Resume 多资源 Git-like Revert、单 AI Session/纯文本 SSE/HMAC Tool、7 类 AI 写提案、Document 回收站、统一岗位可见性与问卷校验已落地
>
> 日期：2026-09-28
>
> 定位：AI Agent 的前置领域基础

## 0. 一页结论

“个人空间”定义为当前用户私有资源的应用层聚合与统一权限边界，不创建一张包含所有字段的 `fc_personal_space` 巨型表。

```text
PersonalSpace(currentUser)
├─ Account            账号安全投影，只读
├─ Profile            个人资料
├─ Resume             结构化简历
├─ Documents[]        简历与问卷附件
├─ Applications[]     当前用户的问卷草稿与投递
├─ Memory             轻量长期偏好
├─ Assistant          单 Session、消息与运行状态
└─ ChangeHistory[]    网页和 Agent 共用的 Git-like 版本历史
```

岗位和问卷定义不属于个人空间：

```text
JobCatalog / QuestionnaireDefinition
                  ↑
PersonalSpace.Application 只保存对它们的引用
```

首期关键决策：

- `spaceId` 不单独生成，内部身份就是当前登录 `userId`；
- 公开 API 不接受 userId，不提供 `/personal-space/{userId}`；
- 不增加全局 space version，各资源维持独立 version；
- 不提供整体 `PUT/PATCH /personal-space`；
- 总览只返回摘要、版本和链接，不加载所有正文、文件或消息；
- 普通网页和 Agent 共用查询、验证、版本、Action 和 Revert 能力；
- Agent 是代表当前用户操作个人空间的一个入口，不是新的权限主体。

当前已完成的首个切片：

```text
GET /personal-space
GET /personal-space/profile
PATCH /personal-space/profile
GET /personal-space/resume
PATCH /personal-space/resume
GET /personal-space/documents
GET /personal-space/documents/deleted
DELETE /personal-space/documents/{fileId}
POST /personal-space/documents/{fileId}/restore
GET /personal-space/applications
GET /personal-space/memory
PUT /personal-space/memory/{key}
DELETE /personal-space/memory/{key}
DELETE /personal-space/memory
GET /personal-space/actions
GET /personal-space/actions/{id}
POST /personal-space/actions/{id}/revert
POST /personal-space/actions/{id}/confirm
POST /personal-space/actions/{id}/reject
GET /personal-space/assistant/session
GET /personal-space/assistant/messages
POST /personal-space/assistant/messages/stream
POST /personal-space/assistant/run/cancel
POST /personal-space/assistant/session/clear
DELETE /personal-space/assistant/session
```

当前切片聚合真实现有数据；Profile、Resume 已支持字段级 SET/CLEAR PATCH，Memory 已实现 6 个白名单 key、4KB 上限与独立 CAS。canonical 写入在同一事务中记录不可变 APPLIED Action/Item，before/after 仅保存实际修改字段并使用 AES-256-GCM 加密。Profile、Resume、Memory、文件上传/软删除状态与问卷作答支持永久 Revert；显式上传形成 APPLIED CREATE Action，回退时保留 blob。简历解析使用一个 Action 的 Profile + Resume 两个 Item 原子确认和回退。Assistant 已具备单用户唯一 Session、SSE、有界上下文与异步摘要、HMAC AgentContext、15 个只读 Tool、7 个写提案 Tool及登录态脱敏确认卡 API。写 Tool 只产生幂等 PENDING Action，登录用户确认后才以 CAS 应用；可用 `AI_CHAT_WRITE_ENABLED` 双端关闭。前端页面接入仍在后续阶段。

## 1. 目标

1. 把分散在 User、Profile、Resume、File、Questionnaire 和 AI 模块的当前用户资源统一成明确领域目录。
2. 为前端提供一个稳定的“我的空间”入口和分区导航。
3. 为 Agent 提供与普通用户能力一致的查询和修改边界。
4. 集中所有权、可见性、字段白名单、验证、版本和 Revert 规则。
5. 保留现有业务表作为当前状态事实源，避免复制和双写全部字段。
6. 允许跨资源原子操作，例如简历解析同时修改 Profile 与 Resume。
7. 让网页修改和 Agent 修改进入同一条 Git-like 历史，而不是建立两套变更系统。

## 2. 非目标

- 不创建保存所有 Profile、Resume、Files、Applications 的巨型 JSON 行；
- 不把岗位目录、岗位正文或问卷题目复制进个人空间；
- 不用一个全局 version 锁住所有资源；
- 不提供反射式 CRUD、任意资源名、任意 JSON Path 或通用 SQL；
- 不把 PersonalSpace 当作一个必须整体加载、整体保存的 ORM Aggregate；
- 不在本阶段实现 LLM、SSE 或 Tool Loop；Agent 在个人空间稳定后接入。

## 3. 资源目录

| 分区 | 当前事实源 | 普通用户能力 | Agent 能力 |
|---|---|---|---|
| Account | `fc_user` | 安全字段只读 | 同等只读 |
| Profile | `fc_user_profile` | 16 字段读写 | 全字段提案、确认、Revert |
| Resume | `fc_resume` | 9 字段读写 | 全字段提案、确认、Revert |
| Documents | `fc_resume_file` | 上传、列表、下载、解析、删除 | 同等能力；删除改软删除 |
| Applications | `fc_questionnaire_answer` | 草稿、提交、重提、查看 | 同等能力；审核字段只读 |
| Memory | `fc_user_memory` | 查看、编辑、删除 | 白名单提案、确认、Revert |
| Assistant | `fc_ai_session/message` | 单 Session 对话 | 当前 Session 内运行 |
| ChangeHistory | `fc_user_change_action/item` | 查看、确认、拒绝、Revert | 只能创建提案，不能自行确认 |

### 3.1 Account

安全投影只包含普通用户本来就能看到的字段，例如：

```text
id, username, studentId, role, status, createdAt, updatedAt
```

密码、认证 Token、SSO 原始响应和内部审计信息不进入投影。Account 不提供个人空间写接口；realName 等可编辑内容归 Profile。

### 3.2 Profile

完整用户可写字段：

```text
realName, gender, birthDate, politicalStatus,
phone, email, wechat, hometown,
grade, major, eduLevel, supervisor,
intentionOrder, intentionCity, intentionDream, mindset
```

### 3.3 Resume

完整用户可写字段：

```text
personalIntro, basicInfo, education, internship, campus,
awards, skills, portfolio, remark
```

### 3.4 Documents

底层首期继续复用 `fc_resume_file`，但在个人空间中统一称 Documents，因为该表同时承载简历和问卷附件。

支持：

- 上传；
- 列表和配额；
- 鉴权下载；
- 简历解析；
- 软删除和恢复；
- 永久数据清除。

模型永远不能访问 `storagePath` 或服务器文件路径。

### 3.5 Applications

个人空间保存当前用户对岗位问卷的草稿和投递。岗位与题目仍由 JobCatalog / QuestionnaireDefinition 提供。

支持：

- 读取动态问卷；
- 保存部分草稿；
- 正式提交和允许状态下的重提；
- 文件答案；
- 查看自己的状态和审核结果；
- WITHDRAWN 领域补偿状态。

管理员审核字段属于管理域，用户和 Agent 都不能直接修改。

### 3.6 Memory

Memory 是个人空间资源，不属于某一次聊天 Session。它保留白名单偏好，例如回答风格、求职目标和临时约束，不复制 Profile 或 Resume 的正式事实。

清空 Agent Session 默认不清 Memory；用户单独清空 Memory 或永久删除个人数据时才清除。

## 4. 身份和权限上下文

所有个人空间能力接受服务端构造的 `PersonalSpaceContext`：

```text
subjectUserId
actorType = USER | AGENT | SYSTEM
origin = BROWSER | AGENT_TOOL | SYSTEM_JOB
capabilities
runId? / epoch?
```

来源：

- 浏览器请求：Java 从 `StpUtil.getLoginIdAsLong()` 构造；
- Agent Tool：Java 从已验证的短期 AgentContext 构造；
- 系统任务：由明确的内部调用方和固定能力构造。

规则：

- context 不能来自请求 Body；
- SQL 所有权条件必须包含 `user_id = subjectUserId`；
- 按 fileId、applicationId 查询时也必须同时校验 userId；
- Agent capabilities 是普通用户能力的子集，不能产生管理员权限；
- 管理员后台继续使用管理域接口，不通过个人空间模拟其他用户；
- 不存在和无权访问统一返回 404，避免资源枚举。

Agent 的权限语义是：

```text
actor = AGENT
subject = 当前登录用户
mode = ON_BEHALF_OF
```

## 5. 数据模型

### 5.1 不增加 PersonalSpace 根表

当前 `fc_user.id` 已经唯一标识用户，空间又没有独立生命周期或必须单独持久化的根状态。因此首期不增加 `fc_personal_space`。

只有出现真实的空间级状态，例如用户选择的空间主题、共享设置或独立启停策略时，才考虑增加窄表；不能为了“看起来像聚合”提前建空壳表。

### 5.2 新表

#### `fc_user_memory`

```text
user_id BIGINT PRIMARY KEY
memory_json JSON NOT NULL
version BIGINT NOT NULL DEFAULT 0
created_at DATETIME(3)
updated_at DATETIME(3)
```

#### `fc_user_change_action`

一次用户可理解的 commit：

```text
id, user_id, actor_type, origin, epoch?, run_id?, request_id,
action_type, reverts_action_id, status,
idempotency_key, args_hash, schema_version,
reason, error_code, confirmed_at, applied_at,
created_at, updated_at
```

#### `fc_user_change_item`

一次 commit 中的资源变更：

```text
id, action_id, item_order,
resource_type, resource_key, operation,
changed_fields, expected_version, applied_version,
before_ciphertext, after_ciphertext, key_version,
created_at
```

网页直接编辑会在同一事务中写 APPLIED action；Agent 编辑先写 PENDING action，用户确认后再应用。

### 5.3 Agent 阶段新增表

```text
fc_ai_session
fc_ai_message
```

Session 只保存对话摘要、epoch 和运行租约；Memory 已提升为个人空间资源，不放在 Session 行中。

### 5.4 现有表增量

```text
fc_user_profile:
  + version

fc_resume:
  + version

fc_resume_file:
  + version
  + updated_at
  + deleted_at
  + content_hash（建议）

fc_questionnaire_answer:
  + version
  + deleted_at
  + WITHDRAWN 状态
```

所有网页、Agent、解析、审核和系统写入口都必须递增 version。

## 6. 查询 Facade

增加 `PersonalSpaceQueryService`，只负责当前用户资源的安全读取和投影，不负责通用 CRUD。

### 6.1 总览

```http
GET /personal-space
```

只返回轻量摘要、资源版本和链接：

```json
{
  "account": {
    "displayName": "张三"
  },
  "sections": {
    "profile": {
      "exists": true,
      "version": 3,
      "updatedAt": "2026-09-28T10:00:00+08:00",
      "permissions": ["READ", "PATCH"],
      "href": "/api/personal-space/profile"
    },
    "resume": {
      "exists": true,
      "version": 6,
      "updatedAt": "2026-09-28T10:01:00+08:00",
      "permissions": ["READ", "PATCH"],
      "href": "/api/personal-space/resume"
    },
    "documents": {
      "count": 3,
      "usedBytes": 1200000,
      "quotaBytes": 31457280,
      "href": "/api/personal-space/documents"
    },
    "applications": {
      "draft": 1,
      "submitted": 2,
      "reviewed": 1,
      "withdrawn": 0,
      "href": "/api/personal-space/applications"
    },
    "memory": {
      "count": 4,
      "version": 2,
      "href": "/api/personal-space/memory"
    },
    "assistant": {
      "sessionExists": true,
      "activeRun": false,
      "href": "/api/personal-space/assistant/session"
    }
  }
}
```

总览不包含简历全文、文件列表、问卷答案、聊天历史或 Action 快照。

### 6.2 详细读取

详细内容继续按资源分页和按需加载：

```text
GET /personal-space/profile
GET /personal-space/resume
GET /personal-space/documents
GET /personal-space/documents/{id}
GET /personal-space/applications
GET /personal-space/applications/{jobPostId}
GET /personal-space/memory
GET /personal-space/actions
GET /personal-space/actions/{id}
GET /personal-space/assistant/session
GET /personal-space/assistant/messages
```

不提供 `expand=all`。

## 7. Mutation Facade

增加 `PersonalSpaceMutationService`，它编排强类型领域命令和 Change History，但不取代各领域 Service 的验证逻辑。

### 7.1 Profile / Resume PATCH

区分“未提供”和“明确清空”：

```json
{
  "expectedVersion": 6,
  "set": {
    "major": "新闻传播学"
  },
  "clear": ["supervisor"]
}
```

不提供整体 `PUT /personal-space`，也不让 Agent 使用整个 DTO 覆盖资源。

### 7.2 Documents

```text
POST   /personal-space/documents
DELETE /personal-space/documents/{id}
POST   /personal-space/documents/{id}/restore
POST   /personal-space/documents/{id}/parse
GET    /personal-space/documents/{id}/download
```

普通删除改为软删除；永久删除只属于明确数据清除流程。

### 7.3 Applications

```text
PUT  /personal-space/applications/{jobPostId}/draft
POST /personal-space/applications/{jobPostId}/submit
POST /personal-space/applications/{jobPostId}/withdraw
```

问卷题目来自公开岗位定义；答案写入前统一验证 questionId、题型、选项、长度、required 和 fileId 所有权。

### 7.4 Memory

```text
PUT    /personal-space/memory/{key}
DELETE /personal-space/memory/{key}
DELETE /personal-space/memory
```

Memory 使用白名单 key 和 version CAS。

## 8. Git-like Change History

PersonalSpace Change History 是网页和 Agent 共用的公共能力。

### 8.1 网页写入

```text
浏览器命令
→ 构造 PersonalSpaceContext(actor=USER)
→ 领域校验
→ 写业务表 + APPLIED action/items
→ 单事务提交
```

### 8.2 Agent 写入

```text
Agent Tool
→ PersonalSpaceContext(actor=AGENT, subject=currentUser)
→ 创建 PENDING action/items
→ 用户确认
→ 领域校验 + CAS
→ 写业务表 + APPLIED action/items
```

Agent 不能确认自己的提案。

### 8.3 Revert

- 原 APPLIED action 永远不可变；
- Revert 创建新的 action，并通过 `reverts_action_id` 连接；
- Revert action 自身也可 Revert；
- 字段使用 before / after / current 三方比较；
- 文件使用软删除/恢复；
- 已审核投递使用 WITHDRAWN，不抹除审核事实；
- 只有明确的个人数据永久删除会销毁历史快照并终止 Revert 能力。

### 8.4 多资源 Action

简历解析会生成一个 action 下的 Profile + Resume items。确认时按稳定资源顺序加锁，在同一事务中全部 CAS；任一 item 冲突则整个 action 不写入。

## 9. Agent 接入

Agent 不直接依赖 UserProfileService、ResumeService 或 Mapper，只通过：

```text
PersonalSpaceQueryService
PersonalSpaceMutationService
```

读取 Tool：

```text
get_personal_space_overview
get_my_profile
get_my_resume
list_my_documents
get_my_application
list_my_applications
get_my_memory
```

修改 Tool：

```text
propose_profile_patch
propose_resume_patch
parse_resume_document
propose_document_delete
propose_application_draft
propose_application_submit
propose_application_withdraw
propose_memory_patch
```

岗位搜索继续走 PersonalSpace 之外的 JobCatalog：

```text
search_visible_jobs
get_visible_job
get_visible_job_questionnaire
```

默认 Prompt 只注入轻量 Memory、空间分区摘要、会话摘要和近期消息。电话、邮箱、完整简历、文件内容和投递答案均按 Tool 需要读取，不在每轮自动加载。

## 10. API 兼容迁移

现有路径先保留：

```text
/user/profile/**
/user/resume/**
/questionnaire/**
```

迁移规则：

1. 先实现 PersonalSpace Query/Mutation Service；
2. 旧 Controller 改为调用同一 Service，行为和响应保持兼容；
3. 新增 `/personal-space/**` 规范接口；
4. 前端逐分区切换到新接口；
5. 契约测试证明新旧写入口产生相同当前状态和 Change History；
6. 前端全部切换并经过一个发布周期后，再决定是否弃用旧路径。

不能同时维护两套字段验证、权限或 Revert 实现。

## 11. 前端个人空间

新增一个“个人空间”页面壳：

```text
个人空间
├─ 概览
├─ 我的资料
├─ 我的简历
├─ 我的文件
├─ 我的投递
├─ AI 助手
├─ AI 记忆
└─ 修改历史
```

页面首次只请求 overview，再按路由或用户展开加载各分区。Agent 可以作为空间内固定面板或独立分区，但不能绕过当前分区的权限与版本。

修改历史展示：

- 操作来源：网页、Agent、系统；
- 修改资源和脱敏字段差异；
- Action/Revert 链；
- PENDING 确认卡；
- 三方冲突解决；
- 永久数据删除提示。

## 12. 实施顺序

### P1 固定资源契约

- 资源目录和 capabilities；
- Profile 16 字段、Resume 9 字段；
- PATCH SET/CLEAR DTO；
- 问卷和文件状态机；
- 敏感字段投影和脱敏规则。

### P2 版本与共享验证

- 四类可变业务表增加 version；
- 所有现有写入口递增 version；
- 岗位/问卷共享可见性；
- 完整问卷答案验证；
- 文件软删除和恢复。

### P3 PersonalSpace Query

- `PersonalSpaceContext`；
- `PersonalSpaceQueryService`；
- overview 和分区读取；
- userId/资源归属测试。

### P4 PersonalSpace Mutation 与 History

- `fc_user_memory`；
- `fc_user_change_action/item`；
- `PersonalSpaceMutationService`；
- 网页 APPLIED history；
- 多资源 action；
- Git-like Revert。

### P5 API 与前端迁移

- `/personal-space/**`；
- 旧 Controller 适配；
- 前端页面壳和分区迁移；
- 新旧契约一致性测试。

### P6 Agent

完成 P1–P5 后，实施 `AI_AGENT_CONVERSATION_PLAN.md` 中的 Session、流式对话和 Tool Loop，并只接 PersonalSpace Query/Mutation Facade。

## 13. 测试

必须覆盖：

- 任何公开路径都不能指定其他 userId；
- overview 不包含完整简历、答案、文件路径或 Action 密文；
- 新旧接口对同一资源具有相同验证和当前状态；
- 普通网页和 Agent 字段能力矩阵保持一致；
- 所有写入口递增 version；
- Profile/Resume SET 与 CLEAR 语义明确；
- 文件上传、软删除、恢复和鉴权下载；
- 问卷题型、选项、required 和附件归属验证；
- 管理员审核后用户撤回不删除审核事实；
- 简历解析跨 Profile/Resume 原子提交；
- 网页写入和 Agent 写入都进入统一历史；
- 任意历史 action 可 Revert，Revert 可再次 Revert；
- 同字段冲突不会覆盖后续修改；
- 永久数据删除后历史密文不可恢复。

## 14. 原子提交建议

### S1 `test(space): establish personal resource contracts`

固定字段、权限、PATCH 和可见性基线测试。

### S2 `feat(space): version user-owned resources`

增加 version、共享验证、文件软删除和问卷 WITHDRAWN。

### S3 `feat(space): expose personal space overview`

增加 Context、Query Service 和轻量 overview。

### S4 `feat(space): record user change history`

增加 Memory、Action、Item 和普通网页 APPLIED history。

### S5 `feat(space): support git-like revert`

增加多资源 action、三方冲突、文件恢复和领域补偿。

### S6 `feat(space): expose canonical resource APIs`

增加 `/personal-space/**`，旧接口改调共享 Service。

### F1 `feat(space): add personal workspace`

在前端仓库增加概览、分区导航和修改历史。

## 15. 完成标准

- 普通用户一个逻辑 PersonalSpace，无额外根表和重复数据；
- overview 轻量且不泄漏敏感正文；
- Profile、Resume、Documents、Applications、Memory、Assistant、History 分区明确；
- 新旧 API 共用相同 Query/Mutation 和验证路径；
- 普通网页与 Agent 能力矩阵自动校验；
- 所有资源具备独立 version，不存在全局热点锁；
- 网页和 Agent 写入进入同一不可变 Action 历史；
- 文件、问卷和跨资源简历解析具备可靠 Revert；
- 前端无 mock，加载、空、成功、冲突和错误状态完整；
- PersonalSpace 稳定后才开放 Agent 写 Tool。
