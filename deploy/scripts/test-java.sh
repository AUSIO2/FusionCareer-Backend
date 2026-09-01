#!/usr/bin/env bash
set -euo pipefail

readRoot="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readCompose="$readRoot/deploy/docker-compose.test.yml"
readOutput="$readRoot/.test-output"
readMode="${1:-junit}"

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

stopServices() {
  docker compose -f "$readCompose" down -v --remove-orphans >/dev/null 2>&1 || true
}

cleanFiles() {
  mkdir -p "$readOutput"
  rm -rf "$readOutput/uploads"
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
    *)
      echo "usage: $0 [junit]" >&2
      return 2
      ;;
  esac
}

trap stopServices EXIT INT TERM
runMode
