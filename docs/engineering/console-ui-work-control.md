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
- The short-lived Wave-2 integration pool is retired after promotion PR #354. UI-018 is serialized cross-cutting work and may target `main` directly under the isolated-work exception recorded in #346/#312.

## Active ledger

| Work ID | Issue | State | Owner / agent | Branch | Actual base / stacked on | Logical dependencies | Shared surfaces / locks | Allowed overlap | Forbidden overlap | PR | Merge / verification |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| UI-000 | #313 | DONE | issue #313 agent | `docs/ui-work-control-ledger` | `main` at `a23733c` | #312 coordination model | Coordination docs only; no runtime lock | May run with UI-151, UI-020, UI-140 | Console implementation behavior, shared component contracts | #318 | Squash-merged to `main` as `37f5f7c`; all PR CI checks passed; issue #313 closed completed |
| UI-151 | #314 | DONE | issue #314 agent | final integration: `refactor/ui-component-decomposition-final` | reconciled on current `main` | Structural refactor only | `src/ui` structural decomposition lock RELEASED | Later shared-foundation work | Intentional primitive API redesign, theme redesign, route/auth semantics | #325 | Full gates passed; squash-merged as `f3fe27b`; issue closed completed |
| UI-020 | #315 | DONE | issue #315 agent | `feat/ui-addressable-routes` | `main` | Route/deep-link semantics and navigation integration | Route/navigation lock RELEASED | Reconciled before UI-151 final integration | Shared primitive redesign, unsupported route/API semantics | #321 | Squash-merged as `167e96c`; issue closed completed |
| UI-140 | #316 | DONE | issue #316 agent | `test/ui-console-harness` | `main` | Reusable non-auth Console test harness | Test-harness lock RELEASED | Harness preserved by later work | Product behavior, route semantics, primitive redesign | #320 | Squash-merged as `7e90c5c`; issue closed completed |
| UI-010 | #327 | DONE | issue #327 agent | `docs/ui-shared-foundation-audit` | Wave-1 flow | Wave 0 complete | Coordination docs lock RELEASED | UI-011, UI-021, UI-030 | Production UI behavior, shared primitive contracts, routing/auth/API semantics | #332 | Audit integrated and issue closed completed |
| UI-011 | #328 | DONE | issue #328 agent | `feat/ui-design-tokens` | Wave-1 flow | Wave 0 complete | Global theme/token lock RELEASED | Later primitive work | DataTable/forms/state/dialog public behavior, routes, capability redesign | #331 | Full gates green; merged as `3e203256`; issue closed completed |
| UI-021 | #329 | DONE | issue #329 agent | `refactor/ui-legacy-routing-cleanup` | Wave-1 flow | UI-020 merged | Residual routing/frame lock RELEASED | Shared visual work | Route URL/meaning changes, shared primitive/theme redesign | #336 | Full gates green; merged as `c2821dbe`; issue closed completed |
| UI-030 | #330 | DONE | issue #330 agent | `feat/ui-overview-refinement` | Wave-1 pool | Existing shared primitives | Overview page lock RELEASED | UI-010, UI-011, UI-021 | Theme/tokens, shared primitive APIs, routing, auth/API semantics | #333 | Integrated through Wave 1; release-quality promotion verified |
| UI-012 | #340 | DONE | issue #340 agent | `feat/ui-data-table-foundation` | Wave-2 baseline `a9018533` | UI-011 / Wave 1 | DataTable lock RELEASED | UI-013 through UI-017 | Domain columns/actions, API behavior, `SemanticWorkspace` redesign | #348 | Integrated in Wave 2; promotion PR #354 verified and merged to `main` as `3606b858c732594708ea65120567c70c409a3ecd` |
| UI-013 | #341 | DONE | issue #341 agent | `feat/ui-form-foundation` | Wave-2 baseline `a9018533` | UI-011 / Wave 1 | Form primitive lock RELEASED | UI-012, UI-014 through UI-017 | Domain validation/workflows, API bodies, secret flows | Wave-2 task PR | Integrated in Wave 2; promotion #354 verified at `3606b858c732594708ea65120567c70c409a3ecd` |
| UI-014 | #342 | DONE | issue #342 agent | `feat/ui-state-foundation` | Wave-2 baseline `a9018533` | UI-011 / Wave 1 | StatePanel lock RELEASED | UI-012, UI-013, UI-015 through UI-017 | API error-policy redesign, capability state mapping | Wave-2 task PR | Integrated in Wave 2; promotion #354 verified at `3606b858c732594708ea65120567c70c409a3ecd` |
| UI-015 | #343 | DONE | issue #343 agent | `feat/ui-dialog-foundation` | Wave-2 baseline `a9018533` | UI-011 / Wave 1 | Dialog primitive lock RELEASED | UI-012 through UI-014, UI-016, UI-017 | IAM operation semantics, ETag/idempotency, auth dialogs | Wave-2 task PR | Integrated in Wave 2; promotion #354 verified at `3606b858c732594708ea65120567c70c409a3ecd` |
| UI-016 | #344 | DONE | issue #344 agent | `feat/ui-responsive-foundation` | Wave-2 baseline `a9018533` | UI-011 / UI-021 | Responsive foundation lock RELEASED | UI-012 through UI-015, UI-017 | Route/navigation meaning, capability layouts, token redesign | Wave-2 task PR | Integrated in Wave 2; promotion #354 verified at `3606b858c732594708ea65120567c70c409a3ecd` |
| UI-017 | #345 | DONE | issue #345 agent | `feat/ui-tabs-foundation` | Wave-2 baseline `a9018533` | UI-011 / Wave 1 | Tabs primitive lock RELEASED | UI-012 through UI-016 | URL routing/view semantics, capability tab content | Wave-2 task PR | Integrated in Wave 2; promotion #354 verified at `3606b858c732594708ea65120567c70c409a3ecd` |
| UI-018 | #346 | IN_REVIEW | issue #346 agent | `test/ui-accessibility-baseline` | exact reviewed `main` at `3606b858c732594708ea65120567c70c409a3ecd` | UI-012 through UI-017 DONE | Serialized accessibility-only lock across converged shared primitives, `SemanticWorkspace`, focused shell/page call sites and accessibility tests | Non-overlapping capability work only | IAM/domain/API/auth/routing semantics, new workflows, unrelated visual redesign | #355 | PR #355 opened direct to `main`; exact Wave-2 merge base preserved; PR CI/review in progress |

## Current phase

Wave 0 and Wave 1 are complete. Wave 2 is **DONE / VERIFIED**: UI-012 through UI-017 converged in `integration/console-ui/wave-2`, promotion PR #354 passed the release-quality gate, and the exact reviewed result was merged to `main` as `3606b858c732594708ea65120567c70c409a3ecd`. The Wave-2 pool is retired and must not receive additional feature work.

UI-018 / #346 is the serialized Wave-3 accessibility baseline and is **IN_REVIEW** in PR #355. It validates and repairs the converged shared foundation for keyboard traversal, focus visibility/restoration, dialog semantics, tabs, form labeling/errors, table names/headers, contrast, reduced motion and narrow-screen reachability. It must preserve IAM requirements, API behavior, authentication/authorization semantics and route meaning. Capability-workspace fan-out remains after this baseline unless a task is demonstrably non-overlapping.

## Shared-foundation acceptance history

The post-Wave-0 audit deliberately separated shared presentation contracts from domain meaning. Wave 2 implemented that split in focused modules:

- UI-012 / #340 — DataTable naming/caption hooks, dense presentation, overflow and domain-neutral row/state affordances.
- UI-013 / #341 — form-control help/error/read-only/required semantics and typed shared presentation behavior.
- UI-014 / #342 — StatePanel/state-surface presentation without changing API error policy.
- UI-015 / #343 — accessible dialog/confirmation association, focus behavior, busy/dismiss policy and narrow-screen sizing.
- UI-016 / #344 — responsive shared page/shell foundation without changing navigation meaning.
- UI-017 / #345 — accessible, overflow-safe shared tabs with route-agnostic tab/panel relationship hooks.

UI-018 owns only the cross-cutting validation and accessibility repairs that become visible after those contracts converge. `SemanticWorkspace` may consume shared hooks but must not become a generic IAM domain model or redefine API behavior.

## Concurrency waves

### Wave 0 — coordination and structural preparation

DONE: UI-000, UI-151, UI-020 and UI-140.

### Wave 1 — shared visual foundation and convergence

DONE: UI-010, UI-011, UI-021 and UI-030; promoted and verified on `main` at `a9018533b936b2f577ec59e733b920a823dfc7ec`.

### Wave 2 — parallel shared primitives

DONE: UI-012 through UI-017. All six task branches started from the reviewed Wave-1 baseline, converged through the short-lived Wave-2 pool, and were promoted through PR #354. The reviewed `main` result is `3606b858c732594708ea65120567c70c409a3ecd`. All Wave-2 primitive locks are released and the pool is retired.

### Wave 3 — accessibility baseline

IN_REVIEW: UI-018 / #346 on `test/ui-accessibility-baseline` via PR #355, based exactly on `3606b858c732594708ea65120567c70c409a3ecd`. This is serialized cross-cutting work. Direct-to-`main` is allowed by the issue-specific isolated-work exception.

### Wave 4 — capability workspace fan-out

Capability workspaces may fan out after UI-018 across Identity, Principal, Catalog, Access, Governance, Credential, Integration, Administration and Audit. Capability ownership remains canonical IAM ownership; this split is only a UI implementation coordination mechanism.

## Shared-surface lock register

Use this section for temporary locks. Add the task/issue, exact surface, reason and release condition. Mark released once the owning PR merges/closes or the task explicitly relinquishes the surface.

| Surface | Owner | Status | Reason / release condition |
| --- | --- | --- | --- |
| Login composition files formerly owned by PR #311 | PR #311 | RELEASED | PR #311 merged into `main` at `a23733c` |
| `src/ui` decomposition targets | UI-151 / #314 | RELEASED | Final reconciled implementation merged through PR #325 |
| `consoleNavigation.ts` route semantics and `App.tsx` routing integration | UI-020 / #315 | RELEASED | PR #321 merged; route contract remains independently governed |
| Reusable Console test harness | UI-140 / #316 | RELEASED | PR #320 merged; later focused tests may extend it without redefining product behavior |
| `src/ui/WyrmgateUiProvider.tsx` global theme/token behavior | UI-011 / #328 | RELEASED | Token baseline merged; accessibility-only focus repair is temporarily covered by UI-018 below |
| Wave-2 primitive modules (`table.tsx`, `forms.tsx`, `state.tsx`, `dialogs.tsx`, responsive foundation, `tabs.tsx`) | UI-012 through UI-017 | RELEASED | Wave 2 promoted and verified through #354 at `3606b858...` |
| Cross-cutting accessibility surfaces: converged shared primitives, `SemanticWorkspace`, focused shell/page call sites and accessibility tests | UI-018 / #346 | ACTIVE | Serialized until PR #355 merges/closes or explicitly releases the lock; no IAM/API/auth/routing semantic changes permitted |

## PR and integration checklist

When a task opens a PR, record the PR number in the active ledger and update #312 if it introduces or changes a shared-surface lock. Before marking a row `READY_FOR_INTEGRATION` or later, verify that the branch still has the recorded ancestry, its scope did not absorb another task's concern, canonical IAM/API/security semantics remain unchanged unless separately approved, and task-specific tests/checks are green.

For UI-018 specifically, verify keyboard-only shared patterns, visible focus and return-focus behavior, dialog/tab/form/table semantics, shared-token contrast, reduced-motion behavior, narrow-screen reachability, Console tests, typecheck and production build. The branch must remain descended from the reviewed Wave-2 promotion and must not reuse the retired Wave-2 integration pool.

After merge, set `MERGED`, complete required verification, then set `VERIFIED` and finally `DONE`. If a merged change requires follow-up correction before verification, keep it out of `DONE` and record the follow-up dependency.

## Authority boundary

This ledger is operational coordination documentation only. In a conflict, use the project authority order: accepted ADRs and current formal requirements/specifications first, then current repository architecture/domain/interface documentation, then implementation/tests. Issue #312 controls UI concurrency mechanics but does not override those sources.
