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

## Reusable primitives

Page migrations should build from the local Wyrmgate-facing primitives rather than styling MUI ad hoc in each capability workspace:

- `Page` and `PageHeader` for workspace structure;
- `Surface` for bounded enterprise content regions;
- `StatePanel` for loading, empty, warning and error states;
- `DataTable` plus table primitives for dense administrative collections;
- `Tabs` and `Tab` for typed workspace switching;
- `StatusChip` for compact state presentation;
- `ConfirmDialog` for deliberate confirmation of materially harmful operations;
- `Button`, `TextInput`, `SelectInput`, `CheckboxInput`, `Modal` and `SidePanel` for common interaction controls.

These primitives are a project-owned compatibility layer. Their public shape should stay driven by Wyrmgate Console needs rather than mirroring every MUI option.

`SemanticWorkspace.tsx` is the project-owned renderer for the repetitive semantic resource pattern used by the control-plane pages. It consumes explicit Wyrmgate resource/action specifications and delegates only presentation controls to `src/ui`; it does not turn those specifications into a generic CRUD domain model or weaken owning-capability semantics.

## Migration status

- First-party sign-in uses the project-owned UI boundary.
- **Overview** uses shared page and enterprise surface primitives.
- **Identities** list/create/detail, canonical-value disclosure and lifecycle/correction actions use shared page, surface, state, table, status and confirmation primitives.
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

Remaining UI work is primarily shell/common-control consolidation, page-specific refinement, richer validation/accessibility, and replacement of residual legacy CSS where it no longer contributes semantic layout.
