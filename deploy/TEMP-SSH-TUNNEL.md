# 临时方案：无法改安全组 9100 时，打通 Python → Java

适用：Python `10.107.13.184` 访问不了 Java `172.22.130.216:9100`（超时）。

## 优先：80/443 机间已开放（比 SSH 隧道简单）

若网管确认 **80（或 443）可在两台 ECS 之间互通**，在 Java 机起 **80 → 9100** 反代，Python 网关改连 Java:80。

**Python 机先测：**

```bash
curl -sv --connect-timeout 5 http://172.22.130.216:80/ 2>&1 | head -15
```

- 若 **不是 timeout**（哪怕 404/502），说明 80 可达，用下面「方案 C」。
- 若 **timeout**，再用下文 SSH 反向隧道（方案 B）。

### 方案 C：Java 机 80 反代 9100

```bash
cd /data/fusioncareer/FusionCareer-Backend
docker compose -f deploy/docker-compose.java.port80.yml up -d
curl -s http://127.0.0.1:80/sys/health
```

**Python 机** 改 `deploy/nginx.python.gateway.conf`：

```nginx
upstream java_backend {
    server 172.22.130.216:80;
}
```

```bash
docker compose -f deploy/docker-compose.gateway.yml restart nginx
curl -s http://127.0.0.1/api/sys/health
```

---

## 自动隧道（推荐：随 Java compose 启动）

不用手敲 `ssh -N`，在 **Java 机** 增加侧车容器，断线自动重连：

```bash
# 1. 免密（一次性）
ssh-keygen -t ed25519 -N "" -f /root/.ssh/id_ed25519_py
ssh-copy-id -i /root/.ssh/id_ed25519_py.pub vmadmin@10.107.13.184

# 2. 配置
cd /data/fusioncareer/FusionCareer-Backend
cp deploy/env.tunnel.example .env.tunnel

# 3. 与 backend 一起启动
docker compose -f deploy/docker-compose.java.image.yml \
  -f deploy/docker-compose.java.tunnel.yml \
  --env-file .env.production --env-file .env.tunnel up -d

docker logs -f deploy-ssh-tunnel-1
```

Python 机 Nginx 保持 `upstream 127.0.0.1:19100` + `docker-compose.gateway.host.yml`。

文件：`deploy/docker-compose.java.tunnel.yml`、`deploy/ssh-tunnel/entrypoint.sh`。

---

## SSH 隧道（80 也不通时，手工）

适用：Python `10.107.13.184` 访问不了 Java `172.22.130.216:9100`（超时），但 **SSH 22 可能仍互通**。

思路：在 Python 本机开 `127.0.0.1:19100` → 隧道 → Java `127.0.0.1:9100`，Nginx 改 upstream 为 `127.0.0.1:19100`。

---

## 步骤 0：测哪边能 SSH

**在 Python 机：**

```bash
ssh -o ConnectTimeout=5 -o BatchMode=yes root@172.22.130.216 echo OK
```

**在 Java 机：**

```bash
ssh -o ConnectTimeout=5 -o BatchMode=yes vmadmin@10.107.13.184 echo OK
```

- Python → Java 通：用 **方案 A（推荐）**
- 仅 Java → Python 通：用 **方案 B（反向隧道）**

---

## 方案 A：Python 上建本地转发（Python 能 SSH 到 Java）

### 1. Python 机配置免密（一次性）

```bash
ssh-keygen -t ed25519 -N "" -f ~/.ssh/id_ed25519_java -q
ssh-copy-id -i ~/.ssh/id_ed25519_java.pub root@172.22.130.216
```

### 2. 建立隧道（前台测试）

```bash
ssh -i ~/.ssh/id_ed25519_java -N \
  -o ServerAliveInterval=30 -o ServerAliveCountMax=3 \
  -L 127.0.0.1:19100:127.0.0.1:9100 \
  root@172.22.130.216
```

另开终端验证：

```bash
curl -s http://127.0.0.1:19100/sys/health
```

### 3. 开机自启（systemd，Python 机）

```bash
sudo tee /etc/systemd/system/fc-java-tunnel.service <<'EOF'
[Unit]
Description=FusionCareer SSH tunnel to Java :9100
After=network-online.target
Wants=network-online.target

[Service]
User=vmadmin
ExecStart=/usr/bin/ssh -N -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o ExitOnForwardFailure=yes -i /home/vmadmin/.ssh/id_ed25519_java -L 127.0.0.1:19100:127.0.0.1:9100 root@172.22.130.216
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now fc-java-tunnel.service
sudo systemctl status fc-java-tunnel.service
```

### 4. 改 Nginx upstream（Python 机）

编辑 `nginx.python.gateway.conf`（**不要用 127.0.0.1**，Nginx 在容器里访问不到宿主机隧道）：

```nginx
upstream java_backend {
    server host.docker.internal:19100;
}
```

**不要用 `host.docker.internal`**：隧道只在宿主机 `127.0.0.1:19100`，容器桥接网访问 `172.17.0.1` 会 **Connection refused**。

改用 **宿主机网络** 启动 Nginx：

```bash
cp deploy/nginx.python.tunnel.conf deploy/nginx.python.gateway.conf
docker compose -f deploy/docker-compose.gateway.yml \
  -f deploy/docker-compose.gateway.tunnel.yml up -d --force-recreate nginx
```

`nginx.python.tunnel.conf` 中 upstream 为 `127.0.0.1:19100`。

或直接使用仓库内 `deploy/nginx.python.tunnel.conf`。

重启网关：

```bash
cd /home/vmadmin/fusioncareer/FusionCareer-Backend
docker compose -f deploy/docker-compose.gateway.yml restart nginx
```

验证：

```bash
curl -s http://127.0.0.1/api/sys/health
```

---

## 方案 B：Java 上建反向隧道（仅 Java 能 SSH 到 Python）

在 **Java 机**：

```bash
ssh -N -o ServerAliveInterval=30 \
  -R 127.0.0.1:19100:127.0.0.1:9100 \
  vmadmin@10.107.13.184
```

Python 上同样把 Nginx 改为 `127.0.0.1:19100`，并建议用 systemd 在 Java 机持久化该 ssh 命令。

---

## 注意

- 隧道进程挂掉 → 域名 `/api` 又会 502，需监控 `fc-java-tunnel.service`。
- 正式环境仍应申请安全组 **9100 ← 10.107.13.184**，再改回 `172.22.130.216:9100`。
- SSO、文件上传都走该隧道，带宽与延迟取决于 SSH 链路。
