# Catalog API and Authority Boundary

## Scope

The first Catalog runtime slice implements authoritative:

- `Application`;
- `ApplicationTarget`;
- `Entitlement`.

It intentionally does not implement Role/RoleVersion, automatic provider observation adoption, AccessAssignment, EffectiveAccess, or provider-side grant execution. Integration may explicitly map a provider entitlement observation to an existing Catalog Entitlement through the ADR-0015 semantic validation boundary.

Machine-readable contract:

- `apps/server/src/main/resources/contracts/openapi/catalog-v1.json`

## Authority boundary

Catalog owns the governed meaning of Application, ApplicationTarget and Entitlement.

Integration may later discover provider-native groups/permissions as `ObservedEntitlement` and memberships as `ObservedGrant`, but provider observations never silently create or mutate Catalog authority.

An Entitlement has a stable IAM ID. A SCIM Group/provider object ID is provider-native observation/mapping evidence, not the Entitlement primary identity. Catalog validates mapping references but does not own or persist provider-observation mappings.

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
