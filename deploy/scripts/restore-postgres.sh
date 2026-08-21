#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: IAM_RESTORE_CONFIRM=RESTORE:<profile> $0 <dev|demo> <release-directory> <backup-file>" >&2
}

if [[ $# -ne 3 ]]; then
  usage
  exit 2
fi

profile="$1"
release_dir="$(readlink -f "$2")"
backup_file="$(readlink -f "$3")"

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

if [[ "${IAM_RESTORE_CONFIRM:-}" != "RESTORE:${profile}" ]]; then
  echo "restore confirmation missing; set IAM_RESTORE_CONFIRM=RESTORE:${profile}" >&2
  exit 2
fi

for required in "$env_file" "$compose_file" "$backup_file" "${backup_file}.sha256"; do
  if [[ ! -f "$required" ]]; then
    echo "required restore input not found: ${required}" >&2
    exit 1
  fi
done

backup_dir="$(dirname "$backup_file")"
backup_name="$(basename "$backup_file")"

if [[ "$backup_name" != "${profile}-"*.dump ]]; then
  echo "backup filename does not match restore profile ${profile}: ${backup_name}" >&2
  exit 2
fi

(
  cd "$backup_dir"
  sha256sum -c "${backup_name}.sha256"
)

compose=(
  docker compose
  -p "$compose_project"
  --env-file "$env_file"
  -f "$compose_file"
)

"${compose[@]}" up -d --wait postgres

# Validate the archive with the same PostgreSQL toolchain before changing state.
"${compose[@]}" exec -T postgres pg_restore --list >/dev/null <"$backup_file"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
safety_backup_dir="${IAM_RESTORE_SAFETY_BACKUP_DIR:-/var/backups/wyrmgate/iam/${profile}/pre-restore}"

if [[ "${IAM_RESTORE_SKIP_SAFETY_BACKUP:-false}" != "true" ]]; then
  "${script_dir}/backup-postgres.sh" "$profile" "$release_dir" "$safety_backup_dir" 30
else
  echo "WARNING: pre-restore safety backup explicitly skipped." >&2
fi

"${compose[@]}" stop server >/dev/null 2>&1 || true

restore_failed() {
  local exit_code=$?
  echo "Restore failed. IAM server remains stopped; inspect PostgreSQL before retrying." >&2
  exit "$exit_code"
}
trap restore_failed ERR

"${compose[@]}" exec -T postgres sh -ceu '
  export PGPASSWORD="$POSTGRES_PASSWORD"
  dropdb \
    --host=127.0.0.1 \
    --username="$POSTGRES_USER" \
    --if-exists \
    --force \
    "$POSTGRES_DB"
  createdb \
    --host=127.0.0.1 \
    --username="$POSTGRES_USER" \
    --owner="$POSTGRES_USER" \
    "$POSTGRES_DB"
'

"${compose[@]}" exec -T postgres sh -ceu '
  export PGPASSWORD="$POSTGRES_PASSWORD"
  exec pg_restore \
    --host=127.0.0.1 \
    --username="$POSTGRES_USER" \
    --dbname="$POSTGRES_DB" \
    --exit-on-error \
    --no-owner \
    --no-acl
' <"$backup_file"

"${compose[@]}" run --rm --no-deps server \
  --spring.main.web-application-type=none \
  --wyrmgate.migrate-only=true

"${compose[@]}" up -d --wait server

trap - ERR
echo "Restore completed for ${profile}: ${backup_file}"
echo "Verify the public health endpoint and application-level recovery checks before resuming normal operations."
