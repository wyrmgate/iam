# ADR-0013: Initial public integration-event transport and compatibility lifecycle

Status: Accepted

## Context

ADR-0008 defines public integration events as curated, independently versioned contracts rather than aliases of internal domain facts. The first Identity event contracts and transport-neutral outbox dispatcher are now implemented, but no external delivery adapter has been selected.

The current runtime has one publication state per internal outbox fact. That state is sufficient for one external delivery target, but it cannot represent independent success, retry, suspension or failure for multiple subscribers. Treating one outbox row as successful after only some destinations receive it would erase delivery truth.

The formal v0.2 SAD states that database-backed outbox delivery is acceptable initially and broker adoption is demand-driven. The active managed DEV topology already supports ordinary outbound HTTPS; adding a broker only to activate the first external consumer would introduce a new operational dependency before fan-out, throughput or decoupled-worker requirements justify it.

A public compatibility policy is also required before consumers depend on the AsyncAPI contract. The current JSON schemas use closed object shapes, so silently adding optional fields to an existing version would not be compatible with strict v1 validation.

## Decision

### First external transport

The first external public integration-event adapter is a **single deployment-configured outbound HTTPS webhook destination**.

This is an implementation/deployment transport choice, not canonical IAM architecture. `IntegrationEventPublisher` remains the transport-neutral boundary so a broker, cloud messaging service or another adapter can replace or supplement webhooks later without changing Identity domain semantics or public event definitions.

The first webhook adapter has these rules:

- exactly one destination is active per runtime deployment;
- HTTPS is mandatory in normal runtime configuration;
- redirects are not followed;
- request/connect execution is bounded by configured timeouts;
- event payload bytes are sent unchanged as `application/json`;
- delivery occurs outside the authoritative transaction and after the outbox claim;
- retry/replay preserves the public event `eventId`;
- the receiver deduplicates by `eventId`.

### Request authentication and replay protection

Webhook requests are authenticated with HMAC-SHA-256 using a deployment secret supplied through the runtime secret/configuration boundary.

The signature input is `<unix-seconds>.<raw-request-body-bytes>`.

Each delivery includes:

- `X-Wyrmgate-Event-Id`;
- `X-Wyrmgate-Event-Type` — the versioned AsyncAPI channel/address;
- `X-Wyrmgate-Delivery-Timestamp`;
- `X-Wyrmgate-Signature: v1=<lowercase-hex-hmac>`.

The shared secret is not part of the event, ordinary API, audit, log or error contract. Consumers should validate the signature, enforce bounded timestamp skew appropriate to their environment, and still deduplicate `eventId` because legitimate at-least-once retries use a fresh delivery timestamp.

### HTTP outcome classification

- HTTP `2xx` means the destination accepted the delivery and the outbox row may become `PUBLISHED`.
- `408`, `425`, `429`, and `5xx` are retryable technical failures.
- connection, timeout and other transient I/O failures are retryable.
- other `4xx` responses are terminal delivery/configuration failures and move the technical publication row to `FAILED` with a normalized error code.
- response bodies, remote exception detail and secrets are not persisted as ordinary outbox error text.

A future adapter may honor transport-specific retry hints such as `Retry-After`, but transport hints never change event semantic identity or authoritative IAM state.

### Single-destination boundary

The first webhook adapter does **not** model subscriptions and does not accept an arbitrary list of destinations.

Multiple independently managed consumers require one of:

1. durable per-destination delivery/subscription state with independent retry/failure semantics; or
2. a broker/cloud fan-out transport that owns subscriber delivery independently of the Wyrmgate outbox publication state.

That later fan-out decision is separate architecture work. It must not be simulated by marking one shared outbox row published after partial delivery.

### Public event compatibility

The version suffix in the channel/address (for example `iam.identity.created.v1`) and envelope `eventVersion` identify a compatibility version.

For the initial public-event policy:

- a published event version has an exact schema and semantic meaning;
- removing or renaming a field, changing a field type/cardinality, adding a required field, changing enum meaning, changing event meaning, or changing the closed payload/envelope shape requires a new event version;
- because current schemas are closed with `additionalProperties: false`, adding even an optional field also creates a new version unless a future accepted compatibility policy deliberately changes that schema posture;
- documentation-only clarification that does not change observable semantics may remain in the same version;
- transport metadata/headers are not part of the semantic event payload version unless the public contract explicitly promotes them.

When a successor event version is introduced for an already consumed event:

- the old version is not removed in the same change that introduces the successor;
- coexistence/deprecation is explicit and documented;
- removal requires a controlled checkpoint appropriate to the active subscriber model and environment;
- retry of one version never mutates it into another version.

The existing v1 Identity events remain the only active versions, so no deprecation window is started by this ADR.

## Consequences

- OD-003 can activate real external Identity event delivery without introducing a broker prematurely.
- The current single outbox publication state remains truthful because it represents one configured destination.
- Webhook consumers receive data-minimized, signed events and must tolerate duplicate, replay and out-of-order delivery.
- Secrets remain deployment configuration and do not enter public payloads or ordinary persistence/logging.
- Multi-subscriber fan-out is deliberately deferred until there is per-destination delivery state or a broker.
- Public event v1 contracts become stable exact-shape compatibility surfaces; field-shape evolution creates new versions.
- A broker/cloud messaging product remains a demand-driven future adapter choice rather than a canonical architectural dependency.
- The next formal Integration/SAD/Security/RTM revision must fold in this decision.
