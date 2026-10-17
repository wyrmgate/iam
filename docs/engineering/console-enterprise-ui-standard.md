# Wyrmgate Console enterprise UI standard

## Status and scope

This document defines the implementation-facing visual and responsive contract for the Wyrmgate IAM Console. It is a presentation contract only. It does not redefine IAM capability ownership, domain semantics, server authorization, API contracts, Tenant resolution, governed actor resolution, workflow meaning or provider state.

The Console remains a semantic control plane over the accepted IAM contracts. Narrow viewports change presentation, not product meaning: authoritative state, observation, evidence, projection, governance decisions, technical fulfillment and provider observations remain distinct wherever those distinctions are material to the operator.

## Design objective

The Wyrmgate Console is an enterprise operator workstation. The target qualities are:

- precise rather than decorative;
- information-dense without becoming cramped;
- calm, neutral and predictable for long administrative sessions;
- semantic in its use of status, warning and destructive color;
- responsive without removing authority-relevant information or operations;
- consistent across Identity, Catalog, Access, Governance, Credential, Integration, Administration and Audit workspaces.

The Console must avoid page-local visual systems that produce competing color, typography, spacing, density or shell behavior. `src/ui` is the application-facing presentation boundary; Material UI remains an implementation dependency behind that boundary.

## Theme roles

### Product accent

The Wyrmgate product accent is the shared blue used for primary actions, selection, focus and product emphasis. Product blue is not a generic status color.

### Neutral surfaces

The normal workspace uses a light neutral canvas, white primary surfaces, subtle secondary surfaces, low-contrast borders and restrained elevation. Ordinary content regions should normally be separated by spacing, borders and headings rather than large shadows.

### Semantic feedback

Success, informational, warning and danger colors describe feedback or operational severity. They are not aliases for lifecycle state, governance risk, provenance or provider fulfillment.

### Domain state

Typed state remains caller-owned. A shared presentation primitive may render a caller-selected tone, but it must not infer IAM business meaning from arbitrary strings. New domain states must not silently borrow unrelated product or feedback colors.

## Typography

The baseline body size is 14 px. The preferred weight vocabulary is 400, 600 and 700.

- page title: 24 px / 700;
- object-detail title: no more than 28 px / 700;
- section title: 16 px / 600-700;
- body: 14 px / 400;
- table body: 13 px;
- table header and metadata: 12 px;
- overline/category label: approximately 11 px / 700.

Console pages must not use marketing-scale hero typography as a substitute for information hierarchy.

## Spacing and shape

Spacing follows an 8 px base with 4 px compact increments. Shared values cover control gaps, field gaps, section gaps, workspace gaps and responsive page gutters.

Rounding is deliberately restrained:

- compact badge: 4 px;
- controls: 6 px;
- normal surfaces/popovers: 8 px;
- dialogs: 10 px.

Large decorative rounding, glass effects and arbitrary page-local shadows are outside the standard.

## Elevation

Normal tables, forms and content surfaces use borders rather than floating-card elevation. Shadows are reserved for overlays such as menus, popovers, drawers and dialogs where physical elevation communicates interaction layering.

## Responsive contract

### Desktop

Desktop uses the labeled navigation rail, contextual top bar and bounded workspace content. Expanded navigation remains the default for discoverability. Wide data workspaces use the shared 96 rem content bound; standard detail pages use 76 rem; focused forms use 52 rem.

### Tablet

Intermediate widths retain desktop information architecture while reducing chrome and layout density. Navigation may narrow, secondary actor/context copy may collapse, two-column forms may become one column, and workspace gutters reduce. Tablet does not become a simplified mobile product.

### Mobile

At narrow widths the primary navigation becomes a drawer. The top bar keeps only the controls required to identify the current workspace and access navigation/actor actions. Tenant, runtime and secondary context may move into the drawer or actor surface.

Mobile uses a minimum effective interactive target of 44 px. Complex data must not lose semantic columns merely to fit the viewport: use horizontal table overflow, prioritized compact views, or drill-in detail where appropriate.

A mobile layout must not hide an IAM distinction or operation solely because it is inconvenient to render.

## Canonical page patterns

New pages should map to one of five primary patterns before introducing a custom composition:

1. **Collection** — search/filter toolbar, bounded table/list, pagination and primary create action.
2. **Object 360** — object header, lifecycle/context metadata, explicit actions, tabs and structured detail sections.
3. **Work queue** — prioritized assigned work, evidence/context, decision actions and durable state.
4. **Configuration** — bounded forms, explicit validation, revision-aware save/activation semantics.
5. **Evidence / operations** — dense immutable or operational records, time/state context and bounded investigation.

## Collection standard

A collection page normally consists of:

1. `PageHeader` with title, concise description and at most one visually dominant primary action;
2. a search/filter `Surface` or `PageToolbar`;
3. explicit loading/error/empty state;
4. `DataTable` for desktop/tablet where the data is genuinely tabular;
5. compact mobile records only when the information can be preserved without semantic loss;
6. deterministic server-backed pagination or load-more behavior.

Decorative metric cards are not the default collection header.

## Detail standard

Object detail pages use a restrained object title, typed lifecycle/context indicators, explicit actions and semantic tabs. Ordinary scalar properties belong in definition-style sections rather than individual cards. Evidence, provenance, fulfillment and destructive operations use dedicated bounded sections when their meaning warrants a boundary.

The Identity 360 reference applies this pattern without creating a generic cross-capability aggregate: Identity-owned state and correction operations remain authoritative Identity behavior, while Principal, Access, Governance and Audit information is read through its existing capability-owned contract and remains semantically distinct in the presentation.

## State presentation

`StatusChip` is not the universal state component. Prefer `StateIndicator` for compact dot-and-label presentation where the caller already knows the semantic tone. Pills/chips are appropriate for discrete tokens such as filters, classifications or compact categorical metadata.

The caller remains responsible for distinguishing examples such as:

- Identity lifecycle;
- AccessAssignment lifecycle;
- EffectiveAccess projection state;
- provisioning fulfillment;
- provider observation;
- governance risk;
- audit/evidence provenance.

## Charts and visualization

Charts are part of the enterprise operator-workbench vocabulary when a bounded, governed dataset becomes materially easier to scan visually. They are not decoration and must not manufacture a distribution, trend, risk score or aggregation that the owning capability does not expose.

- Use shared `src/ui` visualization primitives rather than page-local chart systems.
- Use part-to-whole charts only when the categories form a meaningful total and the cardinality is small enough to remain legible. The shared `DonutChart` is the baseline for compact categorical composition.
- Categorical chart colors use the dedicated non-semantic visualization palette. Success, warning, danger, lifecycle and governance-risk colors must not be reused merely to distinguish arbitrary categories.
- Every chart must retain color-independent meaning through an accessible chart label plus visible textual legend/data values. Do not make hover the only way to read a value.
- Charts should avoid non-essential animation; global reduced-motion behavior remains authoritative.
- Responsive presentation may stack chart and legend, but it must not omit categories or values.
- If the exact aggregation is unavailable or unauthorized, render an explicit state instead of an illustrative or inferred chart.

The IAM Operations provisioning outcome chart and the Tenant Overview Identity-type/Application-origin distributions are reference uses: each visualizes an existing bounded server projection while preserving the owning capability's semantics.

## Tables

The shared administrative baseline is a 40 px row with approximately 8 px block and 12 px inline cell padding. Tables keep real table semantics, accessible headers/captions, bounded horizontal overflow and right-aligned row actions.

Mobile conversion to cards is allowed only when the primary information survives. Audit, provider-operation, grant, evidence and other high-information tables should normally remain tabular or use a deliberate compact/detail pattern.

## Forms

Focused forms should remain bounded rather than stretching across large workspaces. Labels stay visible above controls; placeholders do not replace labels. Required, help, error and read-only semantics use shared form primitives. Mobile forms collapse to one column.

## Actions and destructive operations

Use one visually dominant primary action per local context. Supporting operations use outlined or text treatment. Destructive actions are not permanently saturated in danger color; danger emphasis appears when entering or confirming the destructive operation.

Materially harmful operations continue to use explicit confirmation with the domain-specific consequence stated in plain language. Revision, idempotency, authorization and evaluator requirements remain server/API concerns and must not be weakened by presentation changes.

## Feedback and errors

The Console preserves semantic API distinctions. Authentication, authorization, not-found, conflict, stale revision and mandatory-evaluator-unavailable states must not collapse into a generic error message when the server contract distinguishes them.

Use:

- toast-like transient feedback only for short non-critical confirmation;
- inline state for local form/surface problems;
- banners for system-level or interpretation-changing conditions.

## Motion and accessibility

Non-essential motion is minimal and honors `prefers-reduced-motion`. Keyboard focus remains visibly distinct. Shared components must preserve accessible labels, table semantics, dialog relationships, logical focus order and color-independent meaning. The design target is WCAG 2.2 AA.

## Prohibited presentation patterns

The shared Console standard does not use the following as normal application patterns:

- glassmorphism or translucent card stacks;
- decorative gradients across ordinary workspaces;
- hero-scale page titles;
- arbitrary page-local product colors;
- page-local shell offsets or navigation overrides;
- cards for individual scalar properties;
- decorative KPI-card grids on every collection page;
- large shadows on ordinary content surfaces;
- Unicode characters as long-term production iconography;
- responsive layouts that silently remove IAM semantics or operations;
- browser-derived administrative authority.

## Reference implementation

The Identity collection is the collection reference migration onto this standard. It uses shared `Page`, `PageHeader`, `PageToolbar`, `Surface`, `DataTable`, form, state and modal primitives; `StateIndicator` renders lifecycle state without turning every lifecycle into a filled chip. Because the collection is genuinely tabular, the same semantic `DataTable` is retained across viewport sizes with bounded horizontal overflow; mobile does not introduce a second card-based representation or delete stable ID, type, lifecycle or updated-at context.

Identity 360/detail is the Object 360 reference migration. The object header leads directly into the six semantic views: Overview, Governed profile, Principals, Access, Governance and Evidence. Overview owns the current Identity summary plus lifecycle/access-eligibility context, current control-plane context and lifecycle operations; it does not duplicate the tab navigation with a second investigation menu. Edit metadata and advanced correction remain explicit object-level actions. The presentation does not merge capability ownership: AccessAssignment remains authoritative Access intent, EffectiveAccess remains a projection, Governance evidence remains Governance-owned, direct AuditRecord evidence remains Audit-owned, and provider observation remains separate.

The Principal journey applies the same flow-first collection/detail hierarchy without inventing a new route or aggregate. `/principals` enters through the bounded authoritative inventory; registration and exact-ID lookup are deliberate modal actions rather than permanent forms. Selecting a Principal opens a focused technical-account detail state with definition-style metadata, typed lifecycle presentation and a separate governed-Identity relationship section. One-way correlation remains revision-guarded and consequence-confirmed, while Credentials, desired state, provider observation and fulfillment remain outside Principal authority.

Other capability workspaces should consume the shared visual contract without changing their domain or API semantics.
