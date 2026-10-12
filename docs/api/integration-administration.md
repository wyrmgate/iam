# Integration Administration API

## Status

The first governed control-plane management surface for Integration-owned connector/runtime state is implemented under:

- `/api/v1/connectors`
- `/api/v1/connector-bindings`
- `/api/v1/connector-workers`

The machine-readable contract is `apps/server/src/main/resources/contracts/openapi/integration-admin-v1.json`.

That contract now defines complete typed create/update payloads, success/error responses, required `Idempotency-Key` and strong `If-Match` headers, and operation-level Administration permissions for every ConnectorInstance, ConnectorBinding and ConnectorWorker mutation. Update payloads intentionally exclude immutable identity/target fields and lifecycle state so clients cannot smuggle authority changes through generic PATCH bodies. Connector-worker session/execution operations remain exclusively on the dedicated connector-worker protocol rather than this administration contract.

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

Public ConnectorInstance create/update/disable, ConnectorBinding create/update/disable, ConnectorWorker create/update/disable, and EntitlementObservationMapping create/retire attempts append data-minimized ADR-0031 AuditRecord evidence after the Integration transaction commits or rolls back. Audit retains only the governed actor, exact semantic action, target Integration resource when known, normalized SUCCESS/DENIED/FAILURE outcome, occurrence time, and request correlation ID. Connector configuration, secret references, worker issuer/subject, worker scope/permissions, provider stable IDs, Entitlement IDs, provider observations, request payloads, and exception detail are excluded. Audit persistence failure is logged operationally and never rewrites the Integration business outcome.

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

The public contract maps idempotency or semantic conflicts to `409` and stale revision preconditions to `412`; validation, authorization and not-found outcomes are separately typed. Runtime/OpenAPI alignment tests pin the published request-field sets and public mutation paths to the controller request records and mappings.

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

Within one ConnectorBinding, an active provider stable identifier has at most one active mapping. The Catalog Entitlement reference is not reverse-unique: distinct provider-native identifiers may be explicitly mapped to the same canonical Entitlement when they represent the same governable access meaning.

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

Successful map/unmap commits also append a data-minimized internal `integration.observed-access-input-changed` outbox fact containing the ConnectorBinding scope and mapping aggregate reference. ENTITLEMENT/GRANT reconciliation completion emits the same internal trigger whenever completion materializes current observation state, including positive observations retained from non-COMPLETE runs. Governance leases these facts asynchronously and re-queries current normalized observed access before updating findings. This trigger is internal only and is not a public integration event.

## Public operational status reads

Issue #266 adds a separate read-only public projection for operator diagnosis of existing Integration-owned process state. The companion machine-readable contract is `apps/server/src/main/resources/contracts/openapi/integration-operations-v1.json`.

The public operations are:

- `GET /api/v1/provisioning-jobs`
- `GET /api/v1/provisioning-jobs/{id}`
- `GET /api/v1/provisioning-tasks`
- `GET /api/v1/provisioning-tasks/{id}`
- `GET /api/v1/provisioning-tasks/{taskId}/attempts`
- `GET /api/v1/provisioning-attempts/{id}`
- `GET /api/v1/reconciliation-runs`
- `GET /api/v1/reconciliation-runs/{id}`

These resources report current technical fulfillment/reconciliation process state only. They do not become Access authoritative intent, desired provider state, Catalog authority, or evidence that provider observation is governable access. Downstream provider failure remains an Integration operational condition and never rewrites a valid governance decision.

All collection reads use deterministic descending `(timestamp, id)` order, a maximum page size of 200 and tenant-bound signed continuation cursors. Responses use `Cache-Control: no-store` and preserve request correlation IDs.

Administration defines four explicit default-deny read permissions:

- `provisioning-job:read`
- `provisioning-task:read`
- `provisioning-attempt:read`
- `reconciliation-run:read`

These permissions are not added to `INITIAL_TENANT_ADMIN`; they must be granted explicitly through normal Administration authority management.

The projection is structurally data-minimized. Public responses intentionally exclude worker/execution internals and provider-native detail including:

- provisioning task idempotency keys and payloads;
- worker lease IDs/epochs and lease-management state;
- provider request/object/version identifiers and raw attempt metadata;
- completion fingerprints;
- reconciliation checkpoints and completion lease/fingerprint fields;
- connector configuration, secret references and private credential material.

Only normalized states, normalized failure category/code where already modeled, semantic subject/contract references, timing, revisions, and correlation/causation identifiers are exposed. The browser receives no claim/renew/complete operation and cannot invoke the internal connector-worker execution protocol through this surface.

The operational projection is implemented over the existing Integration schema. It introduces no new persistence authority and requires no data migration.
