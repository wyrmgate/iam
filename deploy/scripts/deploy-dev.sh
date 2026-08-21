#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <release-directory>" >&2
  exit 2
fi

root_dir="/opt/wyrmgate/iam"
current_link="${root_dir}/current"
release_dir="$(readlink -m "$1")"
release_name="$(basename "${release_dir}")"
expected_release_dir="${root_dir}/releases/${release_name}"
previous_release=""

if [[ ! "${release_name}" =~ ^[0-9a-f]{40}$ || "${release_dir}" != "${expected_release_dir}" ]]; then
  echo "release-directory must be /opt/wyrmgate/iam/releases/<40-character-git-sha>" >&2
  exit 2
fi

if [[ -L "${current_link}" ]]; then
  previous_release="$(readlink -f "${current_link}")"
fi

app_compose=(docker compose -p wyrmgate-iam-dev --env-file "${release_dir}/deploy/config/dev.env" -f "${release_dir}/deploy/compose/dev.yml")
edge_compose=(docker compose -p wyrmgate-edge --env-file "${release_dir}/deploy/config/edge.env" -f "${release_dir}/deploy/compose/edge.yml")

rollback() {
  local exit_code=$?
  echo "DEV deployment failed; attempting application rollback." >&2

  if [[ -n "${previous_release}" && -d "${previous_release}" ]]; then
    local previous_app=(docker compose -p wyrmgate-iam-dev --env-file "${previous_release}/deploy/config/dev.env" -f "${previous_release}/deploy/compose/dev.yml")
    local previous_edge=(docker compose -p wyrmgate-edge --env-file "${previous_release}/deploy/config/edge.env" -f "${previous_release}/deploy/compose/edge.yml")
    "${previous_app[@]}" up -d --wait postgres otel-collector server console || true
    "${previous_edge[@]}" up -d caddy || true
    ln -sfn "${previous_release}" "${current_link}"
  else
    echo "No previous healthy DEV release exists; stopping the partial first deployment." >&2
    "${edge_compose[@]}" down || true
    "${app_compose[@]}" down || true
  fi

  exit "${exit_code}"
}
trap rollback ERR

mkdir -p "${root_dir}/releases"
docker network inspect wyrmgate-edge >/dev/null 2>&1 || docker network create wyrmgate-edge >/dev/null

"${app_compose[@]}" pull postgres otel-collector server console
"${edge_compose[@]}" pull caddy

"${app_compose[@]}" up -d --wait postgres otel-collector
"${app_compose[@]}" run --rm --no-deps server \
  --spring.main.web-application-type=none \
  --wyrmgate.migrate-only=true

"${app_compose[@]}" up -d --wait server console
"${edge_compose[@]}" up -d caddy

set -a
# shellcheck disable=SC1090
. "${release_dir}/deploy/config/dev.env"
set +a

for attempt in {1..30}; do
  if curl --fail --silent --show-error --max-time 10 \
    "https://${IAM_PUBLIC_HOST}/actuator/health" >/dev/null; then
    ln -sfn "${release_dir}" "${current_link}"
    trap - ERR
    echo "DEV deployment healthy: ${release_dir}"
    exit 0
  fi
  sleep 5
done

echo "DEV public health check did not become healthy." >&2
false
