# Access Contracts

## Purpose

This document defines the first public Access capability contract for authoritative
`AccessAssignment` management and read-only `EffectiveAccess` projection reads.

Machine-readable contract:

- `apps/server/src/main/resources/contracts/openapi/access-v1.json`

Base path: `/api/v1`.

## Authority boundary

`AccessAssignment` is Access-owned authoritative business access intent.

`EffectiveAccess` is a rebuildable Access-owned entitlement-level projection.
It is never edited through the public API.

DesiredPrincipalState / DesiredGrantState, Integration provisioning work and provider
observation are not public Access resources in this slice.

## AccessAssignment API

Resources:

- `GET /access-assignments`
- `POST /access-assignments`
- `GET /access-assignments/{assignmentId}`

Explicit lifecycle operations:

- `POST /access-assignments/{assignmentId}/suspend`
- `POST /access-assignments/{assignmentId}/resume`
- `POST /access-assignments/{assignmentId}/cancel`
- `POST /access-assignments/{assignmentId}/revoke`

Creation supports exactly one target:

- `ROLE + roleId`; or
- `ENTITLEMENT + entitlementId`.

Public creation uses `MANUAL` provenance only. Governance applies authorized
RequestItems through the internal semantic `AccessIntentCommand`; Access creates
`REQUEST_ITEM` provenance with `provenanceRefId = requestItemId`. Lifecycle-access
reconciliation creates `LIFECYCLE_POLICY_RULE` provenance with the stable logical rule
ID as `provenanceRefId`. Those internal paths are idempotent by provenance and are not
exposed as public arbitrary-provenance creation options.

Principal constraints remain typed:

- `ANY` carries no specific Principal;
- `SPECIFIC` requires one governed Principal and reuses the current Identity/Catalog/Role
  validation already enforced by Access.

Target, principal constraint and validity are not patched after creation in this slice.
Changed business intent creates a successor/new authoritative assignment.

## AccessAssignment operator view

The Console uses a separate bounded read-only operator projection:

- `GET /access-assignment-views`
- `GET /access-assignment-views/{assignmentId}`

This surface does **not** introduce a second authoritative Access resource. The
underlying lifecycle, target, Principal constraint, validity, provenance and revision are
read from the same AccessAssignment authority. The view adds only presentation/query
material needed by the golden Console page:

- optional human labels composed through owning-capability semantic queries;
- server-evaluated temporal classification `CURRENT`, `FUTURE` or `ENDED`;
- the `evaluatedAt` instant for that temporal interpretation;
- bounded server-side filters.

Supported collection filters are:

- `identityId`;
- `lifecycleState`;
- `targetKind` and optional exact `targetId`;
- `provenance`;
- `validity`.

Collection continuation remains ordered by authoritative `createdAt + id`. The signed
cursor is bound to the normalized filter tuple, so a continuation obtained under one
filter context cannot be replayed under another.

Human labels are optional and permission-aware. Access may ask Identity, Catalog or
Principal semantic queries for the current presentation label only when the requesting
operator independently has the corresponding foreign read authority. If not, the stable
identifier remains available without leaking the foreign label. Foreign labels are not
copied into Access authoritative state and cannot mutate it.

## Lifecycle semantics

The public operations expose business meaning rather than arbitrary status mutation.

- `suspend`: ACTIVE or due SCHEDULED -> SUSPENDED while temporally valid;
- `resume`: SUSPENDED -> ACTIVE only while temporal validity is currently effective;
- `cancel`: future SCHEDULED -> CANCELLED;
- `revoke`: ACTIVE/SUSPENDED or due SCHEDULED -> REVOKED while temporally valid.

`REVOKED`, `CANCELLED` and `EXPIRED` remain terminal. The current `validFrom` /
`validUntil` workers repair derived projections without automatically rewriting stored
assignment lifecycle. Semantic validity and reduction eligibility are evaluated directly
from authoritative state/time, so worker delay cannot extend, prematurely start, or
prevent reduction of access.

Neither public lifecycle mutation nor internal request application synchronously calls a
provider. Access emits the existing projection-input fact; EffectiveAccess, desired state
and Integration provisioning converge asynchronously. Governance `APPLIED` means Access
accepted the authoritative AccessAssignment, not that technical provisioning succeeded.

Mutable authoritative assignment operations use strong `ETag: "rev-N"` plus
`If-Match`. Retryable mutations use durable causal `Idempotency-Key` records.

Public AccessAssignment create, suspend, resume, cancel and revoke attempts append
data-minimized ADR-0031 AuditRecord evidence after the Access transaction commits or
rolls back. Audit retains the governed actor, semantic action, target AccessAssignment
when one is known, normalized SUCCESS/DENIED/FAILURE outcome and request correlation
ID. Create denial/failure before an assignment exists carries no resource ID. Identity,
Role, Entitlement and Principal IDs, validity, request bodies, governance/policy evidence,
provider state and exception detail are not copied into AuditRecord. Audit persistence
failure is operationally logged and never rewrites the AccessAssignment business outcome.

## EffectiveAccess API

Resources:

- `GET /effective-access`;
- `GET /effective-access/{effectiveAccessId}`;
- `GET /effective-access/{effectiveAccessId}/supports`.

The list may be filtered by `identityId`. Its cursor is bound to the tenant and exact
identity-filter context.

EffectiveAccess is entitlement-level derived state and is never mutation authority. Every
returned row is revalidated against current Identity eligibility/epoch, AccessAssignment
lifecycle and temporal validity, current Catalog availability, current Role expansion and,
for `SPECIFIC`, the governed Principal/target compatibility from ADR-0055.

Explanation evidence is deliberately bounded. List and detail responses inspect at most the
first 100 ordered support candidates for one EffectiveAccess tuple. `supportCount` is the
number of semantically current supports verified in that bounded page; when
`supportCountTruncated=true`, it is a lower bound rather than an exhaustive total.
`supportsNextCursor` continues the detail explanation through the support collection.

Support pages are ordered deterministically by supporting AccessAssignment ID + path hash and
are capped at 100 candidates. A page may contain fewer semantic items than the requested limit
because stale materialized support is filtered after the bounded raw page is read. A page may
therefore be empty while still carrying `nextCursor`; callers continue until the cursor is
absent when exhaustive explanation is required.

Each support exposes:

- supporting AccessAssignment ID;
- deterministic path hash;
- path depth;
- ordered RoleVersion ID path.

Direct Entitlement assignment support has depth 0 and an empty RoleVersion path.
Role-derived supports preserve their exact ordered version path.

Security/governance current-access queries do not perform an unbounded search for a valid
support. They inspect a bounded support budget. If all candidates inside the budget are stale
while additional candidates remain, Access returns semantic evaluation as unavailable rather
than falsely treating the entitlement as absent. Public Access reads surface this condition as
HTTP 503 with `effective_access_semantic_evaluation_unavailable`; mandatory privilege-increase
evaluation therefore fails closed and retries.

The support subresource first establishes semantic parent existence. A semantically absent
parent returns 404; an exhausted bounded semantic determination returns 503. Only a
semantically current parent may return an empty continuation page.

Access materializes support using the same current Identity epoch, Catalog availability and
SPECIFIC Principal compatibility semantics as reads. Identity eligibility changes and
Principal changes enqueue bounded assignment refresh pages, including all non-terminal
SPECIFIC assignments referencing the governed Principal irrespective of its previous/current
owner. A stale epoch or incompatible SPECIFIC relationship removes derived support without
automatically terminating the assignment. Terminal Catalog unavailability still uses the
ordinary authoritative reduction contract. Support changes and touched-tuple desired-grant
refresh work commit together; desired derivation retries independently if semantic evaluation
is unavailable, so it cannot prevent stale support cleanup.

There is intentionally no EffectiveAccess mutation endpoint.

## EffectiveAccess operator view

The Console may use the equivalent read-only presentation surface:

- `GET /effective-access-views`;
- `GET /effective-access-views/{effectiveAccessId}`.

This view delegates projection semantics and bounded support filtering to the same
`EffectiveAccessReadService`; it does not persist or mutate EffectiveAccess. It adds only
optional permission-aware Identity and Entitlement presentation labels. Detail exposes the
same lower-bound `supportCount`, `supportCountTruncated` and signed
`supportsNextCursor`; subsequent support pages use the canonical
`/effective-access/{effectiveAccessId}/supports` endpoint.

Failure to resolve a foreign presentation label never changes whether EffectiveAccess is
semantically present. The stable Identity/Entitlement IDs remain the cross-capability
references.

## Pagination

AccessAssignment uses deterministic `createdAt + id` ordering.

EffectiveAccess uses stable projection `id` ordering.

Continuation cursors follow ADR-0012:

- signed;
- tenant-bound;
- resource-kind-bound;
- AccessAssignment operator-filter-bound where filters are supplied;
- EffectiveAccess identity-filter-bound;
- time-bounded;
- rotation-safe through signing key IDs.

## Administrative permissions

The semantic control-plane permissions are:

- `access-assignment:read`
- `access-assignment:create`
- `access-assignment:suspend`
- `access-assignment:resume`
- `access-assignment:cancel`
- `access-assignment:revoke`
- `effective-access:read`

The operator-view endpoints reuse these same Access permissions; they do not define a
parallel authorization model. Foreign presentation labels additionally require the
corresponding Identity/Catalog/Principal read authority before Access asks the owner query.

Authentication does not grant these permissions. Every operation consumes the trusted
authenticated actor context and re-evaluates `AdministrativeAuthorizationService`.
Collection reads require collection-authorizing scope; resource-specific grants do not
implicitly authorize collection enumeration.

## Error / retry semantics

The API uses the normal stable control-plane error envelope with correlation ID.

Common semantic errors include:

- `stale_revision`;
- `idempotency_conflict`;
- `idempotency_in_progress`;
- `access_assignment_not_active`;
- `access_assignment_not_suspended`;
- `access_assignment_not_cancellable`;
- `access_assignment_not_revocable`;
- `access_assignment_expired`.

Provider failure does not appear as an AccessAssignment lifecycle error and never restores
revoked/suspended business authority.

## Lifecycle-access policy API

Access v1 now exposes the existing Access-owned lifecycle/birthright policy through:

- `GET /lifecycle-access-policy`;
- `POST /lifecycle-access-policy:activate`.

Activation creates one immutable successor and supersedes the previous active version. The payload is the closed typed predicate model accepted through ADR-0041: ALWAYS; exact SINGLE equality; one-value MULTI CONTAINS; and bounded 2-20-member typed CONTAINS_ANY / CONTAINS_ALL. Typed DECIMAL/DATE/DATETIME semantics remain exact as defined by ADR-0039. Rule IDs remain stable AccessAssignment provenance.

The API does not introduce arbitrary status mutation, JSON expressions, exact-set/subset/count/order operators, compound AND/OR/NOT, relationship predicates or a generic rules engine. Those remain explicit future product semantics, not missing schema coverage.

Activation requires causal idempotency and the explicit `lifecycle-access-policy:activate` permission; read requires `lifecycle-access-policy:read`. Neither permission is silently added to INITIAL_TENANT_ADMIN.
