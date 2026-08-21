# API and Event Contract Model

## Status

Current contract baseline. Public APIs and events must reflect canonical IAM semantics rather than persistence entities or framework types.

## Contract layers

1. **Domain contracts** - internal semantic behavior between capabilities.
2. **Application API** - use-case operations exposed by the IAM application.
3. **Public integration API** - stable contract for external clients/integrators.
4. **Event contracts** - immutable facts published internally or externally.

A domain aggregate is not automatically an API resource. An internal capability contract is not automatically a public API. A domain event is not automatically a public integration event.

## Public API principles

- Expose semantic resources and operations, not database rows.
- Attribute edits may use normal resource update semantics; business/lifecycle transitions use explicit operations.
- Typed IDs are opaque and stable.
- Mutable resources expose a revision. Callers may provide an expected revision, mapped to `If-Match`/ETag or an equivalent transport field.
- Retryable create/command operations support causal idempotency keys. Reusing one key with a different request fingerprint is a conflict.
- Every request receives a correlation ID propagated through domain, audit, async work, provider calls, reconciliation, and findings.
- Public errors use stable machine-readable codes and categories; database/framework exceptions are never public contracts.
- Large mutable collections use deterministic cursor pagination by default.
- Dynamic-attribute filtering is available only for governed attributes declared queryable/filterable.
- Raw source/provider observations are exposed through privileged observation APIs, not silently mixed into canonical Identity responses.

## Async and bulk operations

Commands that begin long-running work return an operation resource or job reference rather than holding the HTTP request open. Examples include reconciliation, source import, identity merge/split, credential rotation, simulations, large exports, and bulk operations.

Bulk operations preserve normal per-item authorization, validation, concurrency, audit, and domain semantics. Bulk packaging is not an invariant bypass.

API acceptance of a governance command does not imply provider fulfillment. Authoritative state, fulfillment, and observed provider state remain separately queryable.

## Query projections

`Identity360`, `Application360`, `ApprovalTask`, dashboards, search results, and similar composed views are projections/read models rather than authoritative aggregate representations. Projection APIs should document freshness/as-of metadata where useful.

Read authorization must constrain the query before pagination/results are produced; do not query an unrestricted population and filter unauthorized objects afterward.

## Internal capability contracts

Internal contracts are strongly typed, consumer-oriented, and transport-neutral. They expose semantic data rather than repositories or foreign aggregate entities.

Important contracts include:

- `IdentityGovernanceContextQuery`
- `RoleExpansionQuery`
- `EffectiveAccessQuery`
- `DesiredAccessStateQuery`
- `PrincipalResolutionQuery`
- `CredentialRequirementQuery`
- `AccessIntentCommand`
- `ReportGovernanceObservation`
- `SecurityAuditPort`

Synchronous calls are preferred for required validation/query behavior before authorization/commit. Events are preferred for state propagation, projection updates, external effects, notification, reconciliation, and other asynchronous reactions.

## Commands versus facts

Commands request an action, for example `RevokeAccessAssignment` or `CreateAccessIntent`.

Domain events describe completed facts in past tense, for example `AccessAssignmentRevoked` or `RoleVersionActivated`.

Governance calling Access through `AccessIntentCommand` does not mean Governance owns AccessAssignment persistence. Access emits the resulting fact after its authoritative transition.

## Event envelope

Internal events use a stable semantic envelope containing at least:

- event ID
- event type and event version
- occurred-at time
- tenant/isolation context where applicable
- aggregate/object type, ID, and revision where applicable
- correlation ID
- causation ID
- minimal semantic payload

`eventId` identifies one specific event. `correlationId` links the wider business/process chain. `causationId` identifies the immediate command/event cause.

There is no global total-order guarantee. Aggregate revisions may be used to detect gaps/out-of-order delivery for one aggregate.

## Delivery semantics

Assume at-least-once delivery. Consumers tolerate duplicates, replay, and out-of-order events through event IDs, idempotency, revision checks, and desired-state revalidation. Do not depend on exactly-once transport semantics.

Projection consumers and irreversible side-effect consumers have different replay behavior. Notifications and provider effects require explicit deduplication where replay would be harmful.

## Public integration events

Internal domain events are mapped to a curated, separately versioned public integration-event catalog. Internal implementation noise is never automatically exposed externally.

External event contracts should be consumer-oriented, minimal, data-classification aware, and compatible with CloudEvents/AsyncAPI if those protocols are selected later.

Breaking semantic/schema changes require a new event/API version or an explicit compatibility transition. Public consumers must not break merely because Java classes, modules, database schemas, or deployment topology change.

## Security and sensitive data

Raw passwords, API secrets, private keys, refresh tokens, connector secrets, and credential material never appear in ordinary resource responses, errors, audit records, bulk results, or events.

Sensitive non-secret identity data is minimized in events. Consumers that need current permitted data should query IAM rather than receiving full profiles in every event.

## AccessAssignment lifecycle refinement

The canonical governance lifecycle is `SCHEDULED`, `ACTIVE`, `SUSPENDED`, `REVOKED`, `EXPIRED`, `CANCELLED`.

Technical revocation progress is not an AccessAssignment lifecycle state. When governance ends an assignment, it becomes `REVOKED` immediately for authorization/effective-access purposes; fulfillment/observation may still show pending, failed, drifted, or externally present technical access.

This preserves the invariant: governance decision != technical fulfillment != observed provider truth.

## Future machine-readable artifacts

Implementation should eventually provide:

- OpenAPI for supported public synchronous APIs
- AsyncAPI/event schema catalog for supported external event channels
- JSON Schema or equivalent payload schemas where useful

These artifacts implement this semantic contract; they do not replace it.