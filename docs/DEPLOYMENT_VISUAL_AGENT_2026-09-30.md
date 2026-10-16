# 2026-09-30 视觉与 AI Agent 生产部署

状态：已部署，生产验证通过。入口：https://fusioncareer.fudan.edu.cn/ 。本次通过 Python 机及既有双向 SSH 隧道发布，保留网关、数据库和上传/采集/OCR 持久卷。

## 版本和工件

- 后端源码基线：`681a935` 加本次工作区迁移改动。
- 前端源码基线：`32f8730` 加视觉迁移、个人空间接线及已有姓名修复。
- 上游算法：`81c51a63cfa88d6546084ae7034ebeab96646950`；vendor 原文件未修改。
- Java 镜像：`fusioncareer-backend:visual-migration-20260930`，ID `sha256:7c40840e7b04fcd3509567c97494a8ce966ffd961f06500c0b33bfc8e470ef73`。
- Java JAR SHA-256：`b310cdfdab257dc2c783717857339b9ce254ee50e392a25ff0a2670a304d38cd`。
- Agent 镜像：`fusioncareer-agent:visual-migration-20260930-r3`，ID `sha256:0f13526e187b882002d3c1e1b504086aff00159b0561d4a532fd42a34dc72e42`。
- 前端入口：`assets/index-DIpDxWgz.js`；index.html SHA-256：`dc1dc7d7444a6b8a82c761741ec12f2a2e4375a77c8b65e8b99fff0944493ef9`。
- Python 机发布目录：`/home/vmadmin/fusioncareer/releases/20260930-visual-agent`。
- Java 机发布目录：`/data/fusioncareer/releases/20260930-visual-agent`。

源码快照、基础镜像包、Agent 修复镜像的 Dockerfile/源码、SQL 选择清单、验证脚本、旧配置与回滚脚本均保留在发布目录。没有把生产密钥写入代码仓库。

## 数据库与配置

线上原先没有个人空间/AI 会话表。本次先备份约 48.8 MB 的 SQL，再按信息架构查询结果执行 14 项增量步骤：资源版本/软删除列及索引、Memory/变更历史/单会话表、消息 input_metadata/presentation 列。已有岗位多城市/多学历列未重复迁移。

生成并保存了新增功能所需的个人空间加密密钥和 AgentContext 签名密钥；既有数据库、SSO、LLM 和服务间 Token 沿用线上配置。正式 Java 和 Agent 的写提案/推荐开关均启用。

实际 Tool 覆盖值为 `recommend_jobs=45s`、`search_jobs=8s`、`parse_resume_file=90s`，单轮总时限 120s。配置保存于服务器受限 env 文件；各超时层关系见 [实施说明](VISUAL_MIGRATION_IMPLEMENTATION.md)。

Nginx 增加 Assistant 的独立 SSE 路由：关闭缓冲/缓存并延长超时。前端先复制版本化资源，最后原子替换入口，旧 hash 资源保留。

预筛对最近 40 篇文章做了只读对照，40 篇均正常通过、无缺失文件，之后启用 PREFILTER_ENABLED。原公众号 18:30、官网 19:30 定时任务保留；没有新建 WeRead 定时任务，WeRead 仍需管理员扫码后启用。

## 部署时修复

真实 DeepSeek V4 接口对缺失 reasoning_content 的 Tool 历史返回 HTTP 400。交互式 Agent 不保留推理正文，推荐按钮还会生成结构化工具请求，因此对 DeepSeek V4 的 Tool 规划、流式回复和 JSON 重排显式使用非思考模式，保持其他提供商调用不变。该行为按 [DeepSeek 官方接口说明](https://api-docs.deepseek.com/guides/thinking_mode/) 实现，并添加回归测试。适配层改动不修改 vendor 源码。

## 生产验证

- Java 与 Agent 健康检查通过，OCR 模型预加载正常。
- DeepSeek 兼容修复后，本地 Python 全量 167 项测试通过；此前 Java 149 项、前端 21 项测试保持通过。
- 真实模型和生产服务互调：10 张岗位卡、`rules+llm`、无降级，回复完成并可从历史恢复。
- 使用私有候选登录入口和专用验收账号验证修改提案：确认前资料不变，确认后更新成功；无真实用户资料参与测试。
- 合成文本与清晰合成图片简历解析均通过，图片结果正确提取了预设技能。
- Python 运行目录 228 个文件逐一与本地源码核验，无差异；线上 Java JAR 哈希一致。
- 源站 HTTP、正式 HTTPS 各 22 个静态文件哈希全部一致。
- 学生/管理登录入口均返回 302 至复旦认证；内部 API 对外 404，匿名 Assistant 接口 401。
- 正式 HTTPS 登录页、复旦校徽、390px 布局与 JavaScript 检查通过。没有代替真实用户登录 UIS。
- 已清理本次候选容器和专用验收账号及其消息/修改记录；真实用户仍为 21，岗位仍为 36,309。

Java 重启会使旧登录会话失效，已打开的页面需重新登录。

## 回滚

保留旧 Agent 镜像 `fc-python-agent:rollback-20260930`、旧 Java 镜像 `fusioncareer-backend:rollback-20260930`，以及数据库、上传文件、runtime 配置、CrawlStore 和前端备份。

Python 机执行：

```sh
bash /home/vmadmin/fusioncareer/releases/20260930-visual-agent/rollback-python.sh
```

Java 机执行：

```sh
bash /data/fusioncareer/releases/20260930-visual-agent/rollback-java.sh
```

回滚应用和静态入口，保留增量表/列与新密钥，避免丢失部署后的用户数据和加密历史。不要删除持久卷或使用 remove-orphans 清理双向隧道。
