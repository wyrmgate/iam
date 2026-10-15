# Console UI implementation boundary

Wyrmgate Console owns its product semantics and visual contract. Material UI (MUI) is currently an implementation library only.

## Rules

- Product/domain-facing Console code should prefer imports from `src/ui` rather than importing `@mui/*` directly.
- `WyrmgateUiProvider` owns the current theme tokens and library-level defaults.
- The local UI boundary may wrap MUI components, normalize props, and expose Wyrmgate-specific primitives as the Console evolves.
- MUI must not define IAM semantics, authorization decisions, tenant resolution, workflow state, or API contracts.
- Server authorization remains final; UI visibility is only an optimization.
- No credential/private material may be persisted in browser storage or exposed through library telemetry.
- A future first-party Wyrmgate component library should replace the implementation behind this boundary incrementally rather than requiring a whole-application rewrite.

The enterprise composition and responsive rules are defined in `docs/engineering/console-enterprise-ui-standard.md`. Capability workspaces should consume that shared contract instead of creating page-local color, typography, density, shell, or responsive systems. `docs/engineering/console-enterprise-ui-convergence.md` records the UI-024 migration boundary and is not a second standard.

## Reusable primitives

Page migrations should build from the local Wyrmgate-facing primitives rather than styling MUI ad hoc in each capability workspace:

- `Page` and `PageHeader` for workspace structure;
- `PageToolbar` for collection filters, search and page-level secondary controls;
- `Surface` for bounded enterprise content regions;
- `StatePanel` for loading, empty, warning and error states;
- `StateIndicator` for compact caller-typed dot-and-label lifecycle/operational presentation;
- `DataTable` plus table primitives for dense administrative collections;
- `Tabs` and `Tab` for typed workspace switching;
- `StatusChip` for token-like categorical metadata where a chip treatment is actually appropriate;
- `ConfirmDialog` for deliberate confirmation of materially harmful operations;
- `Button`, `TextInput`, `SelectInput`, `CheckboxInput`, `Modal` and `SidePanel` for common interaction controls.

`StateIndicator` is presentation-only. The caller owns the IAM meaning and explicitly selects the tone; the primitive must not infer lifecycle, risk, fulfillment, provenance, observation, or governance semantics from arbitrary strings.

`DataTable` keeps the small-table composition model and the stable `./ui` import boundary while standardizing enterprise-density presentation and horizontal overflow. Callers may provide `ariaLabel` or `ariaLabelledBy`, plus an optional visible or visually hidden caption. `DataTableRow` supplies domain-neutral hover/selected affordances for interactive rows, `DataTableActionCell` keeps row actions consistently aligned, and `DataTableStateRow` provides shared empty/loading placement without deciding what a domain-specific state means. Column meaning, formatting, API pagination/filter behavior and semantic actions remain in feature/workspace code.

`Page` has deliberate `narrow`, `standard` and `wide` content-width modes. Shell chrome owns viewport/navigation spacing; capability pages own only their bounded content width and internal composition. Page implementations must not compensate for navigation width with page-specific offsets.

These primitives are a project-owned compatibility layer. Their public shape should stay driven by Wyrmgate Console needs rather than mirroring every MUI option.

`SemanticWorkspace.tsx` is the project-owned renderer for the repetitive semantic resource pattern used by the control-plane pages. It consumes explicit Wyrmgate resource/action specifications and delegates only presentation controls to `src/ui`; it does not turn those specifications into a generic CRUD domain model or weaken owning-capability semantics.

Purpose-built operator workbenches may retain capability-local CSS only for workflow geometry such as split panes, bounded charts, sticky detail placement and responsive rearrangement. Shared product palette, surfaces, typography, table density, control shape, focus and semantic feedback belong to the Enterprise UI contract. `enterprise-console.css` is the temporary authenticated-Console compatibility/convergence layer for those purpose-built workbenches; new capability code should prefer `src/ui` primitives rather than expanding that bridge by default.

## Application shell and navigation

`consoleNavigation.ts` is the shared Console route/navigation registry for shell presentation. Its groups are information architecture only; they do not redefine canonical capability ownership.

The shell uses a labeled, collapsible desktop navigation rail. Expanded navigation is the default for discoverability; collapsed navigation remains an operator preference for dense workspaces and uses flyouts where a group has multiple destinations. Tablet/mobile navigation uses a drawer-style rail and must keep every available destination reachable rather than hiding later groups.

Navigation visibility is a convenience optimization over server-derived context, never authorization. Assigned Governance work is deliberately distinct from administrative configuration: Requests/Approvals and Reviews remain discoverable without generic administrative eligibility because approval/reviewer authority may come from immutable Governance assignment. Governance policy/exception administration remains permission-optimized. Operation-time server authorization is always final.

Tenant display remains server-derived. The shell does not invent browser-controlled tenant switching or derive IAM permission from OAuth/OIDC claims. The command palette is an accelerator over the same navigation registry, not a separate source of routes or authority.

Shell files have distinct implementation responsibilities rather than independent visual authority: `shell.css` owns shell composition, `shell-foundation.css` owns tablet/mobile geometry, `shell-theme.css` binds shell visual roles to Enterprise UI tokens and owns focus/reduced-motion behavior, and `shell-session-golden.css` owns desktop session-control placement. Overlay scrims/shadows remain allowed where they communicate overlay hierarchy.

## Migration status

- First-party sign-in uses the project-owned UI boundary and presents a balanced enterprise split authentication surface with a bounded control-plane topology and compact credential form. The branded illustration/background is an isolated authentication composition and is not a precedent for authenticated operator-workspace visual systems.
- The sign-in page exposes only implemented authentication interactions. Tenant remains server-resolved from the registered Console route, password visibility is local presentation state only, and no inactive language selector, password-recovery, remember-me or upstream-SSO action is fabricated by the browser.
- The sign-in page continues to use only the accepted server-routed first-party login contract. The server owns Tenant routing, session state and operation-time authority; authentication secrets are not persisted in browser storage.
- The application shell has a labeled collapsible desktop navigation rail, complete mobile navigation, command palette, server-derived context and actor controls. Shared shell visual roles now resolve through Enterprise UI tokens while navigation/responsive/session mechanics remain shell-owned.
- Base page primitives expose narrow/standard/wide content widths plus a reusable toolbar so page-specific redesigns land on a stable shell foundation.
- **Overview / IAM Operations** remains a purpose-built task-first operator home, but its local stylesheet now owns only operational layout/chart/responsive mechanics; palette, surfaces, controls, tables, states and typography resolve through the Enterprise UI presentation contract.
- **Identities collection** is the Enterprise UI Standard v1 collection reference migration: shared page/header/toolbar/surface/form/table/state/modal primitives, dense desktop/tablet scanning, semantic compact mobile records, and no page-specific shell overrides.
- **Identity 360/detail** is the Enterprise UI Standard v1 Object 360 reference migration. It uses shared page/header/surface/table/state/form/dialog/tab primitives for the accepted Overview, Governed profile, Principals, Access, Governance and Evidence views. Its ETag/idempotency, lifecycle, merge/split, authorization and capability-owned read semantics are unchanged; the obsolete page-local detail stylesheet and geometry bridge are retired.
- Identity deactivation/decommission, merge and split confirmations use the project-owned `ConfirmDialog` rather than browser-native confirmation UI; the underlying semantic commands, ETag and idempotency contracts are unchanged.
- **Principals** use the semantic workspace for technical-account list/create/read/correlation.
- **Catalog** Applications, Application Targets, Entitlements, Roles and Role Versions use the semantic workspace while preserving their typed resource/action boundaries.
- **Access** remains a dedicated Assignments-first operator workbench with EffectiveAccess explanation and typed lifecycle-policy editing. Its temporary alignment override is retired; capability-local CSS is constrained to workflow geometry and Enterprise UI token consumption. EffectiveAccess is not editable and provider observation remains outside Access authority.
- **Requests & Approvals** remains a purpose-built Governance work queue, but its local stylesheet now owns only queue/detail/responsive geometry; the private palette/decorative card system is retired in favor of shared Enterprise UI roles. Request/approval/evidence/revision/idempotency semantics are unchanged.
- **Governance** reviews, policy versions and exceptions use shared tabs, forms, tables and confirmations while retaining explicit semantic operations.
- **Credentials** use the semantic workspace with a Principal-scoped entry point and the existing secret-reference-only boundary.
- **Integration administration** uses the semantic workspace for Connector/Binding/Worker control-plane surfaces; operational status remains separately data-minimized.
- **Administration** roles/grants/delegations/elevations/break-glass and **My Administrative Authority** use shared Wyrmgate UI primitives.
- **Audit** records, exports, evidence, legal-hold and purge workspaces use shared Wyrmgate UI primitives; destructive evidence operations remain explicit and confirmed.
- Browser-native confirmation has been removed from the shared semantic resource renderer; destructive actions use `ConfirmDialog`.

After UI-024 merge/verification, the remaining release gate is managed-DEV browser acceptance across the converged Console: desktop/mobile reachability, focus/dialog behavior, responsive overflow, and confirmation that no legacy competing UI is reachable. Capability-specific usability refinements may continue afterward, but they must consume the same Enterprise UI presentation contract rather than creating another visual system.
