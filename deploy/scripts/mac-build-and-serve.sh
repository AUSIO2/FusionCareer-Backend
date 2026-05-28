#!/usr/bin/env bash
# Mac：编译 JAR → 构建 linux/amd64 镜像 → 打部署包 → 启动 HTTP 供 Java 机 curl 下载
#
# 用法:
#   ./deploy/scripts/mac-build-and-serve.sh              # 构建并启动 HTTP
#   ./deploy/scripts/mac-build-and-serve.sh --no-serve   # 只构建，不启 HTTP
#   ./deploy/scripts/mac-build-and-serve.sh --serve-only # 只启 HTTP（/tmp 里已有包）
#   MAC_IP=10.230.32.62 ./deploy/scripts/mac-build-and-serve.sh --serve-only
#
# 前置: JDK 17+、Docker Desktop 已运行、项目根目录有 .env.production

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
HTTP_PORT="${HTTP_PORT:-8765}"
TMP_DIR="${TMP_DIR:-/tmp}"
IMAGE_TAG="${IMAGE_TAG:-fusioncareer-backend:prod}"
IMAGE_ARCHIVE="${TMP_DIR}/fusioncareer-backend-prod.tar.gz"
CONFIG_ARCHIVE="${TMP_DIR}/fusioncareer-java-deploy.tgz"
STAGING="${TMP_DIR}/fc-java-deploy"
PID_FILE="${TMP_DIR}/fc-http-server.pid"

DO_BUILD=1
DO_SERVE=1

for arg in "$@"; do
  case "$arg" in
    --no-serve) DO_SERVE=0 ;;
    --serve-only) DO_BUILD=0 ;;
    -h|--help)
      sed -n '2,14p' "$0"
      exit 0
      ;;
    *)
      echo "未知参数: $arg（可用 --no-serve | --serve-only）" >&2
      exit 1
      ;;
  esac
done

# 列出本机所有非回环 IPv4
list_mac_ips() {
  ifconfig 2>/dev/null | awk '/inet / && $2 != "127.0.0.1" {print $2}' \
    || ip -4 addr show 2>/dev/null | awk '/inet / {split($2,a,"/"); print a[1]}'
}

# 自动选 IP：已设置 MAC_IP 优先；否则优先 10.x（校园网），其次 192.168.x
detect_mac_ip() {
  if [[ -n "${MAC_IP:-}" ]]; then
    echo "$MAC_IP"
    return 0
  fi
  local ip preferred=""
  while IFS= read -r ip; do
    [[ -z "$ip" ]] && continue
    if [[ "$ip" =~ ^10\. ]]; then
      preferred="$ip"
      break
    fi
    if [[ -z "$preferred" ]]; then
      preferred="$ip"
    fi
  done < <(list_mac_ips)
  echo "$preferred"
}

stop_http_server() {
  if [[ -f "$PID_FILE" ]]; then
    local pid
    pid="$(cat "$PID_FILE")"
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      echo "已停止 HTTP 服务 (pid $pid)"
    fi
    rm -f "$PID_FILE"
  fi
}

if [[ "$DO_BUILD" -eq 1 ]]; then
  cd "$REPO_ROOT"

  if [[ ! -f ".env.production" ]]; then
    echo "缺少 .env.production，请先执行:" >&2
    echo "  cp deploy/env.java.example .env.production" >&2
    exit 1
  fi

  if ! docker info >/dev/null 2>&1; then
    echo "Docker 未运行，请先启动 Docker Desktop。" >&2
    exit 1
  fi

  echo "==> [1/4] Maven 编译..."
  ./mvnw package -DskipTests -pl fusioncareer-biz -am
  ls -lh fusioncareer-biz/target/*.jar

  echo "==> [2/4] Docker 构建 (linux/amd64)..."
  docker build --platform linux/amd64 \
    -f fusioncareer-biz/Dockerfile.prod \
    -t "$IMAGE_TAG" \
    fusioncareer-biz

  arch="$(docker inspect "$IMAGE_TAG" --format '{{.Architecture}}')"
  echo "    镜像架构: $arch"

  echo "==> [3/4] 导出镜像与配置包..."
  docker save "$IMAGE_TAG" | gzip > "$IMAGE_ARCHIVE"

  rm -rf "$STAGING"
  mkdir -p "$STAGING/deploy/scripts" "$STAGING/fusioncareer-biz/src/main/resources"
  cp deploy/docker-compose.java.image.yml "$STAGING/deploy/"
  cp deploy/scripts/java-deploy-from-mac.sh "$STAGING/deploy/scripts/"
  cp fusioncareer-biz/src/main/resources/schema.sql \
    "$STAGING/fusioncareer-biz/src/main/resources/"
  cp .env.production "$STAGING/"

  COPYFILE_DISABLE=1 tar czf "$CONFIG_ARCHIVE" -C "$STAGING" .
  cp deploy/scripts/java-deploy-from-mac.sh "$TMP_DIR/"

  ls -lh "$IMAGE_ARCHIVE" "$CONFIG_ARCHIVE"
  rm -rf "$STAGING"
fi

if [[ "$DO_SERVE" -eq 0 ]]; then
  echo "构建完成（未启动 HTTP）。"
  exit 0
fi

for f in "$IMAGE_ARCHIVE" "$CONFIG_ARCHIVE"; do
  if [[ ! -f "$f" ]]; then
    echo "缺少文件: $f" >&2
    exit 1
  fi
done

echo "本机 IPv4 地址:"
list_mac_ips | sed 's/^/  - /'

MAC_IP="$(detect_mac_ip)"
if [[ -z "$MAC_IP" ]]; then
  echo "无法检测 IP，请指定: MAC_IP=10.x.x.x $0 --serve-only" >&2
  exit 1
fi

if [[ "$MAC_IP" =~ ^192\.168\. ]]; then
  echo ""
  echo "警告: 当前使用 $MAC_IP（常见为家里 WiFi/热点）。"
  echo "  Java 云主机 172.22.130.216 一般无法访问 192.168.x。"
  echo "  请连校园网后用 10.x.x.x，例如: MAC_IP=10.230.32.62 $0 --serve-only"
  echo ""
fi

stop_http_server

echo "==> [4/4] 启动 HTTP :$HTTP_PORT (目录 $TMP_DIR)，绑定 0.0.0.0 ..."
cd "$TMP_DIR"
python3 -m http.server "$HTTP_PORT" --bind 0.0.0.0 >/tmp/fc-http-server.log 2>&1 &
echo $! >"$PID_FILE"

echo ""
echo "=============================================="
echo " HTTP: http://${MAC_IP}:${HTTP_PORT}/"
echo ""
echo " Java 机执行（IP 必须与下面一致，建议用 10.x 校园网）:"
echo ""
echo "   bash /tmp/java-deploy-from-mac.sh ${MAC_IP} ${HTTP_PORT}"
echo ""
echo " 或:"
echo "   export MAC_IP=${MAC_IP} HTTP_PORT=${HTTP_PORT}"
echo "   bash /tmp/java-deploy-from-mac.sh"
echo ""
echo " 首次下载脚本:"
echo "   curl -f -o /tmp/java-deploy-from-mac.sh http://${MAC_IP}:${HTTP_PORT}/java-deploy-from-mac.sh"
echo "=============================================="
