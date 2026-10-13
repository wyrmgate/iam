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
- `Button`, `TextInput`, `Modal` and `SidePanel` for common interaction controls.

These primitives are a project-owned compatibility layer. Their public shape should stay driven by Wyrmgate Console needs rather than mirroring every MUI option.

## Migration status

- First-party sign-in uses the project-owned UI boundary.
- **My Administrative Authority** now uses shared page, state, surface and data-table primitives.
- **Integration operational status** (provisioning jobs/tasks/attempts and reconciliation runs) now uses shared page, tabs, lookup, state and data-table primitives.
- The remaining semantic workspaces continue to retain their existing CSS classes while they are migrated page-by-page.

Future tranches should preserve the same API, authorization and IAM semantic boundaries while moving Identity, Access, Governance, Catalog, Credential, Integration administration, Administration workflows and Audit onto these reusable primitives.
