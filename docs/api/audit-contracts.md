# Audit API Contract

## Scope

The implemented public Audit control-plane slice exposes immutable `AuditRecord` evidence under ADR-0031 without turning Audit into business authority, an event store, or an observability log. ADR-0034 defines the next durable export/archive boundary; that export runtime is not yet implemented in this document's current API surface.

Base path: `/api/v1`.

Public operations:

- `GET /audit-records` — bounded deterministic AuditRecord search;
- `GET /audit-records/{auditRecordId}` — tenant-scoped AuditRecord read.

Machine-readable contract:

- `apps/server/src/main/resources/contracts/openapi/audit-v1.json`

## Authorization

The implemented read/search operations require the default-deny semantic permission:

- `audit:read`.

ADR-0034 reserves a separate semantic permission for the future durable export operation:

- `audit:export`.

`audit:read` does not imply `audit:export`, and INITIAL_TENANT_ADMIN is not silently expanded.

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

## ADR-0034 export/archive direction

The next Audit implementation slice is a durable asynchronous `AuditExportOperation`, not an unbounded synchronous search response. Export membership is frozen by exact tenant/filter/time-window context plus an Audit-owned `recordedAt` cutoff, then emitted in deterministic bounded pages to a closed UTF-8 NDJSON schema containing only the currently public AuditRecord fields.

Large artifact bytes belong behind a typed external artifact-store adapter; only opaque artifact metadata, byte/record counts, SHA-256 digest, completion state and configured artifact expiry belong in Audit process state. External storage calls remain outside authoritative Audit transactions.

The first archive mechanism defined by ADR-0034 creates immutable verified archive segments without deleting or updating source `audit_record` rows. Public read/search therefore remains online-store backed in this tranche. Destructive AuditRecord purge remains fail-closed pending a separate accepted legal-hold/purge decision.

## Deferred

- ADR-0034 durable export/download runtime and machine-readable API surface;
- archive-segment runtime and versioned retention-policy runtime;
- destructive AuditRecord purge/legal-hold semantics;
- transparent archived-record query after any future online removal;
- EvidenceSnapshot;
- material snapshots and integrity-chain metadata;
- SIEM transport;
- richer search/reporting that would require new explicit requirements.
