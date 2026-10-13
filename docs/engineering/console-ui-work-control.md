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
| UI-000 | #313 | IN_REVIEW | issue #313 agent | `docs/ui-work-control-ledger` | `main` at `a23733c` | #312 coordination model | Coordination docs only; no runtime lock | May run with UI-151, UI-020, UI-140 | Console implementation behavior, shared component contracts | #318 | Branch diff verified; PR review/checks pending |
| UI-151 | #314 | IN_PROGRESS | issue #314 agent | `refactor/ui-component-decomposition` | `main` | None; structural refactor only | Temporary structural ownership of decomposition targets while changing them; no semantic redesign lock | May run with UI-000, UI-020, UI-140; preserve stable `./ui` boundary | Route semantics, broad test-harness redesign, auth-login files, intentional primitive API redesign | — | Not merged |
| UI-020 | #315 | READY | unclaimed / agent placeholder | `feat/ui-addressable-routes` | `main` | May require rebase if UI-151 moves `App.tsx` or page files | Owns route/deep-link semantics, `consoleNavigation.ts`, and routing integration in `App.tsx` while active | May run with UI-000, UI-151, UI-140 | Shared primitive redesign, broad harness redesign, unsupported route/API semantics | — | Not merged |
| UI-140 | #316 | READY | unclaimed / agent placeholder | `test/ui-console-harness` | `main` | Rebase if UI-151 moves a test target | Owns reusable non-auth test harness/helper work while active | May run with UI-000, UI-151, UI-020; prefer new helper files | Product behavior, route semantics, primitive redesign | — | Not merged |

The four initial branches were created from the same `main` baseline and are intended to run concurrently. If issue-thread state is newer than this table, update this document in the coordinating PR/branch rather than treating the stale row as authority.

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

Run these from `main` concurrently:

1. UI-000 — repo-local work-control ledger (`docs/ui-work-control-ledger`)
2. UI-151 — component/file decomposition (`refactor/ui-component-decomposition`)
3. UI-020 — URL-addressable routing (`feat/ui-addressable-routes`)
4. UI-140 — reusable Console test harness (`test/ui-console-harness`)

There is no required merge ordering among these four. UI-151 should merge before the later shared-primitive fan-out. UI-020 may need a normal rebase if UI-151 moves routing/page files structurally. UI-140 should prefer helper additions and narrow non-auth test edits to stay low-conflict.

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
| `src/ui` decomposition targets | UI-151 / #314 | ACTIVE-STRUCTURAL | Structural decomposition only; release when the refactor PR merges/closes. Does not grant semantic redesign ownership. |
| `consoleNavigation.ts` route semantics and `App.tsx` routing integration | UI-020 / #315 | RESERVED | Becomes active when #315 is claimed; release when its PR merges/closes. Structural moves by UI-151 must preserve this semantic ownership. |
| Reusable Console test harness | UI-140 / #316 | RESERVED | Becomes active when #316 is claimed; release when its PR merges/closes. |

## PR and integration checklist

When a task opens a PR, record the PR number in the active ledger and update #312 if it introduces or changes a shared-surface lock. Before marking a row `READY_FOR_INTEGRATION` or later, verify that the branch still has the recorded ancestry, its scope did not absorb another task's concern, canonical IAM/API/security semantics remain unchanged unless separately approved, and task-specific tests/checks are green.

After merge, set `MERGED`, complete required verification, then set `VERIFIED` and finally `DONE`. If a merged change requires follow-up correction before verification, keep it out of `DONE` and record the follow-up dependency.

## Authority boundary

This ledger is operational coordination documentation only. In a conflict, use the project authority order: accepted ADRs and current formal requirements/specifications first, then current repository architecture/domain/interface documentation, then implementation/tests. Issue #312 controls UI concurrency mechanics but does not override those sources.