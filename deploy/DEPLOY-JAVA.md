# Java 机从零部署指南（空机器 + 堡垒机 + Mac 本地编译）

适用：**全新 Ubuntu Java 机** `172.22.130.216`，通过 **复旦云堡垒机** 登录；**不在 Java 机上安装 Maven/编译源码**。  
Python 网关已部署见 [DEPLOY-DUAL.md](DEPLOY-DUAL.md)、[docker-compose.gateway.yml](docker-compose.gateway.yml)。

## 自动化脚本（推荐）

| 步骤 | 机器 | 命令 |
|------|------|------|
| 1 | **Mac** | `cp deploy/env.java.example .env.production` 并编辑 |
| 2 | **Mac** | `chmod +x deploy/scripts/*.sh && ./deploy/scripts/mac-build-and-serve.sh` |
| 3 | **Java** | 按脚本输出的 `MAC_IP` 执行下面两行 |

```bash
export MAC_IP=10.x.x.x    # Mac 脚本打印的校园网 IP
curl -f -o /tmp/java-deploy-from-mac.sh http://${MAC_IP}:8765/java-deploy-from-mac.sh
bash /tmp/java-deploy-from-mac.sh ${MAC_IP} 8765
```

详见 [scripts/README.md](scripts/README.md)。

**注意：** Mac 为 Apple Silicon 时必须构建 **linux/amd64** 镜像（脚本已带 `--platform linux/amd64`）。

---

## 0. 架构与安全组

| 机器 | IP | 跑什么 |
|------|-----|--------|
| Python | `10.107.13.184` | Nginx 网关（HTTPS 经校内 APISIX） |
| Java | `172.22.130.216` | MySQL 8 + Spring Boot `:9100` |

**Java 机安全组（必做）**

| 端口 | 入站来源 | 说明 |
|------|----------|------|
| 9100 | **仅** `10.107.13.184` | Python Nginx 反代 |
| 3306 | 不开放 | MySQL 仅容器内网 |
| 22 | 堡垒机/运维网段 | SSH（按学校规范） |

**部署前准备（纸质/密钥管理）**

- [ ] `DB_PASSWORD`（强密码）
- [ ] `FUDAN_SSO_CLIENT_ID` / `FUDAN_SSO_CLIENT_SECRET`
- [ ] 向复旦信息化登记回调：`https://fusioncareer.fudan.edu.cn/fudan/callback`、`/fudan/slo`
- [ ] 向复旦提供 **Java 机访问 id.fudan.edu.cn 的出口公网 IP**（换 token 用）

---

## 1. Mac 本地：编译 JAR + 构建镜像 + 打部署包

### 1.1 环境

- JDK **17+**（项目 `java.version=17`）
- Docker Desktop **已启动**（鲸鱼图标 Running）
- 项目路径：`/Users/xiong/FusionCareer-Backend`

```bash
cd /Users/xiong/FusionCareer-Backend
java -version
docker info
```

### 1.2 编译 JAR（不依赖 Docker）

```bash
./mvnw package -DskipTests -pl fusioncareer-biz -am
ls -lh fusioncareer-biz/target/*.jar
```

### 1.3 配置 `.env.production`（勿提交 Git）

```bash
cp deploy/env.java.example .env.production
```

编辑 `.env.production`：

```properties
FUDAN_SSO_CLIENT_ID=fusioncareer
FUDAN_SSO_CLIENT_SECRET=你的_secret
DB_PASSWORD=你的强密码
# Agent 未部署时可先保留；仅影响 Java 调 AI，不影响 /sys/health
PYTHON_SERVICE_BASE_URL=http://10.107.13.184:8900
```

### 1.4 构建 Docker 镜像（小上下文，只含 JAR）

**推荐**（不扫整个仓库，避免根目录 `.dockerignore` 排除 `target/`）：

```bash
docker build -f fusioncareer-biz/Dockerfile.prod \
  -t fusioncareer-backend:prod \
  fusioncareer-biz
```

**若拉取 `eclipse-temurin` 超时**（`TLS handshake timeout`）：

1. Docker Desktop → Settings → Docker Engine，增加：

```json
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://docker.1ms.run"
  ]
}
```

2. Apply & Restart 后重试；或编辑 `fusioncareer-biz/Dockerfile.prod`，把 `FROM` 改为：

```dockerfile
FROM docker.m.daocloud.io/library/eclipse-temurin:21-jre-alpine
```

### 1.5 导出镜像与配置包

```bash
docker save fusioncareer-backend:prod | gzip > /tmp/fusioncareer-backend-prod.tar.gz

mkdir -p /tmp/fc-java-deploy/fusioncareer-biz/src/main/resources
cp deploy/docker-compose.java.image.yml /tmp/fc-java-deploy/deploy/
cp fusioncareer-biz/src/main/resources/schema.sql \
  /tmp/fc-java-deploy/fusioncareer-biz/src/main/resources/
cp .env.production /tmp/fc-java-deploy/

cd /tmp/fc-java-deploy
tar czf /tmp/fusioncareer-java-deploy.tgz .
cd -

ls -lh /tmp/fusioncareer-backend-prod.tar.gz /tmp/fusioncareer-java-deploy.tgz
```

上传到堡垒机/Java 机的文件共 **2 个**：

- `fusioncareer-backend-prod.tar.gz`（镜像）
- `fusioncareer-java-deploy.tgz`（compose + schema + env）

---

## 2. 堡垒机传文件到 Java 机

1. 打开 https://blj-fcloud.fudan.edu.cn/shterm  
2. 连接资产 **`172.22.130.216`**  
3. 将上述 2 个文件传到 Java 机 `/tmp/`（页面上传、`rz`、或堡垒机中转 `scp`，按学校规范）

---

## 3. Java 空机：系统初始化 + Docker

以下在 **Java 机终端**执行（堡垒机 SSH 进去后）。系统以 **Ubuntu 22.04/24.04** 为例。

### 3.1 基础工具

```bash
sudo apt update
sudo apt install -y ca-certificates curl gnupg lsb-release
```

### 3.2 安装 Docker（官方源）

```bash
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

sudo systemctl enable docker
sudo systemctl start docker
```

**国内拉镜像慢**：配置镜像加速（与 Mac 相同）

```bash
sudo tee /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://docker.1ms.run"
  ]
}
EOF
sudo systemctl restart docker
```

当前用户免 `sudo` docker（可选，执行后需重新登录 SSH）：

```bash
sudo usermod -aG docker "$USER"
```

### 3.3 部署目录

```bash
# 有 /data 权限时：
sudo mkdir -p /data/fusioncareer/FusionCareer-Backend
sudo chown -R "$USER:$USER" /data/fusioncareer
BASE=/data/fusioncareer/FusionCareer-Backend

# 无 /data 权限时改用：
# BASE=$HOME/fusioncareer/FusionCareer-Backend
# mkdir -p "$BASE"

mkdir -p "$BASE"
cd "$BASE"
tar xzf /tmp/fusioncareer-java-deploy.tgz
ls -la deploy/ fusioncareer-biz/src/main/resources/schema.sql .env.production
```

### 3.4 导入业务镜像 + 拉取 MySQL

```bash
gunzip -c /tmp/fusioncareer-backend-prod.tar.gz | docker load
docker images | grep fusioncareer-backend

docker pull mysql:8.0
```

### 3.5 启动栈

```bash
cd "$BASE"
docker compose -f deploy/docker-compose.java.image.yml --env-file .env.production up -d

docker compose -f deploy/docker-compose.java.image.yml ps
docker compose -f deploy/docker-compose.java.image.yml logs -f --tail=100 backend
```

首次启动 MySQL 会执行 `schema.sql` 初始化库表。  
**警告**：`docker compose down -v` 会删除数据库卷，生产慎用。

---

## 4. 验证

### 4.1 Java 机本机

```bash
curl -s http://127.0.0.1:9100/sys/health
```

期望 JSON，`"status":"UP"`。

### 4.2 Python 机（`10.107.13.184`）

```bash
curl -s --connect-timeout 5 http://172.22.130.216:9100/sys/health
```

失败 → 检查 Java 安全组 **9100 是否只对 Python 机放行**。

### 4.3 校园网 / 域名（HTTPS）

```bash
curl -sk -o /dev/null -w "%{http_code}\n" https://fusioncareer.fudan.edu.cn/api/sys/health
curl -sk -o /dev/null -w "%{http_code}\n" -I https://fusioncareer.fudan.edu.cn/fudan/login
```

网关正常前是 **502**；Java 起来后应为 **200** 或 SSO **302**。

---

## 5. 与 Python 网关的关系

- 用户访问：`https://fusioncareer.fudan.edu.cn` → 校内 APISIX → Python Nginx → Java `9100`
- [application-prod.yml](../fusioncareer-biz/src/main/resources/application-prod.yml) 中 SSO、文件 URL 已写 `https://fusioncareer.fudan.edu.cn`
- Python 网关部署：[docker-compose.gateway.yml](docker-compose.gateway.yml)

---

## 6. 常见问题

| 现象 | 处理 |
|------|------|
| Mac `Cannot connect to Docker daemon` | 打开 Docker Desktop |
| Mac/build `TLS handshake timeout` | 配置 registry-mirrors 或改 Dockerfile.prod 的 FROM 镜像 |
| `COPY failed` / `no source files` | 用 `fusioncareer-biz` 作 build 上下文，勿用根目录 `docker build .` |
| backend 反复重启 | `docker compose logs backend`；查 `.env`、DB 密码、MySQL 是否 healthy |
| Python 仍 502 | Java 9100 未监听或安全组未放行 Python IP |
| SSO 失败 | 回调域名、HTTPS、client-secret、Java 出口 IP |
| `PYTHON_SERVICE_BASE_URL` 连不上 | Agent 未部署时可忽略；上线 Agent 后再开 8900 |

---

## 7. 版本更新（仍本地编译）

**Mac：**

```bash
cd /Users/xiong/FusionCareer-Backend
./mvnw package -DskipTests -pl fusioncareer-biz -am
docker build -f fusioncareer-biz/Dockerfile.prod -t fusioncareer-backend:prod fusioncareer-biz
docker save fusioncareer-backend:prod | gzip > /tmp/fusioncareer-backend-prod.tar.gz
# 上传 tar.gz 到 Java /tmp
```

**Java 机：**

```bash
cd "$BASE"
gunzip -c /tmp/fusioncareer-backend-prod.tar.gz | docker load
docker compose -f deploy/docker-compose.java.image.yml --env-file .env.production up -d --force-recreate backend
```

`.env.production` 或 `schema.sql` 变更时，重新打 `fusioncareer-java-deploy.tgz` 并解压覆盖。

---

## 8. 文件索引

| 文件 | 用途 |
|------|------|
| [fusioncareer-biz/Dockerfile.prod](../fusioncareer-biz/Dockerfile.prod) | Mac 本地 build 业务镜像 |
| [deploy/docker-compose.java.image.yml](docker-compose.java.image.yml) | Java 机 compose（用已导入镜像） |
| [deploy/docker-compose.java.yml](docker-compose.java.yml) | 在机上 `build` 用（空机不推荐） |
| [deploy/env.java.example](env.java.example) | 复制为 `.env.production` |
| [DEPLOY-DUAL.md](DEPLOY-DUAL.md) | 双机总览 |
