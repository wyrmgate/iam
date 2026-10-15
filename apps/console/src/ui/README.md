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

The enterprise composition and responsive rules are defined in `docs/engineering/console-enterprise-ui-standard.md`. Capability workspaces should consume that shared contract instead of creating page-local color, typography, density, shell, or responsive systems.

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

## Application shell and navigation

`consoleNavigation.ts` is the shared Console route/navigation registry for shell presentation. Its groups are information architecture only; they do not redefine canonical capability ownership.

The shell uses a labeled, collapsible desktop navigation rail. Expanded navigation is the default for discoverability; collapsed navigation remains an operator preference for dense workspaces and uses flyouts where a group has multiple destinations. Tablet/mobile navigation uses a drawer-style rail and must keep every available destination reachable rather than hiding later groups.

Navigation visibility is a convenience optimization over server-derived context, never authorization. Assigned Governance work is deliberately distinct from administrative configuration: Requests/Approvals and Reviews remain discoverable without generic administrative eligibility because approval/reviewer authority may come from immutable Governance assignment. Governance policy/exception administration remains permission-optimized. Operation-time server authorization is always final.

Tenant display remains server-derived. The shell does not invent browser-controlled tenant switching or derive IAM permission from OAuth/OIDC claims. The command palette is an accelerator over the same navigation registry, not a separate source of routes or authority.

## Migration status

- First-party sign-in uses the project-owned UI boundary and presents a balanced enterprise split authentication surface with a bounded control-plane topology and compact credential form. The topology shows Identity, Tenant, Credential, Access, Governance, Integration and Audit relationships; its animation is presentation-only and honors reduced-motion preferences.
- The sign-in page exposes only implemented authentication interactions. Tenant remains server-resolved from the registered Console route, password visibility is local presentation state only, and no inactive language selector, password-recovery, remember-me or upstream-SSO action is fabricated by the browser.
- The sign-in page continues to use only the accepted server-routed first-party login contract. The server owns Tenant routing, session state and operation-time authority; authentication secrets are not persisted in browser storage.
- The application shell has a labeled collapsible desktop navigation rail, complete mobile navigation, command palette, server-derived context and actor controls. The shared route registry separates assigned work from administrative configuration without changing domain ownership.
- Base page primitives expose narrow/standard/wide content widths plus a reusable toolbar so page-specific redesigns can land on a stable shell foundation.
- **Overview** uses shared page and enterprise surface primitives.
- **Identities collection** is the first Enterprise UI Standard v1 reference migration: shared page/header/toolbar/surface/form/table/state/modal primitives, dense desktop/tablet scanning, semantic compact mobile records, and no page-specific shell overrides.
- **Identity 360/detail** retains the accepted six-tab semantic workbench and semantic mutation/API behavior; its remaining page-local visual bridge is explicitly follow-up presentation debt rather than a competing shared standard.
- Identity deactivation/decommission, merge and split confirmations use the project-owned `ConfirmDialog` rather than browser-native confirmation UI; the underlying semantic commands, ETag and idempotency contracts are unchanged.
- **Principals** use the semantic workspace for technical-account list/create/read/correlation.
- **Catalog** Applications, Application Targets, Entitlements, Roles and Role Versions use the semantic workspace while preserving their typed resource/action boundaries.
- **Access** assignments, EffectiveAccess projection and lifecycle-policy lookup use the semantic workspace without making the projection editable.
- **Governance** requests, approvals, reviews, policy versions and exceptions use shared tabs, forms, tables and confirmations while retaining explicit semantic operations.
- **Credentials** use the semantic workspace with a Principal-scoped entry point and the existing secret-reference-only boundary.
- **Integration administration** uses the semantic workspace for Connector/Binding/Worker control-plane surfaces; operational status remains separately data-minimized.
- **Administration** roles/grants/delegations/elevations/break-glass and **My Administrative Authority** use shared Wyrmgate UI primitives.
- **Audit** records, exports, evidence, legal-hold and purge workspaces use shared Wyrmgate UI primitives; destructive evidence operations remain explicit and confirmed.
- Browser-native confirmation has been removed from the shared semantic resource renderer; destructive actions use `ConfirmDialog`.

Remaining UI work is page-specific refinement, migration of residual page-local visual systems onto the shared enterprise contract, richer validation/accessibility, and removal of residual legacy presentation bridges once their owning workspace is migrated.
