# Wyrmgate Console experience model

## Status and scope

This document defines the implementation-facing experience model for the Wyrmgate IAM Console. It is a presentation and interaction contract only. It does not redefine IAM requirements, capability ownership, domain semantics, API contracts, Tenant resolution, governed-actor resolution, workflow meaning, provider state or server authorization.

The Console is an enterprise IAM operator workstation. Its design starts from the operator task and the information required to complete that task safely. Visual tokens, component variants and layout rules are derived from that model; they are not the starting point.

## Design order

Console work follows this dependency order:

1. operator job and outcome;
2. journey and decision points;
3. information architecture;
4. page archetype;
5. interaction and state coverage;
6. responsive behavior;
7. visual hierarchy;
8. semantic tokens and shared primitives;
9. page implementation.

A page-local visual need is not sufficient reason to add a new global token or primitive. Shared presentation exists to encode a repeated operator need.

## Primary operator journeys

### Investigate a governed Identity

**Entry:** Identity collection, direct deep link, or another capability-owned reference.

**Questions:**

- Who is the governed subject?
- What is the current Identity lifecycle and access eligibility context?
- Which canonical profile values are readable under current authority?
- Which Principals represent the Identity technically?
- What authoritative AccessAssignment intent exists?
- What EffectiveAccess projection exists now?
- Which Governance records explain current or pending decisions?
- Which Audit evidence explains material changes?

**Result:** the operator can understand the subject without collapsing Identity, Principal, Access, Governance and Audit ownership into one generic object.

### Administer authoritative access intent

**Entry:** Access workspace or an Identity detail access view.

**Questions:**

- What authoritative assignment exists?
- What lifecycle/validity state applies?
- What effective projection is derived from that intent?
- Is an operation an increase, reduction or read-only observation?
- What governance or revision conditions affect the operation?

**Result:** the operator can distinguish business access intent from effective projection and provider realization.

### Decide assigned Governance work

**Entry:** Requests/Approvals or Reviews queue.

**Questions:**

- Why is this decision assigned to me?
- What immutable evidence/context was used to create the decision?
- What current state is relevant before deciding?
- What consequence does KEEP/REVOKE/approve/reject have?

**Result:** the operator can make an assigned decision without generic administrative authority being implied by the UI.

### Investigate fulfillment or reconciliation

**Entry:** Integration operations.

**Questions:**

- What desired/technical work was attempted?
- What is the durable job/task/run state?
- What normalized provider-operation evidence exists?
- Is the record authoritative state, process state or provider observation?
- What requires retry or manual remediation?

**Result:** the operator can investigate technical realization without treating provider observation as authoritative IAM state.

### Administer the current Tenant

**Entry:** current-Tenant administration surface.

**Questions:**

- Which server-resolved Tenant context am I operating in?
- What canonical Tenant metadata is known?
- Is owner/admin continuity intact?
- What governed operational attention is visible from current projections?

**Result:** the operator understands the current isolation context without browser-side Tenant switching or invented lifecycle/subscription concepts.

### Inspect deployment-scoped Platform resources

**Entry:** Platform inventory/detail surfaces available to deployment authority.

**Questions:**

- Which deployment-owned resource am I inspecting?
- What is canonical Platform-owned metadata?
- How is this inspection different from the authenticated current-Tenant context?

**Result:** the operator can inspect deployment resources without implying Tenant authority inheritance or changing the current Tenant context.

## Information architecture

The global shell is the primary product orientation mechanism. It groups real Wyrmgate destinations by operator work area without redefining canonical capability ownership.

Pages may add local navigation only when it helps complete the current journey, such as semantic detail tabs or a small set of related existing destinations. Pages must not reproduce the whole application navigation inside the workspace.

The shell owns:

- product-level navigation;
- current route/work-area orientation;
- server-derived Tenant context;
- governed actor/session context;
- global navigation acceleration such as command navigation.

The workspace owns:

- the current object, collection, queue, configuration or investigation task;
- task-local filters and actions;
- task-local state and evidence;
- semantic detail navigation.

## Canonical page archetypes

Every normal Console page should map primarily to one archetype before introducing a custom composition.

### 1. Collection / inventory

Use for discoverable sets of semantic resources.

Structure:

1. restrained page header;
2. search/filter/action toolbar;
3. explicit loading/error/empty state;
4. dense table or information-preserving compact records;
5. deterministic server-backed continuation.

Examples: Identities, Principals, Applications, Platform Tenants.

### 2. Object 360 / resource detail

Use when the operator needs stable context plus several semantic views of one resource.

Structure:

1. object identity and typed state/context;
2. explicit local actions;
3. semantic tabs or sections;
4. definition-style scalar information;
5. related capability-owned views kept visibly distinct;
6. evidence/provenance/operational detail where relevant.

Examples: Identity 360, Platform Tenant detail.

### 3. Work queue / decision workspace

Use for assigned or prioritized work that leads to a decision.

Structure:

1. queue/filter context;
2. prioritized work list;
3. selected-item evidence/context;
4. deliberate decision actions;
5. durable completion/error feedback.

Examples: Approval inbox, Review inbox.

### 4. Configuration / policy editor

Use for bounded revision-aware administration.

Structure:

1. resource/configuration context;
2. bounded form sections;
3. validation and explanatory constraints;
4. impact/state information;
5. explicit save/activate/retire semantics.

Examples: policy version administration, Integration administration.

### 5. Evidence / operations investigation

Use for immutable evidence or durable technical process state.

Structure:

1. investigation scope/filter;
2. dense chronological or process-oriented records;
3. durable identifiers and time context;
4. explicit distinction between authoritative state, process state, evidence and observation;
5. bounded drill-down rather than decorative summaries.

Examples: Audit, provisioning attempts, reconciliation runs.

### 6. Context / administration overview

Use sparingly when an operator needs a bounded orientation surface rather than a resource inventory or decision queue.

Structure:

1. context identity;
2. compact governed summary;
3. canonical metadata;
4. continuity/attention sections;
5. exact bounded distributions only when the current server projection provides them;
6. recent activity or source information.

Examples: current Tenant administration.

## Interaction hierarchy

Each local context should normally expose one visually dominant primary action. Supporting actions use secondary or text treatment. Destructive operations gain danger emphasis when entering or confirming the destructive path, not as permanent page decoration.

Tables are the default for genuinely tabular administrative data. Cards are not a default substitute for rows on mobile. Use horizontal overflow, prioritized compact records, or drill-in detail only when semantic information remains intact.

Scalar metadata uses definition-style presentation rather than one card per field.

Charts are allowed only when a current bounded governed dataset is materially easier to scan visually. They must never invent trend, health, risk or distribution semantics.

## State coverage

Primary journeys must account for applicable:

- initial/loading;
- empty;
- authentication required;
- authorization denied;
- not found;
- stale revision/conflict;
- mandatory evaluator unavailable;
- recoverable request error;
- submitting/pending mutation;
- success/updated state.

The Console preserves server distinctions rather than flattening them into one generic error surface.

## Responsive model

### Wide desktop

- stable labeled navigation;
- bounded workspace width matched to page archetype;
- dense tabular scanning;
- actions remain close to the object/task they affect;
- avoid unused decorative whitespace.

### Tablet

- preserve desktop information architecture;
- reduce gutters/chrome before removing content;
- collapse multi-column metadata/forms where necessary;
- retain semantic tabs and task-local actions.

### Mobile

- global navigation becomes an overlay/drawer;
- minimum effective interactive target is 44 px;
- page headers and actions stack deliberately;
- tables may scroll horizontally when column meaning would otherwise be lost;
- technical IDs wrap safely;
- no IAM distinction, authority signal or operation disappears solely for visual convenience.

## Visual direction derived from the journeys

The Console should feel like a quiet control room:

- neutral cool canvas with high-legibility white work surfaces;
- dark navigation for stable product orientation, without ornamental gradients;
- restrained blue product accent for selection, focus and primary action only;
- semantic success/warning/danger/info reserved for meaning;
- typography optimized for long administrative sessions rather than marketing emphasis;
- compact but breathable density;
- hierarchy produced by spacing, type weight, alignment and subtle borders before shadows;
- overlays are the primary place where meaningful elevation is used.

## Token policy

The semantic token source must describe roles used by the experience model: canvas, surfaces, text hierarchy, borders, product action/focus, navigation, semantic feedback, lifecycle presentation, visualization, density, shape, page bounds and overlay elevation.

Raw values should not be copied into shell/page styles when an existing semantic role expresses the intent. New roles require a repeated experience need, not visual preference on a single page.

## Reference journey for foundation changes

Identity collection -> Identity 360 is the first reference journey for base redesign work because it exercises:

- collection scanning and filtering;
- page headers/toolbars;
- dense tables;
- object identity and state;
- semantic tabs;
- definition-style metadata;
- capability-distinct related information;
- confirmations and revision-aware operations;
- responsive overflow and mobile action handling.

Foundation changes must preserve the existing Identity API, authority, ETag/idempotency and cross-capability semantic boundaries.