#!/usr/bin/env bash
# Java 机：从 Mac HTTP 下载部署包 → docker load → compose 启动
#
# 用法:
#   bash java-deploy-from-mac.sh <Mac_IP> [HTTP_PORT]
#   bash java-deploy-from-mac.sh 10.230.32.62 8765
#
#   export MAC_IP=10.230.32.62 HTTP_PORT=8765
#   bash java-deploy-from-mac.sh

set -euo pipefail

#  positional 优先于环境变量
MAC_IP="${1:-${MAC_IP:-}}"
HTTP_PORT="${2:-${HTTP_PORT:-8765}}"
TMP_DIR="${TMP_DIR:-/tmp}"
JAVA_BASE="${JAVA_BASE:-/data/fusioncareer/FusionCareer-Backend}"
IMAGE_ARCHIVE="${TMP_DIR}/fusioncareer-backend-prod.tar.gz"
CONFIG_ARCHIVE="${TMP_DIR}/fusioncareer-java-deploy.tgz"
IMAGE_TAG="${IMAGE_TAG:-fusioncareer-backend:prod}"
COMPOSE_FILE="${COMPOSE_FILE:-deploy/docker-compose.java.image.yml}"

if [[ -z "$MAC_IP" ]]; then
  echo "用法: $0 <Mac_IP> [HTTP_PORT]" >&2
  echo "示例: $0 10.230.32.62 8765" >&2
  echo "" >&2
  echo "注意: 192.168.x 是 Mac 局域网地址，Java 云主机通常无法访问。" >&2
  echo "      请用 Mac 在校园网下的 10.x.x.x（与之前 curl 通的那个）。" >&2
  exit 1
fi

BASE_URL="http://${MAC_IP}:${HTTP_PORT}"

require_cmd() {
  for c in "$@"; do
    command -v "$c" >/dev/null 2>&1 || {
      echo "缺少命令: $c" >&2
      exit 1
    }
  done
}

require_cmd curl gunzip docker

echo "=============================================="
echo " Mac 地址 : ${MAC_IP}"
echo " HTTP 端口: ${HTTP_PORT}"
echo " 基础 URL : ${BASE_URL}"
echo "=============================================="

if [[ "$MAC_IP" =~ ^192\.168\. ]]; then
  echo "警告: ${MAC_IP} 多为家里/热点网段，云上的 Java 机很可能连不上。" >&2
  echo "      若下一步失败，请在 Mac 上执行: ipconfig getifaddr en0" >&2
  echo "      使用 10.x.x.x 校园网 IP 重试。" >&2
fi

echo ""
echo "==> [1/7] 测试 Mac HTTP..."
http_code="$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout 8 "${BASE_URL}/" || echo "000")"
if [[ "$http_code" != "200" ]]; then
  echo "无法访问 ${BASE_URL}/ (HTTP ${http_code})" >&2
  echo "" >&2
  echo "排查:" >&2
  echo "  1) Mac 是否运行: ./deploy/scripts/mac-build-and-serve.sh --serve-only" >&2
  echo "  2) Mac 本机: curl -I http://127.0.0.1:${HTTP_PORT}/" >&2
  echo "  3) Java 机: curl -I --connect-timeout 5 ${BASE_URL}/" >&2
  echo "  4) 换 Mac 的 10.x 校园网 IP，不要用 192.168.x" >&2
  exit 1
fi
echo "    OK (HTTP ${http_code})"

echo "==> [2/7] 下载部署包..."
curl -f# --connect-timeout 60 -o "$IMAGE_ARCHIVE" "${BASE_URL}/fusioncareer-backend-prod.tar.gz"
curl -f# --connect-timeout 30 -o "$CONFIG_ARCHIVE" "${BASE_URL}/fusioncareer-java-deploy.tgz"
ls -lh "$IMAGE_ARCHIVE" "$CONFIG_ARCHIVE"

echo "==> [3/7] 检查 Docker..."
if ! systemctl is-active docker >/dev/null 2>&1; then
  systemctl reset-failed docker docker.socket 2>/dev/null || true
  systemctl start docker
fi
docker info >/dev/null

echo "==> [4/7] 导入镜像..."
gunzip -c "$IMAGE_ARCHIVE" | docker load
arch="$(docker inspect "$IMAGE_TAG" --format '{{.Architecture}}' 2>/dev/null || echo unknown)"
echo "    镜像架构: $arch"
if [[ "$arch" != "amd64" ]]; then
  echo "错误: 需要 amd64 镜像，请 Mac 上 docker build --platform linux/amd64 重建。" >&2
  exit 1
fi

echo "==> [5/7] 解压配置..."
mkdir -p "$JAVA_BASE"
cd "$JAVA_BASE"
tar xzf "$CONFIG_ARCHIVE"

echo "==> [6/7] 启动 compose..."
docker pull mysql:8.0
docker compose -f "$COMPOSE_FILE" --env-file .env.production up -d

echo "==> [7/7] 健康检查..."
for _ in $(seq 1 30); do
  if curl -sf --connect-timeout 2 http://127.0.0.1:9100/sys/health; then
    echo ""
    echo "部署成功。"
    docker compose -f "$COMPOSE_FILE" ps
    exit 0
  fi
  sleep 2
done

echo "健康检查超时:" >&2
docker compose -f "$COMPOSE_FILE" logs --tail=80 backend
exit 1
