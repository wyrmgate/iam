# Audit API Contract

## Scope

The implemented public Audit control-plane exposes immutable `AuditRecord` evidence under ADR-0031 plus the first durable ADR-0034 export runtime. Audit remains evidence rather than business authority, an event store, or an observability log. Immutable archive-segment and retention-policy runtime remain deferred.

Base path: `/api/v1`.

Public operations:

- `GET /audit-records` — bounded deterministic AuditRecord search;
- `GET /audit-records/{auditRecordId}` — tenant-scoped AuditRecord read;
- `POST /audit-exports` — request one durable asynchronous closed-schema export;
- `GET /audit-exports/{auditExportId}` — read export process/artifact metadata without exposing storage references;
- `POST /audit-exports/{auditExportId}:download` — re-authorized no-store streaming download after success.

Machine-readable contract:

- `apps/server/src/main/resources/contracts/openapi/audit-v1.json`

## Authorization

The implemented read/search operations require the default-deny semantic permission:

- `audit:read`.

The durable export operations require the separate semantic permission `audit:export`. `audit:read` does not imply `audit:export`, and INITIAL_TENANT_ADMIN is not silently expanded.

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

## Durable export runtime

`AuditExportOperation` is now implemented as a durable asynchronous Audit process. Create requires an exact occurrence-time window `[occurredFrom, occurredUntil)`, optional exact AuditRecord filters, and `Idempotency-Key`. The accepted request captures `snapshotRecordedAt`; later records with an older `occurredAt` but a newer `recordedAt` do not enter that export.

Workers page the frozen membership in deterministic `occurredAt ASC, id ASC` order and write closed UTF-8 NDJSON v1 containing only the public AuditRecord fields. Bounded continuation/count progress is persisted while the external artifact is rebuilt deterministically on retry. Artifact bytes remain outside PostgreSQL behind `AuditExportArtifactStore`; Audit stores only the opaque internal reference, byte/record counts, SHA-256 digest and optional configured expiry.

The first concrete adapter is a deployment-configured filesystem path and is disabled by default. The path must itself provide the durability/shared-storage properties required by its deployment; an ephemeral instance filesystem is not production HA/DR evidence. Public representations never expose the internal artifact reference. Download is re-authorized at request time and uses `Cache-Control: no-store`.

The immutable archive-segment mechanism defined by ADR-0034 is not yet implemented. Public AuditRecord read/search remains online-store backed. Destructive purge remains fail-closed pending a separate legal-hold/purge decision.

## Deferred

- archive-segment runtime and versioned retention-policy runtime;
- destructive AuditRecord purge/legal-hold semantics;
- transparent archived-record query after any future online removal;
- EvidenceSnapshot;
- material snapshots and integrity-chain metadata;
- SIEM transport;
- richer search/reporting that would require new explicit requirements.
