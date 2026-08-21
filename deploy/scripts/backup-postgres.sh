#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 4 || $# -gt 5 ]]; then
  echo "usage: $0 <env-file> <compose-file> <project-name> <backup-dir> [retention-days]" >&2
  exit 2
fi

env_file="$1"
compose_file="$2"
project_name="$3"
backup_dir="$4"
retention_days="${5:-7}"

if [[ ! "${retention_days}" =~ ^[0-9]+$ ]] || (( retention_days < 1 )); then
  echo "retention-days must be a positive integer" >&2
  exit 2
fi

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

install -d -m 700 "${backup_dir}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
base="wyrmgate-${IAM_DB_NAME}-${timestamp}"
tmp="${backup_dir}/.${base}.dump.tmp"
backup="${backup_dir}/${base}.dump"
checksum="${backup}.sha256"

compose=(docker compose -p "${project_name}" --env-file "${env_file}" -f "${compose_file}")

cleanup() {
  rm -f "${tmp}"
}
trap cleanup EXIT

"${compose[@]}" exec -T postgres pg_dump \
  --username="${IAM_DB_USER}" \
  --dbname="${IAM_DB_NAME}" \
  --format=custom \
  --compress=6 \
  --no-owner \
  --no-privileges > "${tmp}"

chmod 600 "${tmp}"
if [[ ! -s "${tmp}" ]]; then
  echo "backup output is empty" >&2
  exit 1
fi

mv "${tmp}" "${backup}"
sha256sum "${backup}" > "${checksum}"
chmod 600 "${checksum}"

find "${backup_dir}" -type f \
  \( -name 'wyrmgate-*.dump' -o -name 'wyrmgate-*.dump.sha256' \) \
  -mtime "+${retention_days}" -delete

trap - EXIT
echo "PostgreSQL backup created: ${backup}"
