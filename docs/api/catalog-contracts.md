# Catalog API and Authority Boundary

## Scope

The Catalog runtime now implements authoritative:

- `Application`;
- `ApplicationTarget`;
- `Entitlement`;
- internal/runtime `Role` and `RoleVersion` composition.

The checked-in public Catalog HTTP contract now exposes Application/ApplicationTarget/Entitlement plus governed Role/RoleVersion management. Access still consumes Role composition only through the framework-neutral `RoleExpansionQuery`; the public management API never becomes an Access projection shortcut. Automatic provider observation adoption, AccessAssignment authority, EffectiveAccess ownership, and provider-side grant execution remain outside Catalog. Integration may explicitly map a provider entitlement observation to an existing Catalog Entitlement through the ADR-0015 semantic validation boundary.

Machine-readable contract:

- `apps/server/src/main/resources/contracts/openapi/catalog-v1.json`

## Authority boundary

Catalog owns the governed meaning of Application, ApplicationTarget and Entitlement.

Integration may later discover provider-native groups/permissions as `ObservedEntitlement` and memberships as `ObservedGrant`, but provider observations never silently create or mutate Catalog authority.

An Entitlement has a stable IAM ID. A SCIM Group/provider object ID is provider-native observation/mapping evidence, not the Entitlement primary identity. Catalog validates mapping references but does not own or persist provider-observation mappings.

## Role runtime boundary

- Role is either BUSINESS or APPLICATION.
- APPLICATION Role belongs to exactly one active Application.
- APPLICATION RoleVersion contains only active target-scoped Entitlements from that Application.
- BUSINESS RoleVersion contains target-scoped Entitlements and/or APPLICATION Roles; BUSINESS -> BUSINESS and APPLICATION -> Role are not supported.
- an active Role has at most one ACTIVE RoleVersion;
- ACTIVE/SUPERSEDED RoleVersion content is immutable;
- BUSINESS expansion resolves member APPLICATION Roles through their current ACTIVE versions and returns exact ordered RoleVersion derivation paths;
- Role activation/retirement emits internal expansion-change facts for Access projection repair; these are not automatically public integration events.

## Public Role management

Base path: `/api/v1`.

Roles:

- `GET /roles`
- `POST /roles`
- `GET /roles/{roleId}`
- `PATCH /roles/{roleId}` — name only
- `POST /roles/{roleId}/retire`

RoleVersions:

- `GET /roles/{roleId}/versions`
- `POST /roles/{roleId}/versions` — creates a complete DRAFT composition snapshot
- `GET /roles/{roleId}/versions/{roleVersionId}`
- `POST /roles/{roleId}/versions/{roleVersionId}/validate`
- `POST /roles/{roleId}/versions/{roleVersionId}/activate`

Role type, application binding and code are immutable after Role creation. RoleVersion membership is supplied as a complete typed set at creation and is not patched in place. Validation re-checks current member references and moves DRAFT to READY. Activation re-checks the composition, moves READY to ACTIVE, and atomically supersedes the prior ACTIVE version.

Role and RoleVersion lifecycle mutations use strong revision ETags plus `If-Match`. RoleVersion has its own positive authoritative revision; DRAFT creation starts at revision 1, validation advances it, activation advances it again, and automatic supersession advances the superseded version revision. Retryable mutations use causal idempotency. Role lists and per-Role version lists use signed tenant/context-bound cursors; a RoleVersion cursor cannot be replayed for another Role.

The initial public composition member union is intentionally typed:

- `ENTITLEMENT` + `entitlementId`;
- `APPLICATION_ROLE` + `roleId`.

Arbitrary JSON composition and arbitrary status mutation are not public contracts.

## Structural invariants

- ApplicationTarget belongs to exactly one Application.
- Entitlement belongs to exactly one Application.
- Entitlement may optionally reference one ApplicationTarget.
- If a target is referenced, the target must belong to the same Application.
- tenant isolation applies to every Catalog row/reference.
- Application code is tenant-unique.
- ApplicationTarget code is unique within its Application.
- Entitlement code is unique within its Application.
- business deletion is retirement, not physical delete.

## First lifecycle slice

The first runtime lifecycle is deliberately small:

`ACTIVE -> RETIRED`

Retirement is terminal in this slice.

Retiring a parent does not cascade business mutation into its targets or entitlements. Historical references remain readable. New targets/entitlements cannot be created beneath a retired Application; new entitlements cannot target a retired ApplicationTarget.

## Mutability

Application:

- `code` is stable after creation;
- `name` is mutable with optimistic revision;
- lifecycle changes through explicit `retire`.

ApplicationTarget:

- `applicationId` and `code` are stable after creation;
- lifecycle changes through explicit `retire`.

Entitlement:

- `applicationId`, optional `applicationTargetId`, `code`, `nativeKey`, and `entitlementType` are creation-time fields in this slice;
- lifecycle changes through explicit `retire`.

Provider discovery is not an entitlement-maintenance operation.

## HTTP resources

Base path: `/api/v1`.

Applications:

- `GET /applications`
- `POST /applications`
- `GET /applications/{applicationId}`
- `PATCH /applications/{applicationId}` — name only
- `POST /applications/{applicationId}/retire`

Application targets:

- `GET /applications/{applicationId}/targets`
- `POST /applications/{applicationId}/targets`
- `GET /application-targets/{targetId}`
- `POST /application-targets/{targetId}/retire`

Entitlements:

- `GET /applications/{applicationId}/entitlements`
- `POST /applications/{applicationId}/entitlements`
- `GET /entitlements/{entitlementId}`
- `POST /entitlements/{entitlementId}/retire`

Lifecycle is not exposed as arbitrary status mutation.

## Concurrency and idempotency

Mutable authoritative operations use strong revision ETags:

`"rev-<revision>"`

Revision-guarded mutations require `If-Match`. Stale writes fail rather than silently overwriting newer Catalog state.

Externally retryable mutations require `Idempotency-Key`. Reusing a key with a different normalized request fingerprint is a conflict; replay with the same fingerprint returns the original resource.

## Pagination

Collections are ordered deterministically by `createdAt`, then stable ID.

Continuation cursors use the platform signing boundary and are:

- opaque;
- integrity-protected;
- tenant-bound;
- resource-family bound;
- Application-bound for target/entitlement collections;
- time-bounded.

A target-list cursor cannot be replayed as an entitlement-list cursor or against another Application.

## Administrative permissions

The initial semantic permissions are:

- `role:read`
- `role:create`
- `role:update`
- `role:retire`
- `role-version:read`
- `role-version:create`
- `role-version:validate`
- `role-version:activate`
- `application:read`
- `application:create`
- `application:update`
- `application:retire`
- `application-target:read`
- `application-target:create`
- `application-target:retire`
- `entitlement:read`
- `entitlement:create`
- `entitlement:retire`

Administrative Authorization remains default-deny and independent from governed IAM Roles/Entitlements.
