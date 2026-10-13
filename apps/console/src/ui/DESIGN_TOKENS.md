# Console design-token baseline

`designTokens.ts` and `WyrmgateUiProvider.tsx` define the shared visual baseline for the Wyrmgate Console. Capability pages and project-owned primitives should consume this baseline rather than introducing local replacements for common color, typography, spacing, density, or shape decisions.

## Color roles

The palette deliberately separates four roles:

- **Product** — the Wyrmgate blue accent used for primary actions, focus and product emphasis.
- **Neutral** — canvas, surfaces, borders and text hierarchy.
- **Semantic** — success, warning, danger and informational feedback. These are not aliases for the product accent.
- **Lifecycle** — compact resource-state colors such as active, pending, suspended and inactive. Lifecycle meaning remains distinct from generic feedback severity and from product emphasis.

A component should choose a color by meaning, not by visual similarity. New lifecycle states should extend the lifecycle vocabulary rather than borrowing product accent colors.

## Enterprise density

The baseline targets dense administrative work without shrinking interactive targets into hard-to-use controls:

- 8 px spacing unit with 4 px compact increments;
- 36 px normal controls and 32 px compact action controls;
- 40 px administrative table-row baseline;
- 8 px block / 12 px inline table-cell padding;
- compact tabs, chips and supporting text while preserving readable body copy.

Page-level composition should use the shared spacing vocabulary (`controlGap`, `sectionGap`, `workspaceGap`) rather than accumulating unrelated one-off values.

## Typography

The default UI text size is 14 px. Body and supporting text are intentionally compact; page headings use a restrained hierarchy rather than dashboard-scale display typography. Buttons, labels and table headers use stronger weight to support scanning in administrative workflows.

## Shape

Shape is intentionally restrained:

- 6 px for compact status-like elements;
- 8 px for controls;
- 9 px for bounded content surfaces;
- 12 px for dialogs.

Page-local styling should not introduce unrelated large-card rounding as a substitute for information hierarchy.

## Shared component defaults

`WyrmgateUiProvider` normalizes common MUI implementation defaults for buttons, form controls, tables, chips, tabs, cards, dialogs, drawers and tooltips. These defaults are presentation infrastructure only. They do not change Wyrmgate IAM semantics, server authorization, route behavior, form contracts, confirmation behavior or capability-owned business state.

The public Wyrmgate primitives remain the supported application-facing boundary. MUI is still an implementation detail behind that boundary.

## Motion and responsive baseline

The global baseline honors `prefers-reduced-motion: reduce` by suppressing non-essential animation and transition duration. It also preserves a 320 px minimum document width so shared responsive work can adapt layout without silently dropping semantic controls or authority-relevant information.

Later responsive and accessibility tasks may refine component behavior, but should preserve this semantic and density contract unless a deliberate shared-token change is coordinated.
