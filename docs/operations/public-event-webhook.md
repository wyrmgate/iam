# Public Integration Event Webhook Operations

## Scope

This runbook covers the first ADR-0013 external delivery adapter for curated Wyrmgate public integration events. It is a **single deployment-configured HTTPS destination**, not a subscription service or multi-consumer fan-out layer.

Identity domain mutations do not call the webhook directly. Authoritative state and internal facts commit first; the runtime dispatcher later claims eligible outbox facts and invokes the webhook outside the authoritative transaction.

## Runtime configuration

Public webhook delivery remains disabled unless explicitly activated.

Required activation variables:

- `IAM_INTEGRATION_EVENTS_ENABLED=true`
- `IAM_INTEGRATION_EVENTS_TRANSPORT=webhook`
- `IAM_INTEGRATION_EVENTS_WEBHOOK_ENDPOINT=https://...`
- `IAM_INTEGRATION_EVENTS_WEBHOOK_SECRET=<deployment secret of at least 32 UTF-8 bytes>`

Optional tuning:

- `IAM_INTEGRATION_EVENTS_WEBHOOK_CONNECT_TIMEOUT` — default `PT5S`
- `IAM_INTEGRATION_EVENTS_WEBHOOK_REQUEST_TIMEOUT` — default `PT10S`
- `IAM_INTEGRATION_EVENTS_POLL_INTERVAL` — default `PT1S`
- `IAM_INTEGRATION_EVENTS_CLAIM_LEASE` — default `PT30S`
- `IAM_INTEGRATION_EVENTS_BATCH_SIZE` — default `50`
- `IAM_INTEGRATION_EVENTS_RETRY_BASE_DELAY` — default `PT5S`
- `IAM_INTEGRATION_EVENTS_RETRY_MAX_DELAY` — default `PT5M`

The endpoint must use HTTPS and must not contain user-info credentials or a fragment. The runtime HTTP client does not follow redirects.

Do not put the webhook secret in repository files, CI output, event payloads, audit records, logs, or troubleshooting tickets. Configure it through the deployment secret mechanism.

## Receiver contract

Each POST sends the AsyncAPI JSON payload unchanged with these headers:

- `Content-Type: application/json`
- `X-Wyrmgate-Event-Id: <eventId>`
- `X-Wyrmgate-Event-Type: <versioned channel/address>`
- `X-Wyrmgate-Delivery-Timestamp: <Unix seconds>`
- `X-Wyrmgate-Signature: v1=<lowercase hex HMAC-SHA-256>`

Signature input is exactly:

`<unix-seconds>.<raw-request-body-bytes>`

The receiver should:

1. read the raw request bytes before any JSON reserialization;
2. reject delivery timestamps outside its allowed clock-skew/replay window;
3. calculate HMAC-SHA-256 with the configured shared secret;
4. compare signatures using a constant-time comparison;
5. deduplicate by `eventId`;
6. validate/process the declared event version;
7. return 2xx only after it has durably accepted responsibility for the event.

At-least-once delivery means a previously accepted event may arrive again. Retries retain the same public `eventId` but use a fresh delivery timestamp/signature.

## HTTP outcomes

Wyrmgate classifies outcomes as follows:

- `2xx`: accepted; outbox publication may become `PUBLISHED`;
- `408`, `425`, `429`, `5xx`, connection failures and timeouts: retryable; outbox remains `PENDING` with bounded backoff;
- `3xx` and other `4xx`: terminal configuration/delivery failure; outbox becomes `FAILED`.

Remote response bodies and exception text are not persisted as ordinary outbox error details. Only normalized error codes are retained.

## Activation procedure

Before enabling delivery in an environment:

1. establish the intended single receiver and confirm it accepts the checked-in Identity AsyncAPI v1 events;
2. provision a random shared secret through the environment secret manager;
3. configure the HTTPS endpoint and secret;
4. enable `transport=webhook`;
5. enable public-event publication;
6. deploy the reviewed `main` revision;
7. create a non-sensitive test Identity/change through normal authorized APIs;
8. confirm the receiver gets the expected event ID/type and verifies the signature;
9. confirm the corresponding outbox event reaches `PUBLISHED`;
10. intentionally exercise a safe retryable receiver response in non-production and verify retry/dedup behavior.

For Railway Serverless DEV, also verify the polling/outbound webhook activity is compatible with the intended serverless idle/cost posture before leaving publication enabled.

## Failure handling

A growing `PENDING` backlog means the destination is unavailable, throttling, or repeatedly returning retryable failures. Correct the destination/availability problem; do not rewrite the authoritative Identity decision.

A `FAILED` publication record requires operator investigation. Typical causes are an invalid endpoint/authentication contract, redirect/client error, or a permanent mapping/contract defect. Do not change a `FAILED` record to `PUBLISHED` manually. Any future operator retry/requeue operation must be explicit, authorized and audited rather than ad-hoc SQL.

## Secret rotation

The first adapter has one active outbound signing secret. Rotate it as a coordinated deployment change:

1. arrange a receiver-side overlap mechanism appropriate to that receiver, or a short controlled maintenance window;
2. install the new secret at the receiver;
3. update the Wyrmgate deployment secret;
4. deploy/restart the Wyrmgate runtime;
5. verify a newly delivered test event with the new secret;
6. retire the old receiver secret after the agreed overlap window.

The secret value must never be copied into documentation or Git history.

## Compatibility and versioning

ADR-0013 makes the current closed v1 JSON schemas exact compatibility surfaces. Field additions/removals, type/cardinality changes, enum semantic changes, or other payload/envelope shape changes create a new event version.

When a successor version is introduced, the existing consumed version is not removed in the same change. Coexistence and deprecation must be explicit. Retries remain on the version originally emitted.

## Multi-subscriber boundary

Do not configure multiple webhook URLs by concatenating values or duplicating POSTs inside the first adapter. The current outbox stores one publication result per event and therefore truthfully represents only one configured destination.

Multiple independent consumers require either durable per-destination delivery state or a broker/cloud fan-out transport selected in later architecture work.
