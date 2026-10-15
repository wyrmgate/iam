# Console Enterprise UI convergence

This document is an implementation/migration note for UI-024 / issue #422. It does **not** create a second visual standard. The authoritative living presentation contract remains `docs/engineering/console-enterprise-ui-standard.md`, and the operator-product target remains `docs/engineering/console-operator-workbench.md`.

## Purpose

UI-022 established Enterprise UI Standard v1 and migrated the Identity collection. UI-023 migrated Identity 360/detail. UI-024 finishes the residual authenticated-Console convergence before managed-DEV browser acceptance in #394.

The change is presentation-only. It does not alter IAM requirements, domain semantics, capability ownership, API contracts, routes, tenant resolution, authorization, persistence, workflows, provider behavior or secret boundaries.

## Presentation ownership

The authenticated Console now has one application-facing visual vocabulary:

- `src/ui/designTokens.ts` is the single source of named product, neutral, semantic, density, shape, typography and layout values used by the shared UI boundary.
- `src/ui/WyrmgateUiProvider.tsx` applies those values to the current component-library implementation.
- `src/ui/cssVariables.ts` derives the `--wg-*` CSS roles directly from `designTokens.ts`; it contains no duplicated palette literals.
- `src/enterprise-console.css` consumes those roles to bridge purpose-built operator workbenches and shell-era CSS where those screens cannot yet be expressed entirely through `src/ui` primitives. It does not define its own token values.
- capability-local CSS may own workflow geometry only: split panes, charts, list/card rearrangement, sticky detail placement and narrow-screen composition. It must not define a competing product palette, type scale, surface system, status system, control shape or focus treatment.

`enterprise-console.css` is therefore a compatibility/convergence layer, not a new domain or component abstraction. New capability work should prefer `src/ui` primitives and `designTokens.ts`; it should not add new selectors to the bridge unless an existing purpose-built workflow genuinely cannot be expressed through those primitives without losing semantics.

## Converged purpose-built workbenches

### Access

The Access workbench keeps its accepted Assignments-first workflow, EffectiveAccess explanation view and typed lifecycle-policy editor. `access-golden.css` now owns layout/responsive mechanics and consumes shared `--wg-*` visual roles. The temporary `access-enterprise-alignment.css` override has been retired. `access-policy-editor.css` is constrained to policy-editor geometry and shared-token consumption.

This changes no AccessAssignment lifecycle, temporal-validity, provenance, principal-constraint, revision, idempotency, EffectiveAccess or Governance/Integration boundary.

### Overview / IAM operations

`iam-operations.css` no longer carries a private `--iam-*` palette or decorative ordinary-workspace gradients/shadows. It retains operational geometry, bounded chart mechanics, responsive work-entry layout and reduced-motion behavior while shared Enterprise UI roles own surfaces, tables, controls, state tones and typography.

No metric is fabricated and no server-data semantics change.

### Requests & Approvals

`requests-approvals.css` no longer carries a private `--ra-*` palette or decorative ordinary-workspace gradients/shadows. It retains work-queue geometry, bounded status visualization, side-panel layout and responsive table/card rearrangement while shared Enterprise UI roles own surfaces, tables, controls, states and typography.

Request, approval, decision, evidence, optimistic revision and causal-idempotency semantics remain unchanged.

## Shell and authentication

The product shell keeps separate files for separate mechanics rather than collapsing unrelated concerns into one giant stylesheet:

- `shell.css` — shell/navigation/overlay composition;
- `shell-foundation.css` — tablet/mobile geometry;
- `shell-theme.css` — token binding, focus and reduced-motion behavior;
- `shell-session-golden.css` — desktop session-control placement.

Those layers are not independent visual standards: shared visual roles resolve through the Enterprise UI `--wg-*` token aliases. Drawer shadows, scrims and command-palette overlays remain allowed because they communicate overlay hierarchy rather than decorating ordinary content surfaces.

The first-party sign-in screen remains an intentionally isolated branded authentication composition. Its control-plane illustration and branded background are not a precedent for authenticated operator workspaces. Authentication controls must still preserve the shared accessibility, control-density and security boundaries, and the sign-in surface must not introduce browser-side tenant, authority or credential semantics.

## Regression boundary

`apps/console/tests/enterprise-convergence.test.mjs` protects the convergence boundary by asserting that:

- the authenticated Console loads the shared enterprise presentation layer last;
- `--wg-*` variable values are derived from `designTokens.ts` rather than duplicated in CSS;
- Access, IAM Operations and Requests/Approvals do not reintroduce private palettes or ordinary-workspace gradients;
- the retired Access alignment shim stays absent;
- the shell resolves its shared visual roles through Enterprise UI tokens;
- mobile Access controls retain the 44px enterprise target baseline through the shared contract.

Capability semantic tests remain authoritative for workflow meaning. These presentation tests must never be used to redefine IAM behavior.

## Completion and acceptance

UI-024 is complete only when its exact reviewed PR head passes Console source-contract tests/typecheck/build plus Core CI, Security CI, Container CI and DEV Deployment Contract, is merged to `main`, and the shared-presentation lock is released.

Managed-DEV browser acceptance #394 follows that merge and remains responsible for final desktop/mobile reachability, focus/dialog behavior, responsive overflow and the absence of reachable legacy competing UI.
