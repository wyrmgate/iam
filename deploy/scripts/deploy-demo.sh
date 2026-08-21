#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <release-directory>" >&2
  exit 2
fi

release_dir="$1"
root_dir="/opt/wyrmgate/iam-demo"
current_link="${root_dir}/current"
previous_release=""

if [[ -L "${current_link}" ]]; then
  previous_release="$(readlink -f "${current_link}")"
fi

app_compose=(docker compose -p wyrmgate-iam-demo --env-file "${release_dir}/deploy/config/demo.env" -f "${release_dir}/deploy/compose/demo.yml")
edge_compose=(docker compose -p wyrmgate-demo-edge --env-file "${release_dir}/deploy/config/edge.env" -f "${release_dir}/deploy/compose/edge.yml")

rollback() {
  local exit_code=$?
  echo "DEMO deployment failed; attempting application rollback." >&2

  if [[ -n "${previous_release}" && -d "${previous_release}" ]]; then
    local previous_app=(docker compose -p wyrmgate-iam-demo --env-file "${previous_release}/deploy/config/demo.env" -f "${previous_release}/deploy/compose/demo.yml")
    local previous_edge=(docker compose -p wyrmgate-demo-edge --env-file "${previous_release}/deploy/config/edge.env" -f "${previous_release}/deploy/compose/edge.yml")
    "${previous_app[@]}" up -d --wait postgres server console || true
    "${previous_edge[@]}" up -d caddy || true
    ln -sfn "${previous_release}" "${current_link}"
  fi

  exit "${exit_code}"
}
trap rollback ERR

mkdir -p "${root_dir}/releases"
docker network inspect wyrmgate-demo-edge >/dev/null 2>&1 || docker network create wyrmgate-demo-edge >/dev/null

"${app_compose[@]}" pull postgres server console
"${edge_compose[@]}" pull caddy

"${app_compose[@]}" up -d --wait postgres
"${app_compose[@]}" run --rm --no-deps server \
  --spring.main.web-application-type=none \
  --wyrmgate.migrate-only=true

"${app_compose[@]}" up -d --wait server console
"${edge_compose[@]}" up -d caddy

set -a
# shellcheck disable=SC1090
. "${release_dir}/deploy/config/demo.env"
set +a

for attempt in {1..30}; do
  if curl --fail --silent --show-error --max-time 10 \
    "https://${IAM_PUBLIC_HOST}/actuator/health" >/dev/null; then
    ln -sfn "${release_dir}" "${current_link}"
    trap - ERR
    echo "DEMO deployment healthy: ${release_dir}"
    exit 0
  fi
  sleep 5
done

echo "DEMO public health check did not become healthy." >&2
false
