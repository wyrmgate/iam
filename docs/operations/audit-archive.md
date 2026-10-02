# Audit Archive and Retention Operations

## Scope

This runbook covers the ADR-0034 V54 internal Audit archive runtime and immutable tenant-scoped retention-policy foundation.

The runtime creates verified immutable evidence copies of frozen AuditRecord ranges. It does **not** delete, update, move, or transparently hydrate source AuditRecords. Destructive purge and legal-hold semantics remain fail-closed and require a separate accepted decision.

## Retention policy

`AuditRetentionPolicyVersion` is immutable and tenant-scoped. A policy version records four reviewed duration inputs:

- export artifact retention;
- archive-generation eligibility age;
- minimum online AuditRecord retention;
- minimum archive retention.

No duration is a canonical product default. Values come from reviewed security, legal, compliance, and operations requirements.

The current slice exposes policy registration only through a typed Audit application boundary. It intentionally does not add arbitrary public policy CRUD or a deployment-global tenant setting. Reusing a tenant policy version with materially different values fails rather than mutating history.

Export completion resolves the policy effective at the export's immutable `snapshotRecordedAt` acceptance cutoff. If one exists, its export-artifact retention controls recorded artifact expiry. The existing export-specific retention setting is only a compatibility fallback when no policy version was effective for that export.

## Archive request boundary

An internal archive request supplies one tenant, exact occurrence-time range `[occurredFrom, occurredUntil)`, and optional correlation/causation IDs. A current retention policy must exist. The range is eligible only when `occurredUntil` is no newer than the policy's archive-eligibility age at request time.

Acceptance records the immutable retention-policy version ID, occurrence range, Audit-owned `snapshotRecordedAt` cutoff, archive schema version, correlation/causation and durable process state. The same tenant + policy version + occurrence range resolves to the same segment, so replay does not create competing archive evidence.

## Worker behavior

Archive processing is disabled by default. Enable it only when the shared Audit artifact root is backed by storage appropriate to the environment:

- `IAM_AUDIT_ARCHIVE_ENABLED=true`
- `IAM_AUDIT_EXPORT_DIRECTORY=/path/to/durable/shared/audit-artifact-storage`

Optional technical tuning:

- `IAM_AUDIT_ARCHIVE_POLL_INTERVAL` — default `PT1S`;
- `IAM_AUDIT_ARCHIVE_CLAIM_LEASE` — default `PT30S`;
- `IAM_AUDIT_ARCHIVE_BATCH_SIZE` — default `10`;
- `IAM_AUDIT_ARCHIVE_MAX_ATTEMPTS` — default `5`;
- `IAM_AUDIT_ARCHIVE_RETRY_DELAY` — default `PT5S`.

Platform scheduling owns only claim/lease/retry mechanics. Audit owns archive and retention semantics.

For each claimed segment the worker:

1. re-reads the exact pinned retention-policy version;
2. starts or restarts the Audit-owned process under revision fencing;
3. rebuilds the deterministic archive artifact from the beginning;
4. pages source AuditRecords in bounded `occurredAt ASC, id ASC` order;
5. includes only rows inside the exact occurrence range with `recordedAt <= snapshotRecordedAt`;
6. writes the closed archive NDJSON schema through the external Audit artifact-store boundary;
7. renews the technical lease and persists bounded continuation/count checkpoints between pages;
8. reopens the completed artifact and independently recomputes record count, byte count and SHA-256;
9. fails closed if any verification value differs;
10. only after verification records the opaque artifact reference, digest/count metadata, `verifiedAt`, completion time and `minimumRetainUntil`.

External artifact writes and verification reads happen outside the authoritative Audit transaction.

## Data minimization and artifact namespace

The filesystem adapter places archive artifacts under a tenant-separated `archive/` namespace beneath the configured Audit artifact root. The reference is opaque Audit process metadata, not a public provider-native identifier, credential, or signed URL.

Archive NDJSON v1 contains only id, occurredAt, recordedAt, optional actorId, actionType, resourceType, optional resourceId, outcome, optional correlationId and optional causationId. Secrets/private credential material, provider-native authentication claims, arbitrary domain payloads, material snapshots, integrity metadata and EvidenceSnapshot content remain excluded.

## Verification and failure

A segment can become `SUCCEEDED` only after the external artifact has been reopened and its count, size and digest match generation. Storage/read-back errors, mismatches, missing pinned policy, stale revision or lease failure cannot produce success. Retry rebuilds the same deterministic artifact from the frozen source set; exhaustion records normalized failure evidence.

## No-purge boundary

V54 does not implement AuditRecord deletion/update, legal holds, purge authorization, transparent archived-record query, archive replacement/compaction, public archive CRUD, EvidenceSnapshot, material snapshot/integrity-chain runtime, or SIEM transport.

`minimumOnlineRetention` is policy input only: because destructive purge does not exist, it grants no removal authority. `minimumArchiveRetention` becomes `minimumRetainUntil` evidence on successful segments, but V54 does not implement artifact deletion.

Any future destructive purge requires a separate accepted ADR defining legal holds, verified archive prerequisites, authorization/dual control, exact tenant/range selection, deletion evidence, retry/failure semantics and public read behavior after online removal.

## Operational verification

1. Register a reviewed immutable tenant policy version through the typed Audit application boundary.
2. Request an eligible non-sensitive historical range.
3. Verify the segment reaches `SUCCEEDED` with record count, byte count, SHA-256, `verifiedAt` and `minimumRetainUntil`.
4. Append a late AuditRecord with occurrence inside the range but recording time after the cutoff and prove it is excluded.
5. Replay the same tenant/policy/range and prove the same segment is returned.
6. Exercise a safe read-back corruption/failure and prove verification fails closed.
7. Verify source `audit.audit_record` rows are unchanged by archive generation.
8. Verify internal artifact references never appear in public APIs, ordinary logs or AuditRecord payloads.
9. Include the shared artifact root in storage protection, backup/recovery, encryption and access-control review.
