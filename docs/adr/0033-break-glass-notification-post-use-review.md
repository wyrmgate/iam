# ADR-0033: Break-glass notification delivery and post-use review completion

Status: Accepted

## Context

ADR-0032 established AdministrativeBreakGlassOperation as distinct Administration-owned emergency authority with short finite validity, configured fail-closed policy, current provider-neutral STRONG assurance, reason/incident evidence, AuditRecord evidence, and two durable Administration-owned obligations created atomically with activation:

- SECURITY_NOTIFICATION
- POST_USE_REVIEW

The controlled v0.6 baseline deliberately remains only partially complete for SEC-ADM-006 because the obligation rows exist but there is no concrete notification delivery path and no typed post-use review completion workflow.

This ADR completes those process semantics without changing whether emergency authority is valid. Notification or review failure must never extend, revoke, recreate, or otherwise rewrite a valid break-glass authority decision.

Issue #231 records the implementation tranche.

## Decision

### 1. Keep obligation completion separate from authority validity

AdministrativeBreakGlassOperation remains the sole emergency authority source.

Authority effectiveness continues to depend only on:

- Administration operation state;
- semantic validity time;
- governed actor eligibility;
- configured break-glass policy requirements; and
- current provider-neutral STRONG assurance where required at use time.

SECURITY_NOTIFICATION and POST_USE_REVIEW are durable compliance/process obligations. Their delivery/completion state does not change emergency authority effectiveness.

Activation still commits the break-glass operation and both obligation records atomically. External delivery never executes in that authoritative activation transaction.

### 2. Use one deployment-configured signed HTTPS security-notification destination

The first concrete SECURITY_NOTIFICATION transport is one deployment-configured HTTPS webhook destination.

This is deliberately a security-notification transport, not a public integration-event subscription service and not a general multi-recipient notification platform.

The adapter:

- is disabled unless explicitly configured;
- requires HTTPS;
- uses a deployment secret of at least 32 UTF-8 bytes;
- signs `<unix-seconds>.<raw-body-bytes>` with HMAC-SHA-256;
- sends a stable obligation/delivery identifier, event type, timestamp and signature headers;
- does not follow redirects;
- uses bounded connect/request timeouts;
- treats 2xx as successful durable acceptance;
- treats 408, 425, 429, 5xx and transient I/O as retryable;
- treats redirect and ordinary 4xx outcomes as terminal technical failure.

The payload is closed and data-minimized. It may contain only the minimum operational correlation necessary for security response, initially:

- notification version/type;
- tenant ID;
- break-glass operation ID;
- actor Identity ID;
- role ID;
- typed scope reference without resolved display/provider payload;
- incident/reference identifier;
- activatedAt;
- validUntil;
- correlation/causation IDs when present.

The payload excludes:

- free-form break-glass reason;
- provider-native authentication claims;
- raw assurance claims/tokens;
- permission-set expansion;
- secrets/private credential material;
- arbitrary Administration persistence payload.

The receiver can use current authorized IAM queries when richer permitted context is required.

### 3. Deliver notification through durable leased work

Administration owns notification obligation state/evidence. Platform owns only lease/timer/retry mechanics.

Notification delivery is driven by durable technical work keyed to the SECURITY_NOTIFICATION obligation. Workers claim retryable work at-least-once and re-read the current obligation before delivery.

Duplicate/replay delivery is expected. The receiver deduplicates by stable delivery/obligation identifier.

A successful delivery marks the SECURITY_NOTIFICATION obligation COMPLETED with completion evidence.

Retryable failure leaves it retryable with bounded backoff. Retry exhaustion or terminal failure moves the obligation to MANUAL_REQUIRED rather than silently marking notification complete.

Notification failure never rolls back activation and never extends authority.

### 4. Post-use review becomes actionable only after authority ends

POST_USE_REVIEW exists from activation time but cannot be completed while the emergency authority is still semantically effective.

The review becomes actionable when either:

- the operation is explicitly REVOKED; or
- current time is at or after validUntil.

Scheduler delay does not keep authority active and does not invalidate review eligibility. A review command always re-reads the operation and checks semantic authority end directly.

### 5. Administration owns typed post-use review evidence

The first post-use review is Administration-owned immutable evidence linked one-to-one to the POST_USE_REVIEW obligation.

A completed review records at minimum:

- break-glass operation ID;
- reviewer governed Identity ID;
- review outcome;
- bounded review summary;
- reviewedAt;
- correlation/causation IDs where present.

Initial outcome vocabulary is intentionally bounded:

- APPROVED_USE
- POLICY_CONCERN
- INCIDENT_FOLLOW_UP_REQUIRED

The review does not retroactively approve emergency authority and does not mutate historical reason/incident/assurance evidence.

The break-glass actor may not review their own operation.

The first slice authorizes review completion with the existing `administration:manage-authorization` operation permission plus explicit self-review denial. This avoids silently adding a new permission to INITIAL_TENANT_ADMIN. A future requirement may introduce independently delegated reviewer authority through a separate ADR.

### 6. Review completion is append-once and idempotent

A POST_USE_REVIEW obligation may be completed exactly once.

The review evidence is immutable after completion. Retry of the same causal completion converges on the same completed result; semantic mismatch for the same idempotency key fails.

Completing the review marks the corresponding POST_USE_REVIEW obligation COMPLETED in the same Administration transaction.

A second materially different review is rejected rather than overwriting evidence.

### 7. Audit remains data-minimized

Public review completion and explicit operator remediation actions produce data-minimized AuditRecord SUCCESS/DENIED/FAILURE evidence outside the authoritative Administration transaction.

AuditRecord may identify:

- actual governed actor;
- semantic action type;
- break-glass operation/review resource ID;
- normalized outcome;
- correlation/causation.

It does not copy the free-form reason, review summary, provider-native assurance claims, or security-notification payload.

Audit failure never rewrites the Administration result.

### 8. Public API remains semantic

The public Administration API adds explicit semantic operations rather than arbitrary obligation-state PATCH.

The first review operation is:

`POST /api/v1/administrative-break-glass-operations/{id}:complete-review`

It requires:

- authenticated governed actor;
- current `administration:manage-authorization` authorization;
- actor != break-glass actor;
- Idempotency-Key;
- strong If-Match against the current break-glass/obligation revision context;
- typed outcome;
- bounded review summary.

Notification transport configuration and retry state are not exposed as mutable public CRUD resources.

### 9. Keep deployment/environment choice outside canonical domain architecture

The signed HTTPS webhook is the first concrete implementation adapter, not a canonical requirement that every future deployment use HTTP.

A future adapter may use a broker, incident-management platform or another transport without changing Administration obligation semantics, provided it preserves the same data-minimization, retry and durable-completion contract.

Multiple independent notification destinations require durable per-destination delivery evidence and are deferred.

## Consequences

- SEC-ADM-006 gains a concrete path to fully implemented notification and post-use review.
- Break-glass activation remains available even if notification infrastructure is temporarily unavailable.
- Security teams receive durable, retryable notification without leaking reason or authentication-provider detail.
- Review evidence is explicit and immutable rather than hidden in logs or mutable obligation flags.
- Review completion cannot become a retroactive authorization or self-attestation bypass.
- Platform remains technical scheduling infrastructure and does not own compliance meaning.
- Existing INITIAL_TENANT_ADMIN permission membership is unchanged.

## Deferred

- multiple security-notification destinations;
- broker/fan-out notification transport;
- independently delegated break-glass reviewer permission/assignment model;
- multi-stage post-use review;
- automatic incident-ticket mutation/closure;
- cross-tenant platform/support emergency access;
- provider-specific authentication-assurance mapping policy.
