#!/usr/bin/env bash
set -euo pipefail

readRoot="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readCompose="$readRoot/deploy/docker-compose.test.yml"
readSeed="$readRoot/deploy/dev-data.sql"

docker compose -f "$readCompose" up -d --wait mysql-test
docker compose -f "$readCompose" exec -T mysql-test \
  mysql -uroot -pfusioncareer_test fusioncareer_test < "$readSeed"
