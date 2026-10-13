# Staged integration CI

Wyrmgate uses short-lived module integration branches when several coordinated issues are being implemented concurrently. The goal is to validate individual changes cheaply, converge the module in one place, and pay the full release-quality CI cost once for the integrated result before it reaches `main`.

This is an engineering workflow. It does not redefine IAM requirements, capability ownership, domain semantics, API contracts, security boundaries, or deployment architecture.

## Flow

```text
feature/task branches
        |
        | pull requests + lightweight module checks
        v
integration/<module>/<wave>
        |
        | one release-candidate pull request + full required CI
        v
main
        |
        v
normal main-triggered publishing/deployment
```

Examples:

- `integration/console-ui/wave-1`
- `integration/console-ui/wave-2`
- `integration/server/access-request-runtime`

Do not create a permanent generic `develop` branch. Integration branches are bounded to one coherent tranche and are retired after that tranche reaches `main`.

## Feature/task pull requests

When a task belongs to an active integration tranche:

1. create or use the task branch recorded by its coordination issue;
2. keep the branch based on the tranche baseline unless a genuine implementation dependency requires stacking;
3. open the PR against the declared `integration/<module>/<wave>` branch, not `main`;
4. run the lightweight `Integration CI` workflow;
5. review the task for its owned scope and merge it into the integration branch after the selected fast checks pass.

`Integration CI` reuses `scripts/ci/component-changes.sh` and currently runs:

- Console tests plus TypeScript/production build when Console files changed;
- server build/tests when server files changed;
- a cheap `repository-fast` check for CI-script syntax and repository hygiene on every integration PR;
- an aggregate `integration-quality` result that fails when component classification, repository hygiene, or a selected fast component check fails or is cancelled.

The lightweight gate intentionally does **not** run Docker Compose validation, API-contract validation, Security CI, Container CI, DEV Deployment Contract, release checks, publishing, deployment, or other main-bound release verification.

## Integration branch responsibility

The integration branch is the convergence point for the tranche. It owns:

- conflict resolution between accepted task branches;
- verification that task ownership boundaries still compose correctly;
- any narrow reconciliation work required only because independently valid task changes interact;
- keeping the tranche ledger current;
- producing one reviewable release-candidate diff against `main`.

Do not use the integration branch to hide unrelated work or to introduce architecture/domain/API changes that were not accepted in the underlying tasks.

If `main` advances materially while a tranche is active, update the integration branch from reviewed `main` and re-run the lightweight checks before opening the final PR.

## Integration to main

Only the converged integration branch opens the normal PR to `main` for that tranche.

That PR is the release-quality gate and remains subject to the repository's existing `main` ruleset and workflows, including the required `quality` status and all applicable Core, Security, Container, DEV deployment-contract, documentation, and other path-triggered checks.

The full checks validate the **integrated exact head**, not every intermediate issue branch independently.

After the PR merges:

- normal `main` push workflows publish/deploy as configured;
- mark the tranche verified/done only after required post-merge verification;
- retire or clearly supersede the short-lived integration branch;
- start the next tranche from the new reviewed `main` baseline.

## Direct-to-main exceptions

A task may bypass an active module pool only when the reason is explicit and reviewable. Examples:

- an urgent security/production correction that cannot wait for the tranche;
- CI/bootstrap work required to establish or repair the integration mechanism itself;
- a truly isolated change for a module with no active pool;
- an operator-approved emergency change following the repository's normal governance/review controls.

Convenience, avoiding conflict resolution, or wanting a faster merge are not bypass reasons.

## Security and deployment boundary

Pooling changes does not weaken the final security or deployment boundary:

- secrets/private credential material remains prohibited from ordinary code paths, logs, events, task payloads and errors;
- final main-bound changes still receive existing security and container validation;
- deployment/publishing remains driven from reviewed `main`, not from integration branches;
- managed DEV/production topology remains defined by the existing deployment and operations documentation.

## Current pilot

Issue #312 uses `integration/console-ui/wave-1` as the first staged integration branch.

UI-011/#328 and UI-021/#329 reached `main` while the pilot was being established, so the pilot integration branch was reconciled to include those accepted baselines plus the staged-CI bootstrap. UI-010/#327 was then merged into the pool. Remaining Wave-1 work should target the integration branch. The final Console Wave-1 integration PR will target `main` and run the normal full gate once for the converged tranche.
