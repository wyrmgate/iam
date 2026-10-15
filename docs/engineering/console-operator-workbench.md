# Console operator-workbench target

## Purpose

This document defines the implementation-facing product and interaction target for the Wyrmgate Console. It exists to prevent UI work from drifting into generic CRUD, API-inspector, or dashboard-card patterns that are technically functional but poor for enterprise IAM operators.

This is a **Console design and implementation target**, not a new IAM authority source. It does not redefine domain semantics, capability ownership, public APIs, authorization, workflow state, persistence, or provider behavior. Those remain governed by the formal specifications, accepted ADRs, and current architecture/domain/API/security documentation.

## Product statement

The Wyrmgate Console is an **enterprise IAM operator workbench**.

It should help an authorized operator answer four questions quickly:

1. **Where am I and what context am I operating in?**
2. **What work or governed resource needs attention?**
3. **What is the authoritative state, and what is only evidence, observation, projection, or fulfillment state?**
4. **What will happen if I perform this operation?**

The Console is not an ORM browser, generic REST client, provider console, log viewer, or browser-side authorization engine.

## Primary operator jobs

The product navigation and page hierarchy should optimize for real work rather than implementation structure.

### Work and decisions

- submit and inspect access requests;
- act on approval work;
- complete access reviews;
- understand review remediation progress;
- inspect typed policy and exception state.

### Governed subjects and technical accounts

- find and inspect an Identity as the governed subject;
- understand lifecycle and governed canonical/effective values;
- inspect or register a Principal as a technical account;
- correlate a Principal to an Identity explicitly;
- preserve the distinction between Identity, Principal, Credential, and provider observation.

### Access and catalog

- understand what access IAM intends through AccessAssignment;
- understand what access is currently effective through the read-only EffectiveAccess projection;
- navigate Application -> ApplicationTarget -> Entitlement relationships;
- understand Business Role vs Application Role and immutable RoleVersion composition;
- perform lifecycle operations with consequence context.

### Integration operations

- administer ConnectorInstance, Binding, and worker registration/status;
- inspect provisioning and reconciliation operational state without treating it as governance authority;
- keep provider-native observation and connector execution detail behind the accepted Integration boundary.

### Administrative authority

- understand AdministrativeRole, Grant, Delegation, Elevation, and BreakGlass as distinct concepts;
- understand typed scope and temporal validity;
- perform high-risk authority operations with explicit consequence context;
- treat current-authority projection as navigation/context only; operation-time server authorization remains final.

### Audit and evidence

- inspect immutable AuditRecord evidence;
- distinguish EvidenceSnapshot, export artifact, archive state, legal hold, and purge process;
- keep destructive evidence lifecycle operations deliberate and visibly distinct from ordinary resource actions.

## Information architecture

The stable Console hierarchy is:

- **Overview** — operator home and work entry points, with runtime/authority context secondary.
- **My Work** — requests/approvals and reviews.
- **Identity** — governed Identities and technical Principals.
- **Access** — authoritative assignments and derived effective access.
- **Governance** — policy and exception administration, while assigned approval/review work remains under My Work.
- **Catalog** — Applications, targets, Entitlements, Roles, and RoleVersions.
- **Integration** — connector administration plus operational provisioning/reconciliation views.
- **Credentials** — Principal-scoped credential metadata lifecycle and durable rotation.
- **Administration** — IAM control-plane authority.
- **Audit** — evidence and evidence-lifecycle operations.

Route meaning remains defined by `consoleNavigation.ts` and the accepted API contracts. UI grouping does not change capability ownership.

## Page archetypes

### Operator home

The home page starts with **work entry points**, not fabricated KPI cards. Runtime, Tenant, actor, and current administrative-authority context are important but secondary.

Do not invent counts, risk scores, health percentages, or trend charts unless an accepted server contract provides the data and semantics.

### Collection + selected detail workbench

The default resource-management pattern is:

1. orient with the current collection;
2. select a meaningful row;
3. inspect the selected resource beside or immediately below the collection;
4. expose semantic operations with that selected resource;
5. keep raw IDs and revision metadata available but secondary.

Direct resource-ID lookup is an advanced/support path, not the primary operator workflow.

Creation should normally be a deliberate secondary action or panel rather than a permanent form occupying the top of every list page.

### Identity 360 governed-subject workbench

`/identities/{identityId}` is the golden governed-subject detail workbench. It is a presentation composition only; it does not create a writable cross-capability aggregate or transfer authority away from the owning capabilities.

The persistent header presents the authoritative Identity type, lifecycle, stable identifier, update time, and access-eligibility interpretation. Metadata editing remains limited to the accepted mutable Identity metadata. Lifecycle transitions remain explicit semantic operations with optimistic revision and causal idempotency behavior. Merge and split remain advanced Identity-owned correction operations rather than ordinary account maintenance.

The detail workspace uses six semantic views:

- **Overview** — authoritative Identity summary and investigation entry points;
- **Governed profile** — canonical resolution state, classification, redaction, and bounded pagination;
- **Principals** — Identity-owned technical representations/accounts, read through an exact subject-scoped Principal query;
- **Access** — authoritative AccessAssignments shown separately from derived EffectiveAccess;
- **Governance** — administratively authorized subject-scoped review campaigns and GovernanceExceptions; AccessRequest history is intentionally excluded while request visibility remains participant-scoped;
- **Evidence** — direct immutable AuditRecords whose resource is the Identity, explicitly not an exhaustive cross-capability history.

Subject-scoped collection reads remain server-authorized collection reads. Filtering by Identity does not grant visibility merely because the actor can see the Identity, and it does not allow the Console to bypass the owning capability. Cursor continuation is bounded to the same tenant and filter context. Provider observation, technical fulfillment, governance decision state, authoritative business state, and derived projection remain visibly distinct.

### Scoped hierarchy workbench

For parent-scoped concepts such as ApplicationTarget, Entitlement, or RoleVersion, the parent scope must be explicit and persistent while operating on children. Selection never becomes authority; the server decides authorization.

### Decision/evidence workbench

Requests, approvals, reviews, exceptions, and evidence pages must visually distinguish:

- requested action;
- decision/evidence state;
- authoritative business state;
- remediation/fulfillment state;
- current provider observation where exposed.

Do not collapse these dimensions into one generic `status` UI.

### High-risk operation surface

Revocation, decommissioning, destructive purge, break-glass, authority grant/revoke, and similar operations must show consequence/context before execution. Destructive styling is reserved for genuinely destructive or privilege-reducing/terminating actions, not ordinary navigation.

## Visual target

The visual system should feel like a dense enterprise control plane:

- quiet neutral canvas;
- clear white content surfaces;
- restrained blue product accent;
- strong but compact title hierarchy;
- tables optimized for scanning rather than oversized cards;
- visible selected-row state for list/detail work;
- tabs that read as workspace navigation;
- compact controls with unambiguous primary/secondary/destructive hierarchy;
- semantic status colors used for meaning, not decoration;
- limited shadow and radius; avoid consumer-dashboard visual noise.

Whitespace is used to separate decisions and concepts, not to inflate every record into a card.

## Interaction rules

### Navigation

Desktop navigation is persistently labeled. Command navigation is an accelerator, never required discovery. Mobile uses a complete drawer; no later capability may become unreachable because of viewport width.

### Progressive disclosure

Put common meaning first. Move implementation-oriented identifiers, revisions, and advanced support lookup behind secondary detail where practical. Never hide information required to make a safe decision.

### Mutations

Public semantic operations remain explicit. Do not add arbitrary status PATCH behavior or generic CRUD affordances.

Revision-sensitive mutations preserve ETag/If-Match behavior. Retryable mutations preserve causal idempotency where required. The UI must present stale/conflict responses as current-state problems rather than silently retrying with guessed state.

### Authorization

Navigation visibility may use the current server-derived authority projection to reduce noise. It is never authorization. A server denial remains final.

The browser must not infer Tenant, governed actor, grantability, delegability, policy eligibility, or break-glass authority from token claims or local state.

### Secrets

Secret/private credential material never becomes ordinary UI state. SecretReference and provider configuration references remain opaque metadata. Do not add reveal, copy, browser-storage, URL, telemetry, or generic error paths for private material.

## Semantic boundaries the UI must make visible

The Console should reinforce, not blur, these canonical distinctions:

- Identity != Principal;
- Principal != provider-observed account;
- Credential metadata != private credential material;
- Catalog Role != AdministrativeRole;
- AccessAssignment != EffectiveAccess;
- governance decision != technical fulfillment;
- Approval evidence != Administration authority;
- connector lifecycle != provisioning/reconciliation observation;
- AuditRecord != application log;
- EvidenceSnapshot != current mutation authority;
- Tenant != Organization.

## State and failure presentation

Every substantial work surface should have deliberate handling for the states relevant to its contract:

- loading;
- empty;
- not authorized;
- not found;
- stale revision;
- semantic conflict;
- evaluator/service unavailable;
- retryable operational failure where applicable.

Do not replace server semantics with generic toast text such as “Something went wrong” when a typed condition is known.

## Accessibility and responsive target

The existing Wave-3 accessibility baseline remains mandatory:

- visible keyboard focus;
- deliberate dialog focus and focus return;
- semantic forms/tables/tabs;
- readable contrast;
- reduced-motion support;
- narrow-screen reachability without avoidable horizontal page overflow.

Data tables may scroll inside their deliberate container when their information density requires it; the entire application shell should not become horizontally unreachable.

## Anti-patterns

Do not introduce:

- generic “Create / Resources / paste ID / raw object detail” as the primary page experience;
- unlabeled icon-only primary navigation;
- fabricated dashboards or vanity metrics;
- a universal IAM resource editor;
- arbitrary JSON editors for typed IAM semantics;
- browser-side permission evaluation;
- generic status dropdowns for semantic lifecycle state machines;
- provider-native editing as authoritative IAM state;
- raw secret material;
- duplicated old and new UI routes for the same semantic resource;
- decorative cards for every datum when a table/detail hierarchy is more usable.

## Definition of done for the redesign tranche

The Console redesign is complete when:

1. the active application shell uses persistent, complete, labeled operator navigation;
2. Overview leads with operator work rather than infrastructure status;
3. generic resource pages use collection + selected detail as the primary pattern;
4. create/direct-ID/technical metadata are progressively disclosed where appropriate;
5. custom Identity and Principal flows remain semantically explicit and visually coherent with the shared workbench;
6. Catalog, Access, Governance, Credential, Integration, Administration, and Audit all inherit or implement the operator-workbench hierarchy without semantic regressions;
7. accessibility/responsive regression coverage passes;
8. no legacy competing capability UI becomes reachable;
9. the exact converged integration head passes Core CI, Security CI, Container CI, and DEV Deployment Contract before promotion to `main`;
10. managed DEV browser acceptance confirms the deployed Console uses the promoted UI.

## Authority

This document governs Console implementation direction only. In a conflict, follow the project authority hierarchy: accepted ADRs and current formal requirements/specifications first, then current repository architecture/domain/interface/security contracts. The Console must adapt to IAM semantics; IAM semantics do not change merely to make a UI easier to implement.
