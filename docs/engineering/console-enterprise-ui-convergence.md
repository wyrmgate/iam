# Console Enterprise UI convergence

This document is an implementation/migration note for Enterprise UI convergence. It does **not** create a second visual standard. The authoritative living presentation contract remains `docs/engineering/console-enterprise-ui-standard.md`, and the operator-product target remains `docs/engineering/console-operator-workbench.md`.

## Purpose

UI-022 established Enterprise UI Standard v1 and migrated the Identity collection. UI-023 migrated Identity 360/detail. UI-024 converged the residual authenticated Console before managed-DEV browser acceptance. Issue #426 completes the same presentation convergence for first-party sign-in after the secured first-run setup path landed.

These changes are presentation-only. They do not alter IAM requirements, domain semantics, capability ownership, API contracts, routes, tenant resolution, authorization, persistence, workflows, provider behavior or secret boundaries.

## Presentation ownership

The Console has one application-facing visual vocabulary:

- `src/ui/designTokens.ts` is the single source of named product, neutral, semantic, density, shape, typography and layout values used by the shared UI boundary.
- `src/ui/WyrmgateUiProvider.tsx` applies those values to the current component-library implementation.
- `src/ui/cssVariables.ts` derives the `--wg-*` CSS roles directly from `designTokens.ts`; it contains no duplicated palette literals.
- `src/enterprise-console.css` consumes those roles to bridge purpose-built authenticated operator workbenches and shell-era CSS where those screens cannot yet be expressed entirely through `src/ui` primitives. It does not define its own token values.
- capability-local CSS may own workflow geometry only: split panes, charts, list/card rearrangement, sticky detail placement and narrow-screen composition. It must not define a competing product palette, type scale, surface system, status system, control shape or focus treatment.
- first-party authentication presentation consumes the same token vocabulary directly through `auth-login.css`; authentication may retain its own composition because it is outside the authenticated shell, but it must not define a second palette, decorative theme or responsive system.

`enterprise-console.css` is therefore a compatibility/convergence layer, not a new domain or component abstraction. New capability work should prefer `src/ui` primitives and `designTokens.ts`; it should not add new selectors to the bridge unless an existing purpose-built workflow genuinely cannot be expressed through those primitives without losing semantics.

## Converged purpose-built workbenches

### Access

The Access workbench keeps its accepted Assignments-first workflow, EffectiveAccess explanation view and typed lifecycle-policy editor. `access-golden.css` owns layout/responsive mechanics and consumes shared `--wg-*` visual roles. The temporary `access-enterprise-alignment.css` override has been retired. `access-policy-editor.css` is constrained to policy-editor geometry and shared-token consumption.

This changes no AccessAssignment lifecycle, temporal-validity, provenance, principal-constraint, revision, idempotency, EffectiveAccess or Governance/Integration boundary.

### Overview / IAM operations

`iam-operations.css` no longer carries a private `--iam-*` palette or decorative ordinary-workspace gradients/shadows. It retains operational geometry, bounded chart mechanics, responsive work-entry layout and reduced-motion behavior while shared Enterprise UI roles own surfaces, tables, controls, state tones and typography.

No metric is fabricated and no server-data semantics change.

### Requests & Approvals

`requests-approvals.css` no longer carries a private `--ra-*` palette or decorative ordinary-workspace gradients/shadows. It retains work-queue geometry, bounded status visualization, side-panel layout and responsive table/card rearrangement while shared Enterprise UI roles own surfaces, tables, controls, states and typography.

Request, approval, decision, evidence, optimistic revision and causal-idempotency semantics remain unchanged.

## Shell, authentication and setup

The product shell keeps separate files for separate mechanics rather than collapsing unrelated concerns into one giant stylesheet:

- `shell.css` — shell/navigation/overlay composition;
- `shell-foundation.css` — tablet/mobile geometry;
- `shell-theme.css` — token binding, focus and reduced-motion behavior;
- `shell-session-golden.css` — desktop session-control placement.

Those layers are not independent visual standards: shared visual roles resolve through the Enterprise UI `--wg-*` token aliases. Drawer shadows, scrims and command-palette overlays remain allowed because they communicate overlay hierarchy rather than decorating ordinary content surfaces.

First-party sign-in now follows the same Enterprise UI presentation contract. Its prior standalone navy/cyan palette, decorative gradients, animated control-plane topology, oversized marketing hero, page-local shadow/radius system and separate viewport override are retired. The sign-in screen remains compositionally distinct from the authenticated shell because it precedes a session, but it uses the same neutral surfaces, restrained product accent, typography, shape, focus, reduced-motion and mobile-target roles as the rest of Console.

Authentication semantics do not change: Tenant routing remains server-resolved from the Console route, password visibility is local presentation state only, server-managed session establishment remains authoritative, and no browser-side Tenant, authority, provider or credential semantics are introduced.

Fresh-install setup remains a separate pre-authentication state projected by `SignInGate` when installation is not `SEALED`. ADR-0045 installation-scoped proof, setup-specific CSRF, server-owned routing, ephemeral password handling, the durable ADR-0044 checkpoint state machine and irreversible seal remain unchanged. Setup already consumes the shared `--wg-*` roles and therefore does not create another visual system.

## Regression boundary

`apps/console/tests/enterprise-convergence.test.mjs` protects the authenticated convergence boundary by asserting that:

- the authenticated Console loads the shared enterprise presentation layer last;
- `--wg-*` variable values are derived from `designTokens.ts` rather than duplicated in CSS;
- Access, IAM Operations and Requests/Approvals do not reintroduce private palettes or ordinary-workspace gradients;
- the retired Access alignment shim stays absent;
- the shell resolves its shared visual roles through Enterprise UI tokens;
- mobile Access controls retain the 44px enterprise target baseline through the shared contract.

`apps/console/tests/auth-layout.test.mjs` protects the first-party authentication convergence boundary by asserting that:

- sign-in consumes canonical `--wg-*` roles without a private auth palette or hard-coded product colors;
- gradients, backdrop effects, the decorative topology graphic and the standalone viewport override stay retired;
- the composition remains restrained and responsive with visible focus, reduced-motion behavior and the enterprise mobile target;
- only supported username/password authentication controls are exposed;
- server-resolved Tenant/session semantics remain explicit and browser authority is not introduced.

Capability and authentication semantic tests remain authoritative for workflow and security meaning. Presentation tests must never redefine IAM behavior.

## Completion and acceptance

UI-024 completed the authenticated-Console convergence after its exact reviewed PR head passed Console source-contract tests/typecheck/build plus Core CI, Security CI, Container CI and DEV Deployment Contract and was merged to `main`.

Issue #426 completes first-party sign-in convergence only when its exact reviewed PR head passes the same required gates and is merged to `main`. Managed-DEV browser acceptance #394 then remains responsible for final setup/sign-in transition, authenticated desktop/mobile reachability, focus/dialog behavior, responsive overflow and the absence of reachable legacy competing UI.
