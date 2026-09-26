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

Public creation uses `MANUAL` provenance only. Governance request/review provenance is
reserved for the later Governance-to-Access command slice.

Principal constraints remain typed:

- `ANY` carries no specific Principal;
- `SPECIFIC` requires one governed Principal and reuses the current Identity/Catalog/Role
  validation already enforced by Access.

Target, principal constraint and validity are not patched after creation in this slice.
Changed business intent creates a successor/new authoritative assignment.

## Lifecycle semantics

The public operations expose business meaning rather than arbitrary status mutation.

- `suspend`: ACTIVE -> SUSPENDED;
- `resume`: SUSPENDED -> ACTIVE only while temporal validity is currently effective;
- `cancel`: future SCHEDULED -> CANCELLED;
- `revoke`: ACTIVE/SUSPENDED -> REVOKED.

`REVOKED`, `CANCELLED` and `EXPIRED` remain terminal. Natural `validFrom` /
`validUntil` boundary materialization remains scheduler-owned. Semantic validity is
still evaluated directly from authoritative state/time, so scheduler delay cannot extend
or prematurely start access.

Lifecycle mutation does not synchronously call a provider. It emits the existing Access
projection-input fact; EffectiveAccess, desired state and Integration provisioning converge
asynchronously.

Mutable authoritative assignment operations use strong `ETag: "rev-N"` plus
`If-Match`. Retryable mutations use durable causal `Idempotency-Key` records.

## EffectiveAccess API

Resources:

- `GET /effective-access`
- `GET /effective-access/{effectiveAccessId}`

The list may be filtered by `identityId`. Its cursor is bound to the tenant and exact
identity-filter context.

EffectiveAccess detail returns normalized explanation evidence:

- supporting AccessAssignment ID;
- deterministic path hash;
- path depth;
- ordered RoleVersion ID path.

Direct Entitlement assignment support has depth 0 and an empty RoleVersion path.
Role-derived supports preserve their exact ordered version path.

Both list and detail filter materialized support against authoritative AccessAssignment
lifecycle and temporal validity at read time. The returned `supportCount` is the count of
currently semantically effective support evidence, not an unverified persisted counter.

There is intentionally no EffectiveAccess mutation endpoint.

## Pagination

AccessAssignment uses deterministic `createdAt + id` ordering.

EffectiveAccess uses stable projection `id` ordering.

Continuation cursors follow ADR-0012:

- signed;
- tenant-bound;
- resource-kind-bound;
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
