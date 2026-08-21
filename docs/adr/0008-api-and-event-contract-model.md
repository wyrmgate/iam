# ADR-0008: API and event contract model

Status: Accepted

## Context

Public APIs, internal capability contracts, domain facts and external integration events serve different compatibility and ownership purposes and must not be collapsed into persistence/entity contracts.

## Decision

Public APIs expose semantic resources and explicit business operations. Business lifecycle transitions are commands, not arbitrary status PATCH operations. Mutable resources expose semantic revision; transports may map this to ETag/If-Match. Retryable state-changing operations support causal idempotency with request-fingerprint conflict detection.

Large mutable collections use deterministic cursor pagination. Bulk/heavy operations are durable asynchronous operation resources with per-item status. Composed views such as Identity360 and ApprovalTask are projections rather than aggregate ownership.

Internal capability contracts are strongly typed and transport-neutral. Commands request actions; domain facts describe completed semantic changes. Internal events are not automatically public contracts. External integration events are curated and separately versioned.

Event delivery assumes at-least-once semantics; consumers tolerate duplicates, replay and out-of-order delivery. Event envelopes preserve event identity, version, tenant/aggregate reference, aggregate revision where applicable, correlation and causation. Sensitive data is minimized.

## Consequences

- Database/framework/service-topology changes do not force public contract changes.
- Exactly-once business effects rely on idempotency/revision semantics, not transport guarantees.
- OpenAPI/AsyncAPI can later describe implementation contracts without becoming the canonical domain model.
