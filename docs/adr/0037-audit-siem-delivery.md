# ADR-0037: Audit SIEM delivery boundary

Status: Accepted

## Context

Audit export/archive are user/evidence lifecycle concerns. Public integration events are curated business integration contracts. SIEM delivery is a separate security telemetry/evidence transport and must not collapse those models.

## Decision

### 1. SIEM delivery is an Audit-owned outbound transport

A successfully appended AuditRecord may enqueue technical SIEM delivery work after/with the Audit transaction.

The closed SIEM v1 message contains only the public AuditRecord fields plus a stable delivery/event ID and schema version. Optional closed material display snapshot may be included only when present and safe. Raw secrets/private credential material and arbitrary domain payloads remain prohibited.

### 2. Delivery is at-least-once and idempotent

Platform scheduled work owns lease, retry and next-attempt mechanics.

The Audit SIEM worker:

1. claims one Audit SIEM work item;
2. re-reads the immutable AuditRecord;
3. maps it to the closed SIEM v1 message;
4. calls the configured publisher outside the authoritative Audit transaction;
5. marks technical work complete on durable acceptance;
6. retries normalized transient failures;
7. records terminal/exhausted delivery state without mutating the AuditRecord.

Receivers deduplicate by stable message ID derived from the AuditRecord ID.

### 3. First adapter is one deployment-configured signed HTTPS destination

The first concrete adapter sends JSON over HTTPS and signs the exact body using HMAC-SHA-256 with a deployment secret. It has bounded connect/request timeouts and does not follow redirects.

- 2xx = accepted;
- 408/425/429/5xx and transient I/O = retryable;
- redirects and other 4xx = terminal technical failure.

Provider endpoint/secret are deployment configuration and never authoritative Audit state.

### 4. SIEM failure never rewrites business or Audit evidence

AuditRecord append remains valid even if SIEM is disabled, unavailable or permanently failing. SIEM technical delivery state is not AuditRecord outcome and is not governance authority.

### 5. Backfill is explicit

Enabling SIEM does not silently replay all historical AuditRecords. Historical backfill, if required, is a separate bounded export/replay operation.

## Consequences

- SIEM remains separate from public integration-event publication;
- external calls remain outside authoritative transactions;
- delivery is retry-safe and data-minimized;
- an unavailable SIEM cannot block privilege reduction or rewrite completed IAM decisions.
