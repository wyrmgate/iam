# Integration Administration API

## Status

The first governed control-plane management surface for Integration-owned connector/runtime state is implemented under:

- `/api/v1/connectors`
- `/api/v1/connector-bindings`
- `/api/v1/connector-workers`

The machine-readable contract is `apps/server/src/main/resources/contracts/openapi/integration-admin-v1.json`.

## Authorization

Every operation consumes the trusted control-plane actor context and evaluates Administration-owned semantic permissions at operation time. Default deny applies.

The implemented permission families are:

- `connector:read|create|update|disable`
- `connector-binding:read|create|update|disable`
- `connector-worker:read|create|update|disable`

ADR-0011 initial-admin permissions are intentionally unchanged. Connector administration must be granted explicitly.

## ConnectorInstance

Connector type is immutable after creation.

Mutable fields use optimistic revision semantics:

- runtime ID/version;
- provider-edge configuration version;
- non-secret provider-edge configuration;
- opaque secret reference.

The secret reference is write-only through the API. Read responses expose only `secretConfigured`.

Secret-shaped configuration keys are rejected before persistence. Ordinary APIs/facts/errors do not expose private provider credentials.

Disable is an explicit operation; arbitrary lifecycle status PATCH is not supported.

## ConnectorBinding

Creation binds one ConnectorInstance to one technical target ID and target kind.

ConnectorInstance, target kind and target ID are immutable after creation.

Contract ID/version plus object-class-specific `supportsCompletePrincipalDiscovery`, `supportsCompleteEntitlementDiscovery`, and `supportsCompleteGrantDiscovery` capabilities are revisioned mutable metadata. A COMPLETE report is destructive-authoritative for absence only when the matching capability is enabled and the binding/configuration/runtime/contract still match the run snapshot.

Same-tenant ConnectorInstance references are enforced structurally.

Disable is explicit.

## Connector worker registration

Worker registration owns:

- immutable external issuer + subject identity;
- enabled/disabled state;
- protocol-major range;
- allowed ConnectorBinding scope;
- allowed runtime/capability/contract versions.

Each runtime permission must be compatible with at least one currently active scoped binding and that binding's active connector runtime/contract.

Updating worker authority atomically replaces scope/permissions and invalidates negotiated sessions. Disabling also invalidates sessions. New worker requests still require successful server-side enabled-subject resolution.

External subject rotation is modeled as create a replacement worker registration, then disable the old one.

## Concurrency and idempotency

All mutations require `Idempotency-Key`.

Updates and disable operations require strong revision `If-Match`, for example `"rev-7"`.

Idempotency records and authoritative Integration mutation commit within the same required transaction. Reuse of one key with a different fingerprint is a conflict.

## Internal facts

Connector, binding and worker create/update/disable operations emit data-minimized internal outbox facts with aggregate ID/revision, correlation ID and an empty JSON payload.

Facts do not include:

- provider configuration;
- secret references;
- worker issuer/subject;
- worker scope/permission details.

These internal facts are not automatically public integration events.


## Entitlement observation mappings

ADR-0015 adds an explicit Integration-owned resolution resource for provider entitlement observations.

A mapping binds one present provider entitlement observation on an active `APPLICATION_TARGET` ConnectorBinding to one existing active Catalog Entitlement for exactly the same ApplicationTarget.

Mapping is not Catalog maintenance and is not access adoption:

- provider discovery never creates or updates Catalog Entitlement authority;
- creating a mapping never creates an AccessAssignment;
- provider IDs remain observation/mapping provenance;
- unmapping is explicit terminal retirement of that mapping record; remapping creates a new record.

Control-plane operations:

- `POST /api/v1/connector-bindings/{bindingId}/entitlement-mappings`
- `GET /api/v1/entitlement-observation-mappings/{id}`
- `POST /api/v1/entitlement-observation-mappings/{id}:unmap`

Create/unmap require causal `Idempotency-Key`. Unmap also requires strong revision `If-Match`.

Semantic default-deny permissions:

- `entitlement-observation-mapping:read`
- `entitlement-observation-mapping:create`
- `entitlement-observation-mapping:retire`

The mapping target is validated through a Catalog-owned semantic query. Integration does not read or mutate Catalog persistence directly.
