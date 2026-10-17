# Console design-token baseline

`designTokens.ts` and `WyrmgateUiProvider.tsx` define the shared visual baseline for the Wyrmgate Console. Capability pages and project-owned primitives consume this baseline rather than introducing local replacements for common color, typography, spacing, density, shape, elevation or responsive decisions.

The broader composition rules are documented in `docs/engineering/console-enterprise-ui-standard.md`.

## Color roles

The palette deliberately separates six roles:

- **Product** — the Wyrmgate blue accent used for primary actions, focus, selection and product emphasis.
- **Neutral** — canvas, surfaces, borders and text hierarchy.
- **Navigation** — dark application chrome with explicit hover/active/text roles.
- **Semantic** — success, warning, danger and informational feedback. These are not aliases for the product accent.
- **Lifecycle** — compact resource-state colors such as active, pending, suspended, inactive and decommissioned. Lifecycle meaning remains distinct from generic feedback severity and from product emphasis.
- **Visualization** — restrained categorical colors and neutral tracks for charts whose categories have no success/warning/danger meaning. These colors must always be accompanied by labels or values so chart interpretation does not depend on color alone.

A component chooses color by meaning, not visual similarity. Capability code should not introduce raw product/status colors when a shared semantic role already exists. Arbitrary chart categories must not borrow lifecycle or feedback colors merely to look distinct.

## Enterprise density

The baseline targets dense administrative work without shrinking interactive targets into hard-to-use controls:

- 8 px spacing unit with 4 px compact increments;
- 36 px normal controls and 32 px compact desktop actions;
- 44 px effective mobile action/control target baseline;
- 40 px administrative table-row baseline;
- 8 px block / 12 px inline table-cell padding;
- compact tabs, state indicators and supporting text while preserving readable body copy.

Page-level composition uses the shared field, section, workspace and responsive gutter vocabulary rather than accumulating unrelated one-off values.

## Typography

The default UI text size is 14 px. The preferred weight vocabulary is 400, 600 and 700.

- page headings: 24 px / 700;
- object-detail headings: no more than 28 px / 700;
- section headings: 16 px / 600-700;
- body: 14 px;
- table body: 13 px;
- table headers and metadata: 12 px.

Console pages use restrained administrative hierarchy rather than marketing-scale hero typography.

## Shape

Shape is intentionally restrained:

- 4 px for compact badges;
- 6 px for controls and compact state-like elements;
- 8 px for bounded content surfaces and popovers;
- 10 px for dialogs.

Page-local styling must not introduce unrelated large-card rounding as a substitute for information hierarchy.

## Elevation

Normal surfaces and tables use borders with no decorative shadow. Shadows are reserved for overlays whose elevation communicates interaction layering, such as popovers, drawers and dialogs.

## Shared component defaults

`WyrmgateUiProvider` normalizes common MUI implementation defaults for buttons, form controls, tables, chips, tabs, cards, dialogs, drawers and tooltips. These defaults are presentation infrastructure only. They do not change Wyrmgate IAM semantics, server authorization, route behavior, form contracts, confirmation behavior or capability-owned business state.

`StateIndicator` is the preferred compact dot-and-label treatment when the caller owns a typed state and its presentation tone. `StatusChip` remains available for genuinely token-like categorical metadata; it is not the universal lifecycle component.

`DonutChart` is the shared compact part-to-whole visualization primitive. It consumes the dedicated visualization palette, includes a visible textual legend and values, and does not infer or color-code business state.

The public Wyrmgate primitives remain the supported application-facing boundary. MUI is still an implementation detail behind that boundary.

## Layout and responsive baseline

The shared page widths remain:

- narrow: 52 rem;
- standard: 76 rem;
- wide: 96 rem.

The responsive contract distinguishes desktop, tablet and mobile presentation. Desktop uses the labeled navigation rail and bounded workspace. Intermediate widths reduce chrome without changing information architecture. Mobile moves navigation to a drawer and uses 16 px normal / 12 px narrow gutters while preserving the same semantic operations and authority-relevant information.

The global baseline honors `prefers-reduced-motion: reduce` by suppressing non-essential animation and transition duration. It preserves a 320 px minimum document width so shared responsive work can adapt layout without silently dropping semantic controls or authority-relevant information.
