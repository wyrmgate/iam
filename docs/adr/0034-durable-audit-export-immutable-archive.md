# ADR-0034: Durable Audit export and immutable evidence archive

Status: Accepted

## Context

ADR-0031 established Audit-owned immutable AuditRecord evidence, replay-safe append semantics, bounded tenant-scoped read/search, and the first public audit:read API.

The remaining Audit work includes high-cardinality export/download, longer-term archive/retention execution, EvidenceSnapshot, material snapshots/integrity metadata, and SIEM transport. These concerns have different semantics and must not be collapsed into one generic logging/export subsystem.

Issue #235 records this tranche.

The current runtime stores append-only AuditRecord rows in PostgreSQL. Public search is intentionally bounded and must not become an unbounded synchronous download mechanism. External/object-storage calls cannot execute inside the authoritative Audit transaction.

## Decision

### 1. Model export as an Audit-owned durable process

Large Audit export is represented by an AuditExportOperation, not by an unbounded GET /audit-records response.

The operation records immutable request context: tenant; requesting governed actor; exact bounded Audit filters; required occurrence-time window [occurredFrom, occurredUntil); an Audit-owned recordedAt snapshot cutoff selected when the request is accepted; export schema version; causal idempotency identity; correlation/causation; and creation time.

The mutable process state is distinct from the immutable request:

REQUESTED -> RUNNING -> SUCCEEDED | FAILED

A successful operation may later have an expired/deleted download artifact while the operation/evidence remains queryable. Artifact expiry therefore does not rewrite the completed export result.

### 2. Freeze export membership with the Audit recording-time cutoff

occurredAt is producer-supplied semantic occurrence time and may be older than insertion time. It cannot alone freeze a concurrent append set.

An export includes only AuditRecords that satisfy the same tenant, the immutable export filters, occurredAt >= occurredFrom, occurredAt < occurredUntil, and recordedAt <= snapshotRecordedAt.

The worker reads those rows in deterministic occurredAt ASC, id ASC order using bounded keyset pages. This makes replay and retry converge on one closed record set even when a late AuditRecord is appended later with an older occurredAt.

### 3. Use a closed data-minimized export schema

The first export schema is versioned separately from database rows and contains only the currently public AuditRecord fields: id, occurredAt, recordedAt, optional actorId, actionType, resourceType, optional resourceId, outcome, optional correlationId, and optional causationId.

The first artifact format is UTF-8 NDJSON with one closed-schema AuditRecord per line.

It does not export raw secrets/private credential material, provider-native authentication claims, arbitrary domain payloads, material_snapshot, integrity_metadata, or future EvidenceSnapshot content. Adding any of those requires a separate governed schema/version decision.

### 4. Keep artifact storage behind an external adapter

Large export bytes are not stored in the authoritative relational database.

Audit writes artifacts through a typed AuditExportArtifactStore-style port. The concrete deployment may use object/blob storage or another durable file service. The artifact write happens outside the authoritative Audit transaction.

To tolerate uncertain outcomes, the object key is deterministic from tenant + export operation ID. Retry must either idempotently replace the same incomplete object or verify an existing object before treating it as the result.

After successful write, Audit commits only data-minimized artifact metadata: opaque storage reference, content type/schema version, byte size, record count, SHA-256 digest, completed time, and artifact expiry time when configured.

Temporary signed download URLs or provider credentials are never persisted as authoritative Audit state.

### 5. Add a dedicated export permission

Starting an export requires a new semantic Administration permission: audit:export.

It is distinct from audit:read. The first export operation is collection-scoped because it selects multiple records. A specific-resource audit:read grant does not imply collection export authority.

INITIAL_TENANT_ADMIN permission membership is not silently expanded. Deployment/bootstrap policy must explicitly grant audit:export where required.

### 6. Retryable mutation semantics remain causal and explicit

POST /api/v1/audit-exports requires Idempotency-Key.

Replaying the same key with the same canonical request returns the same operation. Reusing the key for materially different filters/window/schema fails.

Workers assume at-least-once execution, persist bounded continuation, and re-read current operation state before each page and before finalization. A failure never mutates source AuditRecords.

### 7. Keep download separate from export execution

The public API exposes the durable operation and an explicit download action/result only after SUCCEEDED.

The application may stream from the artifact store or issue a short-lived provider download redirect through an adapter. Provider-native object identifiers, credentials, and long-lived signed URLs are not public IAM resource state.

Download access is re-authorized at request time and is Cache-Control: no-store.

### 8. Define immutable archive segments without weakening AuditRecord append-only state

The first archive mechanism creates immutable AuditArchiveSegment evidence copies from a frozen AuditRecord range.

A segment records at minimum tenant, closed occurrence/recording cutoff range, archive schema version, deterministic artifact reference, record count, byte size, SHA-256 digest, creation/completion evidence, and correlation/causation.

Archive generation is durable, retry-safe, and uses the same external artifact-store boundary.

Archive creation does not update or delete the source audit.audit_record rows. Public Audit read/search continues to use the authoritative online AuditRecord store in this tranche.

This deliberately treats archive as a verified immutable evidence copy first, rather than weakening append-only database protections merely to reduce storage cost.

### 9. Retention policy is explicit and versioned; destructive purge remains fail-closed

Audit may own immutable versioned retention policy for export artifact lifetime, archive-generation eligibility, minimum online AuditRecord retention, and minimum archive retention.

No fixed duration is canonical. Values come from reviewed security/legal/operations requirements.

Physical deletion of AuditRecord evidence is not enabled by this ADR.

A future destructive purge requires a separate accepted decision that defines at least legal/retention hold semantics, proof that required archive copies exist and verify, authorization and dual-control requirements where applicable, exact tenant/range selection, deletion evidence, failure/retry semantics, and public read behavior after online removal.

Until that decision exists, AuditRecord append-only update/delete rejection remains in force.

### 10. Keep EvidenceSnapshot and material snapshot separate

EvidenceSnapshot remains a separate immutable evidence concept for decision-time context. AuditRecord.material_snapshot and integrity_metadata remain unused/reserved.

Audit export/archive does not create a generic JSON snapshot mechanism and does not copy foreign capability state into Audit merely for convenience.

### 11. SIEM transport remains a separate concern

A SIEM/event transport is push/integration delivery, not the same thing as a user-requested export or long-term evidence archive. It remains deferred to a separate transport decision with its own delivery/replay/data-minimization semantics.

## Public interface direction

The next implementation slice should use semantic resources similar to:

- POST /api/v1/audit-exports
- GET /api/v1/audit-exports/{auditExportId}
- POST /api/v1/audit-exports/{auditExportId}:download or an equivalent explicitly authorized download operation

Export creation is asynchronous and returns the durable operation rather than the artifact body.

Archive/retention administration is not exposed as arbitrary CRUD. Any later public/admin surface must use explicit semantic policy/operation resources.

## Consequences

- high-cardinality Audit download no longer pressures bounded synchronous search;
- concurrent/late AuditRecord append does not make one export nondeterministic;
- external object storage remains an adapter rather than canonical Audit authority;
- export artifacts can expire independently from Audit evidence;
- archive evidence can be introduced without weakening current append-only protections;
- destructive retention remains fail-closed until legal-hold/purge semantics are explicit;
- EvidenceSnapshot, material snapshots and SIEM remain separate decisions.

## Deferred

- destructive AuditRecord purge and legal-hold model;
- archived-record transparent query/hydration after online deletion;
- EvidenceSnapshot runtime;
- material_snapshot and integrity_metadata contracts;
- SIEM/event transport;
- multi-format export beyond the closed NDJSON v1 schema;
- cross-tenant/platform-support export.
