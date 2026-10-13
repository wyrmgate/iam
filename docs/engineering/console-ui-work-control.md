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
| UI-010 | #327 | DONE | issue #327 agent | `docs/ui-shared-foundation-audit` | `main` at `f33bfb0`; integrated through `integration/console-ui/wave-1` | Wave 0 complete; audit current foundation before primitive fan-out | Coordination docs lock RELEASED; no runtime lock | UI-011, UI-021, UI-030 | Production UI behavior, shared primitive contracts, routing/auth/API semantics | #332 | Documentation-only audit; PR checks passed; squash-merged to `integration/console-ui/wave-1` as `b1bef57`; issue #327 closed completed |
| UI-011 | #328 | READY | issue #328 agent | `feat/ui-design-tokens` | `main` at `f33bfb0` | Wave 0 complete | **EXCLUSIVE when claimed:** `src/ui/WyrmgateUiProvider.tsx` and global theme/token behavior | UI-010, UI-021, UI-030 | DataTable/forms/state/dialog public behavior, routes, capability redesign | — | Must merge before Wave 2 primitive branches are cut |
| UI-021 | #329 | READY | issue #329 agent | `refactor/ui-legacy-routing-cleanup` | `main` at `f33bfb0` | UI-020 merged | When claimed: residual legacy routing/frame cleanup in `App.tsx` and adjacent obsolete helpers; route contract itself remains unchanged | UI-010, UI-011, UI-030 | Route URL/meaning changes, shared primitive/theme redesign | — | Rebase if concurrent work changes same entry-composition lines |
| UI-030 | #330 | IN_PROGRESS | issue #330 agent | `feat/ui-overview-refinement` | `main` at `f33bfb0` | Existing shared primitives only | Overview page composition only; no shared primitive lock | UI-010, UI-011, UI-021 | Theme/tokens, shared primitive APIs, routing, auth/API semantics | — | Agent reported IN_PROGRESS; rebase after UI-011 if density change materially affects composition |

Wave 0 is complete. UI-000, UI-151, UI-020 and UI-140 are all merged, verified and closed. The structural `src/ui` decomposition now provides focused ownership modules for later shared-foundation work, while routing and reusable test-harness semantics remain independently owned by their accepted implementations.

## Post-Wave-0 shared-foundation audit (UI-010)

The #325 decomposition succeeded at its intended goal: shared primitives now have distinct implementation modules behind the stable `src/ui/index.tsx` boundary. The next work should improve those module contracts rather than re-combine them or introduce page-local replacements.

### Current foundation and concrete gaps

| Area | Current shape | Shared-foundation gap | Page-specific work that stays out of the shared task |
| --- | --- | --- | --- |
| Theme / tokens / density | `WyrmgateUiProvider.tsx` owns palette, shape, typography and a few MUI defaults; several dimensions/colors remain literal in primitive/shell CSS | Establish named, coherent spacing/density, typography, shape, border/focus and semantic-state defaults so later primitives do not invent local values | Overview or capability-specific visual hierarchy and content density choices |
| DataTable | `ui/table.tsx` wraps a small MUI table in an outlined scroll container and re-exports raw table parts | Define shared dense-table behavior for header treatment, cell density/alignment, overflow, empty/loading placement, row-action affordance, selected/interactive rows and accessible caption/label hooks without turning it into a domain data-grid abstraction | Column meaning, API pagination/filter semantics, domain-specific row actions, resource formatting |
| Forms | `ui/forms.tsx` provides thin Button/TextField wrappers, a string-only select and checkbox-with-help | Define consistent field layout, validation/error/help semantics, required/disabled/read-only presentation, typed option values/labels, textarea/select behavior and form density while preserving semantic fields in feature code | Domain validation rules, IAM workflow decisions, API payload construction, secret-bearing flows |
| StatePanel | `ui/state.tsx` is primarily an outlined MUI Alert with optional title/action | Define explicit loading/empty/info/warning/error/forbidden/not-found/conflict/stale-state presentation hooks and action placement; retain server error distinctions instead of collapsing them | Mapping a particular API/domain condition to a state remains in page/workspace code |
| ConfirmDialog / dialogs | `ui/dialogs.tsx` exposes Modal, SidePanel and a basic ConfirmDialog | Standardize accessible title/description association, initial/return focus, busy behavior, destructive emphasis, escape/backdrop policy and consistent action layout; do not encode IAM operation semantics in the primitive | Confirmation copy, whether an operation is materially harmful, ETag/idempotency behavior |
| Tabs | `ui/tabs.tsx` supplies scrollable styled MUI tabs; `WorkspaceTabs` owns local active state | Define the shared accessibility/overflow contract and clarify controlled vs uncontrolled usage. URL-addressable tab/view state must remain a routing concern, not be hidden inside the visual primitive | Route mapping and capability-specific tab sets/content |
| Responsive page layout | `ui/page.tsx` has responsive page/header/toolbar/surface composition; `shell-foundation.css` separately owns shell viewport/navigation breakpoints | Consolidate responsive ownership rules and verify table/form/dialog/page behavior at narrow widths. Shared work must not create page-specific shell offsets or change route semantics | Capability-specific rearrangement when a resource view genuinely needs different composition |
| Accessibility | Basic labels, focusable controls and reduced-motion support exist, but no converged foundation-level acceptance baseline covers all primitives/shell/workspace patterns | After Wave 2 merges, run a cross-cutting baseline for keyboard traversal, focus visibility/restoration, dialog semantics, tab semantics, form labeling/errors, table names/headers, contrast, reduced motion and narrow-screen reachability | Domain wording/content quality and resource-specific accessible names remain with capability owners |

### Shared versus page-specific classification rule

A gap belongs to the shared foundation when the same interaction/presentation rule should hold across multiple capability workspaces and can be expressed without IAM domain meaning. A gap is page-specific when it depends on resource semantics, public API behavior, governance meaning, authorization scope, workflow state, route meaning or operator content hierarchy. `SemanticWorkspace` may consume shared improvements, but it must not become a generic domain model or the owner of primitive presentation contracts.

## Recommended next shared-foundation task rows

These are coordination rows for the next primitive wave. Create GitHub issues/branches only after the current UI-010/UI-011/UI-021/UI-030 tranche converges on `main`. Branch names below are recommendations, not pre-created branches.

| Work ID | Planned issue | Initial state | Recommended branch | Exclusive implementation ownership | Depends on | May run concurrently with | Must not absorb |
| --- | --- | --- | --- | --- | --- | --- | --- |
| UI-012 | DataTable foundation | BACKLOG | `feat/ui-data-table-foundation` | `apps/console/src/ui/table.tsx`; table-focused tests; only necessary barrel export changes | UI-011 merged; current tranche converged | UI-013, UI-014, UI-015, UI-016, UI-017 | Domain columns/actions, API pagination/filter semantics, `SemanticWorkspace` redesign, global tokens |
| UI-013 | Form-control foundation | BACKLOG | `feat/ui-form-foundation` | `apps/console/src/ui/forms.tsx`; form-primitive tests; only necessary barrel export changes | UI-011 merged; current tranche converged | UI-012, UI-014, UI-015, UI-016, UI-017 | Domain validation/workflows, API body semantics, secret flows, global tokens |
| UI-014 | StatePanel/state surfaces | BACKLOG | `feat/ui-state-foundation` | `apps/console/src/ui/state.tsx`; state-surface tests; only necessary barrel export changes | UI-011 merged; current tranche converged | UI-012, UI-013, UI-015, UI-016, UI-017 | API error-policy redesign, capability-specific state mapping, global tokens |
| UI-015 | ConfirmDialog/dialog foundation | BACKLOG | `feat/ui-dialog-foundation` | `apps/console/src/ui/dialogs.tsx`; dialog/focus tests; only necessary barrel export changes | UI-011 merged; current tranche converged | UI-012, UI-013, UI-014, UI-016, UI-017 | IAM operation semantics, ETag/idempotency, login/auth dialogs, global tokens |
| UI-016 | Responsive shared foundation | BACKLOG | `feat/ui-responsive-foundation` | responsive behavior in `apps/console/src/ui/page.tsx` and `apps/console/src/shell-foundation.css`; responsive tests | UI-011 merged; UI-021 merged or rebased away from conflicting shell/frame cleanup | UI-012, UI-013, UI-014, UI-015, UI-017 | Route/navigation meaning, capability-specific layouts, token redesign, `ShellChrome.tsx` structural redesign unless separately coordinated |
| UI-017 | Tabs foundation | BACKLOG | `feat/ui-tabs-foundation` | `apps/console/src/ui/tabs.tsx`; tab-focused tests; only necessary barrel export changes | UI-011 merged; current tranche converged | UI-012, UI-013, UI-014, UI-015, UI-016 | URL routing/view semantics, capability tab content, global tokens |
| UI-018 | Accessibility baseline | BACKLOG | `test/ui-accessibility-baseline` | cross-cutting accessibility fixes/tests in shared primitives, `SemanticWorkspace`, shell and focused page call sites as required | UI-012 through UI-017 merged | Capability work only when files do not overlap; otherwise serialize | New domain/API behavior, visual redesign unrelated to accessibility, route semantics |

### Parallelism and lock plan for Wave 2

1. Merge UI-011 first. Do not branch styling-sensitive primitive work from the pre-token baseline.
2. Cut UI-012 through UI-017 from the same converged `main` after the current four-agent tranche is integrated.
3. Each primitive task takes an exclusive lock only on its listed module/public contract. `src/ui/index.tsx` is a shared barrel: keep export edits minimal and rebase normally if two tasks add exports concurrently.
4. Do not let primitive agents all edit `SemanticWorkspace.tsx`. Shared primitives should remain backward-compatible where practical; consumer migration can be a narrow follow-up or explicitly assigned to one task after primitive contracts converge.
5. UI-016 owns responsive foundation, not shell/navigation semantics. If it discovers that a `ShellChrome.tsx` DOM change is unavoidable, record a separate temporary lock in this ledger before editing it.
6. UI-018 runs after the primitive merge wave because accessibility fixes are intentionally cross-cutting and would otherwise collide with every primitive owner.
7. Capability page fan-out starts after UI-018 unless a page task can prove it consumes the shared contracts unchanged and does not overlap the accessibility files under active repair.

### Expected primitive acceptance baseline

Every Wave 2 primitive task should preserve the stable `./ui` import boundary, avoid capability/domain semantics, include focused regression tests, pass Console tests/typecheck/build, and document any intentional public primitive contract change. Public-contract changes must be explicit; a MUI prop leak or incidental implementation detail is not a Wyrmgate contract by default.

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

### Wave 1 — shared visual foundation and convergence

Current tranche:

1. UI-010 / #327 — shared-foundation audit (`docs/ui-shared-foundation-audit`), coordination docs only.
2. UI-011 / #328 — design-token/density normalization (`feat/ui-design-tokens`), one temporary global theme owner.
3. UI-021 / #329 — residual legacy routing/frame cleanup (`refactor/ui-legacy-routing-cleanup`), preserving UI-020 route semantics.
4. UI-030 / #330 — Overview refinement (`feat/ui-overview-refinement`), consuming shared primitives unchanged.

UI-010, UI-011, UI-021 and UI-030 may run concurrently under their issue boundaries. UI-011 is the only owner of global theme/token behavior during this tranche.

### Wave 2 — parallel shared primitives

After UI-011 and the current tranche converge on `main`, run UI-012 through UI-017 in parallel using the exact module ownership defined above. Any cross-cutting change to the same primitive, `SemanticWorkspace`, shell structure or global theme must be serialized or split into a separate follow-up.

### Wave 3 — accessibility baseline

Run UI-018 after the Wave 2 shared primitive tranche is integrated so it validates and repairs the converged foundation rather than several incompatible intermediate versions.

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
| Coordination documentation in `docs/engineering/console-ui-work-control.md` | UI-010 / #327 | RELEASED | Shared-foundation audit integrated through PR #332 as `b1bef57`; issue #327 closed completed |
| `src/ui/WyrmgateUiProvider.tsx` and global theme/token behavior | UI-011 / #328 | RESERVED | Becomes ACTIVE when #328 starts; release after merge/close. Other tasks may consume tokens but must not redefine them |
| Residual legacy routing/frame cleanup in `App.tsx` and adjacent obsolete helpers | UI-021 / #329 | RESERVED | Becomes ACTIVE when #329 starts; route contract is not part of this lock |
| Overview page composition | UI-030 / #330 | ACTIVE | #330 agent reported IN_PROGRESS; no shared primitive/theme lock |

## PR and integration checklist

When a task opens a PR, record the PR number in the active ledger and update #312 if it introduces or changes a shared-surface lock. Before marking a row `READY_FOR_INTEGRATION` or later, verify that the branch still has the recorded ancestry, its scope did not absorb another task's concern, canonical IAM/API/security semantics remain unchanged unless separately approved, and task-specific tests/checks are green.

After merge, set `MERGED`, complete required verification, then set `VERIFIED` and finally `DONE`. If a merged change requires follow-up correction before verification, keep it out of `DONE` and record the follow-up dependency.

## Authority boundary

This ledger is operational coordination documentation only. In a conflict, use the project authority order: accepted ADRs and current formal requirements/specifications first, then current repository architecture/domain/interface documentation, then implementation/tests. Issue #312 controls UI concurrency mechanics but does not override those sources.
