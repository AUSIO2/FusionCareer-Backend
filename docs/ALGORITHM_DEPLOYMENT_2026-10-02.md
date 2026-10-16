# 2026-10-02 新算法生产发布记录

状态：已于 2026-10-03 完成生产发布和验收。

## 固定版本

- 算法上游：`FusionCareer-Algorithm@1f3d8a7a7d244487294a3793658e625d4d9086f1`。
- Vendor 同步提交：`8b334e0`。
- Python Agent 适配提交：`8004abd`。
- Java 推荐卡与消息协议提交：`18b1d1d`。
- 生产发布提交：`f5ffc85551582af6f330a75118bbd3a21e8ba5a9`。

本轮新增更严格的技术岗过滤、同公司岗位去重、可展示推荐理由、80 条有界候选窗口，以及既有预筛、源统计和 WeRead 管理能力。岗位权限和可见性仍由 Java 控制；历史卡片只保存岗位 ID、受限偏好和限长理由，读取时重新检查岗位状态。

## 生产启用策略

- `AI_CHAT_RECOMMEND_ENABLED=true`：启用受控推荐 Tool。
- `RECOMMENDATION_LLM_ENABLED=true`：模型重排失败时回退规则排序。
- `RECOMMENDATION_COMPANY_SCORE_ENABLED=true`：按发布决定启用企业市值评分；推荐 Tool 预算提高到 110 秒，整轮提高到 150 秒。
- `RECOMMENDATION_AUDIT_LOG_ENABLED=true`：按发布决定启用上游推荐审计；日志只保存在 Agent 私有持久卷，下载接口不开放该目录。
- `PREFILTER_ENABLED=true`：启用新版预筛；上线前先对近期文章做只读抽检。

市值与审计使用受锁的 `recommend-shared` 私有 workspace，以复用缓存并避免并发写日志。发布后先用测试账号完成冷缓存验收；若第三方接口使延迟或失败率不可接受，可分别关闭两个开关，不影响技术岗过滤、同公司去重、规则排序和推荐理由。

## 发布顺序

先在开发机生成不含密钥的版本化发布包：

```bash
./deploy/scripts/build-algorithm-release.sh
```

默认输出到 `/tmp/fusioncareer-algorithm-YYYYMMDD-<commit>/`，包含 Java/Agent 两个 linux/amd64 镜像、配置包、`RELEASE-MANIFEST.txt` 和 `SHA256SUMS`。配置包不会包含 `.env.production`，部署时必须保留服务器现有密钥，只按 `env.*.example` 合并新增变量。

1. 记录并备份当前 Java、Agent 镜像，数据库、Agent runtime、CrawlStore 和 runtime workflow 覆盖文件。
2. 查询 `fc_ai_message.input_metadata` 与 `presentation`。缺列时只执行一次 `V20260930__ai_message_presentation.sql`；已有列不得重复执行。
3. 先发布向后兼容的 Java，确认健康检查、推荐内部入口和消息展示协议。
4. 再发布 Agent，确认 vendor 哈希、工作流目录及生产开关。
5. 使用专用测试账号验证普通聊天、规则推荐、模型推荐、历史恢复、取消/清空、写提案确认与回退。
6. 对近期采集文章抽检新版预筛。WeRead 不自动增加调度，待管理员扫码和增量去重验证后单独启用。

## 回滚

先关闭推荐、预筛和新增调度，再回退 Java 与 Agent 镜像。保留新增数据库列和持久卷，避免破坏新版本写入的消息及用户历史；不要删除 CrawlStore、上传文件或 Agent runtime。

本轮服务器回滚入口：

- Python：`/home/vmadmin/fusioncareer/releases/20261002-algorithm-f5ffc85/rollback-python.sh`
- Java：`/data/fusioncareer/releases/20261002-algorithm-f5ffc85/rollback-java.sh`

## 本地门禁

- Python：168 项通过。
- Java：149 项通过。
- OpenAPI：全新 MySQL 初始化后生成 1,929 个用例，全部通过。
- 前端生产构建、Nginx、变更文件 Ruff、JSON、Compose 和 Git whitespace 检查通过。
- Linux amd64 Agent 镜像构建、非 root 运行、持久卷写入、健康检查和端到端岗位/简历 smoke 通过。
- 最终 Agent 镜像摘要：`sha256:2ff7f38a403cf84543de93ec6336b8076062216b83fa089337bf7bb973b406f2`；镜像内 90 个上游 tracked 文件哈希全部匹配 `1f3d8a7`。
- 最终 Java 镜像摘要：`sha256:54d86faaefc6fc1972da991b2851ae806fa52fbfb23732fd15b7da46d761ff98`。

## 生产验收

- 数据库原有 `input_metadata`、`presentation` 列，本轮未重复执行迁移。
- 发布前备份了 MySQL、上传文件、CrawlStore、Agent runtime 配置、环境文件和容器/镜像信息，并为两侧旧镜像创建独立回滚标签。
- Java、Agent、MySQL、SSH 隧道、Nginx、源站和正式 HTTPS 健康检查全部通过。
- 公网内部接口返回 404、匿名 Assistant 返回 401，学生/管理员登录均正确 302 到复旦统一认证。
- 专用验收账号执行真实 Java → Agent → Java 推荐：返回 10 张岗位卡，`rules+llm`、无降级、10 张均有推荐理由，算法版本 `1f3d8a7`，冷缓存耗时约 15.5 秒。
- 市值评分实际处理 40 家公司并生成缓存；本次样本中上市命中 0、未上市/未命中 40，其中 37 家为 `-2`。该分布需持续观察，若排序偏置明显可单独关闭市值开关。
- 推荐审计实际生成规则与 LLM 两阶段记录。保存脱敏统计后已删除测试审计；正式审计目录为空，市值缓存保留。
- 验收账号及其 2 条 AI 消息已清理；发布前后保持 21 个用户、37,879 个岗位、14 条 AI 消息。
- 无候选容器、测试账号或启动错误日志残留；WeRead 调度未新增。
