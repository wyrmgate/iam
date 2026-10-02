# Audit Archive Operations

## Scope

This runbook covers the ADR-0034 immutable Audit archive-segment runtime and versioned retention-policy foundation introduced with Flyway V54.

Archive generation creates a verified immutable evidence copy of a frozen AuditRecord range. It does not delete or rewrite source AuditRecord rows, replace public AuditRecord search, provide transparent archived-record hydration, or implement legal hold/destructive purge.

The archive worker is disabled by default.

## Retention policy prerequisite

Archive generation requires one effective tenant-owned AuditRetentionPolicyVersion.

A policy version is immutable and records export artifact lifetime, archive-generation eligibility age, minimum online AuditRecord retention, minimum archive retention, effective time, and correlation/causation evidence. These durations are deployment/security/legal inputs; there are no canonical hard-coded retention periods.

Creating a retention-policy version does not authorize physical AuditRecord deletion. No purge path exists in this runtime.

## Runtime configuration

Enable archive processing only when the deployment provides durable storage appropriate to the environment:

- IAM_AUDIT_ARCHIVE_ENABLED=true
- IAM_AUDIT_ARCHIVE_DIRECTORY=/path/to/durable/shared/audit-archive-storage

Optional worker tuning:

- IAM_AUDIT_ARCHIVE_POLL_INTERVAL — default PT1S
- IAM_AUDIT_ARCHIVE_CLAIM_LEASE — default PT30S
- IAM_AUDIT_ARCHIVE_BATCH_SIZE — default 10
- IAM_AUDIT_ARCHIVE_MAX_ATTEMPTS — default 5
- IAM_AUDIT_ARCHIVE_RETRY_DELAY — default PT5S

The current filesystem adapter is an implementation adapter only. Production durability, sharing, encryption, access control, backup and recovery properties belong to the selected deployment storage. Local ephemeral instance storage is not durable archive evidence.

## Frozen membership

When an archive segment is requested, Audit records tenant, the closed occurrence range [occurredFrom, occurredUntil), snapshotRecordedAt, archive schema version, effective retention-policy version, and correlation/causation IDs.

Only AuditRecords satisfying both the occurrence range and recordedAt <= snapshotRecordedAt belong to the segment. A later record with an older occurrence time but a newer recording time is excluded.

The requested occurrence range must already satisfy the effective policy's archive-eligibility age.

## Worker behavior

The worker:

1. claims bounded Platform scheduled work under a lease;
2. re-reads current AuditArchiveSegment state;
3. resets a retryable REQUESTED/RUNNING attempt for deterministic regeneration;
4. pages frozen AuditRecord membership in occurredAt ASC, id ASC order;
5. writes closed UTF-8 NDJSON containing only the public AuditRecord fields;
6. persists bounded continuation and record/byte counts after each page;
7. uses a deterministic tenant + segment-ID artifact reference through the external artifact-store boundary;
8. re-opens the completed artifact;
9. recomputes record count, byte count and SHA-256 from stored bytes;
10. commits SUCCEEDED only when all three values match the generated values.

Artifact I/O occurs outside the authoritative Audit transaction.

If storage or processing fails before the attempt ceiling, technical work is rescheduled. Retry rewrites the same deterministic external object from the frozen membership. On retry exhaustion, Audit records FAILED process evidence.

If re-open verification fails or any count/hash differs, the process fails closed with audit_archive_verification_failed. No artifact reference or digest is committed to the failed segment.

## Immutability

Retention-policy versions reject UPDATE and DELETE.

AuditArchiveSegment freezes its tenant, retention-policy version, occurrence bounds, recording cutoff, schema version, causal metadata and creation evidence. Processing may update only process/checkpoint fields while non-terminal. SUCCEEDED and FAILED segments reject later mutation, and all segment deletion is rejected.

audit.audit_record keeps its existing append-only UPDATE/DELETE rejection throughout archive generation.

## Data minimization

Archive NDJSON v1 contains only id, occurredAt, recordedAt, optional actorId, actionType, resourceType, optional resourceId, outcome, optional correlationId, and optional causationId.

It excludes raw secrets/private credential material, provider-native authentication claims, arbitrary domain payloads, material_snapshot, integrity_metadata, and EvidenceSnapshot content.

## Operational verification

Before enabling an environment:

1. create an explicit effective retention-policy version using reviewed values;
2. request a small eligible non-sensitive archive range through the internal application path;
3. verify the segment reaches SUCCEEDED;
4. verify the persisted record count, byte count and SHA-256;
5. independently inspect the artifact and confirm NDJSON ordering and closed schema;
6. insert a late AuditRecord with an in-range occurrence time after segment acceptance and prove it is excluded;
7. exercise storage corruption/unavailability and verify success is never committed without re-open verification;
8. verify terminal segment and retention-policy rows reject mutation;
9. verify source AuditRecord rows remain present and append-only;
10. include the archive root in backup/recovery and access-control review.

## Explicitly not implemented

AuditRecord physical purge, legal/retention holds, dual-control deletion, transparent archived-record query/hydration, EvidenceSnapshot, material snapshot or integrity-metadata runtime, SIEM transport, arbitrary public archive/retention CRUD, and cross-tenant/platform-support archive remain unavailable.

Any destructive purge requires a separate accepted ADR before implementation.
