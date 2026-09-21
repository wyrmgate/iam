# Event Contract Model

## Contract layers

A domain fact, internal event and public integration event are distinct contracts.

`Domain Fact -> Internal Event -> Integration Event Mapping -> External Event`

Internal facts/events may evolve with the application. Public integration events are curated, compatibility-managed contracts.

## Command vs fact

Commands request work (`CreateAccessIntent`, `ApproveRequestItem`). Facts describe completed semantic changes (`AccessAssignmentCreated`, `ApprovalDecisionRecorded`). Do not call commands events.

## Standard envelope

Internal/public mappings should preserve, where applicable:

- `eventId`
- `eventType`
- `eventVersion`
- `occurredAt`
- `tenantId`
- aggregate/resource type, ID and revision
- `correlationId`
- `causationId`
- semantic payload

`eventId` identifies one event, `correlationId` spans the business/process chain, and `causationId` identifies the immediate cause.

## Delivery semantics

Assume at-least-once delivery. Consumers must tolerate duplicate, replay and out-of-order arrival. There is no total global ordering guarantee. Aggregate revision provides local ordering/gap detection where appropriate.

Side-effect consumers require explicit deduplication/idempotency; projection consumers are rebuildable/replayable.

## Public event design

Public events are consumer-oriented and do not expose internal class/module names or implementation noise. Breaking payload/semantic changes require a new event version. Optional compatible additions follow the published compatibility policy.

Sensitive/PII data is minimized; consumers needing richer current context should query an authorized API. Raw secret/private material is prohibited from events.

CloudEvents, AsyncAPI, JSON Schema, Kafka/NATS/cloud messaging and webhook delivery are transport/description choices rather than canonical domain semantics.

## Runtime publication boundary

The first runtime implementation uses the transactional outbox as the durable handoff from internal facts to public integration events:

```text
Authoritative mutation + internal fact/outbox commit
  -> short outbox claim/lease
  -> curated public-event mapping
  -> external IntegrationEventPublisher adapter
  -> PUBLISHED or retry/terminal technical state
```

The external publisher call occurs after the database statement that claims the outbox row and outside the authoritative transaction that committed the domain change. A crash or transient delivery failure leaves the fact eligible for later retry after its lease/backoff time. Duplicate external delivery remains possible and consumers must deduplicate by `eventId`.

Only explicitly supported internal fact types are claimable by a public-event mapper. Internal event names and payload JSON are never published directly. Permanent mapping/contract defects move the technical outbox record to terminal `FAILED` state with a normalized error code rather than retrying forever; retryable external transport failures remain `PENDING` with bounded backoff.

Publication remains transport-neutral at the application boundary. ADR-0013 selects the first runtime adapter as one deployment-configured signed HTTPS webhook destination. The adapter signs `<unix-seconds>.<raw-body-bytes>` with HMAC-SHA-256, does not follow redirects, and uses bounded connect/request timeouts. A 2xx response accepts the event; 408/425/429/5xx and transient I/O remain retryable; 3xx and other 4xx are terminal technical delivery failures. The current outbox stores one publication result per event, so multi-subscriber fan-out is explicitly deferred until per-destination delivery state or a broker/cloud fan-out transport exists.

## Public event compatibility lifecycle

A public event version is an exact schema-and-semantics contract. The versioned address/channel and `eventVersion` move together.

The current v1 schemas are closed with `additionalProperties: false`. Therefore adding/removing/renaming a field, changing type/cardinality, changing enum meaning, or otherwise changing payload/envelope shape creates a new version. Documentation-only clarification with no observable semantic change may remain on the same version.

A successor version does not remove an already-consumed predecessor in the same change. Coexistence/deprecation is explicit and controlled; retries remain on the version originally emitted. Transport headers are delivery metadata and do not change the semantic event version unless explicitly promoted into the contract.

If publication is not enabled, internal facts remain durable and unclaimed. If publication is enabled without a configured publisher adapter, runtime composition fails rather than pretending delivery succeeded.

## Authority rule

Events do not bypass capability ownership. In particular, observed provider events cannot directly create governed AccessAssignments; they may create observations/findings that lead to an explicit governance decision/command.
