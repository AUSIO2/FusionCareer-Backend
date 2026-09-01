#!/usr/bin/env bash
set -euo pipefail

readRoot="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readCompose="$readRoot/deploy/docker-compose.test.yml"
readOutput="$readRoot/.test-output"
readPid="$readOutput/backend.pid"
readMode="${1:-all}"

checkTools() {
  command -v docker >/dev/null
  command -v curl >/dev/null
  test -x "$readRoot/mvnw"
  docker info >/dev/null
  docker compose version >/dev/null
  java -version >/dev/null 2>&1
}

startDatabase() {
  docker compose -f "$readCompose" up -d --wait mysql-test
}

waitDatabase() {
  docker compose -f "$readCompose" exec -T mysql-test \
    mysqladmin ping -h localhost -pfusioncareer_test --silent
}

runJUnit() {
  (cd "$readRoot" && ./mvnw -B -pl fusioncareer-biz -am test)
}

buildBackend() {
  (cd "$readRoot" && ./mvnw -B -pl fusioncareer-biz -am package -DskipTests -Djacoco.skip=true)
}

startBackend() {
  local readJar
  readJar="$(find "$readRoot/fusioncareer-biz/target" -maxdepth 1 -type f -name '*.jar' ! -name '*.original' | head -n 1)"
  test -n "$readJar"
  mkdir -p "$readOutput"
  SPRING_PROFILES_ACTIVE=test \
  SERVER_PORT=19100 \
  SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:13306/fusioncareer_test?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&allowMultiQueries=true' \
  SPRING_DATASOURCE_USERNAME=root \
  SPRING_DATASOURCE_PASSWORD=fusioncareer_test \
  UPLOAD_BASE_DIR="$readOutput/uploads" \
  UPLOAD_URL_PREFIX=http://127.0.0.1:19100/files \
  FUDAN_SSO_CLIENT_ID=test-client \
  FUDAN_SSO_CLIENT_SECRET=test-secret \
  FUDAN_SSO_REDIRECT_URI=http://127.0.0.1:19100/fudan/callback \
  FUDAN_SSO_FRONTEND_REDIRECT_URL=http://127.0.0.1:5173/ \
  PYTHON_SERVICE_BASE_URL=http://127.0.0.1:9 \
  SA_TOKEN_IS_LOG=false \
    java -jar "$readJar" >"$readOutput/backend.log" 2>&1 &
  echo "$!" >"$readPid"
}

waitBackend() {
  local readAttempt
  for readAttempt in $(seq 1 60); do
    if curl -fsS http://127.0.0.1:19100/sys/health >/dev/null 2>&1 && \
       curl -fsS http://127.0.0.1:19100/v3/api-docs >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  tail -n 120 "$readOutput/backend.log" >&2 || true
  return 1
}

runSchemathesis() {
  mkdir -p "$readRoot/schemathesis-report"
  docker run --rm \
    -e SCHEMATHESIS_TELEMETRY=false \
    -v "$readRoot/schemathesis-report:/app/schemathesis-report" \
    ghcr.io/schemathesis/schemathesis:stable \
    run -w 1 \
    --wait-for-schema 30 \
    --include-method GET \
    --exclude-path-regex '^/(fudan|internal/resume-file/.*/download|user/resume/file/.*/download).*' \
    --checks not_a_server_error \
    -n 10 \
    --seed 20260901 \
    --report junit \
    --report-dir /app/schemathesis-report \
    --coverage-format html \
    --coverage-report-html-path /app/schemathesis-report/schema-coverage.html \
    http://host.docker.internal:19100/v3/api-docs
}

stopServices() {
  if test -f "$readPid"; then
    kill "$(cat "$readPid")" 2>/dev/null || true
    rm -f "$readPid"
  fi
  docker compose -f "$readCompose" down -v --remove-orphans >/dev/null 2>&1 || true
}

cleanFiles() {
  mkdir -p "$readOutput"
  rm -rf "$readOutput/uploads"
  rm -f "$readOutput/backend.log" "$readPid"
}

runMode() {
  checkTools
  cleanFiles
  startDatabase
  waitDatabase
  case "$readMode" in
    junit)
      runJUnit
      ;;
    api)
      buildBackend
      startBackend
      waitBackend
      runSchemathesis
      ;;
    all)
      runJUnit
      buildBackend
      startBackend
      waitBackend
      runSchemathesis
      ;;
    *)
      echo "usage: $0 [junit|api|all]" >&2
      return 2
      ;;
  esac
}

trap stopServices EXIT INT TERM
runMode
