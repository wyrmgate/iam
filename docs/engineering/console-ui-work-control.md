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
- The short-lived Wave-2 integration pool is retired after promotion PR #354. UI-018 was serialized cross-cutting work and targeted `main` directly under the isolated-work exception recorded in #346/#312.

## Active ledger

| Work ID | Issue | State | Owner / agent | Branch | Actual base / stacked on | Logical dependencies | Shared surfaces / locks | Allowed overlap | Forbidden overlap | PR | Merge / verification |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| UI-000 | #313 | DONE | issue #313 agent | `docs/ui-work-control-ledger` | `main` at `a23733c` | #312 coordination model | Coordination docs only; no runtime lock | May run with UI-151, UI-020, UI-140 | Console implementation behavior, shared component contracts | #318 | Squash-merged to `main` as `37f5f7c`; all PR CI checks passed; issue #313 closed completed |
| UI-151 | #314 | DONE | issue #314 agent | final integration: `refactor/ui-component-decomposition-final` | reconciled on current `main` | Structural refactor only | `src/ui` structural decomposition lock RELEASED | Later shared-foundation work | Intentional primitive API redesign, theme redesign, route/auth semantics | #325 | Full gates passed; squash-merged as `f3fe27b`; issue closed completed |
| UI-020 | #315 | DONE | issue #315 agent | `feat/ui-addressable-routes` | `main` | Route/deep-link semantics and navigation integration | Route/navigation lock RELEASED | Reconciled before UI-151 final integration | Shared primitive redesign, unsupported route/API semantics | #321 | Squash-merged as `167e96c`; issue closed completed |
| UI-140 | #316 | DONE | issue #316 agent | `test/ui-console-harness` | `main` | Reusable non-auth Console test harness | Test-harness lock RELEASED | Harness preserved by later work | Product behavior, route semantics, primitive redesign | #320 | Squash-merged as `7e90c5c`; issue closed completed |
| UI-010 | #327 | DONE | issue #327 agent | `docs/ui-shared-foundation-audit` | Wave-1 flow | Wave 0 complete | Coordination docs only; no runtime lock | UI-011, UI-021, UI-030 | Production UI behavior, shared component contracts, routing/auth/API semantics | #332 | Audit integrated and issue closed completed |
| UI-011 | #328 | DONE | issue #328 agent | `feat/ui-design-tokens` | Wave-1 flow | Wave 0 complete | Global theme/token lock RELEASED | Later primitive work | DataTable/forms/state/dialog public behavior, routes, capability redesign | #331 | Full gates green; merged as `3e203256`; issue closed completed |
| UI-021 | #329 | DONE | issue #329 agent | `refactor/ui-legacy-routing-cleanup` | Wave-1 flow | UI-020 merged | Residual routing/frame lock RELEASED | Shared visual work | Route URL/meaning changes, shared primitive/theme redesign | #336 | Full gates green; merged as `c2821dbe`; issue closed completed |
| UI-030 | #330 | DONE | issue #330 agent | `feat/ui-overview-refinement` | Wave-1 pool | Existing shared primitives | Overview page lock RELEASED | UI-010, UI-011, UI-021 | Theme/tokens, shared primitive APIs, routing, auth/API semantics | #333 | Integrated through Wave 1; release-quality promotion verified |
| UI-012 | #340 | DONE | issue #340 agent | `feat/ui-data-table-foundation` | Wave-2 baseline `a9018533` | UI-011 / Wave 1 | DataTable lock RELEASED | UI-013 through UI-017 | Domain columns/actions, API behavior, `SemanticWorkspace` redesign | #348 | Integrated in Wave 2; promotion PR #354 verified and merged to `main` as `3606b858c732594708ea65120567c70c409a3ecd` |
| UI-013 | #341 | DONE | issue #341 agent | `feat/ui-form-foundation` | Wave-2 baseline `a9018533` | UI-011 / Wave 1 | Form primitive lock RELEASED | UI-012, UI-014 through UI-017 | Domain validation/workflows, API bodies, secret flows | Wave-2 task PR | Integrated in Wave 2; promotion #354 verified at `3606b858c732594708ea65120567c70c409a3ecd` |
| UI-014 | #342 | DONE | issue #342 agent | `feat/ui-state-foundation` | Wave-2 baseline `a9018533` | UI-011 / Wave 1 | StatePanel lock RELEASED | UI-012, UI-013, UI-015 through UI-017 | API error-policy redesign, capability state mapping | Wave-2 task PR | Integrated in Wave 2; promotion #354 verified at `3606b858c732594708ea65120567c70c409a3ecd` |
| UI-015 | #343 | DONE | issue #343 agent | `feat/ui-dialog-foundation` | Wave-2 baseline `a9018533` | UI-011 / Wave 1 | Dialog primitive lock RELEASED | UI-012 through UI-014, UI-016, UI-017 | IAM operation semantics, ETag/idempotency, auth dialogs | Wave-2 task PR | Integrated in Wave 2; promotion #354 verified at `3606b858c732594708ea65120567c70c409a3ecd` |
| UI-016 | #344 | DONE | issue #344 agent | `feat/ui-responsive-foundation` | Wave-2 baseline `a9018533` | UI-011 / UI-021 | Responsive foundation lock RELEASED | UI-012 through UI-015, UI-017 | Route/navigation meaning, capability layouts, token redesign | Wave-2 task PR | Integrated in Wave 2; promotion #354 verified at `3606b858c732594708ea65120567c70c409a3ecd` |
| UI-017 | #345 | DONE | issue #345 agent | `feat/ui-tabs-foundation` | Wave-2 baseline `a9018533` | UI-011 / Wave 1 | Tabs primitive lock RELEASED | UI-012 through UI-016 | URL routing/view semantics, capability tab content | Wave-2 task PR | Integrated in Wave 2; promotion #354 verified at `3606b858c732594708ea65120567c70c409a3ecd` |
| UI-018 | #346 | DONE | issue #346 agent | `test/ui-accessibility-baseline` | exact reviewed `main` at `3606b858c732594708ea65120567c70c409a3ecd` | UI-012 through UI-017 DONE | Accessibility lock RELEASED after merge | Capability fan-out may proceed subject to its own ownership/locks | IAM/domain/API/auth/routing semantics, new workflows, unrelated visual redesign | #355 | PR gates Core/Security/Container/DEV all green on tree `9b392e84`; squash-merged to `main` as `fa7882354086e38d3a1ad899c93304c48963b58a` with the identical tree; issue #346 closed completed |
| UI-019 | #357 | DONE | issue #357 agent | `refactor/ui-capability-page-decomposition` | exact `main` at `c996a4ffc6573d52ecf9dd850f67ae8b25345685` | UI-018 DONE / Wave 4 structural preparation | `App.tsx` composition and capability-workspace decomposition lock RELEASED | Capability fan-out may proceed from merged `main` ownership surfaces | IAM/domain/API/auth/routing semantics, shared primitive contracts, visual redesign, capability feature behavior | #358 | Core/Security/Container/DEV gates all green on tree `909eaa8f`; squash-merged to `main` as `2bdb72ff33fcf2f15e8236192d3204a8f5db7b2a` with the identical tree; issue #357 closed completed |
| UI-022 | #414 | DONE | issue #414 agent | `feat/ui-enterprise-standard-v1` | `main` at `564fa474abbf9f8456b82ceb80ac13fea468c039` | Existing shared foundation and Identity collection route | Shared design tokens/theme, page primitives, `StateIndicator`, Identity collection presentation lock RELEASED | Capability-local work may now consume the standard; Access PRs #411/#413 remained independent | Access workspace/API semantics, route/auth semantics, IAM/domain behavior, Identity 360 semantic/API behavior | #415 | Exact head `b4801408c3d3b1b278148dbcaaa9a9e8fe5829c8` passed Console tests/typecheck/build plus Core/Security/Container/DEV; squash-merged to `main` as `505e57e6b45ae16733ad3e8125bb9a8eda7d7a1b` |
| UI-023 | #419 | DONE | issue #419 agent | `refactor/identity360-enterprise-detail` | `main` at `cfe57ff8f2c76f6aa8596bb5b07a034edd4620e6` | UI-022 Enterprise UI Standard v1; #407/#408 accepted Identity 360 semantics | No shared public-contract lock required; existing primitives consumed unchanged | Capability-local work outside Identity detail could proceed | Shared primitive redesign, route/auth/API semantics, Access/Governance/Audit authority, Identity domain behavior | #420 | Exact head `3ad30c07bf007e30a17589eafeba6621e97a4c25` passed all 148 Console source-contract tests, TypeScript build, Core/Security/Container/DEV; squash-merged to `main` as `dfae896f80a97b2eb0e3b3c3f309541fafa14895`; issue #419 closed completed |
| UI-024 | #422 | DONE | issue #422 agent | `refactor/console-enterprise-convergence` | exact `main` at `9248ea6164a1f2273b6fdf8f128b175318a3d6a2` | UI-022/UI-023 complete; #381 remains parent | Shared authenticated-Console presentation lock RELEASED | Capability-local presentation work may consume the standard; shared-contract changes require a new explicit lock | Route/auth/API/IAM semantics, capability authority/state-machine changes, provider behavior | #423 | Exact head `36b7a5f2a2a1511d2f1817d874ff01ae9ae1fd95` passed Core #1639, Security #1636, Container #1627 and DEV Deployment Contract #1485; squash-merged to `main` as `99536c2fc8f2526ed43560e5090a506f0ba82d4c` |
| UI-025 | #440 | IN_REVIEW | issue #440 agent | `feat/tenant-overview-dashboard` | exact `main` at `3bfd3d91427980c0bf87048a4292172f3ef31150` | ADR-0049; Enterprise UI Standard v1 | `consoleNavigation.ts` + `App.tsx` route/composition lock ACTIVE until #441 closes/merges | Capability-local work outside route composition | New Tenant lifecycle/API semantics, browser Tenant selection, capability-owned mutations, shared primitive/theme redesign | #441 | PR open; Console tests/typecheck/build succeeded on the implementation head before this ledger-only update; full PR CI required on the final head |

## Current phase

Wave 0 and Wave 1 are complete. Wave 2 is **DONE / VERIFIED**: UI-012 through UI-017 converged in `integration/console-ui/wave-2`, promotion PR #354 passed the release-quality gate, and the exact reviewed result was merged to `main` as `3606b858c732594708ea65120567c70c409a3ecd`. The Wave-2 pool is retired and must not receive additional feature work.

UI-018 / #346 is **DONE / VERIFIED**. PR #355 established the serialized Wave-3 accessibility baseline and was squash-merged to `main` as `fa7882354086e38d3a1ad899c93304c48963b58a`. The merged commit has the same Git tree (`9b392e843d37de512236795642c2feab8bcdfc25`) as the PR head that passed Core CI, Security CI, Container CI and DEV Deployment Contract. The serialized accessibility lock is released.

UI-019 / #357 is **DONE / VERIFIED**. PR #358 passed Core CI, Security CI, Container CI and DEV Deployment Contract on reviewed tree `909eaa8f0c360259b40e5a91709e02206b6bc687`, then squash-merged to `main` as `2bdb72ff33fcf2f15e8236192d3204a8f5db7b2a` with that identical tree. The structural decomposition lock is released; Wave-4 capability feature branches should start from or rebase onto this merged `main` baseline rather than re-splitting the same ownership surfaces.

UI-022 / #414 is **DONE / VERIFIED**. PR #415 established Enterprise UI Standard v1 tokens/responsive composition and migrated the Identity collection onto shared primitives without changing IAM/domain/API/persistence/authorization semantics. Exact head `b4801408c3d3b1b278148dbcaaa9a9e8fe5829c8` passed Console tests/typecheck/build plus Core CI, Security CI, Container CI and DEV Deployment Contract, then squash-merged to `main` as `505e57e6b45ae16733ad3e8125bb9a8eda7d7a1b`. The shared visual/theme lock is released.

UI-023 / #419 is **DONE / VERIFIED**. PR #420 migrated Identity 360/detail onto the shared Enterprise UI Standard v1 Object 360 composition, retired `identity-detail-golden.css` and the temporary `identity-page-standard.css` bridge, and preserved the accepted #407/#408 Identity/API semantics. Exact head `3ad30c07bf007e30a17589eafeba6621e97a4c25` passed all 148 Console source-contract tests, TypeScript typecheck/build, Core CI, Security CI, Container CI and DEV Deployment Contract, then squash-merged to `main` as `dfae896f80a97b2eb0e3b3c3f309541fafa14895`. No shared public UI contract changed and no shared lock was required.

UI-024 / #422 is **DONE / VERIFIED**. PR #423 converged the residual authenticated Console onto Enterprise UI Standard v1: Access, Overview/IAM Operations and Requests & Approvals retain purpose-built workflow geometry while consuming one canonical visual vocabulary; the temporary Access alignment layer is retired; shell theme/session presentation resolves through the same Enterprise UI roles; and CSS variables derive from `designTokens.ts` rather than duplicating palette values. Exact head `36b7a5f2a2a1511d2f1817d874ff01ae9ae1fd95` passed Core #1639, Security #1636, Container #1627 and DEV Deployment Contract #1485, then squash-merged to `main` as `99536c2fc8f2526ed43560e5090a506f0ba82d4c`. The shared presentation lock is released. Managed-DEV browser acceptance remains the next release gate under #394.

UI-025 / #440 is **IN_REVIEW** on PR #441 from exact current `main` `3bfd3d91427980c0bf87048a4292172f3ef31150`. It adds the current-Tenant management/navigation surface using only server-derived Tenant context and existing tenant-scoped Console projections. `consoleNavigation.ts` and `App.tsx` route/composition remain temporarily serialized until the PR closes or merges; IAM/domain/API/persistence semantics are unchanged.

## Shared-foundation acceptance history

The post-Wave-0 audit deliberately separated shared presentation contracts from domain meaning. Wave 2 implemented that split in focused modules:

- UI-012 / #340 — DataTable naming/caption hooks, dense presentation, overflow and domain-neutral row/state affordances.
- UI-013 / #341 — form-control help/error/read-only/required semantics and typed shared presentation behavior.
- UI-014 / #342 — StatePanel/state-surface presentation without changing API error policy.
- UI-015 / #343 — accessible dialog/confirmation association, focus behavior, busy/dismiss policy and narrow-screen sizing.
- UI-016 / #344 — responsive shared page/shell foundation without changing navigation meaning.
- UI-017 / #345 — accessible, overflow-safe shared tabs with route-agnostic tab/panel relationship hooks.
- UI-018 / #346 — cross-cutting focus visibility, checkbox validation semantics, semantic table headers/naming, tab/panel relationships, reduced-motion and shared-token contrast regression coverage without changing IAM/API/auth/routing semantics.
- UI-022 / #414 — shared enterprise visual/responsive contract, restrained theme/token refinement, caller-typed `StateIndicator`, and Identity collection reference migration without moving domain meaning into shared UI.
- UI-023 / #419 — Identity 360 Object 360 reference migration onto existing shared primitives; retired page-local visual layers while preserving Identity, Principal, Access, Governance and Audit ownership boundaries.
- UI-024 / #422 — residual authenticated-Console convergence: purpose-built workbench CSS is constrained to workflow geometry and shared Enterprise UI token consumption; private Access/Overview/Requests visual systems and the Access alignment shim are retired without changing capability semantics.

`SemanticWorkspace` consumes shared accessibility hooks but remains a presentation consumer; it is not a generic IAM domain model and does not redefine API behavior.

## Concurrency waves

### Wave 0 — coordination and structural preparation

DONE: UI-000, UI-151, UI-020 and UI-140.

### Wave 1 — shared visual foundation and convergence

DONE: UI-010, UI-011, UI-021 and UI-030; promoted and verified on `main` at `a9018533b936b2f577ec59e733b920a823dfc7ec`.

### Wave 2 — parallel shared primitives

DONE: UI-012 through UI-017. All six task branches started from the reviewed Wave-1 baseline, converged through the short-lived Wave-2 pool, and were promoted through PR #354. The reviewed `main` result is `3606b858c732594708ea65120567c70c409a3ecd`. All Wave-2 primitive locks are released and the pool is retired.

### Wave 3 — accessibility baseline

DONE / VERIFIED: UI-018 / #346 was implemented on `test/ui-accessibility-baseline` from exact baseline `3606b858c732594708ea65120567c70c409a3ecd`, passed all four release-quality PR gates, and was promoted directly to `main` through PR #355 as `fa7882354086e38d3a1ad899c93304c48963b58a`.

### Wave 4 — capability workspace fan-out

UI-019 / #357 is DONE / VERIFIED and establishes the stable implementation ownership surfaces for capability feature fan-out. The decomposition is an implementation coordination boundary only; canonical capability and Principal ownership do not change. New Wave-4 capability branches should use merged `main` at or after `2bdb72ff33fcf2f15e8236192d3204a8f5db7b2a` as their baseline.

| Wave-4 ownership surface | Console route(s) | Implementation file after UI-019 | Semantic ownership note |
| --- | --- | --- | --- |
| Identity | `/identities`, `/identities/:identityId` | `apps/console/src/workspaces/IdentityWorkspace.tsx` | Identity capability |
| Principal | `/principals` | `apps/console/src/workspaces/PrincipalsWorkspace.tsx` | Separate UI ownership surface; Principal remains Identity-owned canonical state |
| Catalog | `/catalog` | `apps/console/src/workspaces/CatalogWorkspace.tsx` | Catalog capability |
| Access | `/access` | `apps/console/src/workspaces/AccessWorkspace.tsx` | Access capability |
| Governance | `/requests`, `/reviews`, `/policies` | `apps/console/src/workspaces/GovernanceWorkspace.tsx` | Governance capability; source-policy lookup remains the existing accepted Identity endpoint consumed by this workspace |
| Credential | `/credentials` | `apps/console/src/workspaces/CredentialsWorkspace.tsx` | Credential capability |
| Integration | `/integrations` | `apps/console/src/workspaces/IntegrationsWorkspace.tsx` | Integration capability; `IntegrationOperationsPage` remains a focused child consumer |
| Administration | `/administration` | `apps/console/src/workspaces/AdministrationWorkspace.tsx` | Administration capability; current-authority projection remains server-owned |
| Audit | `/audit` | `apps/console/src/workspaces/AuditWorkspace.tsx` | Audit capability |

`apps/console/src/workspaces/workspaceSpecHelpers.ts` is shared only for existing cursor/value normalization helpers (`list`, `nullable`, `iso`, `csv`). It is not a domain model, route registry, authorization layer or replacement for typed capability semantics. `App.tsx` remains the route/runtime composition owner, while `SemanticWorkspace` and `src/ui` retain their existing shared presentation contracts.

### Wave 5 — residual enterprise convergence

UI-024 / #422 is **DONE / VERIFIED**. It was serialized cross-cutting presentation work on `refactor/console-enterprise-convergence` from exact `main` `9248ea6164a1f2273b6fdf8f128b175318a3d6a2`, passed the full release-quality gate on exact head `36b7a5f2a2a1511d2f1817d874ff01ae9ae1fd95`, and merged through #423 as `99536c2fc8f2526ed43560e5090a506f0ba82d4c`. Managed-DEV browser acceptance #394 is now the next release gate.

## Shared-surface lock register

Use this section for temporary locks. Add the task/issue, exact surface, reason and release condition. Mark released once the owning PR merges/closes or the task explicitly relinquishes the surface.

| Surface | Owner | Status | Reason / release condition |
| --- | --- | --- | --- |
| Login composition files formerly owned by PR #311 | PR #311 | RELEASED | PR #311 merged into `main` at `a23733c` |
| `src/ui` decomposition targets | UI-151 / #314 | RELEASED | Final reconciled implementation merged through PR #325 |
| `consoleNavigation.ts` route semantics and `App.tsx` routing integration | UI-020 / #315 | RELEASED | PR #321 merged; route contract remains independently governed |
| Reusable Console test harness | UI-140 / #316 | RELEASED | PR #320 merged; later focused tests may extend it without redefining product behavior |
| `src/ui/WyrmgateUiProvider.tsx` global theme/token behavior | UI-011 / #328 | RELEASED | Token baseline merged; UI-018 accessibility-only focus repair completed through #355 |
| Wave-2 primitive modules (`table.tsx`, `forms.tsx`, `state.tsx`, `dialogs.tsx`, responsive foundation, `tabs.tsx`) | UI-012 through UI-017 | RELEASED | Wave 2 promoted and verified through #354 at `3606b858...` |
| Cross-cutting accessibility surfaces: converged shared primitives, `SemanticWorkspace`, focused shell/page call sites and accessibility tests | UI-018 / #346 | RELEASED | PR #355 merged to `main` as `fa788235...`; exact merged tree passed the release-quality PR gates |
| `App.tsx` structural composition, legacy `ControlPlanePages.tsx`, and new `src/workspaces/*` ownership layout | UI-019 / #357 | RELEASED | PR #358 passed all four release-quality gates and squash-merged to `main` as `2bdb72ff...` with the identical reviewed tree |
| Enterprise UI Standard v1 shared tokens/theme/page/state presentation plus Identity collection reference migration | UI-022 / #414 | RELEASED | PR #415 exact head passed Console/Core/Security/Container/DEV gates and squash-merged to `main` as `505e57e6b45ae16733ad3e8125bb9a8eda7d7a1b`. |
| Residual authenticated-Console presentation (`enterprise-console.css`, shared/global CSS/theme, shell presentation, Access/Overview/Requests local visual layers, and any touched `src/ui`/`SemanticWorkspace` presentation contract) | UI-024 / #422 | RELEASED | PR #423 exact head `36b7a5f2a2a1511d2f1817d874ff01ae9ae1fd95` passed Core #1639, Security #1636, Container #1627 and DEV #1485, then squash-merged to `main` as `99536c2fc8f2526ed43560e5090a506f0ba82d4c`. |
| `consoleNavigation.ts` route registry and `App.tsx` route composition for current-Tenant surface | UI-025 / #440 | ACTIVE | Release when PR #441 merges/closes or explicitly relinquishes the route/composition change. No shared primitive/theme lock is held. |

## PR and integration checklist

When a task opens a PR, record the PR number in the active ledger and update #312 if it introduces or changes a shared-surface lock. Before marking a row `READY_FOR_INTEGRATION` or later, verify that the branch still has the recorded ancestry, its scope did not absorb another task's concern, canonical IAM/API/security semantics remain unchanged unless separately approved, and task-specific tests/checks are green.

For accessibility-related follow-up work, retain the UI-018 regression baseline covering visible focus, dialog focus behavior, tab/form/table semantics, shared-token contrast, reduced motion and narrow-screen reachability. Any future route, IAM, API or authorization semantic change remains outside that baseline and must be governed independently.

After merge, set `MERGED`, complete required verification, then set `VERIFIED` and finally `DONE`. If a merged change requires follow-up correction before verification, keep it out of `DONE` and record the follow-up dependency.

## Authority boundary

This ledger is operational coordination documentation only. In a conflict, use the project authority order: accepted ADRs and current formal requirements/specifications first, then current repository architecture/domain/interface documentation, then implementation/tests. Issue #312 controls UI concurrency mechanics but does not override those sources.