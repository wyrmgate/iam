#!/usr/bin/env bash
set -euo pipefail

: "${GH_REPO:?GH_REPO is required}"
: "${DEFAULT_BRANCH:?DEFAULT_BRANCH is required}"
: "${EVENT_NAME:?EVENT_NAME is required}"
: "${REPOSITORY_OWNER:?REPOSITORY_OWNER is required}"

keep_workflows=("Core CI" "Security CI" "Container CI")
dry_run="false"
stale_days="7"

if [[ "${EVENT_NAME}" == "workflow_dispatch" ]]; then
  dry_run="${DRY_RUN_INPUT:-true}"
  stale_days="${STALE_DAYS_INPUT:-7}"
fi

if ! [[ "${stale_days}" =~ ^[0-9]+$ ]] || (( stale_days < 1 || stale_days > 365 )); then
  echo "stale_days must be an integer between 1 and 365" >&2
  exit 2
fi

delete_run() {
  local run_id="$1"
  local run_name="$2"
  local branch_name="$3"
  local conclusion="$4"
  local created_at="$5"

  echo "delete candidate: id=${run_id} workflow=${run_name} branch=${branch_name} conclusion=${conclusion} created=${created_at}"

  if [[ "${dry_run}" == "true" ]]; then
    echo "dry-run: keeping ${run_id}"
    return
  fi

  gh api --method DELETE "repos/${GH_REPO}/actions/runs/${run_id}" --silent
  echo "deleted run ${run_id}"
}

branch_has_open_pr() {
  local branch_name="$1"
  local count

  count="$(
    gh api       --method GET       -H "Accept: application/vnd.github+json"       -f state=open       -f head="${REPOSITORY_OWNER}:${branch_name}"       -f per_page=1       "repos/${GH_REPO}/pulls"       --jq 'length'
  )"

  [[ "${count}" != "0" ]]
}

cleanup_branch() {
  local branch_name="$1"
  local runs

  echo "cleaning branch: ${branch_name}"

  runs="$(
    gh api       --paginate       --method GET       -H "Accept: application/vnd.github+json"       -f branch="${branch_name}"       -f status=completed       -f per_page=100       "repos/${GH_REPO}/actions/runs"       --jq '.workflow_runs[] | [.id,.name,.conclusion,.created_at,.head_branch,.head_repository.full_name] | @tsv' |
      sort -t $'\t' -k4,4r
  )"

  [[ -z "${runs}" ]] && return

  declare -A kept=()

  while IFS=$'\t' read -r run_id run_name conclusion created_at head_branch head_repository; do
    [[ -z "${run_id}" ]] && continue
    [[ "${head_branch}" != "${branch_name}" ]] && continue
    [[ "${head_repository}" != "${GH_REPO}" ]] && continue
    [[ "${run_name}" == "Actions Retention" ]] && continue

    keep_this="false"
    for keep_name in "${keep_workflows[@]}"; do
      if [[ "${run_name}" == "${keep_name}" && -z "${kept[${run_name}]:-}" ]]; then
        kept["${run_name}"]="${run_id}"
        keep_this="true"
        echo "keep newest: id=${run_id} workflow=${run_name} branch=${branch_name}"
        break
      fi
    done

    if [[ "${keep_this}" == "true" ]]; then
      continue
    fi

    delete_run "${run_id}" "${run_name}" "${branch_name}" "${conclusion}" "${created_at}"
  done < <(printf '%s\n' "${runs}")
}

if [[ "${EVENT_NAME}" == "pull_request" ]]; then
  if [[ "${PR_HEAD_REPO:-}" != "${GH_REPO}" ]]; then
    echo "skip fork PR branch: ${PR_HEAD_REPO:-unknown}:${PR_HEAD_REF:-unknown}"
    exit 0
  fi

  if [[ -z "${PR_HEAD_REF:-}" || "${PR_HEAD_REF}" == "${DEFAULT_BRANCH}" ]]; then
    echo "nothing to clean"
    exit 0
  fi

  if branch_has_open_pr "${PR_HEAD_REF}"; then
    echo "skip branch with another open PR: ${PR_HEAD_REF}"
    exit 0
  fi

  cleanup_branch "${PR_HEAD_REF}"
  exit 0
fi

if [[ "${EVENT_NAME}" == "workflow_dispatch" && -n "${MANUAL_BRANCH:-}" ]]; then
  if [[ "${MANUAL_BRANCH}" == "${DEFAULT_BRANCH}" ]]; then
    echo "refusing to clean default branch: ${DEFAULT_BRANCH}" >&2
    exit 1
  fi

  if branch_has_open_pr "${MANUAL_BRANCH}"; then
    echo "skip branch with open PR: ${MANUAL_BRANCH}"
    exit 0
  fi

  cleanup_branch "${MANUAL_BRANCH}"
  exit 0
fi

repo_id="$(gh api "repos/${GH_REPO}" --jq '.id')"

mapfile -t branches < <(
  gh api     --paginate     --method GET     -H "Accept: application/vnd.github+json"     -f status=completed     -f per_page=100     "repos/${GH_REPO}/actions/runs"     --jq ".workflow_runs[] | select(.head_repository.id == ${repo_id}) | [.head_branch,.created_at] | @tsv" |
  awk -F '\t' -v default_branch="${DEFAULT_BRANCH}" '$1 != default_branch && $1 != "" { print }' |
  sort -k1,1 -k2,2r |
  awk -F '\t' '!seen[$1]++ { print $1 "\t" $2 }'
)

if [[ "${EVENT_NAME}" == "push" ]]; then
  echo "bootstrap sweep: cleaning historical non-default branches"
  for entry in "${branches[@]}"; do
    branch_name="${entry%%
  if branch_has_open_pr "${branch_name}"; then
    echo "skip branch with open PR: ${branch_name}"
    continue
  fi

  cleanup_branch "${branch_name}"
done
\t'*}"

    if branch_has_open_pr "${branch_name}"; then
      echo "skip branch with open PR: ${branch_name}"
      continue
    fi

    cleanup_branch "${branch_name}"
  done
  exit 0
fi

cutoff_epoch="$(date -u -d "-${stale_days} days" +%s)"

for entry in "${branches[@]}"; do
  branch_name="${entry%%
  if branch_has_open_pr "${branch_name}"; then
    echo "skip branch with open PR: ${branch_name}"
    continue
  fi

  cleanup_branch "${branch_name}"
done
\t'*}"
  latest_created="${entry#*
  if branch_has_open_pr "${branch_name}"; then
    echo "skip branch with open PR: ${branch_name}"
    continue
  fi

  cleanup_branch "${branch_name}"
done
\t'}"
  latest_epoch="$(date -u -d "${latest_created}" +%s)"

  if (( latest_epoch > cutoff_epoch )); then
    continue
  fi

  if branch_has_open_pr "${branch_name}"; then
    echo "skip branch with open PR: ${branch_name}"
    continue
  fi

  cleanup_branch "${branch_name}"
done
