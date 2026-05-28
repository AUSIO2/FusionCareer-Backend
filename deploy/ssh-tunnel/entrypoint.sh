#!/bin/sh
# Java 机 → Python 机 SSH 反向隧道（随 compose 启动，自动重连）
# 将 Python 127.0.0.1:REMOTE_PORT 转发到本机 LOCAL_BACKEND（默认 127.0.0.1:9100）

set -eu

PYTHON_HOST="${PYTHON_HOST:?set PYTHON_HOST}"
PYTHON_USER="${PYTHON_USER:-vmadmin}"
PYTHON_SSH_PORT="${PYTHON_SSH_PORT:-22}"
LOCAL_BACKEND="${LOCAL_BACKEND:-127.0.0.1:9100}"
REMOTE_BIND="${REMOTE_BIND:-127.0.0.1:19100}"
SSH_KEY="${SSH_KEY:-/root/.ssh/id_tunnel}"

apk add --no-cache openssh-client >/dev/null

if [ ! -f "$SSH_KEY" ]; then
  echo "缺少 SSH 私钥: $SSH_KEY（见 deploy/env.tunnel.example）" >&2
  exit 1
fi
chmod 600 "$SSH_KEY" 2>/dev/null || true

KNOWN_HOSTS="/root/.ssh/known_hosts"
mkdir -p /root/.ssh
if [ ! -f "$KNOWN_HOSTS" ] || ! grep -q "$PYTHON_HOST" "$KNOWN_HOSTS" 2>/dev/null; then
  ssh-keyscan -H -p "$PYTHON_SSH_PORT" "$PYTHON_HOST" >>"$KNOWN_HOSTS" 2>/dev/null || true
fi

echo "隧道: ${PYTHON_USER}@${PYTHON_HOST} -R ${REMOTE_BIND} -> ${LOCAL_BACKEND}"

while true; do
  ssh -N \
    -p "$PYTHON_SSH_PORT" \
    -i "$SSH_KEY" \
    -o ServerAliveInterval=30 \
    -o ServerAliveCountMax=3 \
    -o ExitOnForwardFailure=yes \
    -o StrictHostKeyChecking=yes \
    -o UserKnownHostsFile="$KNOWN_HOSTS" \
    -R "${REMOTE_BIND}:${LOCAL_BACKEND}" \
    "${PYTHON_USER}@${PYTHON_HOST}" && echo "SSH 会话结束，5s 后重连..." || echo "SSH 失败，5s 后重连..."
  sleep 5
done
