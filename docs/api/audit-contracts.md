# Audit API Contract

## Scope

The first public Audit control-plane slice exposes immutable `AuditRecord` evidence under ADR-0031 without turning Audit into business authority, an event store, or an observability log.

Base path: `/api/v1`.

Public operations:

- `GET /audit-records` — bounded deterministic AuditRecord search;
- `GET /audit-records/{auditRecordId}` — tenant-scoped AuditRecord read.

Machine-readable contract:

- `apps/server/src/main/resources/contracts/openapi/audit-v1.json`

## Authorization

Both operations require the default-deny semantic permission:

- `audit:read`.

Collection search requires authority applicable to the AuditRecord collection. A specific-resource grant may authorize one AuditRecord read but does not authorize collection search.

Transport authentication remains separate from Administration-owned authorization. Tenant isolation is applied before ordinary Audit lookup semantics.

## Search contract

Search supports exact optional filters only:

- `actorId`;
- `actionType`;
- `resourceType`;
- `resourceId`;
- `outcome` (`SUCCESS`, `DENIED`, `FAILURE`);
- `correlationId`.

There is no free-form text query, SQL-like expression, arbitrary JSON predicate, cross-capability join, or current-state lookup in the Audit search path.

Results are ordered by:

1. `occurredAt DESC`;
2. stable AuditRecord ID `DESC`.

The default page size is 50 and the maximum is 200.

Continuation cursors are signed, tenant-bound, exact-filter-bound and time-bounded. Replaying a cursor with another tenant or filter context, tampering with its contents, or using an expired/unknown-key cursor fails closed as `invalid_cursor`.

## Representation

The public record contains only:

- `id`;
- `occurredAt`;
- `recordedAt`;
- optional `actorId`;
- `actionType`;
- `resourceType`;
- optional `resourceId`;
- `outcome`;
- optional `correlationId`;
- optional `causationId`.

`material_snapshot` and `integrity_metadata` are not exposed because their semantics remain deferred. Raw secrets/private credential material remain prohibited.

AuditRecord remains immutable evidence. Reading Audit does not mutate or reconstruct capability authority.

## Deferred

- audit export/download;
- retention/archive execution;
- EvidenceSnapshot;
- material snapshots and integrity-chain metadata;
- SIEM transport;
- richer search/reporting that would require new explicit requirements.
