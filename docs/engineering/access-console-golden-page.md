# Access Console golden page

## Purpose

`/access` is the Access capability operator workbench. Its primary operator question is:

> What access has IAM authorized, is it currently effective, and what action can I safely take next?

This document records the implementation target for the Access golden-page tranche. It does not redefine Access authority, Governance decisions, Integration fulfillment, Audit evidence, or provider observation. Accepted ADRs, formal specifications, and the canonical architecture/domain/API documentation remain authoritative.

## Semantic hierarchy

The page deliberately preserves four different meanings:

1. **AccessAssignment** — Access-owned authoritative business access intent.
2. **EffectiveAccess** — Access-owned rebuildable, read-only entitlement projection.
3. **Desired technical state / provider fulfillment** — downstream technical realization, not AccessAssignment lifecycle.
4. **Provider observation** — Integration-owned observation, never Access authority.

Governance approval/evaluation is evidence and authorization context. AuditRecord is Audit-owned evidence. Neither is AccessAssignment authority.

## Page architecture

`/access` is Assignments-first and uses URL-addressable semantic views:

- `/access?view=assignments`
- `/access?view=assignments&assignmentId=<uuid>`
- `/access?view=effective&identityId=<uuid>`
- `/access?view=effective&effectiveAccessId=<uuid>`
- `/access?view=policy`

Query state is normal browser history state. Back/Forward and reload preserve the semantic view and selected resource. Opaque signed API cursors are intentionally not persisted in URLs.

### Assignments

Assignments is the default workspace and presents a bounded collection plus selected detail.

The collection prioritizes:

- governed subject;
- Role or Entitlement target;
- Principal constraint;
- authoritative lifecycle;
- temporal validity;
- provenance;
- update time.

The selected detail separates `Authoritative intent` from `Temporal validity`. Raw identifiers and revision metadata are progressively disclosed.

The only assignment lifecycle actions are:

- `Suspend access` for ACTIVE assignments;
- `Resume access` for SUSPENDED assignments;
- `Cancel scheduled access` for future SCHEDULED assignments;
- `Revoke access` for ACTIVE or SUSPENDED assignments.

There is no generic status mutation. Entry controls for cancellation and revocation remain visually restrained until the explicit consequence-confirmation boundary; danger emphasis belongs to confirmation rather than permanently saturating the ordinary workbench.

Every lifecycle mutation uses the current strong revision and one causal Idempotency-Key. A retry of the same operator decision reuses the same key. A `412` never silently retries with a newly fetched revision; the operator must refresh and review current authority.

### Manual grant

`Grant access` is the single dominant page-level creation action. The browser never selects provenance or lifecycle state.

The flow selects:

- one governed Identity through bounded Identity search;
- exactly one Role or Entitlement through bounded Catalog-owned target search;
- `ANY` or `SPECIFIC` Principal constraint;
- for `SPECIFIC`, one Principal from the selected Identity's bounded Principal collection;
- optional `validFrom` and `validUntil`.

The server fixes MANUAL provenance. Assignment creation does not call a provider synchronously.

### Effective access

EffectiveAccess is visibly marked `DERIVED · READ ONLY`.

The collection uses the accepted Identity filter and cursor pagination. Detail shows support count, computation metadata, and the ordered support evidence already owned by Access:

- supporting AccessAssignment;
- direct vs Role-derived interpretation from path depth;
- ordered RoleVersion path;
- deterministic path hash as technical evidence.

There is no EffectiveAccess mutation. The operator follows a supporting AccessAssignment when business intent must change.

### Lifecycle policy

Lifecycle policy is an expert secondary surface.

It presents the current active immutable version and the accepted closed predicate model. The UI must never introduce arbitrary JSON, a free-form expression language, generic AND/OR/NOT, unsupported relationship predicates, or unsupported set/count/order semantics.

Activation creates an immutable successor and supersedes the previous active version. Privilege-increasing effects remain subject to current server-side Governance evaluation. Activation does not imply a tenant-wide retroactive sweep.

## Bounded operator read model

The Console uses `GET /api/v1/access-assignment-views` as a read-only Access-owned operator projection. It is not a new aggregate and cannot mutate authority.

The view provides:

- the underlying authoritative AccessAssignment fields;
- server-evaluated temporal classification (`CURRENT`, `FUTURE`, `ENDED`) with evaluation time;
- bounded server-side filters;
- human labels composed through owning-capability semantic queries.

Foreign labels are optional and permission-aware. The Access view checks the corresponding foreign read authority before querying Identity, Principal, Role, or Entitlement presentation data. If that read is not authorized, the stable identifier remains available without leaking the foreign label.

Supported filters are:

- `identityId`;
- `lifecycleState`;
- `targetKind` plus optional exact `targetId`;
- `provenance`;
- `validity`.

Cursor continuation is signed and bound to the normalized filter tuple. A cursor from one filter context cannot be reused in another.

## Catalog target search

`GET /api/v1/catalog/access-targets` is a Catalog-owned bounded lookup for grant selectors. It accepts only:

- target kind `ROLE` or `ENTITLEMENT`;
- a bounded text query;
- limit 1 through 20.

Only active Catalog targets are returned. Search ordering is deterministic and the endpoint is independently authorized using Role or Entitlement read authority.

This read helper does not move Catalog authority into Access.

## Audit evidence

Selected assignment detail may request up to five AuditRecords filtered by exact AccessAssignment resource reference. Audit is independently authorized and independently loaded.

Audit unavailability or denial never makes the authoritative AccessAssignment unavailable.

Provider payloads, request bodies, secrets, private credential material, and connector-native data are not exposed by this preview.

## Technical realization

The golden page intentionally does not show an assignment-level provisioning/convergence badge. The current accepted Integration read surfaces are process-oriented and do not provide a safe bounded AccessAssignment-correlated realization query.

If such context is added later it must be Integration-owned, read-only, independently authorized, independently loaded, and visually labelled as technical realization rather than Access state.

## Scale and responsive behavior

The implementation assumes enterprise cardinality:

- bounded server filters;
- deterministic cursor pagination;
- no fetch-all;
- no cursor walking for counts;
- no client-side tenant aggregation;
- no browser N+1 label hydration;
- no massive Identity/Role/Entitlement/Principal selectors.

Assignments and EffectiveAccess use the same semantic tables across desktop, tablet and mobile with bounded horizontal overflow. Narrow screens do not maintain a second card representation or drop governed subject, target, Principal constraint, lifecycle, validity, provenance, support or computation context merely to fit the viewport. When a resource is selected on a narrow screen, its detail becomes the full-width task state and the collection may temporarily yield to that selected detail without changing URL semantics.

## Accessibility

The page requires:

- one page `h1` and semantic section headings;
- semantic tables with accessible captions/headers across viewport sizes;
- keyboard-reachable tabs, rows, links, filters, and actions;
- visible focus;
- confirmation-dialog focus management;
- no hover-only operation;
- no color-only state;
- accessible loading/error state text;
- textual equivalents for EffectiveAccess support paths;
- reduced-motion compliance.

## Deferred

The tranche does not introduce:

- tenant Access metric cards without an accepted server metric contract;
- assignment-correlated Integration realization status;
- provider observation inside Access authority surfaces;
- EffectiveAccess mutation;
- bulk AccessAssignment mutation;
- tenant-wide lifecycle-policy reevaluation;
- generic policy expressions;
- provider payload disclosure.

## Closing standard

Same Wyrmgate Console product language as IAM Operations, Requests & Approvals, Identity 360 and the flow-first Identity/Principal journeys; different Access semantics. Authoritative intent, effective projection, technical fulfillment and provider observation remain visibly separate.
