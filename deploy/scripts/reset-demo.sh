#!/usr/bin/env bash
set -euo pipefail

root_dir="/opt/wyrmgate/iam-demo"
current_link="${root_dir}/current"
volume_name="wyrmgate-iam-demo-postgres-data"

if [[ ! -L "${current_link}" ]]; then
  echo "No active DEMO release found at ${current_link}." >&2
  exit 1
fi

release_dir="$(readlink -f "${current_link}")"
app_compose=(docker compose -p wyrmgate-iam-demo --env-file "${release_dir}/deploy/config/demo.env" -f "${release_dir}/deploy/compose/demo.yml")

"${app_compose[@]}" down

docker volume rm "${volume_name}" >/dev/null 2>&1 || true

"${app_compose[@]}" up -d --wait postgres
"${app_compose[@]}" run --rm --no-deps server \
  --spring.main.web-application-type=none \
  --wyrmgate.migrate-only=true
"${app_compose[@]}" up -d --wait server console

set -a
# shellcheck disable=SC1090
. "${release_dir}/deploy/config/demo.env"
set +a

for attempt in {1..30}; do
  if curl --fail --silent --show-error --max-time 10 \
    "https://${IAM_PUBLIC_HOST}/actuator/health" >/dev/null; then
    echo "DEMO reset completed successfully."
    exit 0
  fi
  sleep 5
done

echo "DEMO health check failed after reset." >&2
exit 1
