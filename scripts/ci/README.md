# CI Scripts

Reusable CI support logic belongs here when it is substantial enough not to live directly in workflow YAML.

## Component-aware CI

`scripts/ci/component-changes.sh` is the shared change classifier for the monorepo application workflows. The workflows themselves still start for pull requests and `main` pushes, but expensive component-specific jobs run only when their inputs changed.

The classifier keeps these boundaries explicit:

- `apps/server/**` drives server build, server-container and Railway server-contract work;
- `apps/console/**` drives console build, console-container and Cloudflare Pages contract work;
- dependency audits are scoped to the corresponding server or console dependency manifests;
- workflow files, the classifier itself, and genuinely shared build inputs deliberately widen the affected set;
- manual dispatch validates all components;
- repository-wide secret and filesystem security scans remain global rather than being path-filtered.

A console-only source change therefore does not compile the Java server, build/scan the server image, resolve the Maven dependency tree, or publish a new server image. The inverse applies to server-only source changes.

## Core CI

The workflow lives in `.github/workflows/ci.yml`. Component builds remain reproducible locally with:

```bash
make server-build
make console-build
```

The `server` and `console` jobs are conditional on the component classifier. Repository validation remains a lightweight whole-repository job because it checks Compose rendering, machine-readable API contracts, CI support scripts, and repository hygiene.

Security scanning, dependency vulnerability policy, secret scanning, and container scanning remain separate workflows. Component-specific work in those workflows uses the same change classifier so the decision is consistent.

## GitHub Actions run retention

`.github/workflows/actions-retention.yml` is repository housekeeping, not a quality gate and not a substitute for GitHub's normal retention settings.

The policy intentionally preserves debugging and audit value while removing obsolete pull-request noise:

- workflow runs on the default branch are never deleted by this automation;
- workflow runs are retained while a pull request is open;
- when a same-repository pull request closes, the newest completed `Core CI`, `Security CI`, and `Container CI` run for that branch are retained;
- older completed runs for the closed branch are removed, including failed, cancelled, superseded successful, and retired temporary-workflow runs;
- a nightly sweep applies the same cleanup to non-default branches whose latest completed run is at least seven days old and that have no open pull request;
- fork pull-request branches are skipped;
- the retention workflow never deletes its own runs.

When the retention workflow or its support script changes on `main`, a one-time/bootstrap sweep runs immediately across completed same-repository non-default branches. It skips branches with open pull requests and applies the same keep-newest rule without waiting for the seven-day stale threshold. This is idempotent and exists specifically to clear historical failed, cancelled, superseded, duplicate, and retired-workflow backlog after retention-policy installation or repair.

Manual dispatch defaults to dry-run mode and accepts an optional non-default branch plus a stale-day threshold. Cleanup refuses the default branch and skips branches with an open pull request.

The workflow uses only `actions: write`, `contents: read`, and `pull-requests: read`. No repository-content write permission is granted to the retention job.
