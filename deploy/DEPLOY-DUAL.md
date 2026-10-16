# FusionCareer 双机生产部署（APISIX + Python 网关）

## 架构说明

| 角色 | IP | 职责 |
|------|-----|------|
| **Python 机** | `10.107.13.184` | APISIX 的 HTTP 上游、Nginx `:80`、Agent；`/fudan/*` 反代到 Java |
| **Java 机** | 内网 `172.22.130.216` | MySQL、Java 后端（含 SSO 实现）、文件存储 |

> SSO **登录页 URL** 在 Python 机域名上（如 `https://fusioncareer.fudan.edu.cn/fudan/login`），  
> **认证逻辑**仍在 Java（`FudanSsoController`），由 Python 机 Nginx 转发。

```
用户浏览器 → fusioncareer.fudan.edu.cn:443（校内 APISIX 终止 TLS）
              → Python 10.107.13.184:80（Nginx）
                ├─ /fudan/*  → Java 127.0.0.1:19100
                ├─ /api/*    → Java 127.0.0.1:19100
                ├─ /files/*  → Java 127.0.0.1:19100
                └─ /agent/*  → 本机 Agent :8900
```

## 运维入口

- Mac 直接 SSH 到 Python 机 `vmadmin@10.107.13.184`。
- Java 机不从 Mac 直连；先进入 Python 机，再通过两机间已经配置的 SSH 通道进入 Java。
- 发布包从 Mac 上传到 Python 机一次；需要部署到 Java 时通过该通道传递。
- Java → Python 的反向隧道在 Python 上提供两个本地入口：`127.0.0.1:19022` 用于 Java SSH，`127.0.0.1:19100` 用于 Java API。禁止把私钥或口令写入仓库。
- 业务反向代理继续走 Python `127.0.0.1:19100` → Java `127.0.0.1:9100`，详见 [TEMP-SSH-TUNNEL.md](TEMP-SSH-TUNNEL.md)。

```bash
# Mac 进入 Python
ssh vmadmin@10.107.13.184

# 当前在 Python，进入 Java
ssh -p 19022 root@127.0.0.1

# 当前在 Java，进入 Python
ssh -i /root/.ssh/id_ed25519_py vmadmin@10.107.13.184
```

---

## 部署前：内网互通

**Python 机：**

```bash
curl -s --connect-timeout 5 http://172.22.130.216:9100/sys/health
```

**Java 机：**

```bash
curl -s --connect-timeout 5 http://10.107.13.184:80/
```

安全组建议：

| 机器 | 入站 | 来源 |
|------|------|------|
| Python | 80 | 校内 APISIX/运维网络 |
| Python | 22 | Mac 所在运维网络 |
| Java | 9100 | **仅** `10.107.13.184`（Python 机） |
| Java | 3306 | 不开放 |

---

## 一、Java 机（172.22.130.216）

**空机器 + Mac 本地编译镜像**：见 **[DEPLOY-JAVA.md](DEPLOY-JAVA.md)**（推荐）。

简要（在机上编译，适合已有 JDK/Maven 的机器）：

```bash
cd /data/fusioncareer/FusionCareer-Backend
cp deploy/env.java.example .env.production
vim .env.production   # DB_PASSWORD、FUDAN_SSO_CLIENT_SECRET

sudo apt install -y openjdk-17-jdk maven docker.io docker-compose-v2
mvn package -DskipTests -pl fusioncareer-biz -am

docker compose -f deploy/docker-compose.java.yml --env-file .env.production up -d --build
```

验证（Java 机本机）：

```bash
curl -s http://127.0.0.1:9100/sys/health
```

---

## 二、Python 机（10.107.13.184，APISIX 上游）

上传：`fusioncareer-agent/`、`deploy/docker-compose.agent.yml`、`deploy/nginx.python.conf`、`deploy/env.agent.example`

```bash
cp deploy/env.agent.example fusioncareer-agent/.env.agent
vim fusioncareer-agent/.env.agent

docker compose -f deploy/docker-compose.agent.yml --env-file fusioncareer-agent/.env.agent up -d --build
```

验证（Python 机）：

```bash
# 经本机 Nginx（与用户使用方式一致）
curl -s http://127.0.0.1/api/sys/health
curl -s http://127.0.0.1/agent/api/health
curl -I http://127.0.0.1/fudan/login

# 域名（解析生效后）
curl -s http://fusioncareer.fudan.edu.cn/api/sys/health
curl -I http://fusioncareer.fudan.edu.cn/fudan/login
```

---

## 三、域名与 SSO

- 校内 APISIX 为 `fusioncareer.fudan.edu.cn` 终止 TLS，并将流量转发到 `http://10.107.13.184:80`。
- Python 机不保存站点证书，也不直接监听 443；域名解析和 HTTPS 路由由信息化侧管理。
- 复旦登记回调（不变）：
  - `https://fusioncareer.fudan.edu.cn/fudan/callback`
  - `https://fusioncareer.fudan.edu.cn/fudan/slo`
- 向复旦提供 **Java 机访问 `id.fudan.edu.cn` 的出口公网 IP**（换 token 由 Java 发起，不是 Python）
- 用户入口必须是 **HTTPS**（与 `application-prod.yml` 中 `https://` 一致）；Python 上游保持 HTTP。

用户登录流程：

1. 打开 `https://fusioncareer.fudan.edu.cn/fudan/login`（Python Nginx → Java）
2. 跳转复旦认证 → 回调 `.../fudan/callback`（仍经 Python Nginx → Java）
3. 重定向 `https://fusioncareer.fudan.edu.cn/#/login?token=...`（前端从 fragment 读取 token，避免进入访问日志）

---

## 四、配置文件索引

| 文件 | 部署位置 |
|------|----------|
| `deploy/docker-compose.java.yml` | Java 机 |
| `deploy/docker-compose.agent.yml` | Python 机 |
| `deploy/nginx.python.conf` | Python 机 Nginx |
| `.env.production` | Java 机项目根目录 |
| `fusioncareer-agent/.env.agent` | Python 机 |

旧版 `deploy/nginx.java.conf` 已不用于「APISIX → Python 网关」方案。

---

## 五、安全提醒

- 勿将 `.env.production` / `.env.agent` 提交 Git
- Java `9100` 勿对公网开放，仅 Python 机可访问
- `/internal/**` 无鉴权，依赖网络隔离
