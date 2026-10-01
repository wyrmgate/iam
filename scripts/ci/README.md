# CI Scripts

Reusable CI support logic belongs here when it is substantial enough not to live directly in workflow YAML.

## Core CI

The initial workflow lives in `.github/workflows/ci.yml` and deliberately keeps simple commands in the workflow or root Makefile rather than hiding them behind CI-only scripts.

Stable required-check candidates:

- `server`
- `console`
- `repository`

Developers should be able to reproduce the application checks locally with:

```bash
make server-build
make console-build
```

Repository validation checks Compose rendering and rejects committed generated directories or non-example `.env` files.

Security scanning, dependency vulnerability policy, secret scanning, CodeQL, and container scanning belong to the security-CI sprint rather than this baseline.

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

Manual dispatch defaults to dry-run mode and accepts an optional non-default branch plus a stale-day threshold. Cleanup refuses the default branch and skips branches with an open pull request.

The workflow uses only `actions: write`, `contents: read`, and `pull-requests: read`. No repository-content write permission is granted to the retention job.
