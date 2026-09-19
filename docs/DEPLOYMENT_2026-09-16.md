# 2026-09-16 Python 引擎部署

服务器：10.107.13.184，正式容器 fc-python-agent-1，端口 8900。

## 发布内容

- 固定算法版本：chenxin1209/FusionCareer-Algorithm@72ff6b2b44520f78090958f0c3a7e74959189646。
- 算法完整工作流接入；简历与岗位内部接口通过引擎执行。
- 共享采集模块迁到 app/skills/business/crawlers；官网抓取按职责拆分。
- 原数据卷、wechat.db、Nginx、Java 服务和定时任务保持兼容。

## 镜像与归档

- 发布镜像：fc-python-agent:release-20260916（正式 latest 指向此版本）。
- 镜像 ID：sha256:5653074063508de1654303d0afa924206412528deaac17cae4e97ad089b9edfa。
- 回滚镜像：fc-python-agent:rollback-20260916。
- 服务器发布目录：/home/vmadmin/fusioncareer/releases/20260916-workflow-crawlers。
- 源码归档 SHA-256：a3b7ea84d17dc1c8c66e43a2040994562a9af82562d86180f66c446fc44cac81。
- 备份：previous-source.tar.gz、runtime-before.tar.gz、crawl-before.db。
- release 目录保留 Dockerfile.release、buildx.log、smoke.log、verification.log、rollback.sh。

本次基于已有生产镜像增量安装依赖并替换源码，使用 BuildKit 构建。
标准 Dockerfile 保留用于从基础镜像完整构建。

## 验证

- 本地 140 项测试通过。
- 候选容器在独立 18900 端口验证：健康、注册信息、真实模型双岗位抽取、文本简历解析、合成图片 OCR 全部通过。
- 线上保存的 5 个工作流通过候选版本验证。
- 正式代码 185 个 app 文件与发布清单哈希一致。
- 正式健康检查、Nginx 首页、/agent/api/health、带服务间鉴权的 Java 查询通过。
- 正式岗位接口完成一次合成招聘文本解析，返回 OFFLINE 草稿；候选容器已清理。
- 正式注册 21 个工作流、23 个 Skill。
- 采集记录 9604 条，已结构化 9603 条，与部署前一致。
- 保留定时任务：公众号 18:30、官网 19:30（Asia/Shanghai）。
- 未进行全量抓取或真实用户资料测试；模型测试使用合成文本/图片。

## 回滚

在 Python 服务器执行：

```sh
/home/vmadmin/fusioncareer/releases/20260916-workflow-crawlers/rollback.sh
```

脚本恢复旧源码和镜像并重建 Agent，保留现有数据卷，不回滚数据库，以免覆盖部署后的业务数据。
