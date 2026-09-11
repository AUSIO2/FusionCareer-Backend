#!/usr/bin/env bash
set -euo pipefail

readRoot="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readAgent="$readRoot/fusioncareer-agent"
readPython="$readAgent/.venv/bin/python"
readFrontend="${FRONTEND_ROOT:-$readRoot/../FusionCareer-View}"
readOutput="$readRoot/.test-output/algorithm"
readFakePid="$readOutput/fake.pid"
readAgentPid="$readOutput/agent.pid"
readImage="fusioncareer-agent:algorithm-smoke"
readContainer="fusioncareer-agent-smoke-check"
readVolume="fusioncareer-agent-smoke-runtime"

if ! test -f "$readFrontend/package.json" && test -f "$readFrontend/ui_kits/student/package.json"; then
  readFrontend="$readFrontend/ui_kits/student"
fi
if ! test -f "$readFrontend/package.json" && test -f "$readFrontend/Desktop/FusionCareer Design System_V4/package.json"; then
  readFrontend="$readFrontend/Desktop/FusionCareer Design System_V4"
fi

stopServices() {
  for readPid in "$readAgentPid" "$readFakePid"; do
    if test -f "$readPid"; then
      kill "$(cat "$readPid")" 2>/dev/null || true
      rm -f "$readPid"
    fi
  done
  docker rm -f "$readContainer" >/dev/null 2>&1 || true
  docker volume rm -f "$readVolume" >/dev/null 2>&1 || true
}

checkTools() {
  test -x "$readPython"
  test -f "$readFrontend/package.json"
  command -v curl >/dev/null
  command -v docker >/dev/null
  docker info >/dev/null
}

waitService() {
  local readUrl="$1"
  local readLog="$2"
  local readAttempt
  for readAttempt in $(seq 1 60); do
    curl -fsS "$readUrl" >/dev/null 2>&1 && return 0
    sleep 1
  done
  tail -n 100 "$readLog" >&2 || true
  return 1
}

waitContainer() {
  local readAttempt
  for readAttempt in $(seq 1 60); do
    docker exec "$readContainer" python -c \
      "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8900/api/health', timeout=3)" \
      >/dev/null 2>&1 && return 0
    sleep 1
  done
  docker logs "$readContainer" >&2 || true
  return 1
}

startServices() {
  rm -rf "$readOutput"
  mkdir -p "$readOutput/runtime"
  (
    cd "$readAgent"
    "$readPython" -m uvicorn tests.integration.fake_services:createApp --host 127.0.0.1 --port 18888
  ) >"$readOutput/fake.log" 2>&1 &
  echo "$!" >"$readFakePid"
  waitService http://127.0.0.1:18888/openapi.json "$readOutput/fake.log"

  (
    cd "$readAgent"
    BACKEND_BASE_URL=http://127.0.0.1:18888 \
    LLM_BASE_URL=http://127.0.0.1:18888/v1 \
    LLM_API_KEY=smoke-key \
    LLM_MODEL=fake-model \
    INTERNAL_SERVICE_TOKEN=smoke-internal \
    AGENT_ADMIN_TOKEN=smoke-admin \
    AGENT_RUNTIME_DIR="$readOutput/runtime" \
      "$readPython" -m uvicorn app.main:app --host 127.0.0.1 --port 18901
  ) >"$readOutput/agent.log" 2>&1 &
  echo "$!" >"$readAgentPid"
  waitService http://127.0.0.1:18901/api/health "$readOutput/agent.log"
}

runSmoke() {
  local readCode
  readCode="$(curl -sS -o /dev/null -w '%{http_code}' \
    -H 'Content-Type: application/json' \
    -d '{"text":"private-smoke-marker 招聘后端开发工程师"}' \
    http://127.0.0.1:18901/api/internal/job/structure)"
  test "$readCode" = 403

  curl -fsS -H 'Content-Type: application/json' -H 'X-Internal-Token: smoke-internal' \
    -d '{"text":"private-smoke-marker 招聘后端开发工程师","sourceUrl":"https://example.test/job"}' \
    http://127.0.0.1:18901/api/internal/job/structure >"$readOutput/job.json"
  curl -fsS -H 'Content-Type: application/json' -H 'X-Internal-Token: smoke-internal' \
    -d '{"userId":"42","fileId":"7"}' \
    http://127.0.0.1:18901/api/internal/resume/parse >"$readOutput/resume.json"

  "$readPython" - "$readOutput/job.json" "$readOutput/resume.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as readFile:
    readJob = json.load(readFile)
with open(sys.argv[2], encoding="utf-8") as readFile:
    readResume = json.load(readFile)
assert readJob["jobs"][0]["status"] == "OFFLINE"
assert readJob["jobs"][0]["positionName"] == "后端开发工程师"
assert readResume["profilePatch"]["realName"] == "张同学"
assert readResume["resumePatch"]["skills"] == "Python"
PY
  ! grep -Fq 'private-smoke-marker' "$readOutput/agent.log"
  ! grep -Fq 'private-resume-marker' "$readOutput/agent.log"
  ! find "${TMPDIR:-/tmp}" -maxdepth 1 -name 'fusioncareer-resume-*' -print -quit | grep -q .
}

runAudit() {
  ! git -C "$readRoot" ls-files | grep -Eq '(^|/)(\.env|config\.json|cookies?\.txt)$'
  ! git -C "$readRoot" grep -IqE 'sk-[0-9a-fA-F]{32,}' -- .
  ! git -C "$readRoot" grep -IqE 'WECHAT_COOKIE=.+(pass_ticket|wxuin|wxsid)' \
    -- . ':!deploy/scripts/test-algorithm.sh'
}

runDeploy() {
  BACKEND_BASE_URL=http://java:9100 \
  LLM_BASE_URL=http://llm/v1 \
  LLM_API_KEY=smoke \
  INTERNAL_SERVICE_TOKEN=smoke \
  AGENT_ADMIN_TOKEN=smoke \
  WECHAT_TOKEN= \
  WECHAT_COOKIE= \
    docker compose -f "$readRoot/deploy/docker-compose.agent.yml" config --quiet
  docker run --rm --add-host agent:127.0.0.1 \
    -v "$readRoot/deploy/nginx.python.conf:/etc/nginx/nginx.conf:ro" nginx:alpine nginx -t
  docker build --platform linux/amd64 -t "$readImage" "$readAgent"
  docker run --rm --platform linux/amd64 --entrypoint python "$readImage" \
    -c "import os,paddle,paddleocr; assert os.getuid() == 10001"
  docker volume create "$readVolume" >/dev/null
  docker run --rm --platform linux/amd64 -v "$readVolume:/data/agent" --entrypoint python "$readImage" \
    -c "from pathlib import Path; Path('/data/agent/runtime/smoke').write_text('ok')"
  docker run --rm --platform linux/amd64 -v "$readVolume:/data/agent" --entrypoint python "$readImage" \
    -c "from pathlib import Path; assert Path('/data/agent/runtime/smoke').read_text() == 'ok'"
  docker run -d --platform linux/amd64 --name "$readContainer" -v "$readVolume:/data/agent" \
    -e BACKEND_BASE_URL=http://host.docker.internal:9100 \
    -e LLM_API_KEY=smoke -e INTERNAL_SERVICE_TOKEN=smoke -e AGENT_ADMIN_TOKEN=smoke \
    "$readImage" >/dev/null
  waitContainer
  docker rm -f "$readContainer" >/dev/null
  docker volume rm "$readVolume" >/dev/null
}

runChecks() {
  checkTools
  startServices
  runSmoke
  stopServices
  runAudit
  (cd "$readAgent" && "$readPython" -m pytest -q)
  "$readRoot/deploy/scripts/test-java.sh" all
  (cd "$readFrontend" && npm run build)
  runDeploy
}

trap stopServices EXIT INT TERM
runChecks
