#!/usr/bin/env bash
# Build Java + Agent from a frozen, secret-excluding working-tree snapshot.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
commit="$(git -C "$repo_root" rev-parse HEAD)"
short_commit="$(git -C "$repo_root" rev-parse --short=7 HEAD)"
release_tag="${RELEASE_TAG:-algorithm-$(date +%Y%m%d)-$short_commit}"
case "$release_tag" in
  ''|[-.]*|*[!A-Za-z0-9_.-]*) echo "Invalid RELEASE_TAG: start with a letter, digit or underscore; use letters, digits, dot, underscore or hyphen" >&2; exit 1 ;;
esac
test "${#release_tag}" -le 128 || { echo "RELEASE_TAG is too long" >&2; exit 1; }

require_command() {
  for command_name in "$@"; do
    command -v "$command_name" >/dev/null 2>&1 || {
      echo "Missing required command: $command_name" >&2
      exit 1
    }
  done
}

copy_config() {
  mkdir -p \
    "$staging_dir/deploy" \
    "$staging_dir/docs" \
    "$staging_dir/fusioncareer-biz/src/main/resources/db/migration" \
    "$staging_dir/fusioncareer-biz/src/main/resources"
  cp "$source_dir/deploy/docker-compose.agent.yml" "$staging_dir/deploy/"
  cp "$source_dir/deploy/docker-compose.python.host.yml" "$staging_dir/deploy/"
  cp "$source_dir/deploy/docker-compose.java.image.yml" "$staging_dir/deploy/"
  cp "$source_dir/deploy/env.agent.example" "$staging_dir/deploy/"
  cp "$source_dir/deploy/env.java.example" "$staging_dir/deploy/"
  cp "$source_dir/deploy/nginx.python.host.conf" "$staging_dir/deploy/"
  if [ -f "$source_dir/docs/DEPLOYMENT_RELEASE_2026-10-13.md" ]; then
    cp "$source_dir/docs/DEPLOYMENT_RELEASE_2026-10-13.md" "$staging_dir/docs/"
  fi
  cp "$source_dir/docs/DEPLOYMENT_LONG_ARTICLE_2026-10-10.md" "$staging_dir/docs/"
  cp "$source_dir/fusioncareer-biz/src/main/resources/schema.sql" \
    "$staging_dir/fusioncareer-biz/src/main/resources/"
  cp "$source_dir/fusioncareer-biz/src/main/resources/db/migration/V20260930__ai_message_presentation.sql" \
    "$staging_dir/fusioncareer-biz/src/main/resources/db/migration/"
  usage_guard_migration="$source_dir/fusioncareer-biz/src/main/resources/db/migration/V20261013__ai_usage_guard.sql"
  if [ -f "$usage_guard_migration" ]; then
    cp "$usage_guard_migration" "$staging_dir/fusioncareer-biz/src/main/resources/db/migration/"
  fi
}

require_command docker git gzip shasum tar python3 mktemp
test -x "$repo_root/mvnw"
if [ -n "${RELEASE_OUTPUT_DIR:-}" ]; then
  output_parent="$(cd "$(dirname "$RELEASE_OUTPUT_DIR")" && pwd -P)"
  output_dir="${output_parent%/}/$(basename "$RELEASE_OUTPUT_DIR")"
  case "$output_dir/" in
    "$repo_root/"*) echo "Release output must be outside the source repository" >&2; exit 1 ;;
  esac
  if [ -e "$output_dir" ] || [ -L "$output_dir" ]; then
    echo "Release output already exists; choose a new RELEASE_OUTPUT_DIR: $output_dir" >&2
    exit 1
  fi
  mkdir -m 0700 "$output_dir"
else
  output_dir="$(mktemp -d "/tmp/fusioncareer-$release_tag.XXXXXX")"
fi
source_dir="$output_dir/source"
staging_dir="$output_dir/config"
java_image="fusioncareer-backend:$release_tag"
agent_image="fusioncareer-agent:$release_tag"
java_archive="$output_dir/fusioncareer-backend-$release_tag.tar.gz"
agent_archive="$output_dir/fusioncareer-agent-$release_tag.tar.gz"
config_archive="$output_dir/fusioncareer-config-$release_tag.tgz"
source_archive="$output_dir/source-snapshot.tgz"

echo "==> Freezing tracked and untracked source, including local changes"
python3 "$script_dir/snapshot-release-source.py" "$repo_root" "$output_dir"
commit="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["backendBaseCommit"])' "$output_dir/SOURCE-MANIFEST.json")"
algorithm_commit="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["algorithmCommit"])' "$output_dir/SOURCE-MANIFEST.json")"
source_hash="$(shasum -a 256 "$source_archive" | cut -d ' ' -f1)"
source_content_hash="$(shasum -a 256 "$output_dir/SOURCE-SHA256SUMS" | cut -d ' ' -f1)"
source_dirty="$(python3 -c 'import json,sys; print(str(json.load(open(sys.argv[1]))["sourceDirty"]).lower())' "$output_dir/SOURCE-MANIFEST.json")"
docker info >/dev/null

echo "==> Building Java artifact"
(cd "$source_dir" && ./mvnw -B -pl fusioncareer-biz -am package -DskipTests -Djacoco.skip=true)

echo "==> Building linux/amd64 images"
docker build --platform linux/amd64 -f "$source_dir/fusioncareer-biz/Dockerfile.prod" \
  -t "$java_image" "$source_dir/fusioncareer-biz"
docker build --platform linux/amd64 -t "$agent_image" "$source_dir/fusioncareer-agent"

for image in "$java_image" "$agent_image"; do
  architecture="$(docker image inspect "$image" --format '{{.Architecture}}')"
  test "$architecture" = "amd64" || {
    echo "Unexpected image architecture for $image: $architecture" >&2
    exit 1
  }
done

echo "==> Exporting images"
docker save "$java_image" | gzip -n > "$java_archive"
docker save "$agent_image" | gzip -n > "$agent_archive"
gzip -t "$java_archive"
gzip -t "$agent_archive"

echo "==> Packaging secret-free deployment configuration"
copy_config
java_id="$(docker image inspect "$java_image" --format '{{.Id}}')"
agent_id="$(docker image inspect "$agent_image" --format '{{.Id}}')"
cat > "$staging_dir/RELEASE-MANIFEST.txt" <<EOF
release_tag=$release_tag
backend_base_commit=$commit
source_dirty=$source_dirty
source_snapshot=source-snapshot.tgz
source_snapshot_sha256=$source_hash
source_content_manifest_sha256=$source_content_hash
source_manifest=SOURCE-MANIFEST.json
source_capture_consistency=verified
algorithm_commit=$algorithm_commit
java_image=$java_image
java_image_id=$java_id
agent_image=$agent_image
agent_image_id=$agent_id
company_score_default=true
recommendation_audit_log_default=true

Images are built from the recorded working-tree snapshot, not HEAD alone.
Local env files, keys, runtime data, outputs and ignored artifacts are excluded.
Merge new keys from deploy/env.*.example into the existing server environment.
Production must retain its existing host Compose file/override chain, environment,
network, tunnels, data volumes and bound Nginx file. Included configurations are
references, not replacements. Recreate only the intended application services.
Do not replace or rename the live bound Nginx file, or recreate Nginx/MySQL/tunnels.
Check fc_ai_message columns before applying the included ALTER migration once.
If V20261013__ai_usage_guard.sql is included, it is an optional additive migration,
not executed by this script. Include it in production only for the approved guard
release after checking existing columns and retaining a database backup.
Rollback must keep its added columns and usage/ban data; verify the old application
image still reads/writes its original fields while ignoring extra nullable/default
columns. Do not DROP these columns or clear their values as an application rollback.
EOF
COPYFILE_DISABLE=1 tar czf "$config_archive" -C "$staging_dir" .

(cd "$output_dir" && shasum -a 256 \
  "$(basename "$source_archive")" \
  SOURCE-SHA256SUMS \
  SOURCE-MANIFEST.json \
  "$(basename "$java_archive")" \
  "$(basename "$agent_archive")" \
  "$(basename "$config_archive")" > SHA256SUMS)

echo "==> Release bundle ready: $output_dir"
ls -lh "$output_dir"
cat "$output_dir/SHA256SUMS"
