#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "usage: $0 <env-file> <compose-file> <project-name> <backup-file>" >&2
  exit 2
fi

if [[ "${IAM_RESTORE_CONFIRM:-}" != "RESTORE" ]]; then
  echo "restore refused: set IAM_RESTORE_CONFIRM=RESTORE" >&2
  exit 2
fi

env_file="$1"
compose_file="$2"
project_name="$3"
backup_file="$4"
checksum_file="${backup_file}.sha256"

if [[ ! -r "${backup_file}" || ! -s "${backup_file}" ]]; then
  echo "backup file is missing or empty: ${backup_file}" >&2
  exit 1
fi
if [[ ! -r "${checksum_file}" ]]; then
  echo "checksum file is required: ${checksum_file}" >&2
  exit 1
fi

sha256sum --check "${checksum_file}"

set -a
# shellcheck disable=SC1090
. "${env_file}"
set +a

: "${IAM_DB_NAME:?IAM_DB_NAME is required}"
: "${IAM_DB_USER:?IAM_DB_USER is required}"

for value in "${IAM_DB_NAME}" "${IAM_DB_USER}"; do
  if [[ ! "${value}" =~ ^[A-Za-z0-9_]+$ ]]; then
    echo "database name/user contains unsupported characters" >&2
    exit 2
  fi
done

compose=(docker compose -p "${project_name}" --env-file "${env_file}" -f "${compose_file}")

# Restores are intentionally destructive and must be performed with application
# writers stopped. Ignore absent optional services so this script also works in CI.
"${compose[@]}" stop server console otel-collector >/dev/null 2>&1 || true

"${compose[@]}" exec -T postgres psql --username="${IAM_DB_USER}" --dbname=postgres \
  --set=ON_ERROR_STOP=1 \
  --command="DROP DATABASE IF EXISTS \"${IAM_DB_NAME}\" WITH (FORCE);"
"${compose[@]}" exec -T postgres psql --username="${IAM_DB_USER}" --dbname=postgres \
  --set=ON_ERROR_STOP=1 \
  --command="CREATE DATABASE \"${IAM_DB_NAME}\" OWNER \"${IAM_DB_USER}\";"

cat "${backup_file}" | "${compose[@]}" exec -T postgres pg_restore \
  --username="${IAM_DB_USER}" \
  --dbname="${IAM_DB_NAME}" \
  --exit-on-error \
  --no-owner \
  --no-privileges

"${compose[@]}" exec -T postgres psql --username="${IAM_DB_USER}" --dbname="${IAM_DB_NAME}" \
  --set=ON_ERROR_STOP=1 --command='SELECT 1;' >/dev/null

echo "PostgreSQL restore completed from: ${backup_file}"
