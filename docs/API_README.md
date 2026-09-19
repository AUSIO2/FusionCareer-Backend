# FusionCareer Backend API 接口文档

> **Base URL**: `http://localhost:8080`  
> **认证方式**: Sa-Token，Header 传递 `Fusion-Token: <token>`  
> **统一响应格式**:
> ```json
> { "code": 200, "message": "操作成功", "data": ... }
> ```
> 请求错误仍使用相同结构且 `data` 为 `null`。标准错误的 HTTP 状态与
> `code` 一致：参数错误 400、未登录 401、无权限 403、资源不存在 404、
> 状态冲突 409、服务异常 500；模块业务码（如 410xx、420xx）保持在响应体
> `code` 中，由客户端读取并展示 `message`。

---

## 目录

- [1. 系统接口](#1-系统接口)
- [2. 认证接口（复旦 SSO）](#2-认证接口复旦-sso)
- [3. 用户端接口（需登录）](#3-用户端接口需登录)
  - [3.1 个人资料](#31-个人资料)
  - [3.2 个人简历](#32-个人简历)
  - [3.3 简历文件](#33-简历文件)
  - [3.4 岗位浏览](#34-岗位浏览)
  - [3.5 岗位投递问卷](#35-岗位投递问卷)
- [4. 管理员与内部接口](#4-管理员与内部接口)
  - [4.1 用户管理](#41-用户管理)
  - [4.2 用户资料管理](#42-用户资料管理)
  - [4.3 简历管理](#43-简历管理)
  - [4.4 简历文件管理](#44-简历文件管理)
  - [4.5 岗位管理](#45-岗位管理)
  - [4.6 问卷管理](#46-问卷管理)
- [5. 枚举值参考](#5-枚举值参考)
- [6. 数据库表结构](#6-数据库表结构)

---

## 1. 系统接口

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| GET | `/sys/health` | 系统健康检查 | ❌ |

**响应示例**:
```json
{ "code": 200, "message": "FusionCareer Backend is running smoothly.", "data": { "status": "UP", "timestamp": 1714700000000 } }
```

---

## 2. 认证接口（复旦 SSO）

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| GET | `/fudan/login?target=user|admin` | 重定向至复旦统一认证登录，并保存登录目标 | ❌ |
| GET | `/fudan/callback?code=xxx&state=xxx` | 认证回调（复旦认证中心调用） | ❌ |
| GET | `/fudan/logout` | 主动注销，重定向至复旦退出 | ❌ |
| POST | `/fudan/logout` | 注销本地会话并返回 UIS 退出地址 | ✅ |
| GET | `/fudan/slo?token=xxx` | 被动注销回调（复旦认证中心调用） | ❌ |

> 登录请求和回调必须携带匹配的一次性 `state`。`target` 只决定登录后的落地页，管理员权限仍由数据库角色和 `/admin/**` 后端鉴权决定。登录成功后生成 Sa-Token，并重定向到 `/#/home?token=...` 或 `/#/admin?token=...`；fragment 不会进入 Nginx 请求日志。普通用户请求管理端时回到首页并携带 `notice=admin_forbidden`。

POST 退出响应：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "redirectUrl": "https://id.fudan.edu.cn/..."
  }
}
```

---

## 3. 用户端接口（需登录）

> 所有接口需要 Header: `Fusion-Token: <token>`

### 3.1 个人资料

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/user/me` | 获取当前用户、角色和状态 |
| GET | `/user/profile/get` | 获取个人资料 |
| PUT | `/user/profile/save` | 保存个人资料 |

`GET /user/me` 返回 `UserResponse`，其中 `role` 为 `NORMAL`、`ADMIN` 或 `SUPERADMIN`，`status` 为 `NORMAL` 或 `DISABLED`。管理员路由必须以后端角色校验结果为准。

**PUT 请求体** `UserProfileRequest`:
```json
{
  "realName": "张三",
  "gender": "MALE",
  "birthDate": "2000-01-15",
  "politicalStatus": "LEAGUE_MEMBER",
  "phone": "13812345678",
  "email": "zhangsan@fudan.edu.cn",
  "wechat": "zhangsan_wx",
  "hometown": "上海市",
  "grade": "2022级",
  "major": "新闻学",
  "eduLevel": "BACHELOR",
  "supervisor": "李教授",
  "intentionOrder": "企业公司,新闻媒体",
  "intentionCity": "[\"上海\",\"北京\"]",
  "intentionDream": "想从事数据新闻方向",
  "mindset": "CAUTIOUSLY_OPTIMISTIC"
}
```

### 3.2 个人简历

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/user/resume/get` | 获取个人简历 |
| PUT | `/user/resume/save` | 保存个人简历 |

**PUT 请求体** `ResumeRequest`:
```json
{
  "personalIntro": "本科新闻学专业...",
  "basicInfo": "基础信息文本",
  "education": "教育背景文本",
  "internship": "实习经历文本",
  "campus": "在校经历文本",
  "awards": "荣誉奖励文本",
  "skills": "掌握技能文本",
  "portfolio": "作品集文本",
  "remark": "备注"
}
```

### 3.3 简历文件

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/user/resume/file/upload` | 上传简历文件（multipart/form-data, field: `file`） |
| GET | `/user/resume/file/list` | 获取我的简历文件列表 |
| GET | `/user/resume/file/{fileId}/download` | 下载指定简历文件（流式） |
| DELETE | `/user/resume/file/{fileId}` | 删除指定简历文件 |
| GET | `/user/resume/file/quota` | 查询存储配额 |

**上传限制**: PDF / JPG / PNG，单文件 ≤ 20MB，个人总配额 30MB

**文件列表响应** `ResumeFileResponse`:
```json
{
  "id": 1234567890,
  "originalName": "我的简历.pdf",
  "url": "http://localhost:8080/files/resumes/xxx/2026-05-03/abc.pdf",
  "fileSize": 102400,
  "mimeType": "application/pdf",
  "createdAt": "2026-05-03T12:00:00"
}
```

### 3.4 岗位浏览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/job/{id}` | 获取岗位详情 |
| GET | `/job/list` | 分页查询已发布岗位列表 |

**GET `/job/list` 查询参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| `page` | int | 页码，默认 1 |
| `size` | int | 每页大小，默认 10 |
| `keyword` | string | 搜索关键词（岗位名/公司名） |
| `jobCategory` | enum | 岗位大类 |
| `jobSubCategory` | enum | 岗位二级分类 |
| `recruitType` | enum | 招聘类型 |
| `workDurationType` | enum | 工作时长类型 |
| `workPeriodType` | enum | 实习时长 |
| `workMode` | enum | 工作形式 |
| `workProvince` | string | 工作省份 |
| `workCity` | string | 工作城市 |
| `salaryMin` | int | 查询薪资下限，与岗位薪资区间相交 |
| `salaryMax` | int | 查询薪资上限，与岗位薪资区间相交 |
| `sortBy` | enum | `NEWEST`（默认）或 `DEADLINE` |
| `recommended` | boolean | 是否只查询推荐岗位 |
| `sourceType` | enum | 来源类型 |

岗位响应额外包含：

- `recommended`：是否推荐。
- `applicationCount`：已提交和已审核的投递数，不包含草稿。

### 3.5 岗位投递问卷

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/questionnaire/questions/{jobPostId}` | 获取某岗位的投递问卷 |
| POST | `/questionnaire/submit` | 提交问卷作答（重复提交覆盖更新） |
| GET | `/questionnaire/my/{jobPostId}` | 查看我对某岗位的作答 |
| POST | `/questionnaire/upload` | 上传问卷附件（multipart/form-data, field: `file`） |

**POST `/questionnaire/submit` 请求体** `QuestionnaireSubmitRequest`:
```json
{
  "jobPostId": 2050967238021779458,
  "answers": "[{\"questionId\":1,\"value\":\"研一\"},{\"questionId\":2,\"value\":\"男\"},{\"questionId\":3,\"value\":\"310101200001011234\"}]"
}
```
> `answers` 字段是 JSON 字符串，数组中每个对象包含 `questionId`（题目ID）和 `value`（作答值）。

**问卷题目响应** `JobPostQuestionResponse`:
```json
{
  "id": 1234567890,
  "jobPostId": 2050967238021779458,
  "sortOrder": 1,
  "title": "请填写你的年级",
  "questionType": "RADIO",
  "options": ["大一", "大二", "大三", "大四", "研一", "研二", "研三"],
  "required": true,
  "placeholder": null,
  "createdAt": "2026-05-03T23:50:00",
  "updatedAt": "2026-05-03T23:50:00"
}
```

---

## 4. 管理员与内部接口

### 4.0 浏览器管理员接口

浏览器管理接口需要 `Fusion-Token`。岗位管理允许 `ADMIN` 和 `SUPERADMIN`；系统管理仅允许 `SUPERADMIN`：

| 资源 | 路径 | 能力 |
|------|------|------|
| 系统管理（仅超级管理员） | `/admin/user/**` | 分页查看、修改角色、用户资料/简历查询、简历下载、Excel 导出 |
| 岗位 | `/admin/job-post/**` | 列表、详情、创建、Excel 批量导入、更新、回收与恢复 |
| 问卷题目 | `/admin/questionnaire/questions/**` | 读取、整组保存、删除 |
| 投递审核 | `/admin/questionnaire/answers/**` | 列表、详情、单条审核、批量审核、导出 |

未登录返回 HTTP 401，无对应角色返回 HTTP 403。普通管理员访问 `/admin/user/**` 返回 403。
超级管理员继承岗位和投递审核权限，角色变更在下次请求立即生效；数据库仍保存单一角色。

投递导出：

```text
GET /admin/questionnaire/answers/job/{jobPostId}/export?format=csv
GET /admin/questionnaire/answers/job/{jobPostId}/export?format=zip
GET /admin/questionnaire/answers/job/{jobPostId}/export?format=zip&content=profile&content=resume&content=files
```

- `answerIds` 可选，可重复传递或使用逗号分隔，只导出选中的投递。
- `content` 可选且可重复传递，支持 `profile`（用户资料）、`resume`（在线简历正文）和 `files`（用户上传的简历文件）。
- 选择 `profile` 或 `resume` 时，相应字段追加到 `applications.csv`；选择 `files` 时必须使用 ZIP。
- CSV 带 UTF-8 BOM，可直接用 Excel 打开。
- 未传 `content` 时保持旧行为：ZIP 包含 `applications.csv` 和问卷文件题引用的附件。
- 传入 `content=files` 时，ZIP 包含选中投递用户上传的全部简历文件。
- 草稿不会进入导出结果。

系统管理用户接口（全部要求 SUPERADMIN）：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/user/list?page=1&size=10&username=xxx` | 分页查询用户列表 |
| GET | `/admin/user/{id}` | 查询账号信息，不返回密码 |
| GET | `/admin/user/{id}/profile` | 查询完整个人资料 |
| GET | `/admin/user/{id}/resume` | 查询在线简历正文 |
| GET | `/admin/user/{id}/resume/file/list` | 查询简历文件元数据 |
| GET | `/admin/user/{id}/resume/file/{fileId}/download` | 流式下载属于该用户的简历文件 |
| GET | `/admin/user/export?username=xxx&role=NORMAL&userIds=1,2` | 下载用户信息 `.xlsx`，参数均可选 |
| PUT | `/admin/user/{id}/role?role=SUPERADMIN` | 设置为 NORMAL、ADMIN 或 SUPERADMIN；不可撤销自己的超级管理员权限 |

列表与导出的 `username` 匹配用户名或学号/工号，`role` 精确匹配角色；`userIds` 支持逗号分隔或重复参数，
与其他筛选条件取交集。未传筛选时导出全部用户，包含所有分页，而非仅当前页。
Excel 包含账号信息、用户资料、简历正文三个工作表，用用户ID关联；无资料的用户保留空白行，
不包含密码、登录令牌和文件存储路径。ID、学号、手机号按文本保存，用户输入不会成为 Excel 公式。
单字段超过 Excel 的 32767 字符限制会明确返回 400，不截断正文。

用户不存在返回 404；存在但未填写的资料/简历为空。下载时文件不存在或不属于指定用户返回 404。
文件和 Excel 响应包含 `Content-Disposition: attachment` 以及 `Cache-Control: no-store`。
原 `/files/**` 简历链接现在也要求登录：仅文件所有者或超级管理员可访问。
前端预览/下载应携带 Fusion-Token 请求文件流，再使用 Blob 展示或下载，不能依赖匿名静态链接。

前端菜单建议：ADMIN 显示岗位管理（含问卷/投递审核），SUPERADMIN 同时显示岗位管理和系统管理；
NORMAL 不进入管理员端。登录回跳已支持超级管理员的 `target=admin`。

首位超级管理员由运维根据明确指定的账号初始化，系统不会自动提升现有管理员：

```sql
UPDATE fc_user SET role = 2 WHERE student_id = '指定的学号或工号' AND status = 1;
```

初始化后可由超级管理员在系统管理中授予/撤销其他用户权限。role 列已是 TINYINT，无需扩列迁移。
本地 dev 模式可用 `/fudan/login?role=SUPERADMIN&target=admin` 登录独立的 `dev-admin-super` 测试账号；生产不启用模拟登录。

岗位 Excel 导入：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/job-post/import-template` | 下载 `.xlsx` 导入模板 |
| POST | `/admin/job-post/import` | 上传 Excel，`multipart/form-data` 字段名为 `file` |

导入兼容平台模板和学院现有的“岗位需求总表”，支持合并的单位、补贴和联系人单元格。
全部行校验通过后才批量创建，导入岗位统一保存为 `PUBLISHED` 并立即发布，不限制单次导入条数。
`workTimeRequirement`、`careerDirection`、`internalCompensation`、`contactName`、`contactInfo`
和 `internalRemark` 会出现在管理员列表/详情中，标记“不对外”的字段不会出现在学生端岗位响应中。

岗位文档积压处理：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/job-post/structure-pending` | 查询清理状态和待处理文档数量 |
| POST | `/admin/job-post/structure-pending` | 后台启动清理；重复调用不会创建第二个任务 |

任务持续分批处理到 `pendingCount=0`。POST 返回 HTTP 200 的统一 `R` 响应，内部
`data.status` 为 `RUNNING`；使用 GET 查询 `COMPLETED`、`FAILED` 或剩余数量。

岗位回收站：

| 方法 | 路径 | 说明 |
|------|------|------|
| DELETE | `/admin/job-post/{id}` | 软删除到回收站，原因记为“人工删除” |
| GET | `/admin/job-post/list?status=RECYCLED` | 查看回收站及回收原因 |
| PUT | `/admin/job-post/{id}/restore` | 恢复为未发布草稿 |

回收状态为 `RECYCLED`（数据库值 `3`），并记录 `recycleReason`、`recycledAt`。

### 内部服务接口

> 路径前缀 `/internal/**`，不经过 Sa-Token 拦截器，仅供 Python 等内网服务直接调用。

浏览器和公网客户端不得调用 `/internal/**`；生产 Nginx 对 `/api/internal/**` 返回 404。该路径只允许 Python 等服务在隔离网络中直连 Java。

### 4.1 用户管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/internal/user` | 创建用户 |
| GET | `/internal/user/{id}` | 获取用户详情 |
| GET | `/internal/user/list?page=1&size=10&username=xxx` | 分页查询用户列表 |
| PUT | `/internal/user/{id}` | 更新用户 |
| DELETE | `/internal/user/{id}` | 删除用户 |

### 4.2 用户资料管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/internal/user-profile/{userId}` | 创建用户资料 |
| GET | `/internal/user-profile/{userId}` | 获取用户资料 |
| GET | `/internal/user-profile/list` | 获取所有用户资料 |
| PUT | `/internal/user-profile/{userId}` | 更新用户资料 |
| DELETE | `/internal/user-profile/{userId}` | 删除用户资料 |

### 4.3 简历管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/internal/resume/{userId}` | 创建简历 |
| GET | `/internal/resume/{userId}` | 获取简历 |
| GET | `/internal/resume/list` | 获取所有简历 |
| PUT | `/internal/resume/{userId}` | 更新简历 |
| DELETE | `/internal/resume/{userId}` | 删除简历 |

### 4.4 简历文件管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/internal/resume-file/{userId}/upload` | 为用户上传简历文件（multipart/form-data, field: `file`） |
| GET | `/internal/resume-file/{userId}/list` | 获取用户的简历文件列表 |
| GET | `/internal/resume-file/{fileId}/download` | 下载指定文件（流式响应） |

### 4.5 岗位管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/internal/job-post` | 创建岗位 |
| POST | `/internal/job-post/batch` | 批量创建岗位 |
| GET | `/internal/job-post/{id}` | 获取岗位详情 |
| GET | `/internal/job-post/list` | 分页查询岗位列表（含所有状态） |
| PUT | `/internal/job-post/{id}` | 更新岗位 |
| DELETE | `/internal/job-post/{id}` | 删除岗位 |

**POST 请求体** `JobPostRequest`:
```json
{
  "sourceType": "PLATFORM",
  "companyName": "复旦大学新闻学院",
  "department": "学工办",
  "positionName": "学生助理",
  "jobCategory": "ACADEMIC",
  "jobSubCategory": "FURTHER_STUDY",
  "recruitType": "DAILY_INTERNSHIP",
  "headcount": 3,
  "workStartDate": "2026-06-01",
  "workEndDate": "2026-08-31",
  "workDaysPerWeek": 3,
  "workDurationType": "THREE_TO_FOUR_DAYS",
  "workPeriodType": "THREE_TO_SIX_MONTHS",
  "workMode": "OFFLINE",
  "workCity": "上海",
  "workProvince": "上海市",
  "workLocation": "复旦大学邯郸路校区",
  "salaryMin": 3000,
  "salaryMax": 5000,
  "salaryDisplay": "150/天",
  "jobDesc": "协助老师完成日常行政工作...",
  "reqEduLevel": "BACHELOR",
  "reqMajor": "不限",
  "reqGradYear": "2026届",
  "reqSkills": "Office 办公软件",
  "reqOther": "认真负责",
  "recommended": true,
  "status": "PUBLISHED"
}
```

### 4.6 问卷管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/internal/questionnaire/questions/batch/{jobPostId}` | 批量保存问卷（整组替换） |
| GET | `/internal/questionnaire/questions/{jobPostId}` | 获取某岗位的问卷题目列表 |
| DELETE | `/internal/questionnaire/questions/{jobPostId}` | 删除某岗位的所有问卷题目 |
| GET | `/internal/questionnaire/answers/job/{jobPostId}?page=1&size=20` | 分页查看某岗位的所有问卷作答 |
| GET | `/internal/questionnaire/answers/{id}` | 查看单条问卷作答详情 |

**POST 批量保存问卷请求体** `List<JobPostQuestionRequest>`:
```json
[
  {
    "sortOrder": 1,
    "title": "请填写你的年级",
    "questionType": "RADIO",
    "options": ["大一", "大二", "大三", "大四", "研一", "研二", "研三"],
    "required": true,
    "placeholder": null
  },
  {
    "sortOrder": 2,
    "title": "请填写身份证号",
    "questionType": "TEXT",
    "required": true,
    "placeholder": "18位身份证号码"
  },
  {
    "sortOrder": 3,
    "title": "请简述申请理由",
    "questionType": "TEXTAREA",
    "required": true,
    "placeholder": "200字以内"
  },
  {
    "sortOrder": 4,
    "title": "请上传个人简历",
    "questionType": "FILE_UPLOAD",
    "required": false,
    "placeholder": "支持 PDF/JPG/PNG"
  }
]
```

**问卷作答响应** `QuestionnaireAnswerResponse`:
```json
{
  "id": 1234567890,
  "jobPostId": 2050967238021779458,
  "userId": 2050967239074549761,
  "username": "张三",
  "studentId": "22307110001",
  "answers": "[{\"questionId\":1,\"value\":\"研一\"},{\"questionId\":2,\"value\":\"310101200001011234\"}]",
  "createdAt": "2026-05-03T23:54:29",
  "updatedAt": "2026-05-03T23:54:29"
}
```

---

## 5. 枚举值参考

### QuestionType（题目类型）
| 枚举值 | code | 说明 |
|--------|------|------|
| `TEXT` | 1 | 单行文本 |
| `TEXTAREA` | 2 | 多行文本 |
| `RADIO` | 3 | 单选 |
| `CHECKBOX` | 4 | 多选 |
| `FILE_UPLOAD` | 5 | 文件上传 |

### SourceType（岗位来源）
| 枚举值 | code | 说明 |
|--------|------|------|
| `PLATFORM` | 1 | 平台发布 |
| `CRAWL` | 2 | 就业资讯源爬取 |

### JobCategory（岗位大类）
| 枚举值 | code | 说明 |
|--------|------|------|
| `ACADEMIC` | 1 | 学术教职 |
| `GOVERNMENT` | 2 | 党政机关 |
| `MEDIA` | 3 | 新闻媒体 |
| `ENTERPRISE` | 4 | 企业公司 |
| `OTHER` | 9 | 其他 |

### RecruitType（招聘类型）
| 枚举值 | code | 说明 |
|--------|------|------|
| `BIG_INTERNSHIP` | 1 | 大实习 |
| `SMALL_INTERNSHIP` | 2 | 小实习 |
| `DAILY_INTERNSHIP` | 3 | 日常实习 |
| `CAMPUS_RECRUITMENT` | 4 | 应届生招聘 |
| `CAMPUS_SCREENING` | 5 | 应届生摸排 |
| `OTHER` | 6 | 其他 |
| `BOTH_INTERNSHIP` | 7 | 大/小实习均可 |

### JobPostStatus（岗位状态）
| 枚举值 | code | 说明 |
|--------|------|------|
| `OFFLINE` | 0 | 已下线 |
| `PUBLISHED` | 1 | 发布中 |
| `EXPIRED` | 2 | 已截止 |

### WorkMode（工作形式）
| 枚举值 | code | 说明 |
|--------|------|------|
| `ONLINE` | 1 | 线上 |
| `OFFLINE` | 2 | 线下 |
| `HYBRID` | 3 | 线上线下均可 |

### Gender（性别）
| 枚举值 | code | 说明 |
|--------|------|------|
| `MALE` | 1 | 男 |
| `FEMALE` | 2 | 女 |
| `OTHER` | 3 | 其他 |

### EduLevel（学历层次）
| 枚举值 | code | 说明 |
|--------|------|------|
| `BACHELOR` | 1 | 本科生 |
| `ACADEMIC_MASTER` | 2 | 学术硕士 |
| `PROFESSIONAL_MASTER` | 3 | 专业硕士 |
| `DOCTORATE` | 4 | 博士研究生 |

### UserRole（用户角色）
| 枚举值 | code | 说明 |
|--------|------|------|
| `NORMAL` | 0 | 普通用户 |
| `ADMIN` | 1 | 管理员 |
| `SUPERADMIN` | 2 | 超级管理员 |

---

## 6. 数据库表结构

| 表名 | 说明 |
|------|------|
| `fc_user` | 用户账号表 |
| `fc_user_profile` | 用户资料表（基础信息+意向） |
| `fc_resume` | 用户简历+作品集表 |
| `fc_resume_file` | 用户简历文件元数据表 |
| `fc_job_post` | 岗位信息表 |
| `fc_job_post_question` | 岗位投递问卷题目表 |
| `fc_questionnaire_answer` | 学生问卷作答表 |

> 完整建表脚本见 `fusioncareer-biz/src/main/resources/schema.sql`

---

## 接口统计

| 分类 | 接口数 |
|------|--------|
| 系统 | 1 |
| 认证（复旦SSO） | 4 |
| 用户端（需登录） | 14 |
| 内部管理（无需登录） | 28 |
| **总计** | **47** |
