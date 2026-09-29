# FusionCareer AI Agent 单会话、轻量记忆与可逆工具实施计划

> 状态：实施中；PersonalSpace 前置能力、单 Session、纯文本 SSE、HMAC AgentContext、15 个只读 Tool，以及 7 个可确认写提案 Tool（含 Profile + Resume 跨资源简历解析）已落地
>
> 日期：2026-09-28
>
> 范围：`FusionCareer-Backend`、`fusioncareer-agent`、独立前端仓库 `FusionCareer-View`

> 前置条件：先完成 [`PERSONAL_SPACE_PLAN.md`](PERSONAL_SPACE_PLAN.md)。本计划中的 Agent 是个人空间的一种交互入口；资源查询、修改、版本和 Revert 均复用 PersonalSpace Query/Mutation Facade。

## 0. 一页结论

| 决策 | 首期方案 |
|---|---|
| Session | `fc_ai_session.user_id` 主键；一个用户永久唯一一个逻辑 Session |
| 持久化 | 个人空间三表：UserMemory、UserChangeAction、UserChangeItem；Agent 两表：Session、Message；不增加独立 Run 表 |
| 记忆 | `fc_user_memory` 中 4KB 白名单记忆 + Session 2000 字摘要 + 最近 12 条消息 |
| Agent | 现有 AsyncOpenAI 上实现最多 3 轮的单 Agent Tool Loop |
| 搜索 | 固定领域 Tool；Java 依据短期签名上下文执行行级和字段级权限 |
| 修改 | Python 只提案；Java确认后使用 version CAS 写入 |
| 回退 | 不设时间窗口；任何 APPLIED action 都可随时创建新的 REVERT action，像 Git 一样保留历史 |
| 流式 | 浏览器 POST fetch → Java SseEmitter → Python StreamingResponse |
| 基础设施 | 复用 MySQL、Spring MVC、FastAPI；不新增 Redis、MQ、向量库或 Agent 框架 |
| 交付量 | PersonalSpace 前置 + 完整 Agent 用户能力对等、永久 revert、前端和生产门禁 18–26 人日 |

首期按“先纯文本与只读、后开启可逆写入”灰度，任何写 Tool 都可通过 `AI_CHAT_WRITE_ENABLED` 一键关闭。

## 1. 目标

本计划为学生端增加一个就业场景 AI Agent，固定以下产品与技术目标：

1. 每个登录用户最多拥有一个永久逻辑 Session，不提供多会话列表或新建会话能力。
2. Session 支持多轮对话、流式回复、刷新后恢复以及显式清空。
3. Agent 具有轻量记忆，但不引入 Redis、向量数据库或独立 Memory 服务。
4. Agent 可搜索数据库，但只能读取当前用户依法可见的数据和公开字段。
5. 普通用户通过学生端公开 API 能读取或修改的资源，Agent 原则上提供同等能力，包括完整资料、简历、文件、简历解析、问卷草稿与提交；系统字段和管理员能力除外。
6. 业务数据修改必须可审计、可确认、幂等；任何已应用修改都能在账户生命周期内随时发起 Git 风格 revert。
7. Java 继续负责登录态、权限、业务数据库和最终写入；Python Agent 保持无状态。
8. 复用现有 FastAPI、AsyncOpenAI、Spring MVC、MyBatis-Plus、Sa-Token 和 MySQL。
9. 浏览器不直连 Python，也不访问任何 `/internal/**` 接口。
10. 用户正文、简历全文、Tool 原始参数和内部字段不得进入应用日志。

## 2. 非目标

首期不实现以下能力：

- 多 Agent、Agent handoff 或自治任务规划；
- 多会话、会话分享、会话文件夹；
- 语义向量检索、知识库 RAG 或网页搜索；
- 任意 SQL、任意 HTTP、Shell、文件系统等通用 Tool；
- 普通学生端不存在的发消息、发邮件、外部通知或第三方系统操作；
- Agent 修改普通用户本身无权修改的用户名、用户角色、账号状态、学工号、密码或审计字段；
- 跨进程精确恢复到某个 LLM token；
- 将模型思维链、隐藏推理或完整 Tool 返回值展示给用户。

这些能力只有在真实需求和运行数据证明必要时再单独设计。

## 3. 当前基线与缺口

### 3.1 可直接复用

- `fusioncareer-agent/app/integrations/llm.py` 已封装 OpenAI-compatible `AsyncOpenAI` 客户端。
- `fusioncareer-agent/app/integrations/backend.py` 已支持 Python 异步调用 Java Internal API。
- `fusioncareer-agent/app/api/deps/internal_auth.py` 已提供服务间 Token 校验。
- Java 已使用 Sa-Token，并可通过 `StpUtil.getLoginIdAsLong()` 获得当前用户。
- Java 已有用户资料、简历、岗位和问卷领域 Service。
- Java 已使用 JDK `HttpClient` 和 Spring MVC，不需要迁移 WebFlux。
- MySQL 8 可继续作为 Session、消息、记忆和变更审计的唯一事实源。

### 3.2 必须补齐

- 当前 LLM Client 只构造 system + 单条 user message，且一次性返回完整文本。
- 当前 Java `PythonServiceClient` 只处理同步 JSON 响应。
- 数据库没有 AI Session、消息或可逆操作记录。
- 用户资料、简历、文件和问卷更新都没有乐观版本号，不足以安全 revert。
- 当前文件删除会同时物理删除 blob 和元数据，无法恢复。
- 当前问卷答案验证不完整，且取题/提交没有统一复用发布岗位可见性。
- 当前岗位详情查询不保证岗位仍对普通用户可见。
- Nginx 未为 SSE 关闭响应缓冲。
- 当前 Agent 容器只 `expose: 8900`，Java 双机直连地址可能不可达。

### 3.3 普通用户现有能力矩阵

Agent 的目标能力边界以普通学生端公开 API 为准，而不是以数据库全部列为准。

| 资源 | 普通用户当前能力 | 可写字段/输入 | Agent 目标 |
|---|---|---|---|
| 账号 | 读取当前账号 | id、username、studentId、role、status 等只读字段 | `read_account`；不提供写 Tool |
| 个人资料 | 读取、保存 | 16 个 `UserProfileRequest` 字段 | 全字段读取、patch、confirm、revert |
| 结构化简历 | 读取、保存 | 9 个 `ResumeRequest` 文本字段 | 全字段读取、patch、confirm、revert |
| 简历/附件文件 | 上传、列表、下载、删除、查询配额 | 用户提供的文件；服务生成 ID、路径、大小和 MIME | 接收附件、列出、解析、下载、软删除、恢复 |
| 简历解析 | 解析已有文件并更新资料/简历 | fileId、解析出的 profile/resume patch | 先生成跨资源提案，确认后原子应用 |
| 岗位 | 搜索、筛选、查看详情 | 查询参数，无用户写字段 | 完整公开筛选和可见详情，只读 |
| 投递问卷 | 读取题目、保存草稿、正式提交、重提、上传附件、查看自己的记录 | jobPostId + 动态 answers | 动态答题、草稿、提交、附件、历史和领域化 revert |
| AI 记忆 | 当前系统尚无 | 白名单偏好 | 查看、patch、删除、revert |
| 登录会话 | 登录、退出 | SSO/本地会话状态 | 不作为模型 Tool，由现有认证 UI 管理 |

个人资料 16 个用户可写字段：

```text
realName, gender, birthDate, politicalStatus,
phone, email, wechat, hometown,
grade, major, eduLevel, supervisor,
intentionOrder, intentionCity, intentionDream, mindset
```

结构化简历 9 个用户可写字段：

```text
personalIntro, basicInfo, education, internship, campus,
awards, skills, portfolio, remark
```

岗位公开筛选参数：

```text
jobCategory, jobSubCategory, recruitType,
workDurationType, workPeriodType, workMode,
workProvince, workCity, reqEduLevel,
salaryMin, salaryMax, sortBy, recommended,
sourceType, internalApply, keyword, page, size
```

`status` 即使由客户端提交，也必须由普通用户查询强制为 `PUBLISHED`，不能成为 Agent 越权开关。

动态问卷回答类型：

```text
TEXT, TEXTAREA, RADIO, CHECKBOX, FILE_UPLOAD
```

共享 `QuestionnaireAnswerValidator` 已统一校验未知 questionId、重复题目、题型、选项、文本长度、必填项和附件归属；普通页面与 Agent Tool 复用同一入口。

现有 Python `insert_user_profile` 和 `insert_resume` Skill 已列出全部 16/9 个字段，可复用字段清单；它们允许输入直接携带 userId，只适合受信 Workflow，不能直接暴露为对话 Tool。对话 Tool 必须改用本计划的 AgentContext 身份边界。

## 4. 总体架构决策

```text
浏览器
  │  Fusion-Token / POST SSE
  ▼
Java Spring MVC
  ├─ PersonalSpace Query/Mutation
  │   ├─ Account/Profile/Resume/Documents/Applications/Memory
  │   └─ 权限、版本、Action、Revert
  └─ Assistant
      ├─ 唯一 Session、消息、摘要
      ├─ 请求幂等、运行租约、限流
      └─ SSE 中继与最终落库
  │  X-Internal-Token + 短期 AgentContext
  ▼
Python FastAPI
  ├─ 上下文装配
  ├─ 最多 3 轮的 Tool Loop
  ├─ Pydantic Tool 参数校验
  ├─ OpenAI-compatible 流式调用
  └─ 通过 Java Internal API 执行 Tool
      │
      ├─ LLM Provider
      └─ Java 受控领域查询/变更提案
```

核心边界如下：

| 组件 | 负责 | 不负责 |
|---|---|---|
| 浏览器 | PersonalSpace 分区、输入、流式展示、确认卡、Revert 和 Memory 管理 | 权限判断、拼装历史、直连 Python |
| PersonalSpace Java | 当前用户资源、权限、Memory、版本、Action 和 Revert | Prompt 推理、模型 Tool 决策 |
| Assistant Java | Session、消息、摘要、幂等、租约、SSE | 直接修改业务资源 |
| Python | Prompt、历史裁剪、Tool Loop、流式协议 | 登录态、业务数据库、长期状态 |
| MySQL | 所有持久事实与审计 | token 级流式缓存 |
| Nginx | 网关、SSE 透传、隐藏 Internal API | 用户权限和 Tool 权限 |

现有 DAG Workflow 继续处理岗位结构化、简历解析和定时批任务；对话 Agent 不嵌入 DAG。

## 5. 单用户单 Session

### 5.1 Session 定义

Session 是 PersonalSpace 下用户永久唯一的 Assistant 对话资源，不等于 PersonalSpace 本身。公开 API 不接收 `userId` 或 `sessionId`，全部从当前登录态推导。Session 在用户第一次发送消息时惰性创建。

数据库以 `user_id` 为主键，硬性保证同一用户最多一条 Session 记录。创建使用 `INSERT ... ON DUPLICATE KEY`，禁止“先查再插”的竞态写法。

### 5.2 清空语义

区分三种操作：

1. **清空对话**：`epoch + 1`，清除摘要和运行租约，保留长期记忆。
2. **清空记忆**：只清除 `memory_json`，不删除消息。
3. **重置 Assistant**：使当前 epoch 和 run 失效，删除消息和摘要；下次发送时重新惰性创建。PersonalSpace Memory 和已应用变更历史均保留。

账号注销或明确的个人数据永久删除属于另一条高优先级隐私流程，会删除加密 action 历史，并明确告知之后无法恢复版本。

使用 `epoch` 的原因是：清空后旧模型流即使迟到，也无法通过 `runId + epoch` 校验重新写回已清空的对话。

清空或重置时，将旧 epoch 尚未确认的 `PENDING` action 标记为 `SUPERSEDED`；已经 `APPLIED` 的 action 和其 revert 能力不受影响。

### 5.3 单用户并发

Session 保存 `active_run_id` 和 `lease_until`。开始一轮前执行条件更新：

```sql
UPDATE fc_ai_session
SET active_run_id = :runId,
    lease_until = :leaseUntil,
    updated_at = CURRENT_TIMESTAMP(3)
WHERE user_id = :userId
  AND (active_run_id IS NULL OR lease_until < CURRENT_TIMESTAMP(3));
```

- 更新 1 行：获得运行权。
- 更新 0 行：返回 `409 AI_RUN_ACTIVE`。
- 流式运行期间定时续租。
- 完成、失败或取消时仅允许匹配 `active_run_id` 的请求释放租约。
- 定时清理过期租约，并将对应 `PENDING/STREAMING` 助手消息标为 `FAILED`。
- 不允许持有数据库事务等待 LLM 或用户确认。

## 6. 数据模型

### 6.1 `fc_ai_session`

| 字段 | 类型 | 约束与用途 |
|---|---|---|
| `user_id` | BIGINT | 主键，关联当前用户 |
| `epoch` | BIGINT | 非空，默认 1；清空对话时递增 |
| `summary` | TEXT | 压缩后的旧对话摘要 |
| `summary_through_message_id` | BIGINT | 摘要覆盖到的最后消息 ID |
| `active_run_id` | CHAR(36) | 当前运行 UUID，可为空 |
| `lease_until` | DATETIME(3) | 当前运行租约过期时间 |
| `last_message_at` | DATETIME(3) | Session 排序与状态展示 |
| `created_at` | DATETIME(3) | 创建时间 |
| `updated_at` | DATETIME(3) | 更新时间 |

索引：

- `PRIMARY KEY (user_id)`；
- `KEY idx_ai_session_lease (lease_until)`，供过期恢复任务使用。

### 6.2 `fc_ai_message`

| 字段 | 类型 | 约束与用途 |
|---|---|---|
| `id` | BIGINT | 雪花 ID 主键 |
| `user_id` | BIGINT | Session 所属用户 |
| `epoch` | BIGINT | 消息所属 Session 世代 |
| `run_id` | CHAR(36) | 服务端生成的本轮运行 UUID |
| `request_id` | VARCHAR(64) | 前端生成的单轮幂等键 |
| `role` | TINYINT | 1-user，2-assistant |
| `status` | TINYINT | 0-pending，1-streaming，2-completed，3-failed，4-cancelled |
| `content` | MEDIUMTEXT | 用户输入或最终助手文本 |
| `attachment_ids` | JSON | 用户消息显式附带的自有 fileId；助手消息为空 |
| `model` | VARCHAR(128) | 实际使用模型，可为空 |
| `finish_reason` | VARCHAR(32) | 正常结束、长度限制、Tool 上限等 |
| `prompt_tokens` | INT | Provider 可用时记录 |
| `completion_tokens` | INT | Provider 可用时记录 |
| `error_code` | VARCHAR(64) | 稳定错误码，不保存内部堆栈 |
| `created_at` | DATETIME(3) | 创建时间 |
| `updated_at` | DATETIME(3) | 更新时间 |

索引和唯一约束：

```text
PRIMARY KEY (id)
UNIQUE KEY uk_ai_message_request (user_id, epoch, request_id, role)
UNIQUE KEY uk_ai_message_run (user_id, epoch, run_id, role)
KEY idx_ai_message_page (user_id, epoch, id)
KEY idx_ai_message_state (status, updated_at)
```

同一 `request_id` 对应一条 user 消息和一条 assistant 消息。Tool 调用过程不单独作为聊天消息持久化，避免把内部数据混入可见历史。

### 6.3 `fc_user_memory`

Memory 由个人空间持有，不随 Agent Session reset 删除：

| 字段 | 类型 | 用途 |
|---|---|---|
| `user_id` | BIGINT | 主键，当前空间所有者 |
| `memory_json` | JSON | 白名单长期记忆，应用层限制 4KB |
| `version` | BIGINT | CAS 版本，默认 0 |
| `created_at` | DATETIME(3) | 创建时间 |
| `updated_at` | DATETIME(3) | 更新时间 |

### 6.4 `fc_user_change_action`

该表由个人空间提供，相当于 Git commit，保存网页或 Agent 的一次用户可理解操作。一个 action 可以通过 `fc_user_change_item` 原子修改多个资源，例如一次简历解析同时更新 Profile 和 Resume。

| 字段 | 类型 | 用途 |
|---|---|---|
| `id` | BIGINT | 操作 ID |
| `user_id` | BIGINT | 操作所有者 |
| `actor_type` | VARCHAR(16) | USER、AGENT、SYSTEM |
| `origin` | VARCHAR(32) | PROFILE_UI、RESUME_UI、FILE_UI、AGENT_TOOL 等固定来源 |
| `epoch` | BIGINT | Agent 来源时的 Session 世代；网页操作可为空 |
| `run_id` | CHAR(36) | Agent 来源运行 UUID；网页操作可为空 |
| `request_id` | VARCHAR(64) | 对话轮次或网页操作幂等键 |
| `tool_name` | VARCHAR(64) | 固定 Tool 名 |
| `action_type` | TINYINT | 1-apply，2-revert |
| `reverts_action_id` | BIGINT | 回退操作引用的原操作 |
| `status` | TINYINT | pending/applied/rejected/superseded/conflict/failed |
| `idempotency_key` | VARCHAR(96) | Tool 操作幂等键 |
| `args_hash` | CHAR(64) | 规范化参数 SHA-256 |
| `schema_version` | SMALLINT | 变更载荷版本，默认 1 |
| `reason` | VARCHAR(256) | 用户可理解的修改原因 |
| `error_code` | VARCHAR(64) | 稳定失败原因 |
| `confirmed_at` | DATETIME(3) | 用户确认时间 |
| `applied_at` | DATETIME(3) | 实际执行时间 |
| `created_at` | DATETIME(3) | 创建时间 |
| `updated_at` | DATETIME(3) | 更新时间 |

约束和索引：

```text
UNIQUE KEY uk_ai_action_idempotency (user_id, idempotency_key)
KEY idx_ai_action_user (user_id, status, created_at)
KEY idx_ai_action_revert (reverts_action_id)
```

同一个幂等键如果参数哈希不同，返回 `409 SPACE_ACTION_KEY_CONFLICT`。

`APPLIED` action 是不可变历史记录，不因后来 revert 而改成“已回滚”状态。每次 revert 都创建一条新的 `action_type=REVERT` 记录，并通过 `reverts_action_id` 指向被反向变更的 action；REVERT action 本身也能再次被 revert。

由于历史需要长期可还原，后续业务字段重命名、枚举迁移或 JSON 结构升级必须同时提供 action payload 迁移或按 `schema_version` 读取的兼容转换；不能直接删除旧字段解释逻辑，否则会破坏永久 revert 承诺。

### 6.5 `fc_user_change_item`

该表相当于一个 commit 中被修改的文件，保存单个资源的版本、字段差异和加密快照。

| 字段 | 类型 | 用途 |
|---|---|---|
| `id` | BIGINT | Item ID |
| `action_id` | BIGINT | 所属 action |
| `item_order` | SMALLINT | 稳定应用和展示顺序 |
| `resource_type` | VARCHAR(32) | PROFILE、RESUME、RESUME_FILE、QUESTIONNAIRE_ANSWER、MEMORY |
| `resource_key` | VARCHAR(128) | userId、fileId 或 jobPostId 等领域键 |
| `operation` | VARCHAR(24) | PATCH、CREATE、SOFT_DELETE、RESTORE、STATE_TRANSITION |
| `changed_fields` | JSON | 实际修改字段名，不保存敏感明文 |
| `expected_version` | BIGINT | 提案时资源版本；资源不存在时为空 |
| `applied_version` | BIGINT | 应用后资源版本 |
| `before_ciphertext` | MEDIUMBLOB | AES-GCM 加密的修改前字段快照 |
| `after_ciphertext` | MEDIUMBLOB | AES-GCM 加密的修改后字段快照 |
| `key_version` | SMALLINT | 加密密钥版本 |
| `created_at` | DATETIME(3) | 创建时间 |

约束和索引：

```text
UNIQUE KEY uk_ai_change_item_order (action_id, item_order)
UNIQUE KEY uk_ai_change_item_resource (action_id, resource_type, resource_key)
KEY idx_ai_change_item_resource (resource_type, resource_key, action_id)
```

确认 action 时按 `resource_type + resource_key` 固定排序锁定和更新所有 item；任一权限、验证或 CAS 失败则整个数据库事务不写入。这样简历解析可以在一个 action 中同时修改 Profile 和 Resume，不产生半成功。

文件字节无法纳入 MySQL 事务。文件类 action 先把用户已上传的字节写入不可变存储，再在数据库事务中切换元数据状态；失败时由恢复任务处理未引用暂存文件。模型参数永远不包含本地路径或 storagePath。

### 6.6 业务表版本与可逆资源

为以下表增加 `version BIGINT NOT NULL DEFAULT 0`：

- `fc_user_profile`；
- `fc_resume`；
- `fc_resume_file`；
- `fc_questionnaire_answer`。

为可逆资源增加：

- `fc_resume_file.deleted_at DATETIME(3)`、`updated_at DATETIME(3)`、可选 `content_hash`；
- `fc_questionnaire_answer.deleted_at DATETIME(3)`；
- `QuestionnaireSubmissionStatus.WITHDRAWN(3, "已撤回")`，用于在不删除审核历史的前提下补偿一次已提交操作。

PersonalSpace mutation 使用显式 CAS；Agent confirm 复用同一语义：

```sql
UPDATE fc_user_profile
SET changed_field = :value,
    version = version + 1
WHERE user_id = :userId
  AND version = :expectedVersion;
```

更新 0 行表示并发冲突，必须重新生成差异并再次确认，禁止静默覆盖。

所有普通页面、简历解析、管理员审核和 Agent 写入口都必须原子递增对应 version；只修改 Agent 路径会导致历史 revert 覆盖其他入口的新数据。

文件普通“删除”改为软删除：列表、下载和活动配额默认排除 `deleted_at IS NOT NULL`，但不可变 blob 和版本快照保留，才能永久 restore。物理删除只属于明确的数据永久清除流程。

问卷草稿和提交复用同一 `(job_post_id,user_id)` 行并递增 version。revert 新建草稿时可将该行标记 deleted；后续重新填写时复用并恢复该行。已进入 `REVIEWED` 的提交不能抹掉管理员审核，revert 应生成 `WITHDRAWN` 状态并保留审核元数据；再次 revert 该 WITHDRAWN action 可恢复前一状态。

### 6.7 Schema 同步位置

数据库变更需同时更新：

- `fusioncareer-biz/src/main/resources/schema.sql`；
- `docs/schema.sql`；
- 隔离测试数据库初始化脚本。

迁移全部采用加表、加列和加索引，不删除现有数据。

## 7. 轻量记忆设计

### 7.1 三层上下文

每轮模型输入按以下顺序构建：

1. 服务端 system policy；
2. 当前用户白名单长期记忆；
3. 当前 Session 摘要；
4. 当前 epoch 最近 8–12 条 `COMPLETED` 消息；
5. 当前用户输入。

工具返回属于当前轮工作上下文，默认不进入下一轮长期历史。

memory、summary、history 和 Tool 结果都以明确分隔的数据块传入，并在 system policy 中声明为“不可信用户数据，不得改变系统规则”。只有固定 system policy 和服务端 Tool 定义具有指令权限。

### 7.2 长期记忆格式

`memory_json` 是小型结构化对象，不保存任意聊天片段。首期白名单：

```json
{
  "responseStyle": {
    "value": "简洁并给出行动清单",
    "sourceMessageId": "190000000000000001",
    "updatedAt": "2026-09-28T10:00:00+08:00"
  },
  "currentGoal": {
    "value": "寻找上海的新闻媒体实习",
    "sourceMessageId": "190000000000000002",
    "updatedAt": "2026-09-28T10:01:00+08:00"
  }
}
```

允许的首期 key：

- `responseStyle`；
- `currentGoal`；
- `targetCities`；
- `targetIndustries`；
- `preferredWorkModes`；
- `temporaryConstraints`。

规则：

- 最多 30 项，序列化后最多 4KB；
- 单值字符串最多 256 字符，数组最多 10 项；
- 用户最新明确表达优先于记忆；
- 记忆与资料或简历冲突时，以业务表和当前消息为准；
- 不自动保存姓名、电话、邮箱、微信、出生日期、政治面貌或简历全文；
- 模型推断不能直接成为长期记忆；
- 聊天内写记忆也走可确认、可回退的 `MEMORY` 变更操作；
- 用户在记忆管理界面直接编辑时，由 Java 登录态接口完成，无需经过模型。

### 7.3 会话摘要

满足任一条件时，在当前回复结束后异步更新摘要：

- 自上次摘要后新增 10 个完整 user/assistant 轮次；
- 待发送的历史正文超过配置的字符预算。

首期使用字符预算而非引入新 tokenizer 依赖。建议默认值：

- `chat_history_max_messages=12`；
- `chat_context_max_chars=20000`；
- `chat_summary_max_chars=2000`。

摘要任务仅处理 `summary_through_message_id` 之后的完整消息，并通过游标 CAS 防止并发覆盖。摘要失败不影响用户当前回复，继续使用旧摘要和最近消息。

摘要不得写入模型未明确表达的事实，也不得将 Tool 内部字段或错误堆栈写入长期内容。

Java 在回复完成后判断阈值；需要摘要时异步调用 Python `POST /api/internal/chat/summarize`。请求只包含旧摘要、待压缩的已完成消息和目标 messageId；Python 使用无 Tool、低温度、固定输出上限的摘要提示词返回纯文本。Java 校验长度后按 `epoch + summaryThroughMessageId` 做 CAS 更新。该调用失败时不重试当前用户请求，也不阻塞 SSE `done`。

## 8. Agent 运行模型

### 8.1 有界 Tool Loop

Python 使用现有 `AsyncOpenAI` 实现单 Agent 循环：

```text
准备 system、memory、summary、history、current input
→ 调用模型
→ 若返回 Tool Call：校验并执行允许的 Tool
→ 把安全 Tool 结果加入本轮上下文
→ 再次调用模型
→ 无 Tool Call 时输出最终文本
```

硬限制：

- 最多 3 轮模型调用；
- 最多 4 次 Tool 调用；
- 每个 run 最多创建 1 个写入提案；需要同时更新多个资源时合并为一个 action 下的多个 change item；
- 只读 Tool 可以受控并行，写入提案 Tool 必须串行；
- 单个查询 Tool 默认 3 秒超时；
- Tool 返回序列化后最多 8KB；
- 单次 Agent 总超时默认 120 秒；
- 单用户最多一个 active run；
- 全局使用有界并发，不使用无限线程或无限任务队列。

达到限制时返回可理解的部分结果，并以稳定 `finishReason` 结束，不能无限循环。

只读 Tool 的瞬时网络错误最多自动重试一次且仍计入总超时；写提案只允许携相同 idempotencyKey 重试，不能生成新的 Tool Call 来规避幂等约束。

### 8.2 Provider 兼容

继续使用 OpenAI-compatible Chat Completions，不在首期引入 PydanticAI、OpenAI Agents SDK、LangGraph 或其他运行时。

必须通过 Fake Provider 和至少一个真实配置 Provider 验证：

- streamed text delta；
- streamed Tool Call 参数拼接；
- Tool finish reason；
- usage 字段缺失；
- thinking/reasoning 字段存在时的忽略行为；
- 流中断、限流、超时和非法 Tool 参数。

若 Provider 不支持流式 Tool Call，通过配置降级为“Tool 阶段非流式、最终文本流式”；不得静默关闭 Tool 权限校验。

### 8.3 Python 目录建议

保持实现紧凑，建议仅增加：

```text
fusioncareer-agent/app/chat/models.py
fusioncareer-agent/app/chat/service.py
fusioncareer-agent/app/chat/tools.py
fusioncareer-agent/tests/chat/
```

并最小修改：

- `app/integrations/llm.py`：增加 messages、tools 和 stream 能力；
- `app/integrations/backend.py`：增加 Agent Tool Internal API；
- `app/api/routers/internal.py`：增加 `/chat/stream`；
- `app/config.py`：只增加已确定需要的上限和密钥配置。

不创建通用 Agent Factory、Provider Factory 或第二套插件系统。

## 9. Tool 权限与数据可见性

### 9.1 双重身份边界

现有 `X-Internal-Token` 只能证明调用方是受信 Python 服务，不能证明当前操作属于哪个用户。对话 Tool 额外使用短期 `AgentContext`：

```json
{
  "sub": "当前用户ID",
  "run": "运行UUID",
  "epoch": 3,
  "request": "前端幂等键",
  "scopes": [
    "account:read", "profile:read", "resume:read", "file:read",
    "job:search", "questionnaire:read", "application:read",
    "profile:propose", "resume:propose", "file:propose",
    "questionnaire:propose", "memory:propose"
  ],
  "iat": 1790550000,
  "exp": 1790550300
}
```

实现规则：

- Java 在登录态校验后签发；
- 使用标准库 HMAC-SHA256 和 Base64URL，不新增 JWT 依赖；
- 使用独立环境变量 `AGENT_CONTEXT_SECRET`，不得复用登录 Token 或 LLM Key；
- 默认有效期 5 分钟，只覆盖一次最大 120 秒的运行；
- Java 调 Python 时通过 `X-Agent-Context` 发送；
- Python 不解析、不修改，只在 Tool 回调 Java 时原样转发；
- Java Internal Tool 接口同时校验 `X-Internal-Token` 和 `X-Agent-Context`；
- 验证签名、过期时间、run、epoch 和 scope，并使用常量时间比较签名；
- AgentContext 永远不返回浏览器，不写日志，不作为模型输入。

这里的 5 分钟仅是内部服务能力凭证有效期，与 action 的确认或 revert 生命周期无关；历史 APPLIED action 不依赖旧 AgentContext。

### 9.2 模型不可控制的参数

以下内容不得出现在模型可见 Tool Schema 中：

- `userId`、`sessionId`、`epoch`、`runId`；
- 角色和权限范围；
- 表名、列名、SQL 片段；
- Internal URL、Token、Header；
- 返回字段投影规则。

Python Tool handler 从当前 `RunContext` 获取这些信息。模型只能提供经过 Pydantic 校验的业务过滤条件或字段 patch。

### 9.3 首期只读 Tool

| Tool | 模型参数 | Java 权限规则 | 最大返回 |
|---|---|---|---|
| `read_account` | 无 | 当前登录账号的公开自有信息，只读 | 2KB |
| `read_profile` | 无 | 只读 `context.userId` 的资料 | 4KB |
| `read_resume` | `sections?` | 只读 `context.userId` 的简历；只返回请求章节 | 8KB |
| `list_files` | page、size | 只列当前用户未软删除文件 | 20 条/8KB |
| `read_file` | fileId | 校验所有权；返回元数据或供解析器读取，不把二进制塞入 Prompt | 2KB 元数据 |
| `read_file_quota` | 无 | 当前用户活动配额和历史存储量 | 2KB |
| `search_jobs` | keyword、city、category、workMode、page、size | 仅发布中且未截止岗位，size ≤ 10 | 10 条/8KB |
| `read_job` | jobId | 重新校验该岗位当前可见 | 8KB |
| `read_questionnaire` | jobPostId | 岗位可见且题目属于该岗位 | 20 题/16KB |
| `list_applications` | status、page、size | 只读当前用户自己的投递 | 10 条/8KB |
| `read_application` | jobPostId | 只读当前用户对该岗位的答案和状态 | 16KB |

禁止提供 `query_database`、`run_sql` 或任意资源读取 Tool。

`search_jobs` 使用固定 DTO：

```json
{
  "keyword": "新媒体",
  "jobCategory": "MEDIA",
  "jobSubCategory": null,
  "recruitType": "DAILY_INTERNSHIP",
  "workDurationType": null,
  "workPeriodType": null,
  "workMode": "OFFLINE",
  "workProvince": "上海",
  "workCity": "上海",
  "reqEduLevel": null,
  "salaryMin": null,
  "salaryMax": null,
  "recommended": null,
  "sourceType": null,
  "internalApply": null,
  "sort": "NEWEST",
  "page": 1,
  "limit": 10
}
```

约束：keyword 最多 100 字符；page 为正整数；limit 为 1–10；枚举直接使用现有 API 枚举；`status` 不对模型开放并由 Java 强制为 PUBLISHED；不允许任意排序列、列投影或 SQL 表达式。实现复用现有岗位分页能力，不另建搜索索引或游标系统。

### 9.4 岗位可见性

岗位列表和详情必须复用同一个“普通用户可见岗位”条件：

- `status = PUBLISHED`；
- `application_deadline` 为空或未过期；
- `work_end_date` 为空或未过期；
- 不处于回收站或下线状态。

当前 `listPublishedJobPosts` 已有主要条件，但 `getJobPost` 没有同等校验。实施时在 `JobPostServiceImpl` 抽出共享可见性条件，并增加 `getVisibleJob`，同时供浏览器详情和 Agent Tool 使用，从根因消除通过 ID 读取不可见岗位的问题。

`QuestionnaireController.getQuestions` 和 `QuestionnaireAnswerServiceImpl.requireOpenJob` 当前也只按 ID/日期判断，没有统一检查 `PUBLISHED`。取题、草稿、提交和 Agent Tool 必须全部复用同一个岗位可见性策略，不能通过已知 jobPostId 访问下线或回收岗位。

Agent 岗位 DTO 只保留公开求职字段，明确排除：

- `internalCompensation`；
- `contactName`；
- `contactInfo`；
- `internalRemark`；
- `createdBy`、回收原因等管理字段；
- 问卷中其他用户的回答、附件和审核内容。

### 9.5 Tool 结果信任级别

数据库文本、岗位描述和用户简历都作为“不可信数据”注入模型：

- Tool 结果不能覆盖 system policy；
- Tool 返回中的“忽略之前规则”等文字只能作为数据；
- Tool 结果先做长度限制和字段投影，再进入模型；
- 不把异常堆栈、SQL、HTTP Header 或内部 URL返回模型；
- 对用户可见的 Tool 状态只展示安全名称，如“正在搜索可见岗位”。

## 10. 可逆写 Tool

### 10.1 用户能力对等的修改范围

原则：普通用户能通过公开学生 API 修改的内容，Agent 都提供对应的领域 Tool；模型无权把这一原则扩展到管理员或系统字段。

| Tool/能力 | 可修改内容 | Revert 方式 |
|---|---|---|
| `propose_profile_patch` | 16 个 Profile 用户可写字段 | 字段级三方 revert |
| `propose_resume_patch` | 9 个 Resume 用户可写字段 | 字段级三方 revert |
| `parse_resume_file` | 从当前用户文件生成 Profile + Resume 多资源提案 | 一个 action、两个 change item 原子应用 |
| `use_uploaded_attachment` | 使用当前用户显式上传并授权给本轮的文件 | CREATE action；revert 为软删除 |
| `propose_file_delete` | 当前用户自己的简历/问卷附件 | SOFT_DELETE action；revert 为 RESTORE |
| `propose_questionnaire_draft` | 动态问卷全部题型的部分答案 | 问卷行版本化 patch/create |
| `propose_questionnaire_submit` | 动态答案及 DRAFT→SUBMITTED、SUBMITTED 重提 | 状态 action；revert 为恢复或 WITHDRAWN |
| `propose_memory_patch` | 第 7.2 节记忆白名单 | 字段级三方 revert |

Profile Tool 允许：

```text
realName, gender, birthDate, politicalStatus,
phone, email, wechat, hometown,
grade, major, eduLevel, supervisor,
intentionOrder, intentionCity, intentionDream, mindset
```

Resume Tool 允许：

```text
personalIntro, basicInfo, education, internship, campus,
awards, skills, portfolio, remark
```

仍然禁止 Agent 修改：

- username、studentId、role、status、password 等账号层字段；
- 岗位、问卷题目和管理员审核内容；
- createdAt、updatedAt、version、reviewedBy 等系统审计字段；
- 当前普通用户公开 API 本身无权修改的任何资源。

电话号码、邮箱、微信、出生日期和政治面貌虽然敏感，但当前普通用户确实可以自行编辑，因此 Agent 可以提出修改；确认卡和历史查询必须按字段脱敏，加密快照不得进入 Prompt 或日志。

模型可见的提案参数统一为字段 patch，不复用整个 `UserProfileRequest` 或 `ResumeRequest`：

```json
{
  "changes": [
    {
      "field": "intentionCity",
      "operation": "SET",
      "value": ["上海", "杭州"]
    }
  ],
  "reason": "按用户要求更新意向城市"
}
```

- `field` 是每个 Tool 自己的枚举，不接受任意 JSON Path；
- `operation` 首期只有 `SET` 和 `CLEAR`；
- Profile/Resume 可以在一次提案中修改其全部白名单字段；
- Java 读取当前资源和 version，模型不能提供 before 值或 expectedVersion；
- Java 使用现有业务枚举、长度和交叉字段规则再次验证；
- 展示标题、字段中文名和 before/after 差异完全由 Java 生成。

`intentionCity` 对模型暴露为字符串数组，Java 负责与当前数据库 JSON 文本表示转换，不能要求模型手写转义 JSON。

问卷 Tool 使用结构化答案而不是 JSON 字符串：

```json
{
  "jobPostId": "190000000000000010",
  "answers": [
    {"questionId": "190000000000000011", "value": "回答文本"},
    {"questionId": "190000000000000012", "value": ["选项A", "选项B"]},
    {"questionId": "0", "value": {"fileId": "190000000000000013"}}
  ]
}
```

Java 必须验证岗位当前可见、题目属于岗位、questionId 唯一、答案类型匹配、单选/多选值来自 options、文本长度合法，以及 fileId 属于当前用户且未软删除。草稿允许缺少非完整答案；正式提交必须满足全部 required 题。

上传的文件字节不经过模型 JSON。前端先通过登录态 multipart 接口上传用户选中的附件，再把只对当前用户有效的 fileId 放入本轮消息附件列表；Agent 只能使用该列表或 `list_files` 返回的自有 fileId。

Java 返回给 Python 的安全结果：

```json
{
  "actionId": "190000000000000003",
  "status": "PENDING",
  "title": "修改意向城市",
  "changes": [
    {
      "field": "intentionCity",
      "label": "意向城市",
      "beforeDisplay": "北京",
      "afterDisplay": "上海、杭州"
    }
  ],
  "baseVersion": 7
}
```

### 10.2 两阶段协议

模型没有直接写数据库的能力。写 Tool 只创建提案：

只有当前用户消息明确表达保存、修改、删除、提交、解析并应用等写入意图时，模型才能调用写提案 Tool；从简历或聊天中推断出的可能值只能作为建议，不能自动创建修改提案。

```text
1. 模型调用 propose_*_patch
2. Python 校验 Tool 参数并携 AgentContext 调 Java
3. Java 重新校验 scope、字段、值和资源归属
4. Java 读取当前资源及 version，计算字段级 before/after
5. Java 保存 PENDING action，返回脱敏确认卡和 baseVersion
6. 当前 Agent run 结束，不在后台等待用户
7. 用户通过登录态接口点击确认或拒绝
8. Java 再次校验并执行 CAS 更新
9. 成功后展示“已修改”和“撤销”入口
```

自然语言中的“好的”“确认”等回复不能由模型自行当作授权；确认必须来自受登录态保护的操作接口或明确的前端确认按钮。

### 10.3 提案幂等

Python 为每次 Tool Call 生成稳定 `idempotencyKey`，建议格式为：

```text
<runId>:<toolCallId>
```

Java 规范化 patch 后计算 `argsHash`：

- 同一用户、同一 key、相同 hash：返回既有 action；
- 同一用户、同一 key、不同 hash：返回冲突；
- 不允许因网络重试创建两条提案。

### 10.4 确认执行

`confirm` 在单个短事务内执行：

1. `SELECT ... FOR UPDATE` 锁定 action；
2. 校验 action 属于当前登录用户；
3. 校验状态为 `PENDING`，且没有被拒绝或 supersede；
4. 按稳定顺序锁定并重新读取全部 change item 的目标资源；
5. 逐 item 校验 `version = expectedVersion` 或预期不存在；
6. 使用与普通业务接口相同的 DTO 和领域规则验证每个 after 值；
7. 在同一数据库事务中应用全部 item，仅更新 patch 字段并各自 `version + 1`；
8. 保存每个 item 的加密 after 快照、`appliedVersion`，并将 action 置为 `APPLIED`；
9. 提交事务。

重复确认已成功的 action 时返回同一成功结果。版本冲突返回 `SPACE_ACTION_CONFLICT`，不能自动合并或覆盖。

当前 `UserProfileServiceImpl.saveOrUpdateProfile` 和 `ResumeServiceImpl.saveOrUpdateResume` 没有显式 SET/CLEAR 契约或 CAS。个人空间阶段必须先实现 `PersonalSpaceMutationService`；Agent 只调用该共享入口，不新增或直连另一套写服务。

### 10.5 Git 风格 Revert

确认提案和 revert 都不设置固定时间窗口：

- PENDING 提案只要目标资源仍处于 `expectedVersion` 就可以确认；
- 资源版本变化、用户拒绝或 Session reset 后，提案变为 `SUPERSEDED`，需要重新生成；
- 任意 `APPLIED` action 在账户生命周期内都可以随时发起 revert；
- 原 action 永久保持 `APPLIED`，历史不被改写或删除；
- revert 是新的 action，仍需展示差异并由用户确认；
- REVERT action 本身也可以再被 revert，等价于重新应用上一次修改。

生成 revert 提案时，Java 对原 action 的每个修改字段做三方比较：

```text
before  = 原 action 应用前的值
after   = 原 action 应用后的值
current = 当前业务表中的值

current == after  → 自动生成 after → before 的反向 patch
current == before → 该字段已经恢复，记为 no-op
其他情况          → 同字段后来被修改，进入 CONFLICT
```

无冲突流程：

```text
APPLIED action A
→ 用户请求 revert preview
→ 创建 PENDING REVERT action R，R.revertsActionId = A.id
→ 用户确认 R
→ 以当前 version 做 CAS 应用反向 patch
→ R 变为 APPLIED，A 保持 APPLIED
```

有冲突时，Java 返回 `before/after/current` 的脱敏三方差异。用户可以逐字段选择“恢复原值”或“保留当前值”，选择结果生成新的 REVERT 提案并再次确认。系统绝不静默覆盖后来修改，但也不因时间经过而失去 revert 能力。

首期以字段为最小合并单位：字符串、枚举、日期、JSON 数组和对象都作为一个原子字段比较；JSON 使用稳定序列化比较，但数组顺序保持业务语义。任一字段发生未解决冲突时整次 REVERT 不写入，避免部分成功造成难以理解的状态。

如果所有字段都已经等于 before，返回 `SPACE_ACTION_ALREADY_REVERTED`，不创建空 action。重复提交同一个 revert idempotencyKey 返回原有结果。

### 10.6 版本快照安全与长期保留

- `before/after` 使用 Java 标准库 AES-GCM 加密；
- 加密密钥来自独立环境变量，并记录 `keyVersion` 以支持轮换；
- IV 每条记录随机生成并与密文一起保存；
- 日志、SSE 和普通查询响应不得包含密文或完整敏感快照；
- 确认卡只展示必要字段级差异；联系方式等敏感字段的旧值默认脱敏，新值仅在当前登录用户的受保护确认卡中展示；
- before/after 只保存该 action 实际修改的字段，不保存整行无关字段；
- 加密版本快照在账户生命周期内保留，以保证任何历史 action 都能随时 revert；
- 密钥轮换必须保留旧 keyVersion 的解密能力，或在删除旧密钥前完成快照重新加密；
- 用户账号删除或依法执行完整数据删除时，删除 action、密文和密钥关联；数据已删除后不再承诺 revert；
- Tool 参数和 action 内容不写应用访问日志。

### 10.7 文件和问卷的领域化 Revert

文件不能用普通字段 patch 假装可逆：

- 上传到 Agent 的文件由前端通过 multipart 传输，模型只得到当前用户的 fileId 和安全元数据；
- 上传成功即记录 APPLIED CREATE action，因为用户选择并上传文件本身就是明确操作；
- 普通删除和 Agent 删除统一改为 `deleted_at` 软删除，不物理删除 blob；
- revert CREATE 等价于 SOFT_DELETE；revert SOFT_DELETE 等价于 RESTORE；
- list/download/parse/questionnaire 默认只接受未软删除文件；
- 明确永久数据删除是唯一物理删除入口，并在执行前提示永久失去 revert 能力；
- 活动文件继续受现有 30MB 配额；系统另外统计历史 blob 占用，历史容量不足时阻止新上传，不能通过静默清理旧 blob 破坏 revert。

简历解析是一个多资源操作：

1. 读取当前用户自有文件；
2. 只生成解析结果，不直接调用现有自动写回路径；
3. 建立一个 action，至少包含 PROFILE 和 RESUME 两个 change item；
4. 用户确认后在同一 MySQL 事务中 CAS 应用全部 item；
5. 任一 item 冲突时整个 action 不写入。

问卷不是任意 JSON 字段，而是受岗位、题目和状态机约束的领域资源：

- DRAFT 可反复 patch；
- SUBMIT action 同时记录 answers 和 submissionStatus；
- SUBMITTED 在未 REVIEWED 前按当前业务规则允许重提；
- REVIEWED 后不允许改写 answers 或删除审核结果；
- revert 一个后来已被审核的 SUBMIT action 时，生成 WITHDRAWN 状态并保留 reviewedAt、reviewedBy、reviewPassed、reviewComments；
- revert WITHDRAWN action 可恢复其前一状态，只要没有新的管理员状态变化；
- applicationCount 排除 DRAFT 和 WITHDRAWN；
- 学生 tabCounts、管理员列表/导出和所有 enum switch 必须显式处理 WITHDRAWN；
- 截止时间阻止新草稿/提交，但不阻止对历史 PersonalSpace action 发起 revert；涉及审核状态时仍走三方冲突和领域补偿。

## 11. 状态机

### 11.1 Session 运行状态

```text
IDLE ── acquire lease ──> RUNNING
RUNNING ── done/error/cancel ──> IDLE
RUNNING ── lease expired ──> RECOVERING ──> IDLE
```

数据库不必保存额外枚举，`active_run_id` 是否为空即可表达 IDLE/RUNNING；RECOVERING 是恢复任务的瞬时逻辑状态。

### 11.2 消息状态

```text
PENDING → STREAMING → COMPLETED
    ├──────────────→ FAILED
    └──────────────→ CANCELLED
```

- 用户消息直接以 `COMPLETED` 写入；
- 助手消息在调用 Python 前写为 `PENDING`；
- 收到 Python `start` 后转为 `STREAMING`；
- 文本 delta 只在内存累积，不逐 token 写数据库；
- 收到 `done` 后一次性写入最终文本和 usage；
- 失败和取消保留消息壳及稳定错误码，不把未完成文本加入后续上下文。

### 11.3 Action 状态

```text
PENDING APPLY
   ├── confirm + CAS success ──> APPLIED APPLY（不可变）
   ├── reject ──> REJECTED
   ├── base version changed ──> SUPERSEDED
   └── system failure ──> FAILED

APPLIED action A
   └── request revert ──> PENDING REVERT R
                              ├── confirm + CAS success ──> APPLIED REVERT（不可变）
                              ├── three-way conflict ──> CONFLICT
                              └── reject ──> REJECTED
```

`APPLIED` 记录不再发生状态变化；是否被反向修改通过后续 `REVERT` action 链计算。失败的系统操作进入 `FAILED`，并保存稳定错误码；不能把内部异常文本持久化为用户可见内容。

如果来源 run 在正常 `done` 之前失败或取消，该 run 创建的 `PENDING` action 统一转为 `REJECTED`，`error_code=RUN_NOT_COMPLETED`；已 `APPLIED` 的 action 不受对话连接状态影响。

## 12. Java 公开 API

Nginx 对外增加 `/api` 前缀，Java Assistant Controller 使用 `/personal-space/assistant`。Memory、Documents 和 Actions 路径由前置 PersonalSpace API 提供。

### 12.1 Session 与消息

| 方法 | Java 路径 | 用途 |
|---|---|---|
| GET | `/personal-space/assistant/session` | 获取当前用户唯一 Session 状态，未创建时返回空状态 |
| GET | `/personal-space/assistant/messages?beforeId=&size=` | 游标分页读取当前 epoch 消息，默认 20，最大 50 |
| POST | `/personal-space/documents` | multipart + clientRequestId 上传用户提供给空间/Agent 的文件，并记录 APPLIED CREATE action |
| POST | `/personal-space/assistant/messages/stream` | 发送消息并返回 `text/event-stream` |
| POST | `/personal-space/assistant/run/cancel` | 取消当前用户 active run |
| POST | `/personal-space/assistant/session/clear` | 清空对话，保留 Memory |
| DELETE | `/personal-space/assistant/session` | 重置 Assistant Session 和消息；保留 Memory 与变更历史 |

发送消息请求：

```json
{
  "clientRequestId": "018f6f5d-3f1c-7a90-b4f1-7cda2af9c010",
  "content": "用这份简历更新我的资料",
  "fileIds": ["190000000000000013"]
}
```

校验：

- `clientRequestId` 必填，长度不超过 64，只接受 UUID/ULID 安全字符；
- `content` trim 后非空，最多 8000 字符；
- `fileIds` 最多 5 个；Java 必须逐个验证属于当前用户且未软删除；
- 同一 requestId 重试不得再次调用模型；
- 若原请求已完成，返回已有完整消息的 snapshot 事件；
- 若同一请求仍在运行，返回当前运行状态，不创建新消息；
- 其他 requestId 遇到 active run 返回 409。

### 12.2 记忆

| 方法 | Java 路径 | 用途 |
|---|---|---|
| GET | `/personal-space/memory` | 查看全部白名单记忆 |
| PUT | `/personal-space/memory/{key}` | 用户直接设置一个记忆项 |
| DELETE | `/personal-space/memory/{key}` | 删除一个记忆项 |
| DELETE | `/personal-space/memory` | 清空全部长期记忆 |

直接记忆接口使用 `fc_user_memory.version` 做 CAS。冲突时重新读取，不做静默覆盖。

### 12.3 变更操作

| 方法 | Java 路径 | 用途 |
|---|---|---|
| GET | `/personal-space/actions?beforeId=&size=` | 分页查看当前用户完整修改历史和 revert 链 |
| GET | `/personal-space/actions/{id}` | 查看一个 action 的脱敏详情和三方冲突信息 |
| POST | `/personal-space/actions/{id}/confirm` | 确认并执行 PENDING 操作 |
| POST | `/personal-space/actions/{id}/reject` | 拒绝 PENDING 操作 |
| POST | `/personal-space/actions/{id}/revert` | 基于任意 APPLIED action 创建 REVERT 提案 |

所有按 actionId、messageId 查询的 SQL 必须同时带 `user_id = 当前登录用户`，不能先按 ID 查询再在 Controller 判断归属。

### 12.4 统一响应例外

普通接口继续使用 `{code,message,data}`。`POST /personal-space/assistant/messages/stream` 的处理规则：

- 流开启前的登录、参数、幂等和租约错误使用正常 HTTP 状态与统一响应；
- 流开启后的模型、Tool 和网络错误使用 SSE `error` 事件；
- 前端必须先检查 HTTP 状态和 `Content-Type`，再进入 SSE 解析。

## 13. Java Internal Tool API

仅 Python 可访问，必须同时携带两个认证 Header。

| 方法 | 路径 | Scope | 用途 |
|---|---|---|---|
| GET | `/internal/agent/account` | `account:read` | 当前账号只读投影 |
| GET | `/internal/agent/profile` | `profile:read` | 当前上下文用户资料投影 |
| GET | `/internal/agent/resume` | `resume:read` | 当前上下文用户简历投影 |
| GET | `/internal/agent/files` | `file:read` | 当前用户活动文件和配额 |
| GET | `/internal/agent/files/{id}` | `file:read` | 当前用户文件元数据/解析输入 |
| POST | `/internal/agent/jobs/search` | `job:search` | 搜索普通用户可见岗位 |
| GET | `/internal/agent/jobs/{id}` | `job:read` | 读取一个当前可见岗位 |
| GET | `/internal/agent/jobs/{id}/questionnaire` | `questionnaire:read` | 当前可见岗位的动态题目 |
| GET | `/internal/agent/applications` | `application:read` | 当前用户自己的投递 |
| GET | `/internal/agent/applications/{jobPostId}` | `application:read` | 当前用户单份草稿/投递 |
| POST | `/internal/agent/actions/prepare` | 对应 `*:propose` | 创建受控变更提案 |

Internal API 不接受 `userId`。Verifier 从 `AgentContext.sub` 取得用户，并将其作为不可变参数传给领域 Service。

## 14. Python Internal Chat API

Java 调用：

```text
POST /api/internal/chat/stream
X-Internal-Token: <service token>
X-Agent-Context: <signed context>
Content-Type: application/json
Accept: text/event-stream
```

请求体：

```json
{
  "runId": "6274f9c9-bf88-42ea-b811-cdf37ef73a6f",
  "epoch": 3,
  "requestId": "018f6f5d-3f1c-7a90-b4f1-7cda2af9c010",
  "input": "帮我找上海的新闻媒体实习",
  "attachments": [
    {"fileId": "190000000000000013", "name": "resume.pdf", "mimeType": "application/pdf"}
  ],
  "memory": {},
  "summary": "用户目前主要关注媒体实习。",
  "history": [
    {"role": "user", "content": "我想找实习"},
    {"role": "assistant", "content": "你更关注哪些城市和行业？"}
  ]
}
```

Python 对 Java 提供的 memory、summary 和 history 仍执行结构、角色、数量和长度校验，不能信任网络边界上的任意 JSON。

摘要接口：

```text
POST /api/internal/chat/summarize
X-Internal-Token: <service token>
```

该接口不提供 Tool、不访问数据库，只返回 `{summary, throughMessageId}`。Java 仍是摘要游标和 Session 状态的唯一写入方。

## 15. SSE 事件协议

浏览器使用 `fetch` 发送 POST 并读取 `ReadableStream`；原生 `EventSource` 无法方便地发送 JSON Body 和 `Fusion-Token` Header，不作为首期方案。

### 15.1 事件类型

```text
event: start
data: {"runId":"...","requestId":"...","userMessageId":"...","assistantMessageId":"..."}

event: delta
data: {"runId":"...","seq":1,"text":"正在"}

event: tool_status
data: {"runId":"...","callId":"...","name":"search_jobs","status":"RUNNING"}

event: action_required
data: {"runId":"...","actionId":"...","title":"修改意向城市","changes":[],"baseVersion":7}

event: done
data: {"runId":"...","messageId":"...","finishReason":"stop","usage":{}}

event: error
data: {"runId":"...","reason":"AI_UPSTREAM_UNAVAILABLE","message":"暂时无法生成回答","retryable":true}

event: cancelled
data: {"runId":"...","reason":"CLIENT_DISCONNECTED"}

event: ping
data: {"at":"2026-09-28T10:00:00+08:00"}
```

### 15.2 协议约束

- `start` 是第一个业务事件；
- 每个 run 只有一个终态：`done`、`error` 或 `cancelled`；
- `delta.seq` 严格递增；
- 15 秒没有业务事件时发送 `ping`；
- Tool 状态只包含安全名称和状态，不包含原始参数或返回值；
- `action_required` 的标题、字段标签和脱敏差异由 Java 生成；
- 不发送模型 reasoning、内部堆栈、SQL、Token 或 AgentContext；
- Java 必须先提交最终消息和释放租约，再发送 `done`；
- 浏览器断开后 Java 取消 Python 请求，将助手消息标记为 `CANCELLED` 并释放租约；
- 连接断开和完成同时发生时，以数据库事务中先成功提交的终态为准；
- 首期不保存 token 事件用于重放；刷新后通过消息和 action API恢复最终状态。

### 15.3 重试语义

- 同一 `clientRequestId` 已完成：SSE 返回 `start`、单个完整 snapshot `delta` 和 `done`，不再次调用模型；
- 同一 `clientRequestId` 仍运行：返回 409 和当前 runId；
- 同一 `clientRequestId` 已失败或取消：返回保存的终态，前端“重试”必须生成新的 requestId；
- 同一 idempotency key 对应不同正文：返回 `AI_REQUEST_KEY_CONFLICT`。

## 16. Java 实现计划

### 16.1 API 模块

在 `fusioncareer-api` 增加最小请求和响应 DTO：

```text
AiMessageRequest
AiSessionResponse
AiMessageResponse
AiToolRequest / AiToolResponse
```

Attachment、Memory、Action、Job 和 Questionnaire DTO 复用 PersonalSpace/JobCatalog 已固定的契约，不在 Agent 模块重复定义。外部序列化字段遵守既定 camelCase 契约。DTO 使用 Bean Validation 限制长度、枚举、集合大小和必填项。

### 16.2 业务模块

建议增加以下职责明确的类：

| 类 | 职责 |
|---|---|
| `PersonalSpaceAssistantController` | 登录态公开 Session、消息、取消和清空 API |
| `InternalAiToolController` | Python 专用受控 Tool API |
| `AiChatService` | Session 惰性创建、租约、消息状态和历史读取 |
| `AgentContextService` | HMAC 签发与验证 |
| `AgentStreamClient` | JDK HttpClient 读取 Python SSE并转发 |

Agent 阶段只增加两个 Entity/Mapper：

```text
AiSessionEntity / AiSessionMapper
AiMessageEntity / AiMessageMapper
```

Memory、Action、Item、Profile/Resume/File/Questionnaire mutation 均复用前置个人空间实现的 `PersonalSpaceQueryService`、`PersonalSpaceMutationService` 和对应 Entity/Mapper。

不要增加通用 Repository、事件总线、Factory 或第二套 HTTP Client 抽象。`AgentStreamClient` 专门解决现有 `RestClient` 不支持逐事件消费的问题。

### 16.3 现有业务写入口

Profile、Resume、File、Questionnaire 的 version、PATCH、Action 和 Revert 由 PersonalSpace 前置阶段统一完成。Agent 阶段禁止再增加一条绕过 `PersonalSpaceMutationService` 的写路径。

普通旧客户端可暂时不提交 version，但兼容 Controller 必须转调 PersonalSpace mutation，并在每次成功更新时原子 `version + 1`。Agent confirm 使用同一实现并额外要求 `WHERE version = expectedVersion`。

如果提案时资源不存在：

- action 快照记录 `exists=false`；
- confirm 只在资源仍不存在时插入 version 1；
- revert 通过字段级三方比较生成反向 patch；资源后来没有变化时可删除首期新建的空资源，否则进入冲突处理；
- 任何中间修改都返回冲突。

### 16.4 加密与密钥

新增部署变量：

```text
AGENT_CONTEXT_SECRET=<HMAC secret>
USER_CHANGE_KEY=<AES-256 key>
USER_CHANGE_KEY_VERSION=1
```

密钥启动时校验长度。`USER_CHANGE_KEY` 属于 PersonalSpace 基础设施，缺失时生产业务写入必须启动失败或进入明确只读维护状态；`AGENT_CONTEXT_SECRET` 缺失时禁用 Agent Tool。不得使用默认密钥。两个密钥只存在于 Java 机，Python 不获取 Action 解密密钥或 HMAC 签名密钥。

## 17. 前端实施计划

前端位于独立 `FusionCareer-View` 仓库，使用单独提交。首期页面无需多会话侧边栏，只需要一个 Agent 工作区。

### 17.1 页面状态

必须处理：

- 首次空状态；
- 历史加载和向上分页；
- 正在连接；
- 文本流式追加；
- Tool 运行状态；
- 本轮附件上传、选择、移除和解析状态；
- 变更确认卡；
- 完成、失败、取消和超时；
- 页面刷新后的消息恢复；
- active run 时禁用重复发送；
- 可撤销操作和冲突提示；
- 记忆查看、编辑、删除和全部清空。

### 17.2 API 客户端

- 使用统一 API Client 注入 `Fusion-Token`；
- `fetch` POST `/api/personal-space/assistant/messages/stream` 并按行解析 SSE；
- 用户选文件时先 multipart 上传 `/api/personal-space/documents`，只把返回的 fileId 加入消息请求；
- 先检查 HTTP 状态和 `Content-Type`；
- 页面卸载时通过 `AbortController` 断开，并调用取消接口；
- 不把完整历史提交给后端；
- `clientRequestId` 在点击发送时生成，同一次网络重试保持不变；
- 用户主动“重新生成”时生成新的 requestId；
- 消息正文不持久化到 LocalStorage；数据库响应是唯一事实源。

### 17.3 确认卡

确认卡至少展示：

- 修改资源和字段；
- 脱敏的修改前/后值；
- 变更原因；
- 提案基于的数据版本；
- 确认、拒绝按钮；
- 应用成功后的“Revert 此修改”按钮；
- REVERT action 与被反向修改 action 的历史关系；
- 三方冲突时的原值、应用后值、当前值和逐字段选择。

按钮必须防重复提交。确认或回退冲突后重新读取 action，不显示假成功。

### 17.4 可访问性

- 流式文本区域使用适当的 `aria-live`，避免每个 token 都触发朗读；
- Tool 状态和错误不能只依赖颜色；
- 确认、拒绝和撤销可通过键盘操作；
- 聚焦顺序在新消息、确认卡和错误之间保持可预测；
- 用户可暂停自动滚动并复制最终文本。

## 18. 错误契约

AI 模块使用真实 HTTP 状态，并在统一响应 `data.reason` 中提供稳定机器码：

```json
{
  "code": 409,
  "message": "当前已有回答正在生成",
  "data": {
    "reason": "AI_RUN_ACTIVE",
    "retryable": true,
    "runId": "6274f9c9-bf88-42ea-b811-cdf37ef73a6f"
  }
}
```

建议错误集：

| HTTP | reason | 场景 |
|---|---|---|
| 400 | `AI_VALIDATION_ERROR` | 消息、记忆或 patch 不合法 |
| 403 | `AI_CONTEXT_INVALID` | Internal AgentContext 无效或 scope 不足 |
| 404 | `AI_RESOURCE_NOT_FOUND` | 消息、action、岗位不存在或不可见 |
| 409 | `AI_RUN_ACTIVE` | 同用户已有 run |
| 409 | `AI_REQUEST_KEY_CONFLICT` | 同幂等键对应不同正文 |
| 409 | `AI_SESSION_CHANGED` | 请求基于旧 epoch |
| 409 | `SPACE_ACTION_CONFLICT` | confirm 已 stale 或 revert 出现同字段冲突 |
| 409 | `SPACE_ACTION_ALREADY_REVERTED` | 目标字段已经全部处于 before 状态 |
| 429 | `AI_RATE_LIMITED` | 全局并发队列或用户频率超限 |
| 502 | `AI_UPSTREAM_UNAVAILABLE` | Provider 返回失败 |
| 503 | `AI_SERVICE_DISABLED` | 功能关闭、密钥缺失或 Agent 不健康 |
| 504 | `AI_RUN_TIMEOUT` | 总运行超时 |

增加一个 AI 模块异常处理器，携带 `httpStatus`、`reason`、安全 message 和 `retryable`，不要把 Provider 原始异常传给用户。现有 `resolveHttpStatus` 只识别有限状态码，不能让 429/502/503/504 意外返回 HTTP 200。

## 19. 幂等、取消与恢复

### 19.1 新建一轮

在短事务内完成：

1. 惰性插入 Session；
2. 检查 `(user_id, epoch, request_id)` 已有消息；
3. 相同 requestId 不同 content hash 返回冲突；
4. CAS 获取 Session 租约；
5. 写入 `COMPLETED` user 消息和 `PENDING` assistant 消息；
6. 提交事务；
7. 提交后才调用 Python。

正文 hash 不保存原文副本，可放在 assistant 消息扩展字段或通过 user 消息正文现算。实现时优先现算，除非性能数据证明需要新增列。

### 19.2 最终提交

提交模型结果前重新校验：

```text
session.userId == current user
session.epoch == run epoch
session.activeRunId == runId
assistant.status in (PENDING, STREAMING)
```

任一条件不满足即丢弃迟到结果，并将旧助手消息标记为 `CANCELLED` 或 `FAILED`，不能写回当前上下文。

### 19.3 客户端断开

- Java 停止向 SseEmitter 写入；
- 取消 JDK HttpClient 请求和 Python coroutine；
- Python 停止读取 Provider 流；
- Java 将消息标记为 `CANCELLED` 并释放匹配 runId 的租约；
- 将该 run 尚未确认的 `PENDING` action 标记为 `REJECTED`；
- 未完成文本不作为后续上下文；
- 前端可使用新的 requestId 重试。

### 19.4 服务崩溃

定时恢复任务扫描：

- `lease_until < NOW(3)` 且 `active_run_id IS NOT NULL` 的 Session；
- 对应 `PENDING/STREAMING` 助手消息；
- 当前版本已变化但仍为 `PENDING` 的 action，将其标记为 `SUPERSEDED`。

恢复任务使用条件更新，确保多 Java 实例只会有一个完成状态修复。首期不恢复模型生成，只把运行标记失败并允许用户重试。

## 20. 限流与资源上限

不引入 Redis。首期使用以下组合：

- 数据库租约保证每用户最多一个 active run；
- Java 有界 `TaskExecutor` 限制全局 SSE 代理并发；
- Python `asyncio.Semaphore` 限制全局模型调用并发；
- 队列满时直接返回 429，不无限排队；
- Nginx 和 Java 限制请求体大小；
- Tool、历史、记忆、摘要和输出均有硬上限；
- 超时后取消下游请求并释放资源。

初始建议值：

| 配置 | 默认值 |
|---|---|
| Java active streams | 16 |
| Java waiting queue | 64 |
| Python active runs | 16 |
| 单用户 active run | 1 |
| 消息长度 | 8000 字符 |
| 最近消息 | 12 条 |
| 记忆 | 4KB / 30 项 |
| 单 Tool 返回 | 8KB |
| 单 run Tool 调用 | 4 次 |
| 单 run 模型调用 | 3 次 |
| 单 run 总超时 | 120 秒 |

这些值全部通过环境配置，但不提供运行时任意修改管理面；上线后依据指标调整。

## 21. 测试计划

### 21.1 Java 单元与集成测试

使用隔离 MySQL，不连接真实 Python 或真实模型。

Session 与消息：

- 并发首次请求只创建一条 Session；
- 同用户第二个 run 返回 409；
- 过期租约可被安全接管；
- 相同 requestId 不重复消息和模型调用；
- 相同 requestId 不同正文返回冲突；
- clear 递增 epoch 且保留记忆；
- reset 清除 Assistant Session，但不清 PersonalSpace Memory；
- 旧 epoch 的迟到结果不能写回；
- 失败和取消助手消息不进入历史；
- 分页 SQL 始终包含 userId 和当前 epoch。

记忆：

- 非白名单 key、错误类型、超长值和超过 4KB 被拒绝；
- memory CAS 冲突不覆盖新值；
- 清空对话不清记忆；
- 清空记忆不清消息；
- 摘要游标 CAS 阻止旧任务覆盖新摘要。

可见性：

- Python 请求体伪造 userId 无效；
- 过期、篡改、错误 scope、错误 run 和旧 epoch 的 AgentContext 均失败；
- 只允许读取当前用户资料、简历和投递；
- 文件列表、读取、解析和问卷附件只能使用当前用户未软删除文件；
- 发布、下线、截止、回收站岗位在列表与详情的结果一致；
- 下线、截止、回收站岗位不能取题、保存草稿或提交；
- 不存在与不可见岗位统一 404；
- Agent 岗位 DTO 不含内部薪酬、联系人、内部备注和管理字段。

用户能力对等：

- Profile 16 个字段逐一覆盖 SET、CLEAR、类型、长度和敏感显示；
- Resume 9 个字段逐一覆盖 SET、CLEAR 和长度；
- intentionCity 数组与数据库 JSON 文本往返一致；
- 问卷拒绝未知 questionId、重复题、错误题型、非法选项和他人 fileId；
- 草稿允许缺项，submit 强制 required；
- SUBMITTED 可按现有规则重提，REVIEWED 不允许修改答案；
- 简历解析的 Profile + Resume 多 item action 要么全部成功，要么全部失败；
- 文件 CREATE、SOFT_DELETE、RESTORE 均保留 blob 并正确更新 version；
- 已审核提交的 revert 产生 WITHDRAWN，不清除审核元数据。

Action：

- Tool 重试不重复创建 action；
- 同 key 不同 patch 返回冲突；
- 非所有者确认、拒绝、revert 均返回 404；
- 目标资源 version 改变后，旧提案变为 `SUPERSEDED` 且不能确认；
- 重复 confirm 和 revert 请求幂等；
- 提案后普通页面修改导致 confirm stale；
- 后续只修改无关字段时，revert 保留无关字段并恢复原 action 字段；
- 后续修改同一字段时，revert 返回三方冲突且不写数据；
- 用户解决冲突后可确认新的 REVERT action；
- revert action 可再次 revert；
- 模拟跨多年时间后，历史 APPLIED action 仍能解密并生成 revert；
- 成功 revert 只恢复目标 action 修改的字段；
- 加密快照不以明文出现在数据库检查、日志或响应中；
- 密钥轮换后历史快照仍可解密和 revert；
- 账号数据永久清除后快照和 action 一并删除。

SSE 代理：

- 正常事件顺序和唯一终态；
- 心跳；
- Python 断流、非法事件、超时和 5xx；
- 浏览器断开触发取消；
- `done` 前数据库已完成最终提交；
- Tool 参数、返回和推理不泄漏到事件。

### 21.2 Python 测试

全部使用 Fake LLM 和 Fake Java Tool Server，不访问网络：

- 纯文本流；
- 多段 text delta 拼接；
- Tool Call 参数跨 chunk 拼接；
- 非法 JSON 参数；
- 未注册 Tool；
- scope 拒绝后立即终止；
- 只读 Tool 成功、无数据、超时和一次重试；
- 写 Tool 只生成提案，不直接声称修改成功；
- Tool 调用和模型轮次达到上限；
- AgentContext 原样转发且不进入 Prompt；
- 上下文顺序、消息裁剪、摘要和记忆大小；
- Tool 结果中的 Prompt Injection 不能覆盖 system policy；
- Provider 无 usage、不同 finish reason 和流中断；
- cancel 关闭 Provider 流和 Tool 任务；
- 日志不含用户正文、Tool 内容和 Token。

### 21.3 契约与端到端

扩展现有 Fake Services 和集成脚本：

1. 启动隔离 MySQL、Java、Python Fake LLM；
2. 模拟登录用户 A 和用户 B；
3. 完成普通多轮对话；
4. 搜索岗位并验证不可见字段未返回；
5. 尝试跨用户读取并确认被拒绝；
6. 一次修改全部 Profile 字段并确认敏感字段脱敏；
7. 上传并解析简历，确认 Profile + Resume 原子应用；
8. 软删除文件并 revert，确认同一 blob 可重新下载；
9. 生成问卷草稿、提交并验证动态答案和附件归属；
10. 对已审核投递执行 revert，确认生成 WITHDRAWN 且审核历史保留；
11. 确认普通字段修改并检查 version；
12. revert 并检查原值恢复；
13. 制造同字段后续编辑并验证三方冲突；
14. 清空 Session 并验证旧流无法复活；
15. 扫描 Java/Python 日志，确认隐私标记不存在。

### 21.4 前端验证

- 空、加载、流式、Tool、确认、完成、失败、取消状态；
- 刷新恢复消息与 action；
- 重复点击发送、确认和 revert；
- 401、409、429、502、503、504；
- 键盘操作、焦点和屏幕阅读器提示；
- 生产构建不包含 mock、固定 Token 或内部地址。

## 22. 可观测性

日志仅记录：

- `runId`、`requestId`、`actionId`；
- userId 的不可逆 HMAC 标识，不记录原始 userId；
- 模型名、耗时、字符数和 Provider 状态；
- Tool 名、耗时、outcome、返回条数和是否截断；
- action 状态转换和冲突类型；
- 租约接管、超时和清理数量。

指标：

```text
ai_runs_active
ai_runs_total{status}
ai_run_duration_seconds
ai_first_delta_seconds
ai_provider_errors_total{reason}
ai_tool_calls_total{tool,outcome}
ai_tool_duration_seconds{tool}
ai_actions_total{resource,status}
ai_action_conflicts_total{phase}
ai_memory_bytes
ai_file_history_bytes
ai_stale_leases_total
```

告警优先覆盖：Provider 连续失败、Tool 权限拒绝异常升高、租约大量过期、action 冲突率突增和 active stream 饱和。

不得采集 Prompt、消息正文、简历正文、Tool 原始结果、before/after 明文或签名 Token。

## 23. Nginx 与部署网络

### 23.1 浏览器到 Java 的 SSE

在通用 `location /api/` 之前增加更具体的 AI 路由，仍然只转发 Java：

```nginx
location ^~ /api/personal-space/assistant/ {
    proxy_pass http://java_backend/personal-space/assistant/;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_buffering off;
    proxy_cache off;
    gzip off;
    proxy_read_timeout 300s;
}
```

Java SSE 响应同时设置：

```text
Content-Type: text/event-stream; charset=UTF-8
Cache-Control: no-cache, no-transform
X-Accel-Buffering: no
```

需要同步修改所有实际使用的 Nginx 配置变体，避免只修本地或只修单机配置。

### 23.2 Java 到 Python

当前 `deploy/docker-compose.agent.yml` 只对 Docker 网络 `expose: 8900`，而 Java 示例使用跨主机 `http://10.107.13.184:8900`。生产上线前必须明确修复：

- Agent 端口只绑定 Python 机内网地址，不绑定公网地址；
- 主机防火墙只允许 Java 机访问；
- `/api/internal/**` 继续要求 `X-Internal-Token`；
- 公网 Nginx 继续拒绝 `/agent/api/internal/**`；
- 部署 smoke 必须从 Java 机实际请求 Python health 和 chat stream，不能只在 Python 机本地测试。

禁止为了连通而把全部 Agent Internal API 直接暴露到公网。

### 23.3 超时对齐

保证超时从内向外递增：

```text
Provider 单次调用 < Python 单 run 总超时
Python 总超时 < Java Internal 读取超时
Java SSE 超时 < Nginx proxy_read_timeout
```

建议首期：45 秒单次 Provider、120 秒总 run、150 秒 Java Internal、180 秒 Java SSE、300 秒 Nginx。15 秒心跳避免空闲链路被中间代理关闭。

## 24. 配置

只增加当前需求必需配置：

### Java

```text
AI_CHAT_ENABLED=false
AI_CHAT_WRITE_ENABLED=false
AI_CHAT_MAX_STREAMS=16
AI_CHAT_QUEUE_SIZE=64
AI_CHAT_LEASE_SECONDS=45
AI_CHAT_RUN_TIMEOUT_SECONDS=120
AGENT_CONTEXT_SECRET=<secret>
USER_CHANGE_KEY=<base64 AES-256 key>
USER_CHANGE_KEY_VERSION=1
```

### Python

```text
CHAT_MAX_RUNS=16
CHAT_MAX_MODEL_CALLS=3
CHAT_MAX_TOOL_CALLS=4
CHAT_MAX_OUTPUT_TOKENS=2048
CHAT_TOOL_TIMEOUT_SECONDS=3
CHAT_RUN_TIMEOUT_SECONDS=120
CHAT_PROVIDER_STREAM_TOOLS=true
```

规则：

- 无生产默认密钥；
- `AI_CHAT_ENABLED=false` 时公开消息接口返回 503；
- `AI_CHAT_WRITE_ENABLED=false` 时只注册读 Tool，不仅仅在 Prompt 中声称禁用；
- 配置非法时启动失败或安全地禁用对应功能；
- 不增加运行时任意编辑这些安全配置的管理 API。

## 25. 数据保留与用户控制

默认保留规则：

- 当前 epoch 完整消息保留 180 天；
- 清空对话后的旧 epoch 消息在 24 小时内物理删除；
- Session 摘要随当前 epoch 保存；
- 长期记忆保留到用户单独清除 Memory 或账号数据永久删除；
- action 的 changed-fields、before/after 加密快照和 revert 链在账户生命周期内保留；
- action 历史默认不按时间清理，以兑现永久 revert；
- 软删除文件的 blob 在账户生命周期内保留，并单独统计历史存储占用；
- 失败和取消的空助手消息保留 7 天用于故障统计，然后清理；
- 账号删除时同步删除 Session、消息、记忆、action 快照和用户关联审计元数据。

用户必须能：

- 查看系统当前保存的全部长期记忆；
- 修改或删除单条记忆；
- 清空所有记忆；
- 清空对话但保留记忆；
- 重置 Assistant，并可单独清空 Memory；
- 分页查看完整 PersonalSpace 修改历史和 revert 链，并可对任意 APPLIED action 发起 revert。

永久 revert 依赖长期保存加密变更字段。生产开启写 Tool 前必须确认学校数据保留政策允许该设计；如果政策要求提前清除版本快照，就不能同时承诺永久 revert，必须先由产品和数据治理明确取舍。用户主动执行账号/个人数据永久删除时，以删除权优先，并明确告知删除后不能再恢复历史版本。

## 26. 分阶段实施

### 阶段 0：完成个人空间前置

先完成 `PERSONAL_SPACE_PLAN.md` 的资源版本、共享验证、Query/Mutation Facade、Memory、Change History、Canonical API 和前端空间壳。Agent 不得在这些能力完成前自行实现临时替代品。

验收：普通网页已经通过 PersonalSpace Service 读写，并生成统一 APPLIED history。

### 阶段 1：固定 Agent 契约和测试夹具

输出：

- 本计划评审通过；
- Session、消息、action 状态枚举；
- SSE 事件 JSON Schema；
- Tool Schema 和安全字段投影；
- Fake LLM 的 text、Tool、错误流样本。

验收：Java/Python 可分别用夹具解析同一协议，不调用真实模型。

### 阶段 2：数据库和单 Session 基础

实现：

- 两张 Agent 表：Session、Message；
- Session 惰性创建、租约和 epoch；
- 消息分页、clear、reset；
- 幂等 requestId；
- 过期租约恢复任务。

验收：并发测试证明每用户仅一个 Session、一个 active run，clear 后旧结果不能写回。

### 阶段 3：Python 单 Agent 文本流

实现：

- messages + stream 能力；
- 有界单 Agent 循环；
- Python Internal SSE；
- Java `AgentStreamClient` 和 `SseEmitter` 中继；
- 取消、超时、心跳和最终一次落库。

验收：Fake LLM 下完成多轮、刷新恢复、断开取消、超时和幂等重试。

### 阶段 4：PersonalSpace Memory 和会话摘要

实现：

- 从 `PersonalSpaceQueryService` 读取已实现的 Memory；
- Prompt 上下文排序；
- 历史字符预算；
- 异步摘要和游标 CAS；
- 清空或重置 Assistant 均保留 Memory；Memory 使用个人空间独立接口管理。

验收：长对话上下文有界，记忆可查看、修改、删除，敏感推断不被自动保存。

### 阶段 5：受控只读 Tool

实现：

- AgentContext HMAC；
- Java Internal Agent Tool API；
- PersonalSpace Query Tool Adapter 和 JobCatalog 安全投影；
- Python Tool Registry；
- 岗位列表和详情共享可见性条件。

验收：用户 A 无法通过任何 Tool 参数或资源 ID读取用户 B 或不可见岗位数据。

### 阶段 6：可逆写 Tool

当前进度：Profile、Resume、Memory、文件软删除、问卷草稿/提交及简历解析的强类型提案、显式上传文件的 APPLIED CREATE Action、Tool Call 幂等、`action_proposed` SSE、登录用户 confirm/reject、脱敏确认卡 API 与永久 Revert 已落地，并由 `AI_CHAT_WRITE_ENABLED` 双端开关控制。简历解析使用一个多 Item Action 原子修改 Profile + Resume；已审核投递撤回使用 WITHDRAWN 且保留审核信息。前端页面接入确认卡仍待实现。

实现：

- 复用已完成的 `PersonalSpaceMutationService`；
- Agent Tool 到 PersonalSpace PENDING action 的强类型 Adapter；
- AgentContext capability 映射；
- 前端在 Assistant 中展示 PersonalSpace 已实现的确认、拒绝和 Revert 卡；
- Agent 不得自行确认或绕过 PersonalSpace CAS。

验收：重复请求不重复写；无关字段后来变化时仍能安全 revert；同字段后来变化时生成三方冲突；用户解决冲突后可完成 revert；revert-of-revert 恢复前一状态。

### 阶段 7：前端 Assistant 接入

实现：

- 单会话聊天页；
- 流式客户端和 Tool 状态；
- 确认卡、撤销和冲突界面；
- 链接到个人空间 Memory 与 ChangeHistory 分区；
- 完整错误与可访问性状态。

验收：无 mock，前后端路径、请求、事件和响应完全一致，生产构建通过。

### 阶段 8：部署、灰度与观测

实现：

- 增量 SQL；
- Agent 内网连通；
- Nginx SSE；
- 功能开关；
- 指标、日志、告警和 runbook；
- Fake LLM 生产拓扑 smoke。

验收：只读灰度和写 Tool 灰度分别通过后才面向全部用户开放。

## 27. 原子提交计划

后端和前端遵守各自仓库独立提交。建议顺序：

前置提交使用 `PERSONAL_SPACE_PLAN.md` 的 S1–S6/F1，本节只列 Agent 增量。

### C1 `feat(ai): persist one chat session per user`

- 增量 SQL、Entity、Mapper；
- Session、消息、epoch、租约和幂等；
- 最小 MySQL 集成测试。

### C2 `feat(ai): stream bounded chat responses`

- Python 流式 LLM 和内部接口；
- Java SSE 中继；
- Fake LLM 文本流、取消和错误测试。

### C3 `feat(ai): compose personal space context`

- 读取 PersonalSpace Memory 和 overview；
- 会话摘要、上下文预算和边界测试。

### C4 `feat(ai): scope read tools to visible data`

- AgentContext；
- 专用 Internal Tool API；
- PersonalSpace Query Adapter 与 JobCatalog；
- 跨用户、字段泄露和 Prompt Injection 测试。

### C5 `feat(ai): propose personal space changes`

- 强类型写 Tool 到 PersonalSpace PENDING action；
- capability scope、幂等和多资源提案测试；
- Agent 无法自行 confirm 的权限测试。

### F1 `feat(ai): add single-session chat workspace`

- 前端消息页、历史和流式显示；
- 加载、空、错误、取消和刷新恢复。

### F2 `feat(ai): connect personal space actions`

- Tool 状态，并复用个人空间确认、Revert、冲突与 Memory UI；
- 可访问性和生产构建。

### C6 `chore(deploy): enable agent chat streaming`

- Nginx、Compose、环境变量和内网端口；
- 部署/回滚 runbook；
- Fake LLM 双机 smoke。

每个提交包含自己的最小可运行验证，不把测试全部推迟到最后。

## 28. 迁移与发布顺序

仓库虽然存在 `db/migration` SQL，但当前 Maven 配置没有 Flyway/Liquibase 运行依赖，不能假设应用启动会自动执行迁移。

发布步骤：

1. 完成并部署 `PERSONAL_SPACE_PLAN.md`，保持 Agent 功能关闭。
2. 验证 PersonalSpace 新旧 API、资源 version、Memory、Action/Item 和 Revert。
3. 备份 MySQL，执行仅包含 `fc_ai_session/message` 的 Agent 增量 SQL。
4. 同步更新两份完整 schema 和隔离测试初始化脚本。
5. 部署 Java Assistant API，保持 `AI_CHAT_ENABLED=false`。
6. 配置 AgentContext HMAC、Python 地址和超时；ChangeHistory 密钥沿用 PersonalSpace 配置。
7. 部署 Python Chat Internal API。
8. 修复并验证 Java 到 Python 的内网 8900 连通性。
9. 部署 Nginx SSE 配置，验证无缓冲和心跳。
10. 开启 Chat，但保持 `AI_CHAT_WRITE_ENABLED=false`，先灰度纯文本和读 Tool。
11. 使用测试用户验证跨用户隔离、岗位可见性、超时、清空和断线。
12. 开启写 Tool，只允许生成 PersonalSpace PENDING action；确认和 Revert 继续走既有空间 API。
13. 部署 Assistant 前端并完成全链路 smoke。
14. 观察错误率、首 token、Tool 权限拒绝、action 冲突和租约恢复指标后全量开放。

### 发布回滚

出现问题时按以下顺序回滚：

1. 关闭 `AI_CHAT_WRITE_ENABLED`，立即停止新写提案；
2. 必要时关闭 `AI_CHAT_ENABLED`；
3. 取消 active run，并等待/修复过期租约；
4. 回退前端和应用镜像；
5. 保留新增表、version 字段和审计记录，不做破坏性 DDL 回滚；
6. 已 APPLIED 的操作仍可按原版本规则回退；
7. 不删除持久卷或现有算法运行数据。

## 29. 工期估算

以下为人日估算，不含产品视觉反复和真实 Provider 排障：

| 工作 | 人日 |
|---|---:|
| PersonalSpace 后端、版本、Memory、History、Revert | 7–10 |
| PersonalSpace 前端页面壳与分区迁移 | 2–3 |
| Agent Session、Python Loop、Java SSE | 4–6 |
| Agent Query/Mutation Tool Adapter 与前端接入 | 3–4 |
| 集成测试、部署、灰度和 runbook | 2–3 |
| **合计** | **18–26** |

Java、Python 和前端可在契约固定后并行，但数据库状态机、SSE 协议和 Tool DTO 必须先定稿。

PersonalSpace 本身约 9–13 人日；在其上增加“单 Session + 纯文本 Agent”约 4–6 人日；完整 Tool Adapter、前端接入和生产门禁后，整体按 18–26 人日安排更稳妥。

## 30. 风险与缓解

| 风险 | 缓解措施 |
|---|---|
| Provider 的流式 Tool Call 与 OpenAI 格式有差异 | Fake 契约测试、真实 Provider smoke、Tool 阶段可配置非流式降级 |
| Nginx 缓冲导致伪流式 | 专用 location、`X-Accel-Buffering: no`、逐 delta smoke |
| Java/Python 双机端口不可达 | 内网绑定、来源防火墙、从 Java 主机执行 smoke |
| 模型构造 userId 或任意查询 | Tool Schema 无 userId；Java AgentContext 和固定领域查询 |
| 岗位详情泄露下线或内部字段 | 共享可见性 predicate 和专用 Agent DTO |
| 重复发送造成重复计费/写入 | requestId、Tool idempotencyKey、数据库唯一约束 |
| 清空后旧流复活 | epoch + runId 双重校验 |
| Agent revert 覆盖用户后续编辑 | 字段级三方比较、最终 CAS；同字段冲突必须由用户解决 |
| 永久 before/after 快照扩大隐私面 | 只存变更字段、AES-GCM、密钥轮换、严格访问控制和账号删除清理 |
| 文件永久可恢复导致 blob 持续增长 | 软删除、内容哈希去重、历史占用指标与容量告警；容量不足时阻止新上传而不删历史 |
| 提交问卷后发生管理员审核 | 使用 WITHDRAWN 补偿状态保留审核历史，不允许 Agent 擦除管理员事件 |
| 长对话成本失控 | 摘要、最近消息、字符预算、模型/Tool 硬上限 |
| 单机内存并发耗尽 | Java/Python 有界并发和 429，不无限排队 |
| 服务崩溃遗留 RUNNING | 数据库租约和幂等恢复任务 |

## 31. 完成标准

### 功能

- 任意并发条件下，同一用户数据库中最多一个 Session；
- 一个用户同一时间最多一个 active run；
- 多轮消息可流式显示、刷新恢复、取消和清空；
- 清空或重置 Assistant 保留 PersonalSpace Memory；
- 记忆可查看、编辑、删除，且不会自动保存敏感推断；
- Agent 能搜索用户可见岗位和读取当前用户自己的数据；
- 普通用户当前可编辑的 Profile、Resume、File 和 Questionnaire 能力都有对应 Agent Tool；
- Profile 全部 16 个字段、Resume 全部 9 个字段都支持 SET、CLEAR、confirm 和 revert；
- TEXT、TEXTAREA、RADIO、CHECKBOX、FILE_UPLOAD 问卷答案都可保存草稿和提交；
- 用户提供的文件可上传、解析、软删除、恢复和下载，且模型不能访问服务器路径；
- 所有 Agent 写 Tool 只能产生可确认 action 或由用户显式文件上传形成的 APPLIED CREATE action；
- confirm 成功后业务数据和 version 正确更新；
- 任意历史 APPLIED action 无时间限制地支持 revert；
- 后续只改无关字段时可自动 revert 且保留无关字段；
- 后续修改同字段时返回三方冲突，用户解决后仍能生成并应用 REVERT action；
- REVERT action 自身可再次 revert，且原 action 历史不被改写。

### 安全

- 浏览器无法访问 Internal API；
- Tool 无法传 userId、SQL、表名或权限条件；
- 跨用户、不可见岗位和内部字段测试全部通过；
- AgentContext 篡改、过期、错误 scope 和旧 epoch 均被拒绝；
- 日志、SSE、错误响应不包含正文、Token、密文、内部字段或堆栈；
- Action 快照已加密、支持密钥轮换，并在账户生命周期内保持可解密。

### 质量

- Java、Python、契约、端到端和前端最小验证全部通过；
- 测试不访问真实模型或生产服务；
- 前端无生产 mock；
- Nginx 真正逐事件转发；
- 所有失败路径释放租约；
- 文档、schema、环境示例和 runbook 同步更新；
- 变更遵守 `agent.md` 命名、提交和 Definition of Done。

## 32. 已固定的首期产品默认值

除非产品明确变更，实施按以下默认值执行：

1. 一个用户只有一个永久逻辑 Session。
2. 清空或重置 Assistant 都保留长期记忆；记忆由 PersonalSpace 独立管理。
3. 同用户一次只能运行一轮回复。
4. 除用户显式 multipart 上传文件形成的 CREATE action 外，聊天内所有写操作都需要确认卡。
5. 提案不按时间失效；只会因拒绝、Session reset 或目标资源版本变化而 supersede。
6. 已应用 action 不设 revert 时限；revert 通过新的不可变 action 表达。
7. 写 Tool 覆盖普通用户可编辑的完整 Profile、Resume、File、Questionnaire 和 Memory 能力。
8. 只读 Tool 覆盖当前账号、资料、简历、文件、配额、投递、动态问卷和公开岗位。
9. 不提供任意 SQL、管理员写入、岗位修改、物理文件删除或外部通知 Tool。
10. 不引入 Redis、向量库、MQ 或新的 Agent 框架。
11. 浏览器断开即取消本轮，未完成文本不进入下一轮上下文。
