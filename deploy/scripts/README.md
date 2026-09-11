# 部署脚本（Mac 构建 + Java 机拉取）

本仓库仅 **后端 + 网关 compose**。前端在独立仓库 **FusionCareer-View** 的 `deploy/scripts/`。

算法集成发布前执行：

```bash
FRONTEND_ROOT=/absolute/path/FusionCareer-View ./deploy/scripts/test-algorithm.sh
```

配置、smoke、会话更新与回滚见 `docs/ALGORITHM_RUNBOOK.md`。

## 流程概览

```text
后端 (本仓库):
  Mac: mac-build-and-serve.sh --no-serve → /tmp 镜像包 + 配置包
  Mac → Python                       → 上传一次
  Python → Java SSH 通道             → 传包 → docker compose up

前端 (FusionCareer-View 仓库):
  Mac 构建 → 直接上传 → Python 静态目录
```

## 1. Mac（项目根目录）

```bash
cp deploy/env.java.example .env.production
# 编辑 .env.production

chmod +x deploy/scripts/*.sh
./deploy/scripts/mac-build-and-serve.sh --no-serve
```

脚本在 `/tmp` 生成发布包，不启动 HTTP。

## 2. 上传与进入服务器

**统一规则：** Mac 直接 SSH 到 Python 机；Java 机不从 Mac 直连，统一从 Python 机通过 SSH 通道进入和传递发布包。完整入口规范见 [DEPLOY-DUAL.md](../DEPLOY-DUAL.md)。

Mac 构建后，将生成的包上传到 Python 机：

```bash
ls -lh /tmp/fusioncareer-backend-prod.tar.gz \
  /tmp/fusioncareer-java-deploy.tgz
scp /tmp/fusioncareer-backend-prod.tar.gz \
  /tmp/fusioncareer-java-deploy.tgz \
  vmadmin@10.107.13.184:/tmp/
ssh vmadmin@10.107.13.184
```

在 Python 机通过 `127.0.0.1:19022` 反向 SSH 通道传到 Java：

```bash
scp -P 19022 \
  /tmp/fusioncareer-backend-prod.tar.gz \
  /tmp/fusioncareer-java-deploy.tgz \
  root@127.0.0.1:/tmp/
```

然后进入 Java 执行部署：

```bash
ssh -p 19022 root@127.0.0.1
mkdir -p /data/fusioncareer/FusionCareer-Backend
cd /data/fusioncareer/FusionCareer-Backend
tar xzf /tmp/fusioncareer-java-deploy.tgz
gunzip -c /tmp/fusioncareer-backend-prod.tar.gz | docker load
docker compose -f deploy/docker-compose.java.image.yml \
  --env-file .env.production up -d --force-recreate backend
```

## 3. 验证

```bash
curl -s http://127.0.0.1:9100/sys/health
curl -sk https://fusioncareer.fudan.edu.cn/api/sys/health
```

## 选项

| 脚本 | 参数 | 说明 |
|------|------|------|
| `mac-build-and-serve.sh` | `--no-serve` | 只构建，不启 HTTP |
| `mac-build-and-serve.sh` | `--serve-only` | `/tmp` 已有包，只启 HTTP |
| 环境变量 | `HTTP_PORT` | 默认 8765 |

## 更新版本

Mac 重新跑 `mac-build-and-serve.sh --no-serve`，把新包上传到 Python 机，再经 SSH 通道传到 Java 并执行上面的 `docker load` 与 `docker compose ... --force-recreate backend`。

前端更新见 **FusionCareer-View** 仓库 `deploy/scripts/README.md`。

仅更新后端镜像时，在 Java 机执行：

```bash
cd /data/fusioncareer/FusionCareer-Backend
gunzip -c /tmp/fusioncareer-backend-prod.tar.gz | docker load
docker compose -f deploy/docker-compose.java.image.yml --env-file .env.production up -d --force-recreate backend
```
