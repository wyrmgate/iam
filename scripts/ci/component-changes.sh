#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_EVENT_NAME:?GITHUB_EVENT_NAME is required}"
: "${GITHUB_EVENT_PATH:?GITHUB_EVENT_PATH is required}"

output_file="${GITHUB_OUTPUT:-/dev/stdout}"

core_server=false
core_console=false
container_server=false
container_console=false
security_server_deps=false
security_console_deps=false
dev_pages=false
dev_server=false
dev_docs=false

set_all() {
  core_server=true
  core_console=true
  container_server=true
  container_console=true
  security_server_deps=true
  security_console_deps=true
  dev_pages=true
  dev_server=true
  dev_docs=true
}

if [[ "${GITHUB_EVENT_NAME}" == "workflow_dispatch" ]]; then
  set_all
  changed_files=("<manual dispatch: validate all components>")
else
  read -r base_sha head_sha < <(
    python3 - "${GITHUB_EVENT_NAME}" "${GITHUB_EVENT_PATH}" <<'PY'
import json
import sys

name, path = sys.argv[1:]
with open(path, encoding="utf-8") as handle:
    event = json.load(handle)

if name == "pull_request":
    print(event["pull_request"]["base"]["sha"], event["pull_request"]["head"]["sha"])
elif name == "push":
    print(event.get("before", ""), event.get("after", ""))
else:
    raise SystemExit(f"Unsupported event for component change detection: {name}")
PY
  )

  if [[ -z "${head_sha}" ]]; then
    echo "Unable to resolve head SHA for ${GITHUB_EVENT_NAME}." >&2
    exit 1
  fi

  if [[ -z "${base_sha}" || "${base_sha}" =~ ^0+$ ]]; then
    mapfile -t changed_files < <(git diff-tree --no-commit-id --name-only -r "${head_sha}")
  else
    mapfile -t changed_files < <(git diff --name-only "${base_sha}" "${head_sha}")
  fi

  for file in "${changed_files[@]}"; do
    case "${file}" in
      scripts/ci/component-changes.sh)
        set_all
        ;;
    esac

    case "${file}" in
      apps/server/*|.java-version|Makefile|.github/workflows/ci.yml)
        core_server=true
        ;;
    esac

    case "${file}" in
      apps/console/*|.nvmrc|Makefile|.github/workflows/ci.yml)
        core_console=true
        ;;
    esac

    case "${file}" in
      apps/server/*|.dockerignore|.github/workflows/container.yml)
        container_server=true
        ;;
    esac

    case "${file}" in
      apps/console/*|.dockerignore|.github/workflows/container.yml)
        container_console=true
        ;;
    esac

    case "${file}" in
      apps/server/pom.xml|apps/server/mvnw|apps/server/.mvn/*|.github/workflows/security.yml)
        security_server_deps=true
        ;;
    esac

    case "${file}" in
      apps/console/package.json|apps/console/package-lock.json|.github/workflows/security.yml)
        security_console_deps=true
        ;;
    esac

    case "${file}" in
      apps/console/*|.dockerignore|.github/workflows/dev-cd.yml)
        dev_pages=true
        ;;
    esac

    case "${file}" in
      apps/server/*|scripts/ci/dev-bootstrap-entrypoint.sh|.dockerignore|.github/workflows/dev-cd.yml)
        dev_server=true
        ;;
    esac

    case "${file}" in
      docs/README.md|docs/engineering/dev-cd.md|docs/engineering/public-demo.md|docs/engineering/edge.md|docs/engineering/observability.md|docs/operations/dev-managed-activation.md|docs/operations/first-run-web-setup.md|.github/workflows/dev-cd.yml)
        dev_docs=true
        ;;
    esac
  done
fi

printf 'Changed files considered by component-aware CI:\n'
printf '  %s\n' "${changed_files[@]:-<none>}"

for name in \
  core_server \
  core_console \
  container_server \
  container_console \
  security_server_deps \
  security_console_deps \
  dev_pages \
  dev_server \
  dev_docs; do
  printf '%s=%s\n' "${name}" "${!name}" | tee -a "${output_file}"
done
