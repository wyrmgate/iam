# Break-glass Security Notification Operations

## Scope

This runbook activates the ADR-0033 SECURITY_NOTIFICATION adapter for Administration break-glass operations.

The adapter sends one data-minimized signed HTTPS notification per durable Administration obligation. It is not the public integration-event webhook, not a generic notification platform, and not a multi-recipient fan-out service.

Emergency authority validity never depends on delivery success. Notification failure cannot extend, revoke, recreate, or roll back a break-glass operation.

## Runtime configuration

Delivery is disabled unless explicitly enabled.

Required:

- `IAM_BREAK_GLASS_NOTIFICATION_ENABLED=true`
- `IAM_BREAK_GLASS_NOTIFICATION_ENDPOINT=https://...`
- `IAM_BREAK_GLASS_NOTIFICATION_SECRET=<deployment secret of at least 32 UTF-8 bytes>`

Optional tuning:

- `IAM_BREAK_GLASS_NOTIFICATION_CONNECT_TIMEOUT` — default `PT5S`
- `IAM_BREAK_GLASS_NOTIFICATION_REQUEST_TIMEOUT` — default `PT10S`
- `IAM_BREAK_GLASS_NOTIFICATION_POLL_INTERVAL` — default `PT1S`
- `IAM_BREAK_GLASS_NOTIFICATION_CLAIM_LEASE` — default `PT30S`
- `IAM_BREAK_GLASS_NOTIFICATION_BATCH_SIZE` — default `25`
- `IAM_BREAK_GLASS_NOTIFICATION_MAX_ATTEMPTS` — default `8`
- `IAM_BREAK_GLASS_NOTIFICATION_RETRY_BASE_DELAY` — default `PT5S`
- `IAM_BREAK_GLASS_NOTIFICATION_RETRY_MAX_DELAY` — default `PT5M`

The endpoint must use HTTPS and must not contain user-info credentials or a fragment. Redirects are not followed.

Never place the notification secret in Git, logs, AuditRecord, support tickets, or ordinary application configuration committed to the repository.

## Receiver contract

Each delivery is JSON and includes these headers:

- `Content-Type: application/json`
- `X-Wyrmgate-Notification-Id: <SECURITY_NOTIFICATION obligation UUID>`
- `X-Wyrmgate-Notification-Type: iam.administration.break-glass.security-notification.v1`
- `X-Wyrmgate-Delivery-Timestamp: <Unix seconds>`
- `X-Wyrmgate-Signature: v1=<lowercase hex HMAC-SHA-256>`

Signature input is exactly:

`<unix-seconds>.<raw-request-body-bytes>`

The receiver should:

1. read raw body bytes before JSON reserialization;
2. enforce a bounded timestamp-skew/replay window;
3. calculate HMAC-SHA-256 with the shared secret;
4. compare signatures in constant time;
5. deduplicate by `X-Wyrmgate-Notification-Id`;
6. durably accept responsibility before returning 2xx.

Delivery is at-least-once. Retries reuse the same obligation ID and therefore must be deduplicated.

## Payload boundary

The v1 payload is closed and data-minimized. It contains operational correlation only:

- version/type;
- tenant ID;
- security-notification obligation ID;
- break-glass operation ID;
- actor Identity ID;
- role ID;
- typed scope reference;
- incident/reference identifier;
- activation time;
- valid-until time;
- correlation/causation IDs when present.

It deliberately excludes:

- free-form emergency reason;
- raw/provider-native authentication or assurance claims;
- permission expansion;
- secrets/private credential material;
- arbitrary Administration persistence data.

Use separately authorized IAM queries if the security responder requires richer context.

## Delivery outcomes

- **2xx** — durable acceptance; obligation becomes `COMPLETED`.
- **408 / 425 / 429 / 5xx / transient I/O** — retryable while the bounded attempt budget remains.
- **3xx / ordinary 4xx / encoding failure** — terminal; obligation becomes `MANUAL_REQUIRED`.
- **Retry exhaustion** — obligation becomes `MANUAL_REQUIRED`.

Only normalized technical error codes are retained. Remote response bodies and raw exception detail are not stored as ordinary obligation evidence.

A `MANUAL_REQUIRED` notification does not change emergency authority history or post-use-review eligibility. It requires explicit operator follow-up.

## Activation procedure

Before enabling an environment:

1. establish the intended single security receiver;
2. verify it can preserve raw body bytes, validate the HMAC signature, enforce timestamp skew, deduplicate by obligation ID, and durably accept before 2xx;
3. provision a random shared secret using the deployment secret manager;
4. set the endpoint and secret;
5. enable notification delivery;
6. deploy a reviewed `main` revision with green exact-head checks;
7. exercise a non-production break-glass activation using non-sensitive test evidence;
8. verify one notification is received with valid signature and the obligation reaches `COMPLETED`;
9. exercise a safe retryable response such as 429/5xx and verify retry with the same obligation ID;
10. exercise a safe terminal 4xx and verify `MANUAL_REQUIRED`;
11. confirm emergency authority expiry/revocation remains independent of notification state.

## Secret rotation

The first adapter has one active secret.

Rotate as a coordinated deployment change:

1. prepare receiver-side overlap or a controlled maintenance window;
2. install the new secret at the receiver;
3. update the deployment secret;
4. restart/redeploy Wyrmgate;
5. verify a new test notification;
6. retire the old receiver secret after the overlap window.

## Operational checks

Alert or investigate when:

- PENDING notification age grows unexpectedly;
- repeated retries approach the configured attempt limit;
- any obligation becomes `MANUAL_REQUIRED`;
- the receiver rejects signatures or timestamps;
- delivery polling fails repeatedly.

Do not manually mark an obligation COMPLETED with ad-hoc SQL. Any future explicit operator retry/requeue/remediation operation must be semantic, authorized, auditable, and Administration-owned.

## Multi-destination boundary

Do not configure multiple URLs by concatenation or duplicate POSTs inside this adapter.

Multiple independent destinations require durable per-destination delivery evidence or a broker/fan-out architecture and remain deferred by ADR-0033.
