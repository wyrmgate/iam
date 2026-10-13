# Console UI work-control ledger

This document is the repository-local coordination ledger for concurrent Wyrmgate Console/UI implementation under issue #312 and epic #263. It coordinates task ownership, branch ancestry, overlap boundaries, shared-surface locks, integration state and verification. It does **not** redefine IAM requirements, architecture, domain semantics, security rules, API contracts, capability ownership or authorization behavior; those remain governed by the canonical formal specifications, accepted ADRs and current repository architecture/interface documentation.

Update this ledger when a UI task is claimed, its branch ancestry changes, a shared public UI surface is locked or released, a PR opens, or merge/verification state changes. Do not rely on chat history as the durable coordination record.

## Work states

`BACKLOG -> READY -> CLAIMED -> IN_PROGRESS -> BLOCKED -> READY_FOR_INTEGRATION -> IN_REVIEW -> MERGED -> VERIFIED -> DONE`

`CANCELLED` is terminal. `MERGED` is not `DONE`; required Console tests, typecheck/build and any task-specific DEV verification must complete before `DONE`.

## Branch and overlap rules

- Feature/capability branches normally start from `main`.
- Stack a branch on another unmerged branch only when there is a genuine implementation dependency. Record the stacked-on branch here, then rebase onto `main` after the prerequisite merges.
- Parallelize across ownership boundaries. Serialize changes to the same shared public UI contract.
- Consuming a shared component unchanged does not require a lock.
- Changes to `src/ui` public primitives/`WyrmgateUiProvider`, `SemanticWorkspace`, `consoleNavigation.ts`, `ShellChrome.tsx`, `App.tsx` routing/entry composition, or shared/global CSS/theme require an explicit temporary owner when the public contract or behavior changes.
- A structural move by one task does not transfer semantic ownership from another task. Rebase rather than independently redefining the same concern.
- Business rules, Tenant resolution, governed-actor resolution and Administration authorization remain server-side. UI work must not invent unsupported APIs, generic status mutation, browser authority or secret-bearing flows.

## Active ledger

| Work ID | Issue | State | Owner / agent | Branch | Actual base / stacked on | Logical dependencies | Shared surfaces / locks | Allowed overlap | Forbidden overlap | PR | Merge / verification |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| UI-000 | #313 | DONE | issue #313 agent | `docs/ui-work-control-ledger` | `main` at `a23733c` | #312 coordination model | Coordination docs only; no runtime lock | May run with UI-151, UI-020, UI-140 | Console implementation behavior, shared component contracts | #318 | Squash-merged to `main` as `37f5f7c`; all PR CI checks passed; issue #313 closed completed |
| UI-151 | #314 | DONE | issue #314 agent | final integration: `refactor/ui-component-decomposition-final` | rebased/integrated on current `main` after UI-140/#320 and UI-020/#321 merged | Structural refactor only; reconciled with already merged routing and reusable test-harness work | `src/ui` structural decomposition lock RELEASED | Wave 0 work converged without changing route/auth/API semantics; stable `./ui` boundary preserved | Intentional primitive API redesign, theme redesign, route semantics, auth semantics | #325 | Original #319 and intermediate #324 superseded; #325 passed Core CI, Security CI, DEV Deployment Contract and Container CI, squash-merged as `f3fe27b`; issue #314 closed completed |
| UI-020 | #315 | DONE | issue #315 agent | `feat/ui-addressable-routes` | `main` | Route/deep-link semantics and navigation integration | Route/navigation lock RELEASED | Reconciled before UI-151 final integration | Shared primitive redesign, broad harness redesign, unsupported route/API semantics | #321 | Squash-merged to `main` as `167e96c`; issue #315 closed completed |
| UI-140 | #316 | DONE | issue #316 agent | `test/ui-console-harness` | `main` | Reusable non-auth Console test harness | Test-harness lock RELEASED | Harness preserved by UI-151 final integration | Product behavior, route semantics, primitive redesign | #320 | Squash-merged to `main` as `7e90c5c`; issue #316 closed completed |

Wave 0 is complete. UI-000, UI-151, UI-020 and UI-140 are all merged, verified and closed. The structural `src/ui` decomposition now provides focused ownership modules for later shared-foundation work, while routing and reusable test-harness semantics remain independently owned by their accepted implementations.

## Login composition status

PR #311 (`fix/console-login-composition`) previously had exclusive ownership of:

- `apps/console/src/SignInGate.tsx`
- `apps/console/src/AuthControlPlaneGraphic.tsx`
- `apps/console/src/auth-login-viewport.css`
- `apps/console/tests/auth-layout.test.mjs`
- login-related portions of `apps/console/src/ui/README.md`

PR #311 merged into `main` as commit `a23733c` on 13 October 2026. The temporary exclusive lock is therefore released. New work on those files still follows the normal shared-surface/issue ownership rules and must preserve accepted authentication semantics.

## Concurrency waves

### Wave 0 — coordination and structural preparation

Completed:

1. UI-000 — repo-local work-control ledger (`docs/ui-work-control-ledger`)
2. UI-151 — component/file decomposition (final integration via `refactor/ui-component-decomposition-final`)
3. UI-020 — URL-addressable routing (`feat/ui-addressable-routes`)
4. UI-140 — reusable Console test harness (`test/ui-console-harness`)

The initial four tasks were intentionally run concurrently and reconciled before final integration. UI-151 ultimately merged after UI-020 and UI-140, preserving both route semantics and the reusable harness while establishing smaller shared-primitive ownership units.

### Wave 1 — shared visual foundation

After UI-151 merges, normalize design tokens/density first. This is a shared/global contract and should have one temporary owner.

### Wave 2 — parallel shared primitives

After token/density normalization, DataTable, form controls, state panels, confirmation/dialog patterns and responsive foundation may proceed in parallel when each task owns distinct modules/public contracts. Any cross-cutting change to the same primitive or global theme must be serialized.

### Wave 3 — accessibility baseline

Run the accessibility baseline after the shared primitive tranche is integrated so it validates the converged foundation rather than several incompatible intermediate versions.

### Wave 4 — capability workspace fan-out

Capability workspaces may then fan out in parallel across Identity, Principal, Catalog, Access, Governance, Credential, Integration, Administration and Audit. Capability ownership remains canonical IAM ownership; this work split is only a UI implementation coordination mechanism.

## Shared-surface lock register

Use this section for temporary locks. Add the task/issue, exact surface, reason and release condition. Remove or mark released once the owning PR merges/closes or the task explicitly relinquishes the surface.

| Surface | Owner | Status | Reason / release condition |
| --- | --- | --- | --- |
| Login composition files formerly owned by PR #311 | PR #311 | RELEASED | PR #311 merged into `main` at `a23733c` |
| `src/ui` decomposition targets | UI-151 / #314 | RELEASED | Final reconciled implementation merged through PR #325 as `f3fe27b`; future public-contract changes require a new explicit owner |
| `consoleNavigation.ts` route semantics and `App.tsx` routing integration | UI-020 / #315 | RELEASED | PR #321 merged as `167e96c`; future route-semantic changes require a new explicit owner |
| Reusable Console test harness | UI-140 / #316 | RELEASED | PR #320 merged as `7e90c5c`; future broad harness changes require a new explicit owner |

## PR and integration checklist

When a task opens a PR, record the PR number in the active ledger and update #312 if it introduces or changes a shared-surface lock. Before marking a row `READY_FOR_INTEGRATION` or later, verify that the branch still has the recorded ancestry, its scope did not absorb another task's concern, canonical IAM/API/security semantics remain unchanged unless separately approved, and task-specific tests/checks are green.

After merge, set `MERGED`, complete required verification, then set `VERIFIED` and finally `DONE`. If a merged change requires follow-up correction before verification, keep it out of `DONE` and record the follow-up dependency.

## Authority boundary

This ledger is operational coordination documentation only. In a conflict, use the project authority order: accepted ADRs and current formal requirements/specifications first, then current repository architecture/domain/interface documentation, then implementation/tests. Issue #312 controls UI concurrency mechanics but does not override those sources.
