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

## Authority rule

Events do not bypass capability ownership. In particular, observed provider events cannot directly create governed AccessAssignments; they may create observations/findings that lead to an explicit governance decision/command.
