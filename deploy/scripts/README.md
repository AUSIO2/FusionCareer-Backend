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
  Mac: mac-build-and-serve.sh       →  /tmp 镜像包 + 配置包，HTTP :8765
  Java: java-deploy-from-mac.sh     →  curl 下载 → docker compose up

前端 (FusionCareer-View 仓库):
  Mac: deploy/scripts/mac-build-frontend.sh
  Python: deploy/scripts/python-deploy-frontend-from-mac.sh
```

## 1. Mac（项目根目录）

```bash
cp deploy/env.java.example .env.production
# 编辑 .env.production

chmod +x deploy/scripts/*.sh
./deploy/scripts/mac-build-and-serve.sh
```

脚本会打印 `MAC_IP`，并保持 HTTP 运行。

## 2. Java 机（堡垒机 SSH）

**重要：** Java 云主机 `172.22.130.216` 只能访问 Mac 的 **校园网 10.x.x.x**，一般 **不能** 访问家里 WiFi 的 `192.168.50.x`。

在 Mac 上查可用 IP：

```bash
ifconfig | grep "inet " | grep -v 127.0.0.1
# 选 10.x.x.x，不要选 192.168.x
```

```bash
# 推荐：IP 和端口作为参数传入（避免 export 未生效）
curl -f -o /tmp/java-deploy-from-mac.sh http://10.230.32.62:8765/java-deploy-from-mac.sh
bash /tmp/java-deploy-from-mac.sh 10.230.32.62 8765
```

若脚本还在 Mac 的 HTTP 上，可先：

```bash
curl -fO http://10.230.32.62:8765/fusioncareer-java-deploy.tgz
# 解压后 deploy/scripts/ 会随配置包一起在首次 deploy 时解压到 JAVA_BASE
```

更简单：第一次用配置包解压后即包含脚本：

```bash
export MAC_IP=10.230.32.62
cd /data/fusioncareer/FusionCareer-Backend
bash deploy/scripts/java-deploy-from-mac.sh
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

Mac 重新跑 `mac-build-and-serve.sh`，Java 机再跑 `java-deploy-from-mac.sh`（会 `--force-recreate` 逻辑在 compose up -d 中由新镜像触发，可加 `docker compose up -d --force-recreate backend`）。

前端更新见 **FusionCareer-View** 仓库 `deploy/scripts/README.md`。

仅更新后端镜像时 Java 机可：

```bash
cd /data/fusioncareer/FusionCareer-Backend
curl -f# -o /tmp/fusioncareer-backend-prod.tar.gz http://${MAC_IP}:8765/fusioncareer-backend-prod.tar.gz
gunzip -c /tmp/fusioncareer-backend-prod.tar.gz | docker load
docker compose -f deploy/docker-compose.java.image.yml --env-file .env.production up -d --force-recreate backend
```
