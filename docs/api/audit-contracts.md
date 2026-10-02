# Audit API Contract

## Scope

The implemented Audit control-plane exposes immutable `AuditRecord` evidence under ADR-0031, durable export/archive under ADR-0034, governed evidence lifecycle under ADR-0035, typed evidence snapshots/integrity under ADR-0036, and separate SIEM delivery under ADR-0037. Audit remains evidence rather than business authority, an event store, or an observability log.

Base path: `/api/v1`.

Public operations:

- `GET /audit-records` — bounded deterministic AuditRecord search;
- `GET /audit-records/{auditRecordId}` — tenant-scoped AuditRecord read;
- `POST /audit-exports` — request one durable asynchronous closed-schema export;
- `GET /audit-exports/{auditExportId}` — read export process/artifact metadata without exposing storage references;
- `POST /audit-exports/{auditExportId}:download` — re-authorized no-store streaming download after success;
- `POST /audit-evidence-lifecycle/legal-holds` and `GET /audit-evidence-lifecycle/legal-holds/{holdId}` — create/read explicit legal holds;
- `POST /audit-evidence-lifecycle/legal-holds/{holdId}:release` — revision-guarded hold release;
- `POST /audit-evidence-lifecycle/purges` and `GET /audit-evidence-lifecycle/purges/{purgeId}` — request/read destructive purge operations;
- `POST /audit-evidence-lifecycle/purges/{purgeId}:approve` — separately authorized dual-control approval;
- `GET /evidence-snapshots` and `GET /evidence-snapshots/{evidenceSnapshotId}` — bounded immutable EvidenceSnapshot search/read.

Machine-readable contract:

- `apps/server/src/main/resources/contracts/openapi/audit-v1.json`

## Authorization

The implemented read/search operations require the default-deny semantic permission:

- `audit:read`.

The durable export operations require `audit:export`. Legal-hold management uses `audit:hold`; destructive purge uses `audit:purge`; EvidenceSnapshot reads use `evidence-snapshot:read`. These permissions are independent, default-deny, and are not silently added to INITIAL_TENANT_ADMIN.

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

`materialSnapshot` is an optional closed `audit-material-v1` explanatory display snapshot and `integrityMetadata` is derived `audit-integrity-v1` SHA-256 tamper-detection metadata. Stable IDs remain authoritative references. Raw secrets/private credential material remain prohibited.

AuditRecord remains immutable evidence. Reading Audit does not mutate or reconstruct capability authority.

## Durable export runtime

`AuditExportOperation` is now implemented as a durable asynchronous Audit process. Create requires an exact occurrence-time window `[occurredFrom, occurredUntil)`, optional exact AuditRecord filters, and `Idempotency-Key`. The accepted request captures `snapshotRecordedAt`; later records with an older `occurredAt` but a newer `recordedAt` do not enter that export.

Workers page the frozen membership in deterministic `occurredAt ASC, id ASC` order and write closed UTF-8 NDJSON v1 containing only the public AuditRecord fields. Bounded continuation/count progress is persisted while the external artifact is rebuilt deterministically on retry. Artifact bytes remain outside PostgreSQL behind `AuditExportArtifactStore`; Audit stores only the opaque internal reference, byte/record counts, SHA-256 digest and optional configured expiry.

The first concrete adapter is a deployment-configured filesystem path and is disabled by default. The path must itself provide the durability/shared-storage properties required by its deployment; an ephemeral instance filesystem is not production HA/DR evidence. Public representations never expose the internal artifact reference. Download is re-authorized at request time and uses `Cache-Control: no-store`.

Flyway V54 implements archive-segment and immutable retention-policy state. V55 adds explicit legal holds, dual-control purge operations, a segment-specific archived-record query index, a transparent online-or-archived AuditRecord read view, and a database purge fence. Retention policy alone never grants deletion authority: execution revalidates minimum-online retention, a verified covering archive, exact archived-row membership, no matching ACTIVE hold, and requester/approver separation immediately before deletion. Ordinary AuditRecord UPDATE/DELETE remains rejected by the database trigger.

V56 activates the reserved material/integrity columns as closed typed contracts and adds relational immutable `EvidenceSnapshot`. New AuditRecords derive SHA-256 integrity metadata inside Audit; replay verifies stored integrity when present. Archive NDJSON v2 and the archived query index preserve material/integrity evidence. EvidenceSnapshot creation is an internal typed producer boundary; the public API is read-only and exact-filtered.

ADR-0037 SIEM delivery is a separate opt-in technical transport. A committed AuditRecord is offered to Platform scheduled work only after the Audit transaction completes. Delivery re-reads the immutable record, emits a closed `audit-siem-v1` message to one deployment-configured signed HTTPS endpoint, retries bounded transient failures, and never rewrites AuditRecord outcome or business authority.

## Deferred

- archive artifact deletion after `minimumArchiveRetention` plus legal/compliance policy authorization;
- historical SIEM backfill/replay as a separate bounded operation;
- multi-destination SIEM fan-out;
- richer search/reporting that would require new explicit requirements.
