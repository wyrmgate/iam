#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <dev|demo> <release-directory> <backup-directory> [retention-days]" >&2
}

if [[ $# -lt 3 || $# -gt 4 ]]; then
  usage
  exit 2
fi

profile="$1"
release_dir="$(readlink -f "$2")"
backup_dir="$3"
retention_days="${4:-14}"

case "$profile" in
  dev)
    compose_project="wyrmgate-iam-dev"
    env_file="${release_dir}/deploy/config/dev.env"
    compose_file="${release_dir}/deploy/compose/dev.yml"
    ;;
  demo)
    compose_project="wyrmgate-iam-demo"
    env_file="${release_dir}/deploy/config/demo.env"
    compose_file="${release_dir}/deploy/compose/demo.yml"
    ;;
  *)
    echo "unsupported environment profile: ${profile}" >&2
    exit 2
    ;;
esac

if [[ ! "$retention_days" =~ ^[0-9]+$ || "$retention_days" -lt 1 ]]; then
  echo "retention-days must be a positive integer" >&2
  exit 2
fi

for required in "$env_file" "$compose_file"; do
  if [[ ! -f "$required" ]]; then
    echo "required deployment file not found: ${required}" >&2
    exit 1
  fi
done

install -d -m 0700 "$backup_dir"

compose=(
  docker compose
  -p "$compose_project"
  --env-file "$env_file"
  -f "$compose_file"
)

if ! "${compose[@]}" ps --status running --services | grep -Fxq postgres; then
  echo "PostgreSQL service is not running for ${profile}" >&2
  exit 1
fi

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
final_file="${backup_dir}/${profile}-${timestamp}.dump"
checksum_file="${final_file}.sha256"
temp_file="$(mktemp "${backup_dir}/.${profile}-${timestamp}.XXXXXX.dump")"

cleanup() {
  rm -f "$temp_file"
}
trap cleanup EXIT

umask 077

"${compose[@]}" exec -T postgres sh -ceu '
  export PGPASSWORD="$POSTGRES_PASSWORD"
  exec pg_dump \
    --host=127.0.0.1 \
    --username="$POSTGRES_USER" \
    --dbname="$POSTGRES_DB" \
    --format=custom \
    --compress=6 \
    --no-owner \
    --no-acl
' >"$temp_file"

if [[ ! -s "$temp_file" ]]; then
  echo "backup archive is empty" >&2
  exit 1
fi

"${compose[@]}" exec -T postgres pg_restore --list >/dev/null <"$temp_file"

mv "$temp_file" "$final_file"
chmod 0600 "$final_file"
trap - EXIT

(
  cd "$backup_dir"
  sha256sum "$(basename "$final_file")" >"$(basename "$checksum_file")"
)
chmod 0600 "$checksum_file"

find "$backup_dir" -maxdepth 1 -type f \
  \( -name "${profile}-*.dump" -o -name "${profile}-*.dump.sha256" \) \
  -mtime "+${retention_days}" -delete

echo "Backup created: ${final_file}"
