# FusionCareer 前后端一致性实施计划

## 1. 目标与基线

目标是让前端 V4、Java 后端、Python 服务和生产网关使用同一套接口、字段、枚举、认证与权限协议，完成以下闭环：

1. UIS 登录与退出。
2. 学生查看岗位、维护资料和简历、保存草稿、提交问卷、查看投递状态。
3. 管理员发布岗位、配置问卷、审核投递、导出数据与附件。
4. Python 服务继续通过内网调用 `/internal/**`，浏览器不得访问该路径。

实施基线：

- 后端：当前 `database` 分支。
- 前端：V4 视觉层，加上生产版本提交 `6213cf0` 已验证的 API、SSO 和问卷逻辑。
- 学生 API：`/api/**`。
- 浏览器管理 API：`/api/admin/**`。
- 服务间 API：Java 直连 `/internal/**`。
- 统一认证头：`Fusion-Token`。
- 统一响应：`{ "code": 200, "message": "...", "data": ... }`。

## 2. 提交总数与原则

计划共 **17 次提交**：

- Java 测试前置提交 2 次：`T1`、`T2`。
- 后端仓库 7 次：`B1` 至 `B7`。
- 前端仓库 8 次：`F1` 至 `F8`。

每次提交必须：

- 只解决一个可描述的问题。
- 可独立编译和回滚。
- 同时包含该改动需要的最小验证。
- 不混入格式化、无关重构或未完成代码。
- 不提交密码、Token、API Key 或生产 `.env`。

本阶段只建立和执行 Java 后端自动化测试：

- 不新增 Python 自动化测试任务。
- 不新增前端单元测试或 E2E 测试任务。
- 前端提交只执行 `npm run build` 和人工联调验收。
- Java 测试禁止连接生产数据库、真实 UIS、DeepSeek 或 Python 服务。

## 3. Java 测试前置提交

`T1` 和 `T2` 必须在 `B1` 至 `B7`、`F1` 至 `F8` 之前完成并合并。后续功能提交不得绕过这套测试基线。

### T1 `test(backend): establish Java test baseline`

内容：

- 复用已有 `spring-boot-starter-test`，使用 JUnit 5、Spring Boot Test 和 MockMvc。
- 新增 `application-test.yml`，所有测试配置通过 `test` profile 注入。
- 新增独立 MySQL 8 测试 Compose，不使用 H2，不连接开发或生产数据库。
- 测试数据库从空库初始化，测试数据使用事务回滚或显式清理。
- 文件测试使用临时上传目录，禁止写入 `/data/fusioncareer/uploads`。
- 增加统一 Java 测试入口脚本，负责启动 MySQL、等待健康、执行 Maven 测试并清理容器。
- 增加 JaCoCo 分支和行覆盖报告，但暂不设置全仓库百分比门槛。
- 建立首批只描述当前有效行为的基线测试：
  - Spring Context 可以启动。
  - `/sys/health` 返回统一 `R` 结构。
  - 未登录访问学生接口返回 HTTP 401。
  - 非法请求返回统一错误结构。
  - 上传目录和测试数据库均来自 test profile。
- 测试方法遵守 `agent.md` 的简短“动词 + 名词”命名规则。

限制：

- 本提交只建立可通过的测试基础，不把已知分页缺陷写成一个永久失败的测试。
- 分页回归测试在 `B1` 中先加入，再与修复一同提交。
- 不引入 Testcontainers；CI 和本地都复用 Docker MySQL 8。

验收：

```bash
./deploy/scripts/test-java.sh
```

- 可以在全新测试数据库上重复执行。
- 连续执行两次结果一致。
- 测试结束后无残留业务数据和上传文件。
- JaCoCo 报告可以生成。

### T2 `test(api): add Schemathesis Java API suite`

内容：

- 使用现有 SpringDoc `/v3/api-docs` 作为唯一测试契约。
- 增加 Schemathesis Java API 测试脚本，只测试 Java 服务。
- Java 服务必须连接 `T1` 的隔离 MySQL。
- 首期自动覆盖：
  - OpenAPI 中公开操作可被发现。
  - 合法和非法基础参数。
  - 缺失字段、错误类型和非法枚举。
  - 响应 Content-Type 和 Schema。
  - 未认证接口的预期 401。
  - 非法输入不得产生 HTTP 500。
- 排除 `/internal/**`、真实 SSO callback、SLO 和所有可能访问外部系统的操作。
- 固定随机种子和单接口样例上限，保证 PR 运行时间稳定。
- 输出 JUnit XML，供后续 CI 展示结果。

限制：

- 本提交不创建生产或开发账号。
- 首期不自动测试真实 UIS 登录。
- 管理员和学生认证接口在 `B5` 建立稳定角色机制后，再扩展测试 Token 注入。
- Schemathesis 发现的业务规则缺陷必须转写为 Java JUnit 回归测试，不能只保留随机复现日志。

验收：

- 在隔离 Java 服务上可以重复运行。
- 不访问 Python、DeepSeek、UIS 或生产地址。
- 所有生成请求只影响测试数据库。
- 发现 HTTP 500、Schema 不一致或意外状态码时命令返回非零。

### 3.1 本机工具与安装清单

当前开发机已经具备：

| 工具 | 当前状态 | 用途 |
|---|---|---|
| JDK | 21.0.7，项目目标 Java 17 | 编译和运行后端 |
| Maven Wrapper | 3.9.14 | 固定 Maven 入口 |
| Docker CLI | 29.1.2 | 运行测试容器 |
| Docker Compose | 2.40.3 | 编排 MySQL 和测试服务 |
| `uv/uvx` | 已安装 | 备用运行 Schemathesis |

本机当前唯一前置阻塞是 Docker Desktop daemon 未启动。开始实现前先执行：

```bash
open -a Docker
docker info
docker compose version
java -version
./mvnw -version
```

不需要安装：

- 全局 Maven。
- 本机 MySQL。
- H2。
- Testcontainers。
- 全局 Schemathesis。
- Python 测试依赖。
- 前端测试框架。

测试期间按需下载：

| 组件 | 版本/镜像 | 获取方式 |
|---|---|---|
| MySQL | `mysql:8.0` | Docker Compose 自动拉取 |
| JaCoCo | `0.8.15` | Maven 自动拉取 |
| Schemathesis | `ghcr.io/schemathesis/schemathesis:stable` | Docker 自动拉取 |

Schemathesis 默认使用官方 Docker 镜像，不使用 `pip install`。本机 `uvx` 首次下载曾因 `jsonschema-rs` 网络超时失败，Docker 镜像可以避免 Python wheel 下载和架构差异。

### 3.2 T1/T2 文件清单

| 文件 | 操作 | 内容 |
|---|---|---|
| `pom.xml` | 修改 | 增加 `jacoco.version=0.8.15` |
| `fusioncareer-biz/pom.xml` | 修改 | 配置 JaCoCo `prepare-agent` 和 `report` |
| `.gitignore` | 修改 | 忽略测试报告、PID、日志和临时上传目录 |
| `deploy/docker-compose.test.yml` | 新增 | 独立 MySQL 8 测试服务 |
| `deploy/scripts/test-java.sh` | 新增 | 本机统一测试入口 |
| `fusioncareer-biz/src/test/resources/application-test.yml` | 新增 | Java test profile |
| `fusioncareer-biz/src/test/java/com/fusioncareer/FusionCareerApplicationTest.java` | 新增 | Java 基线测试 |
| `schemathesis-report/` | 运行生成并忽略 | JUnit XML 和 Schema 覆盖报告 |

不创建测试工具类、Fixture 工厂或抽象基类。首批测试共享内容很少，直接放在一个测试类中；出现第三次真实重复后再提取。

### 3.3 `docker-compose.test.yml` 配置

只定义一个 MySQL 服务：

```text
service: mysql-test
image: mysql:8.0
container port: 3306
host port: 13306
database: fusioncareer_test
username: root
password: fusioncareer_test
timezone: Asia/Shanghai
```

挂载：

```text
fusioncareer-biz/src/main/resources/schema.sql
  → /docker-entrypoint-initdb.d/01-schema.sql:ro
```

要求：

- 使用独立 named volume，脚本结束时通过 `down -v` 删除。
- 健康检查使用 `mysqladmin ping`。
- 不暴露除 `13306` 外的端口。
- 不读取 `.env.production` 或生产数据库密码。

当前项目未启用 Flyway，`db/migration/` 下的 SQL 不会自动执行。T1/T2 从空库加载完整 `schema.sql`；在正式引入迁移工具前，不假设这些增量 SQL 会自动生效。

### 3.4 `application-test.yml` 配置

```yaml
server:
  port: ${TEST_SERVER_PORT:19100}

spring:
  datasource:
    url: ${TEST_DB_URL:jdbc:mysql://127.0.0.1:13306/fusioncareer_test?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&allowMultiQueries=true}
    username: ${TEST_DB_USERNAME:root}
    password: ${TEST_DB_PASSWORD:fusioncareer_test}

upload:
  base-dir: ${TEST_UPLOAD_DIR:${java.io.tmpdir}/fusioncareer-test-uploads}
  url-prefix: http://127.0.0.1:19100/files

fudan-sso:
  client-id: test-client
  client-secret: test-secret
  redirect-uri: http://127.0.0.1:19100/fudan/callback
  frontend-redirect-url: http://127.0.0.1:5173/

python-service:
  base-url: http://127.0.0.1:9
```

`python-service` 指向不可用端口，确保测试意外调用 Python 时立即失败，而不是访问真实服务。

`application-test.yml` 位于 `src/test/resources`，只供 JUnit classpath 使用，不会打入可部署 JAR。`test-java.sh` 独立启动 JAR 时必须显式注入等价环境变量：

```text
SERVER_PORT
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD
UPLOAD_BASE_DIR
UPLOAD_URL_PREFIX
FUDAN_SSO_CLIENT_ID
FUDAN_SSO_CLIENT_SECRET
FUDAN_SSO_REDIRECT_URI
FUDAN_SSO_FRONTEND_REDIRECT_URL
PYTHON_SERVICE_BASE_URL
SA_TOKEN_IS_LOG
```

### 3.5 `test-java.sh` 函数清单

脚本只实现以下函数，均遵守“动词 + 名词”：

| 函数 | 职责 |
|---|---|
| `checkTools` | 检查 Docker、Compose、Java 和 Maven Wrapper |
| `startDatabase` | 启动测试 MySQL |
| `waitDatabase` | 等待 MySQL healthcheck |
| `runJUnit` | 运行 Maven/JUnit/MySQL 集成测试 |
| `buildBackend` | 构建可启动的 Java JAR |
| `startBackend` | 用 test profile 启动 Java 19100 |
| `waitBackend` | 等待 `/sys/health` 和 `/v3/api-docs` |
| `runSchemathesis` | 使用官方容器测试 Java OpenAPI |
| `stopServices` | 停止 Java 并执行 Compose `down -v` |
| `cleanFiles` | 删除测试上传目录、PID 和临时日志 |
| `runMode` | 分派 `junit`、`api`、`all` 三种模式 |

脚本要求：

- 使用 `set -euo pipefail`。
- 使用 `trap stopServices EXIT INT TERM`，失败也必须清理。
- 不打印数据库密码、Token 或完整响应正文。
- Java PID 写入被 `.gitignore` 忽略的测试输出目录。
- 默认模式为 `all`。

### 3.6 Java 基线测试函数

`FusionCareerApplicationTest` 使用：

```text
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
```

首批方法：

| 函数 | 断言 |
|---|---|
| `startContext` | Spring Context 成功启动 |
| `readHealth` | `/sys/health` 返回 HTTP 200 和统一 `R` |
| `rejectGuest` | 未登录访问 `/user/profile/get` 返回 HTTP 401 |
| `returnError` | 非法枚举请求返回统一错误 JSON，不返回 HTML |
| `readUploadPath` | 上传目录位于系统临时目录而非生产目录 |

首批测试不新增 mock 框架封装，不启动真实 UIS，不调用 Python，不发送网络请求到外部域名。

### 3.7 Schemathesis 本机配置

本机通过 Docker Desktop 的 `host.docker.internal` 访问 Java：

```text
schema: http://host.docker.internal:19100/v3/api-docs
workers: 1
generation seed: 20260901
max examples: 10/operation
methods: GET only
excluded paths: ^/fudan/.*
checks: not_a_server_error
telemetry: disabled
report: JUnit XML
```

等价运行命令由 `runSchemathesis` 封装，核心命令为：

```bash
docker run --rm \
  -e SCHEMATHESIS_TELEMETRY=false \
  -v "$PWD/schemathesis-report:/app/schemathesis-report" \
  ghcr.io/schemathesis/schemathesis:stable \
  run -w 1 \
  --wait-for-schema 30 \
  --include-method GET \
  --exclude-path-regex '^/(fudan|internal/resume-file/.*/download|user/resume/file/.*/download).*' \
  --checks not_a_server_error \
  -n 10 \
  --seed 20260901 \
  --report junit \
  --report-dir /app/schemathesis-report \
  --coverage-format html \
  --coverage-report-html-path /app/schemathesis-report/schema-coverage.html \
  http://host.docker.internal:19100/v3/api-docs
```

T2 首次只跑 GET，避免自动修改测试数据；初始门禁只要求不得出现 HTTP 500。当前 OpenAPI 尚未完整声明 401/403，如果立即启用全部状态码契约检查会产生无意义失败。等 `B5` 提供稳定测试角色、Token 和完整认证响应文档后，再扩展 POST/PUT/DELETE 及 `status_code_conformance`、`response_schema_conformance`；不得在 T2 偷加测试专用生产接口。

### 3.8 本机跑通顺序

T1 完成后：

```bash
open -a Docker
docker info
./deploy/scripts/test-java.sh junit
```

T2 完成后：

```bash
./deploy/scripts/test-java.sh api
./deploy/scripts/test-java.sh all
```

验收输出：

```text
fusioncareer-biz/target/site/jacoco/index.html
schemathesis-report/junit-*.xml
schemathesis-report/schema-coverage.html
```

在 `all` 连续成功两次之前，不开始 `B1` 或任何前端提交。

### 3.9 本机验证记录

2026-09-01 已在 macOS ARM64 本机完成：

- Docker Desktop 29.1.2 启动正常。
- MySQL 8 测试容器从空 volume 初始化成功。
- JUnit 5：5 个测试通过，0 失败，0 跳过。
- JaCoCo 0.8.15：成功分析 31 个 Java 类并生成 HTML。
- Java test profile JAR：成功监听 `127.0.0.1:19100`。
- SpringDoc：成功暴露 OpenAPI 3.0.1，共 53 个操作。
- Schemathesis 4.25.2：首期选择 22 个 GET 操作，生成 994 个用例，全部通过。
- 9 个学生接口因未注入测试 Token 返回 401，记录为预期 warning。
- `./deploy/scripts/test-java.sh all` 连续执行两次成功。
- 每次运行后 Java PID、MySQL 容器、network 和 volume 均清理完成。

已确认的后续限制：

- B5 前不自动测试真实学生或管理员 Token。
- 当前只以 `not_a_server_error` 作为 Schemathesis 阻断检查。
- 当前数据库从完整 `schema.sql` 初始化，尚未验证增量迁移链。
- 不测试前端、Python、UIS 和 DeepSeek。

## 4. 后端提交

### B1 `fix(pagination): enable MyBatis-Plus pagination`

内容：

- 注册 `MybatisPlusInterceptor`。
- 注册 MySQL `PaginationInnerInterceptor`。
- 将分页参数限制为 `page >= 1`、`1 <= size <= 100`。
- 覆盖岗位、用户、资料、简历、投递和问卷作答列表。
- 保持现有 `PageResult` 响应结构不变。

验收：

- `size=1` 时 `list.length` 必须为 1。
- `total`、`page`、`size`、`totalPages` 正确。
- `./mvnw test` 通过。

### B2 `feat(job): align search filters and sorting contract`

内容：

- 在 `JobPostQueryRequest` 增加：
  - `workProvince`
  - `salaryMin`
  - `salaryMax`
  - `sortBy=NEWEST|DEADLINE`
  - `recommended`
- 省份和城市分别查询，不再用省份值匹配 `work_city`。
- 薪资查询采用区间相交规则。
- `NEWEST` 按创建时间倒序。
- `DEADLINE` 按截止日期正序，空日期排最后。
- 保持现有岗位分类、招聘类型、工作形式等筛选不变。

验收：

- V4 发出的每个筛选参数都影响 SQL。
- 截止日期排序跨全部分页正确。
- 省份和城市组合查询正确。

### B3 `feat(job): add recommendation and application count`

内容：

- 新增版本化 SQL 迁移文件，为岗位增加 `recommended`，默认 `false`；当前阶段由部署步骤显式执行，不假设 Flyway 已启用。
- `JobPostEntity`、`JobPostRequest`、`JobPostResponse` 增加 `recommended`。
- `JobPostResponse` 增加 `applicationCount`。
- `applicationCount` 只统计 `SUBMITTED` 和 `REVIEWED`，不统计草稿。
- 使用批量聚合查询，禁止岗位列表产生 N+1 查询。

验收：

- `/job/list?recommended=true` 只返回推荐岗位。
- 管理端可以读取真实投递数。
- 旧岗位迁移后均为非推荐状态。

### B4 `fix(auth): align secure SSO callback and logout flow`

内容：

- 登录时生成随机 `state` 并存入服务端 session。
- 回调必须验证 `state`，非法或重复值直接拒绝。
- 登录成功跳转到 `/#/login?token=...`，让 Token 位于 fragment，避免进入 Nginx 请求日志。
- 删除 OAuth code、Sa-Token、SLO Token 和完整 UIS 用户信息日志。
- 增加带 `Fusion-Token` 的 `POST /fudan/logout`，返回 UIS 注销地址。
- 暂时保留旧 GET 注销入口以兼容线上版本。

验收：

- 登录成功后 Nginx 日志不包含 Token。
- `state` 不匹配时登录失败。
- 退出同时结束本地会话和 UIS 会话。

### B5 `feat(auth): expose current user and role authorization`

内容：

- 增加 `GET /user/me`，返回当前用户 ID、学号、用户名、角色和状态。
- 实现 Sa-Token `StpInterface`，从 `fc_user.role` 返回角色。
- 验证 `@SaCheckRole("ADMIN")` 可用。
- 禁止 `DISABLED` 用户继续登录。

验收：

- 普通用户返回 `NORMAL`。
- 管理员返回 `ADMIN`。
- 未登录返回 HTTP 401。
- 普通用户访问管理员测试接口返回 HTTP 403。

### B6 `feat(admin): expose role-protected management APIs`

内容：

- 新增浏览器使用的受保护接口：
  - `/admin/job-post/**`
  - `/admin/questionnaire/questions/**`
  - `/admin/questionnaire/answers/**`
- 复用现有 Service，不复制业务逻辑。
- 覆盖岗位 CRUD、批量创建、问卷题目整组保存、作答列表和详情、单条与批量审核。
- 管理 Controller 统一使用 `@SaCheckRole("ADMIN")`。
- Nginx 对 `/api/internal/**` 返回 404。
- `/internal/**` 保留给 Python 服务直连 Java。

验收：

- 管理员可以访问 `/api/admin/**`。
- 普通用户返回 403。
- 外部 `/api/internal/**` 返回 404。
- Python 服务直连 `/internal/**` 不受影响。

### B7 `feat(admin): export applications and resume attachments`

内容：

- 增加投递导出：
  - `GET /admin/questionnaire/answers/job/{jobPostId}/export?format=csv`
  - `GET /admin/questionnaire/answers/job/{jobPostId}/export?format=zip`
- CSV 使用 UTF-8 BOM，保证 Excel 可直接打开。
- ZIP 包含 `applications.csv` 和简历附件。
- 支持通过 `answerIds` 只导出所选投递。
- 清洗文件名，防止 ZIP 路径穿越。
- 使用 JDK CSV 文本输出和 `ZipOutputStream`，不增加 Apache POI。

验收：

- 中文 CSV 在 Excel 中不乱码。
- ZIP 可解压且目录安全。
- 只能导出当前岗位和指定投递。
- 普通用户返回 403。

## 5. 前端提交

### F1 `refactor: promote V4 to the canonical frontend`

内容：

- 将 V4 整理为唯一正式应用目录。
- 删除 V3/V4 重复入口和过期路径。
- 修正 README 的启动目录。
- 保持 V4 页面和样式不变，本提交不接 API。

验收：

- `npm ci` 通过。
- `npm run build` 通过。
- 页面视觉与 V4 一致。

### F2 `feat(api): restore gateway-aware API client`

内容：

- 从 `6213cf0` 迁入统一 API 客户端。
- 默认 API 前缀为 `/api`，允许 `VITE_API_BASE` 覆盖。
- 自动附加 `Fusion-Token`。
- 统一处理 JSON、FormData、Blob 下载和 `{code,message,data}`。
- HTTP 401 时清除本地 Token。
- Token 键统一为 `fusion-career-token`。
- 删除所有硬编码 `http://localhost:9100`。
- 不引入 Axios 或额外状态管理库。

验收：

- 生产构建产物不包含 `localhost:9100`。
- 所有业务请求通过 `/api/**`。
- `npm run build` 通过。

### F3 `feat(auth): connect SSO lifecycle and route guards`

内容：

- 恢复 fragment Token 消费。
- UIS 登录按钮跳转 `/fudan/login`。
- 登录后调用 `/user/me`。
- 为业务路由增加 `requiresAuth`。
- 为 `/admin` 增加 `roles: ['ADMIN']`。
- 删除管理员假密码登录，管理员也使用 UIS。
- 退出时调用新的 POST 接口并跳转 UIS 注销地址。

验收：

- 未登录无法访问业务页。
- 普通用户无法访问 `/admin`。
- 管理员登录后可以访问 `/admin`。
- 退出后本地 Token 和 UIS 会话均失效。

### F4 `feat(job): connect V4 job browsing and filters`

内容：

- 接通 `/job/list` 和 `/job/{id}`。
- 使用后端真实岗位字段和枚举。
- 将筛选、分页和排序交给后端。
- 推荐轮播请求 `recommended=true&page=1&size=6`。
- 招聘类型使用后端明确枚举，不再把所有实习折叠成 `DAILY_INTERNSHIP`。
- 生产环境失败时显示错误，禁止回退 mock。

验收：

- 每个筛选控件产生正确查询参数。
- 页码、总数和排序正确。
- 推荐轮播只显示推荐岗位。
- 岗位详情字段完整。

### F5 `feat(profile): connect profile resume and file APIs`

内容：

- 接通资料、简历正文、文件列表、上传、下载、删除和配额接口。
- 对齐 `Gender`、`PoliticalStatus`、`EduLevel` 和 `Mindset`。
- 文件限制统一为 PDF/JPG/JPEG/PNG、20MB、总配额 30MB。
- 空表单值按协议转换为 `null`。

验收：

- 保存后刷新数据不丢失。
- 上传、下载、删除和配额刷新正常。
- 非本人文件无法访问。

### F6 `feat(questionnaire): connect draft submit and application history`

内容：

- 迁入并适配 `useQuestionnaireForm.js`。
- 对齐问卷包装响应、草稿、正式提交、单岗位作答、我的投递和附件上传。
- `answers` 保持 JSON 字符串。
- `FILE_UPLOAD` 保存文件 ID。
- `CHECKBOX` 使用逗号分隔字符串。
- 截止或已审核后禁止编辑。
- 删除生产问卷 mock。

验收：

- 草稿可以保存和恢复。
- 提交后状态为 `SUBMITTED`。
- 审核后状态为 `REVIEWED`。
- 前后端必填校验一致。
- `tabCounts` 与列表一致。

### F7 `feat(admin): connect job and questionnaire management`

内容：

- 用 `/admin/job-post/**` 替换 AdminView 岗位 mock。
- 接通列表、新建、编辑、发布、下线、删除、批量创建和推荐状态。
- 展示真实 `applicationCount`。
- 岗位保存成功后单独调用问卷题目整组保存接口。
- 不把 `questions` 塞进 `JobPostRequest`。

验收：

- 新建和发布岗位后学生端可见。
- 下线后学生端不可见。
- 推荐岗位进入轮播。
- 问卷题目修改后刷新仍存在。

### F8 `feat(admin): connect review export and remove production mocks`

内容：

- 接通投递列表、详情、单条审核和批量审核。
- 展示投递状态、审核结果、审核意见和审核时间。
- 接通 CSV 和 ZIP 下载。
- UI 文案统一为“CSV（Excel 可打开）”。
- 删除所有 mock 岗位、学生、学号、问卷、简历链接和只弹 Toast 的假操作。
- 更新 README 和部署说明。

验收：

- 完成“发布岗位 → 配置问卷 → 学生提交 → 管理员审核 → 导出”全链路。
- 生产构建不包含 mock 学生数据。
- `npm run build` 通过。

## 6. 执行顺序

推荐依赖顺序：

```text
T1 → T2 → B1 → B2 → B3 → B4 → B5
                                ↓
          F1 → F2 → F3 → F4 → F5 → F6
                                ↓
          B6 → F7 → B7 → F8
```

允许并行：

- 只有 `T1`、`T2` 均通过后，才允许开始功能提交。
- `B1` 至 `B5` 与 `F1` 可以在测试基线完成后并行。
- `F5` 与 `F6`，前提是 `F2` 已完成。
- `B7` 与 `F7`。
- `F8` 必须等待 `B7`。

后续每个后端提交必须：

- 为新增或修改的业务分支增加最小 JUnit 回归测试。
- 默认达到分支覆盖；认证、权限、校验和状态迁移覆盖全部已定义决策场景。
- 通过 `T1` 的完整 Java 测试入口。
- 接口契约变化时同步更新 OpenAPI，并通过 `T2`。

## 7. 合并与部署顺序

1. 合并 `T1`、`T2`，在主分支建立 Java 测试门禁。
2. 合并并部署 `B1` 至 `B5`。
3. 执行数据库迁移并验证旧前端仍可使用。
4. 合并并部署 `F1` 至 `F6`，完成学生端人工联调。
5. 合并 `B6`、`B7`，验证 Python 内网调用。
6. 合并并部署 `F7`、`F8`。
7. 最后启用 Nginx `/api/internal/**` 阻断规则。
8. 运行 Java API smoke test。
9. 使修复前产生的旧 SSO Token 失效。

## 8. 完成标准

- 学生端实际使用的接口全部连接真实后端。
- V4 构建产物不包含 `localhost:9100`。
- 生产环境不存在业务 mock。
- UIS 登录、角色判断和退出闭环正常。
- 分页、筛选和排序在数据库层生效。
- 管理员只能通过 `/admin/**` 操作。
- 浏览器不能访问 `/internal/**`。
- 推荐状态和投递数来自数据库。
- 问卷草稿、提交、审核和导出闭环正常。
- 请求 DTO、响应 DTO、枚举和 README 一致。
- Java JUnit、MySQL 集成测试、Schemathesis、前端构建和 Java API smoke test 全部通过。

## 9. 功能实现函数清单

以下是各功能提交预计新增或修改的主要函数。实现时优先复用现有方法；如果现有方法已经完成同一职责，不重复创建。最终命名遵守 `agent.md` 的简短“动词 + 名词”规则。

### 9.1 后端函数

| 提交 | 函数 | 职责 |
|---|---|---|
| B1 | `createPagination` | 创建 MyBatis-Plus 分页拦截器 |
| B1 | `normalizePage` | 限制页码和每页数量 |
| B2 | `buildJobQuery` | 构建岗位筛选条件 |
| B2 | `sortJobs` | 应用最新或截止日期排序 |
| B3 | `countApplications` | 批量统计岗位投递数 |
| B3 | `mapApplications` | 将统计结果写入岗位响应 |
| B4 | `createState` | 创建 OAuth state |
| B4 | `verifyState` | 校验并消费 OAuth state |
| B4 | `buildLoginUrl` | 构建 UIS 登录 URL |
| B4 | `processCallback` | 处理 OAuth callback |
| B4 | `buildLogoutUrl` | 构建 UIS 注销 URL |
| B5 | `readUser` | 读取当前用户响应 |
| B5 | `checkStatus` | 拒绝禁用用户 |
| B5 | `readRoles` | 从业务用户读取角色；框架强制方法名除外 |
| B6 | `createJobPost` | 管理员创建岗位 |
| B6 | `createJobPosts` | 管理员批量创建岗位 |
| B6 | `readJobPost` | 管理员读取岗位详情 |
| B6 | `readJobPosts` | 管理员分页读取岗位 |
| B6 | `updateJobPost` | 管理员更新、发布或下线岗位 |
| B6 | `deleteJobPost` | 管理员删除岗位 |
| B6 | `readQuestions` | 管理员读取问卷题目 |
| B6 | `updateQuestions` | 管理员整组保存问卷题目 |
| B6 | `deleteQuestions` | 管理员删除问卷题目 |
| B6 | `readAnswers` | 管理员分页读取投递 |
| B6 | `readAnswer` | 管理员读取投递详情 |
| B6 | `reviewAnswer` | 管理员审核单条投递 |
| B6 | `reviewAnswers` | 管理员批量审核投递 |
| B7 | `exportAnswers` | 分派 CSV 或 ZIP 导出 |
| B7 | `buildCsv` | 生成 UTF-8 BOM CSV |
| B7 | `buildZip` | 生成 CSV 与附件 ZIP |
| B7 | `sanitizeFilename` | 清理附件文件名和 ZIP 路径 |

### 9.2 后端测试函数

| 提交 | 测试函数 | 场景 |
|---|---|---|
| B1 | `readPage` | 分页只返回当前页 |
| B1 | `rejectPage` | 拒绝非法分页参数 |
| B1 | `limitPage` | 限制最大 size |
| B2 | `filterProvince` | 省份筛选正确 |
| B2 | `filterCity` | 城市筛选正确 |
| B2 | `filterSalary` | 薪资区间相交正确 |
| B2 | `sortDeadline` | 截止日期全局排序正确 |
| B2 | `sortNewest` | 创建时间倒序正确 |
| B3 | `readRecommendations` | 只返回推荐岗位 |
| B3 | `countApplications` | 统计已提交和已审核投递 |
| B3 | `excludeDrafts` | 投递数排除草稿 |
| B4 | `createState` | 登录 URL 包含随机 state |
| B4 | `rejectState` | 拒绝非法或重复 state |
| B4 | `hideToken` | 重定向和日志不泄漏 Token |
| B4 | `logoutSession` | 注销返回 UIS 地址并结束本地会话 |
| B5 | `readUser` | `/user/me` 返回用户和角色 |
| B5 | `rejectDisabled` | 禁用用户不能登录 |
| B5 | `allowAdmin` | 管理员访问管理接口 |
| B5 | `rejectNormal` | 普通用户访问管理接口返回 403 |
| B6 | `createJobPost` | 管理员创建岗位 |
| B6 | `updateQuestions` | 问卷题目整组更新 |
| B6 | `reviewAnswer` | 单条审核状态迁移 |
| B6 | `reviewAnswers` | 批量审核状态迁移 |
| B6 | `blockInternal` | 网关阻断浏览器 internal 路径 |
| B7 | `exportCsv` | CSV 字段、编码和中文正确 |
| B7 | `exportZip` | ZIP 包含 CSV 和附件 |
| B7 | `sanitizeFilename` | 路径穿越文件名被清理 |
| B7 | `rejectExport` | 普通用户不能导出 |

### 9.3 前端函数

前端本阶段不写自动化测试，但实现函数仍保持简短命名。

| 提交 | 函数 | 职责 |
|---|---|---|
| F2 | `readToken` | 读取本地 Token |
| F2 | `saveToken` | 保存本地 Token |
| F2 | `deleteToken` | 删除本地 Token |
| F2 | `buildUrl` | 构建 `/api` URL |
| F2 | `requestApi` | 统一发起 API 请求 |
| F2 | `readJson` | 解包 JSON 响应 |
| F2 | `uploadForm` | 上传 FormData |
| F2 | `downloadFile` | 下载 Blob 文件 |
| F3 | `consumeToken` | 消费 fragment Token |
| F3 | `readUser` | 读取当前用户和角色 |
| F3 | `guardRoute` | 执行登录和角色守卫 |
| F3 | `loginUser` | 跳转 UIS 登录 |
| F3 | `logoutUser` | 本地和 UIS 注销 |
| F4 | `buildFilters` | 构建岗位筛选参数 |
| F4 | `loadJobs` | 加载岗位列表 |
| F4 | `loadJob` | 加载岗位详情 |
| F4 | `loadRecommendations` | 加载推荐岗位 |
| F4 | `mapJob` | 映射岗位响应到视图模型 |
| F5 | `loadProfile` | 加载个人资料 |
| F5 | `saveProfile` | 保存个人资料 |
| F5 | `loadResume` | 加载简历正文 |
| F5 | `saveResume` | 保存简历正文 |
| F5 | `loadFiles` | 加载简历文件 |
| F5 | `uploadFile` | 上传简历文件 |
| F5 | `downloadFile` | 下载简历文件 |
| F5 | `deleteFile` | 删除简历文件 |
| F5 | `loadQuota` | 加载文件配额 |
| F6 | `loadQuestions` | 加载问卷题目和截止信息 |
| F6 | `loadAnswer` | 加载单岗位作答 |
| F6 | `loadApplications` | 加载我的投递 |
| F6 | `saveDraft` | 保存问卷草稿 |
| F6 | `submitAnswers` | 正式提交问卷 |
| F6 | `uploadAttachment` | 上传问卷附件 |
| F6 | `buildAnswers` | 序列化 answers JSON |
| F6 | `parseAnswers` | 解析 answers JSON |
| F6 | `validateAnswers` | 校验前端必填项 |
| F7 | `loadJobs` | 管理员加载岗位 |
| F7 | `createJob` | 管理员创建岗位 |
| F7 | `updateJob` | 管理员更新、发布或下线岗位 |
| F7 | `deleteJob` | 管理员删除岗位 |
| F7 | `saveQuestions` | 管理员保存问卷题目 |
| F8 | `loadAnswers` | 管理员加载投递列表 |
| F8 | `loadAnswer` | 管理员加载投递详情 |
| F8 | `reviewAnswer` | 管理员审核单条投递 |
| F8 | `reviewAnswers` | 管理员批量审核投递 |
| F8 | `exportCsv` | 下载 CSV |
| F8 | `exportZip` | 下载 ZIP |
