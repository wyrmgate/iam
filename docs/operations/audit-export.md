# Audit Export Operations

## Scope

This runbook covers the first ADR-0034 durable Audit export runtime. It is a tenant-scoped asynchronous export of immutable AuditRecord evidence to a closed UTF-8 NDJSON v1 artifact.

Export is disabled by default. It does not replace bounded AuditRecord search, SIEM transport, EvidenceSnapshot, immutable archive-segment retention, or destructive purge/legal-hold policy.

## Runtime configuration

Enable the worker/API creation path only when a deployment provides storage appropriate to that environment:

- `IAM_AUDIT_EXPORT_ENABLED=true`
- `IAM_AUDIT_EXPORT_DIRECTORY=/path/to/durable/shared/audit-export-storage`

Optional tuning:

- `IAM_AUDIT_EXPORT_POLL_INTERVAL` — default `PT1S`
- `IAM_AUDIT_EXPORT_CLAIM_LEASE` — default `PT30S`
- `IAM_AUDIT_EXPORT_BATCH_SIZE` — default `10`
- `IAM_AUDIT_EXPORT_MAX_ATTEMPTS` — default `5`
- `IAM_AUDIT_EXPORT_RETRY_DELAY` — default `PT5S`
- `IAM_AUDIT_EXPORT_ARTIFACT_RETENTION` — optional duration; blank means no runtime expiry is assigned

The first adapter writes to a configured filesystem root. That root is merely an implementation adapter. For production, the path must itself be backed by storage whose durability, sharing, encryption, access control, backup and recovery characteristics satisfy the selected production design. Local ephemeral instance files are not production evidence.

## Request contract

Creating an export requires:

- `audit:export`;
- `Idempotency-Key`;
- exact `occurredFrom` and `occurredUntil` bounds;
- optional exact actor/action/resource/outcome/correlation filters.

The accepted operation records an Audit-owned `snapshotRecordedAt`. A later AuditRecord whose `occurredAt` falls inside the request window but whose `recordedAt` is newer than that cutoff is not part of the export.

Reusing the same idempotency key with the same normalized request returns the same operation. Reusing it with a materially different request fails.

## Worker behavior

The worker:

1. claims Platform scheduled work with a lease;
2. re-reads the AuditExportOperation;
3. resets a retryable REQUESTED/RUNNING attempt to a fresh deterministic artifact rewrite;
4. reads frozen source membership in bounded `occurredAt ASC, id ASC` pages;
5. writes only the public AuditRecord fields as one JSON object per line;
6. persists bounded continuation/count progress after each page;
7. atomically replaces the deterministic artifact only after the stream completes;
8. records record count, byte count, SHA-256 and optional expiry in Audit;
9. marks the operation SUCCEEDED and the technical work complete.

If processing fails before the configured attempt ceiling, technical work is rescheduled. A later attempt rewrites the artifact from the frozen snapshot rather than trusting a partial external object. Retry exhaustion records normalized FAILED process evidence.

## Download

`POST /api/v1/audit-exports/{id}:download`:

- requires current `audit:export` authorization;
- succeeds only for a SUCCEEDED, non-expired artifact;
- uses `Cache-Control: no-store`;
- streams the artifact without returning the internal storage reference;
- does not persist or expose signed provider URLs or credentials.

## Data minimization

NDJSON v1 contains only:

- id;
- occurredAt;
- recordedAt;
- optional actorId;
- actionType;
- resourceType;
- optional resourceId;
- outcome;
- optional correlationId;
- optional causationId.

It excludes raw secrets/private credential material, material snapshots, integrity metadata, provider-native authentication claims, free-form domain payloads, and future EvidenceSnapshot content.

## Operational checks

When export is enabled:

1. create a small non-sensitive export through the authorized API;
2. verify the operation reaches SUCCEEDED;
3. verify record/byte counts and SHA-256 are present;
4. download and verify the NDJSON is ordered oldest-first within the frozen membership;
5. append a late AuditRecord with an older occurrence time and prove it does not alter an already accepted export;
6. exercise a safe artifact-storage failure and verify retry/FAILED behavior;
7. verify the internal artifact reference/path never appears in the public resource, logs or AuditRecord payload;
8. include the configured artifact root in environment backup/recovery and access-control review if the deployment depends on it.

Archive/retention policy and destructive evidence purge remain separate later work.
