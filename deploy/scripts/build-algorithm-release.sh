#!/usr/bin/env bash
# Build a secret-free, versioned Java + Agent deployment bundle.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
commit="$(git -C "$repo_root" rev-parse HEAD)"
short_commit="$(git -C "$repo_root" rev-parse --short=7 HEAD)"
release_tag="${RELEASE_TAG:-algorithm-$(date +%Y%m%d)-$short_commit}"
output_dir="${RELEASE_OUTPUT_DIR:-/tmp/fusioncareer-$release_tag}"
staging_dir="$output_dir/config"
java_image="fusioncareer-backend:$release_tag"
agent_image="fusioncareer-agent:$release_tag"
java_archive="$output_dir/fusioncareer-backend-$release_tag.tar.gz"
agent_archive="$output_dir/fusioncareer-agent-$release_tag.tar.gz"
config_archive="$output_dir/fusioncareer-config-$release_tag.tgz"

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
  cp "$repo_root/deploy/docker-compose.agent.yml" "$staging_dir/deploy/"
  cp "$repo_root/deploy/docker-compose.java.image.yml" "$staging_dir/deploy/"
  cp "$repo_root/deploy/env.agent.example" "$staging_dir/deploy/"
  cp "$repo_root/deploy/env.java.example" "$staging_dir/deploy/"
  cp "$repo_root/deploy/nginx.python.conf" "$staging_dir/deploy/"
  cp "$repo_root/docs/ALGORITHM_DEPLOYMENT_2026-10-02.md" "$staging_dir/docs/"
  cp "$repo_root/fusioncareer-biz/src/main/resources/schema.sql" \
    "$staging_dir/fusioncareer-biz/src/main/resources/"
  cp "$repo_root/fusioncareer-biz/src/main/resources/db/migration/V20260930__ai_message_presentation.sql" \
    "$staging_dir/fusioncareer-biz/src/main/resources/db/migration/"
}

require_command docker git gzip shasum tar
test -x "$repo_root/mvnw"
docker info >/dev/null

rm -rf "$output_dir"
mkdir -p "$output_dir"

echo "==> Building Java artifact"
(cd "$repo_root" && ./mvnw -B -pl fusioncareer-biz -am package -DskipTests -Djacoco.skip=true)

echo "==> Building linux/amd64 images"
docker build --platform linux/amd64 -f "$repo_root/fusioncareer-biz/Dockerfile.prod" \
  -t "$java_image" "$repo_root/fusioncareer-biz"
docker build --platform linux/amd64 -t "$agent_image" "$repo_root/fusioncareer-agent"

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
backend_commit=$commit
algorithm_commit=1f3d8a7a7d244487294a3793658e625d4d9086f1
java_image=$java_image
java_image_id=$java_id
agent_image=$agent_image
agent_image_id=$agent_id
company_score_default=true
recommendation_audit_log_default=true

This archive intentionally contains no .env.production or secrets.
Merge new keys from deploy/env.*.example into the existing server environment.
Check fc_ai_message columns before applying the included ALTER migration once.
EOF
COPYFILE_DISABLE=1 tar czf "$config_archive" -C "$staging_dir" .
rm -rf "$staging_dir"

(cd "$output_dir" && shasum -a 256 \
  "$(basename "$java_archive")" \
  "$(basename "$agent_archive")" \
  "$(basename "$config_archive")" > SHA256SUMS)

echo "==> Release bundle ready: $output_dir"
ls -lh "$output_dir"
cat "$output_dir/SHA256SUMS"
