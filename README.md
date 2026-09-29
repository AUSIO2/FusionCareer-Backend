# FusionCareer Backend

> 复旦大学新闻学院就业服务平台后端 —— Spring Boot + MyBatis-Plus + Sa-Token

## 技术栈

| 组件 | 版本 |
|------|------|
| Java | 25 |
| Spring Boot | 3.2.5 |
| MyBatis-Plus | 3.5.6 |
| Sa-Token | 1.45.0 |
| MySQL | 8.x |
| Maven | 多模块（fusioncareer-api / fusioncareer-biz） |

## 快速启动

```bash
# 1. 确保 MySQL 运行中，创建数据库
mysql -u root -e "CREATE DATABASE IF NOT EXISTS fusioncareer DEFAULT CHARSET utf8mb4;"

# 2. 编译 & 启动
./mvnw install -N -q && ./mvnw install -pl fusioncareer-api -q
./mvnw spring-boot:run -pl fusioncareer-biz

# 3. 验证
curl http://localhost:8080/sys/health
```

## 项目结构

```
FusionCareer-Backend/
├── fusioncareer-api/          # 公共层：DTO、枚举、常量
│   └── src/main/java/
│       ├── dto/req/           # 请求 DTO
│       ├── dto/res/           # 响应 DTO
│       ├── enums/             # 枚举定义
│       └── common/            # 统一响应 R、PageResult
├── fusioncareer-biz/          # 业务层：实体、Mapper、Service、Controller
│   └── src/main/java/
│       ├── config/            # 配置类
│       ├── entity/            # 数据库实体
│       ├── mapper/            # MyBatis Mapper
│       ├── service/           # Service 接口 & 实现
│       ├── controller/        # 用户端 Controller
│       └── controller/internal/ # 内部管理 Controller
├── docs/
│   └── API_README.md          # 详细 API 文档
└── pom.xml
```

前端 Vue 应用在独立仓库 **[FusionCareer-View](https://github.com/AUSIO2/FusionCareer-View)**（`ui_kits/student`），部署脚本见该仓库 `deploy/scripts/`。

## 数据库

共 7 张业务表，建表脚本见 `fusioncareer-biz/src/main/resources/schema.sql`。

| 表 | 说明 |
|----|------|
| `fc_user` | 用户账号 |
| `fc_user_profile` | 用户资料（个人信息 + 求职意向） |
| `fc_resume` | 用户简历（文本内容） |
| `fc_resume_file` | 简历文件元数据 |
| `fc_job_post` | 岗位信息 |
| `fc_job_post_question` | 岗位投递问卷题目 |
| `fc_questionnaire_answer` | 学生问卷作答 |

---

## API 接口总览

> 详细文档（含请求体/响应体示例、枚举值参考）见 [`docs/API_README.md`](docs/API_README.md)

### 系统 & 认证

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| GET | `/sys/health` | 健康检查 | ❌ |
| GET | `/fudan/login` | 重定向至复旦 SSO 登录 | ❌ |
| GET | `/fudan/callback?code=xxx&state=xxx` | SSO 回调 | ❌ |
| GET | `/fudan/logout` | 主动注销 | ❌ |
| POST | `/fudan/logout` | 注销并返回 UIS 退出地址 | ✅ |
| GET | `/fudan/slo?token=xxx` | 被动注销回调 | ❌ |
| GET | `/user/me` | 当前用户、角色与状态 | ✅ |

### 用户端接口（需要 `Fusion-Token` 认证）

#### 个人空间

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/personal-space` | 获取当前用户个人空间总览 |
| GET | `/personal-space/profile` | 获取个人空间资料 |
| PATCH | `/personal-space/profile` | 使用 SET/CLEAR 和版本号修改资料 |
| GET | `/personal-space/resume` | 获取个人空间结构化简历 |
| PATCH | `/personal-space/resume` | 使用 SET/CLEAR 和版本号修改结构化简历 |
| GET | `/personal-space/documents` | 获取个人空间文件与配额 |
| GET | `/personal-space/documents/deleted` | 获取个人空间回收站文件 |
| DELETE | `/personal-space/documents/{fileId}` | 将个人空间文件移入回收站 |
| POST | `/personal-space/documents/{fileId}/restore` | 恢复回收站文件 |
| GET | `/personal-space/applications` | 分页获取个人空间投递 |
| GET | `/personal-space/memory` | 获取轻量长期记忆 |
| PUT | `/personal-space/memory/{key}` | 设置一个长期记忆项 |
| DELETE | `/personal-space/memory/{key}` | 删除一个长期记忆项 |
| DELETE | `/personal-space/memory` | 清空长期记忆 |
| GET | `/personal-space/actions` | 分页获取安全变更历史 |
| GET | `/personal-space/actions/{actionId}` | 获取一条变更的安全详情 |
| GET | `/personal-space/actions/{actionId}/confirmation` | 获取脱敏 before/after 确认卡 |
| POST | `/personal-space/actions/{actionId}/revert` | 创建三方比较回退提案 |
| POST | `/personal-space/actions/{actionId}/confirm` | 确认并执行写入或回退提案 |
| POST | `/personal-space/actions/{actionId}/reject` | 拒绝写入或回退提案 |
| GET | `/personal-space/assistant/session` | 获取当前用户唯一 AI Session 状态 |
| GET | `/personal-space/assistant/messages` | 分页获取当前 epoch 消息 |
| POST | `/personal-space/assistant/messages/stream` | 发送消息并获取 SSE 文本流 |
| POST | `/personal-space/assistant/run/cancel` | 取消当前 AI 运行 |
| POST | `/personal-space/assistant/session/clear` | 清空对话并保留 Memory |
| DELETE | `/personal-space/assistant/session` | 重置 Session 并保留 Memory 与历史 |

Profile、Resume、Memory、文件上传/回收站和问卷写入会在同一事务中记录不可变 Action/Item。用户显式上传文件会立即形成 `APPLIED CREATE` Action；Revert 只软删除元数据并保留 blob，再次 Revert 可恢复同一文件。实际修改字段的 before/after 使用 AES-256-GCM 加密；任意 APPLIED 操作均可创建永久回退提案。生产部署必须配置 `USER_CHANGE_KEY`（可用 `openssl rand -base64 32` 生成）和 `USER_CHANGE_KEY_VERSION`。

AI 当前开放 15 个固定只读 Tool。设置 `AI_CHAT_WRITE_ENABLED=true` 后额外开放 Profile、Resume、Memory、文件删除、问卷草稿、问卷提交和简历解析共 7 个提案 Tool；它们只创建 `PENDING` Action，业务数据必须由登录用户通过确认接口应用。`parse_resume_file` 在一个 Action 中原子修改 Profile + Resume，任一版本冲突都会整体回滚。文件删除只移入回收站，问卷已审核后的撤回使用 `WITHDRAWN` 并保留审核信息，所有已应用操作均可继续 Revert。Tool Schema 不包含 userId，Java 使用 5 分钟 HMAC AgentContext 重新校验用户、run、epoch 和 scope；生产部署需额外配置独立的 `AGENT_CONTEXT_SECRET`。

Agent 运行时默认最多调用 4 个 Tool、查询 Tool 3 秒超时、整轮 120 秒超时，并使用有界并发。Tool 业务错误只以稳定安全码进入模型上下文，堆栈、SQL、Header 和内部响应不会进入 SSE 或 Prompt。

单 Session 上下文固定最多 12 条、20K 字符。累计 10 个完整轮次或超过字符预算后，Java 异步调用无 Tool 摘要接口，并用 `epoch + summaryThroughMessageId` CAS 写回；摘要失败不阻塞当前回复，也不会把 Tool 结果写入长期摘要。

Java 每分钟扫描过期运行租约：使用条件更新释放匹配的 `activeRunId`，将未完成助手消息标记为 `FAILED/LEASE_EXPIRED`，并拒绝该运行产生的未确认 Action。多实例同时扫描时只有一个实例能成功回收。

#### 个人资料 & 简历

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/user/profile/get` | 获取个人资料 |
| PUT | `/user/profile/save` | 保存个人资料 |
| GET | `/user/resume/get` | 获取简历 |
| PUT | `/user/resume/save` | 保存简历 |

#### 简历文件

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/user/resume/file/upload` | 上传简历文件（multipart） |
| GET | `/user/resume/file/list` | 获取文件列表 |
| GET | `/user/resume/file/{fileId}/download` | 下载文件 |
| DELETE | `/user/resume/file/{fileId}` | 将文件移入回收站 |
| GET | `/user/resume/file/quota` | 查询存储配额 |

#### 岗位浏览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/job/{id}` | 岗位详情 |
| GET | `/job/list?page=1&size=10&keyword=xxx` | 分页搜索岗位 |

管理员可通过 `GET /admin/job-post/import-template` 下载岗位模板，并将填写后的文件上传到
`POST /admin/job-post/import`（multipart 字段 `file`）批量创建并发布岗位。

#### 岗位投递问卷

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/questionnaire/questions/{jobPostId}` | 获取投递问卷 |
| POST | `/questionnaire/submit` | 提交作答（重复提交覆盖更新） |
| GET | `/questionnaire/my/{jobPostId}` | 查看我的作答 |
| POST | `/questionnaire/upload` | 上传问卷附件（multipart） |

### 内部服务接口（`/internal/**`，无需认证）

> 仅供 Python 等内网服务直连 Java。

浏览器管理后台改用受 `ADMIN` 角色保护的路径：

| 资源 | 路径 |
|------|------|
| 用户管理 | `/admin/user/**` |
| 岗位管理 | `/admin/job-post/**` |
| 问卷与投递审核 | `/admin/questionnaire/**` |

`/internal/**` 仅供内网服务直连 Java，公网 Nginx 对 `/api/internal/**` 返回 404。
管理员可通过 `/admin/questionnaire/answers/job/{jobPostId}/export?format=csv|zip` 导出投递。

#### 用户管理 `/internal/user`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/internal/user` | 创建用户 |
| GET | `/internal/user/{id}` | 获取用户 |
| GET | `/internal/user/list?page=&size=` | 分页查询 |
| PUT | `/internal/user/{id}` | 更新用户 |
| DELETE | `/internal/user/{id}` | 删除用户 |

#### 用户资料管理 `/internal/user-profile`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/internal/user-profile/{userId}` | 创建资料 |
| GET | `/internal/user-profile/{userId}` | 获取资料 |
| GET | `/internal/user-profile/list` | 获取全部 |
| PUT | `/internal/user-profile/{userId}` | 更新资料 |
| DELETE | `/internal/user-profile/{userId}` | 删除资料 |

#### 简历管理 `/internal/resume`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/internal/resume/{userId}` | 创建简历 |
| GET | `/internal/resume/{userId}` | 获取简历 |
| GET | `/internal/resume/list` | 获取全部 |
| PUT | `/internal/resume/{userId}` | 更新简历 |
| DELETE | `/internal/resume/{userId}` | 删除简历 |

#### 简历文件管理 `/internal/resume-file`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/internal/resume-file/{userId}/upload` | 上传文件 |
| GET | `/internal/resume-file/{userId}/list` | 文件列表 |
| GET | `/internal/resume-file/{fileId}/download` | 下载文件 |

#### 岗位管理 `/internal/job-post`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/internal/job-post` | 创建岗位 |
| POST | `/internal/job-post/batch` | 批量创建 |
| GET | `/internal/job-post/{id}` | 获取详情 |
| GET | `/internal/job-post/list` | 分页查询 |
| PUT | `/internal/job-post/{id}` | 更新岗位 |
| DELETE | `/internal/job-post/{id}` | 删除岗位 |

#### 问卷管理 `/internal/questionnaire`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/internal/questionnaire/questions/batch/{jobPostId}` | 批量保存问卷（整组替换） |
| GET | `/internal/questionnaire/questions/{jobPostId}` | 获取题目列表 |
| DELETE | `/internal/questionnaire/questions/{jobPostId}` | 删除全部题目 |
| GET | `/internal/questionnaire/answers/job/{jobPostId}?page=&size=` | 分页查看作答 |
| GET | `/internal/questionnaire/answers/{id}` | 查看作答详情 |

---

## 统一响应格式

```json
{
  "code": 200,
  "message": "操作成功",
  "data": { ... }
}
```

异常时 `code ≠ 200`，`data` 为 `null`，`message` 包含错误描述。

## License

Internal use only — 复旦大学新闻学院.
