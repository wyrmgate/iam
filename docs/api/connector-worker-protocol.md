# Remote Connector-Worker Protocol

## Status

This document is the living OD-004 interface contract for remote connector execution. ADR-0014 is the durable architecture decision; the checked-in machine-readable contract is:

- `apps/server/src/main/resources/contracts/openapi/connector-worker-v1.json`

The v1 wire contract is **defined but not runtime-exposed yet**. Runtime implementation follows with the first real Integration-owned provisioning/reconciliation persistence slice so implementation convenience does not redefine the protocol.

## Purpose and boundary

The protocol lets an out-of-process connector runtime execute provider-facing Integration work without database access or authority over IAM governance state.

Canonical flow:

`Integration process/task state -> leased remote work -> provider call/discovery -> normalized result/observation -> Integration state transition`

The worker is an execution agent. It does not own ConnectorBinding, ProvisioningTask, ReconciliationRun, desired access, governance intent, or reconciliation completeness.

## Transport and direction

Protocol v1 uses worker-initiated HTTPS with JSON.

The worker establishes a session, claims bounded work, renews leases when needed, appends discovery observations, and completes work. Wyrmgate does not require inbound worker reachability and does not require a broker for v1.

The root path is `/internal/connector-worker/v1`. This is an internal Integration execution interface, not a public administration API.

## Authentication and execution authorization

Each request uses a dedicated connector-worker bearer token over TLS.

A validated issuer + subject identifies the external runtime subject only. Integration then resolves that subject to an enabled server-side worker registration. The registration, not token roles/scopes/tenant claims, determines the worker's allowed tenant, ConnectorBinding scope, protocol/runtime versions, and semantic capabilities.

Session IDs are negotiation/correlation state, not credentials. Every request remains authenticated and must match the worker registration that owns the session.

## Version negotiation

The worker advertises supported protocol majors plus connector runtime descriptors, semantic capabilities, and connector contract/schema versions.

The server chooses one compatible protocol major and returns server limits such as claim size, long-poll maximum, observation batch maximum, and lease duration.

Protocol-major compatibility is distinct from connector runtime compatibility and connector payload schema compatibility. The server never assigns a connector payload version the worker did not advertise.

## Leasing and fencing

Every claimed work item contains:

- `workId`;
- stable causal `operationId`;
- `leaseId`;
- monotonically increasing `leaseEpoch`;
- `leaseExpiresAt`;
- server-selected `tenantId` and `connectorBindingId`;
- semantic `workKind`;
- `contractId` + `contractVersion`;
- stable `idempotencyKey`;
- correlation/causation IDs;
- bounded connector-edge payload.

After lease expiry, the work may be claimed again with a higher epoch. Renew/observation/completion requests must carry the current lease ID and epoch. A stale generation cannot overwrite a newer attempt.

At-least-once delivery is expected. Provider-side operations must use the stable operation/idempotency identity where the provider permits it, and Wyrmgate remains responsible for revalidation before changing Integration process state.

## Provider-specific payload boundary

`payload`, observation state, and result metadata may carry provider-specific JSON only at the connector adapter edge and only under an explicit connector contract/schema ID + version.

That data is not canonical IAM semantics and must not become a generic EAV/policy/query model.

Raw passwords, provider access/refresh tokens, private keys, generated credential secrets, and equivalent private material are forbidden in ordinary protocol payloads. Provider credentials are resolved through approved secret infrastructure available to the worker by opaque configuration/reference. Operations that require raw secret delivery through this protocol are unsupported in v1.

## Completion semantics

Worker completion reports normalized execution facts. They may identify retryability, normalized provider error code, provider request ID, retry-after guidance, provider object/version identifiers, and bounded non-secret metadata.

Integration owns the resulting ProvisioningTask/ReconciliationRun transition. A worker cannot revive stale desired state, change governance intent, or decide that a privilege should remain authorized.

Duplicate identical completion is idempotent. Conflicting completion for the same lease generation is rejected rather than resolved last-writer-wins.

## Reconciliation and observation batching

Discovery work may append bounded batches with stable `batchId` and sequence. Batches are idempotently accepted under the current lease generation.

The worker may report `COMPLETE`, `PARTIAL`, or `UNKNOWN` coverage as evidence. Integration alone decides effective reconciliation/source-import completeness after validating scope, connector capability, binding/configuration/runtime versions, checkpoint continuity, and run health.

Therefore a partial/unknown remote run may refresh positive observations but cannot authorize destructive absence inference.

## v1 operation set

The machine-readable contract defines:

- `POST /sessions` — negotiate a worker session;
- `POST /sessions/{sessionId}/work:claim` — claim bounded leased work;
- `POST /sessions/{sessionId}/work/{workId}/lease:renew` — renew the current lease generation;
- `POST /sessions/{sessionId}/work/{workId}/observations` — append idempotent discovery batches;
- `POST /sessions/{sessionId}/work/{workId}:complete` — report normalized completion.

These are semantic wire operations. No database entity is exposed through the protocol.

## Compatibility

Protocol v1 schemas are closed core contracts. An incompatible change to session, leasing, fencing, authentication meaning, or required core message shape creates a new protocol major.

New connector-specific work capability or payload schema can evolve independently when negotiated by `contractId`/`contractVersion`; unsupported versions are never assigned to a worker.

A causal work attempt is not silently upgraded to a different wire/schema version during retry.

## Implementation boundary

This phase intentionally does not expose the endpoints yet because the Integration-owned provisioning/reconciliation aggregates and persistence needed to back them are not implemented. The first runtime slice must implement the accepted contract rather than changing the protocol to fit an accidental table/API shape.
