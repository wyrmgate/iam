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
- `connector-binding:read|create|update|disable|reconcile`
- `connector-worker:read|create|update|disable`
- `entitlement-observation-mapping:read|create|retire`
- `principal-observation-mapping:read|create|retire`

ADR-0011 initial-admin permissions are intentionally unchanged. Connector and observation-mapping administration must be granted explicitly.

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

Public ConnectorInstance create/update/disable, ConnectorBinding create/update/disable, ConnectorWorker create/update/disable, EntitlementObservationMapping create/retire, and PrincipalObservationMapping create/retire attempts append data-minimized ADR-0031 AuditRecord evidence after the Integration transaction commits or rolls back. Audit retains only the governed actor, exact semantic action, target Integration resource when known, normalized SUCCESS/DENIED/FAILURE outcome, occurrence time, and request correlation ID. Connector configuration, secret references, worker issuer/subject, worker scope/permissions, provider stable IDs, Entitlement IDs, provider observations, request payloads, and exception detail are excluded. Audit persistence failure is logged operationally and never rewrites the Integration business outcome.

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

## Principal observation mappings

ADR-0050 is implemented through an explicit Integration-owned provider-account correlation resource. It is the Principal-side counterpart to EntitlementObservationMapping and never transfers Principal authority out of Identity.

Control-plane operations:

- `POST /api/v1/connector-bindings/{bindingId}/principal-mappings`
- `GET /api/v1/principal-observation-mappings/{id}`
- `POST /api/v1/principal-observation-mappings/{id}:unmap`

The create request supplies only a provider-native stable account identifier and an exact existing governed `principalId`. Integration requires a present observed Principal on the active APPLICATION_TARGET binding, then validates the Principal through an Identity-owned semantic query for same-Tenant/same-ApplicationTarget agreement. Username, email, display name and `Principal.nativePrincipalKey` are never fallback correlation authority.

Within one ConnectorBinding, one active provider stable identifier maps to at most one Principal. Retirement is terminal and history-preserving; remapping creates a new mapping identity. Mapping create/unmap emits the existing data-minimized `integration.observed-access-input-changed` trigger so Governance re-queries current normalized observation state.

Semantic default-deny permissions:

- `principal-observation-mapping:read`
- `principal-observation-mapping:create`
- `principal-observation-mapping:retire`

These operations are causal-idempotent and revision-aware. Mapping state and audit exclude provider payloads, credentials/secrets and unrelated provider attributes.

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

## Upstream authentication handoff (ADR-0071)

Integration owns explicit tenant-bound FederationTrust and FederatedSubjectLink semantics; the typed semantic read/verification SPI lives in `integration.application.federation`. This is separate from ConnectorBinding observation mappings and Administration external control-plane actor binding. ADR-0077–0079 implement persistence/management, exact active links, narrow OIDC verification, one-use callback and federated session activation; SAML remains deferred. Authentication consumes exact trust/link and current Tenant/Identity eligibility and never manufactures a local Credential. See [IdP contracts](idp-sso-contracts.md#federation-adapter-contract-adr-0071).

## Exact provider realization (ADR-0072)

New grant provisioning requires both a present observed account explicitly mapped to the governed Principal and a present observed entitlement mapped to the Catalog Entitlement in the same active binding. No native Principal key fallback. Multiple eligible routes deny; historical reduction retains exact prior evidence under ADR-0056. Existing account operations resolve exact binding mappings or compatible successful account-provisioning provenance. No automatic mapping backfill. ADR-0076 implements current claim/execution fencing; ADR-0080 implements bounded recovery of confirmed grant effects. Unknown effects remain blocked pending explicit evidence/remediation.

## Federation administration (ADR-0077/V37)

Dedicated read/create/retire permissions for `/api/v1/federation-trusts` and `/api/v1/federated-subject-links`. POST creates with Idempotency-Key; GET exact ID returns strong revision ETag; POST `/{id}:retire` requires If-Match and Idempotency-Key. Initial OIDC trust accepts issuer, audience, settings.authorizationEndpoint/tokenEndpoint/keyId/publicKeySpki (public RSA SPKI base64 only), HTTPS same-origin endpoints, public-client PKCE RS256 profile. Link accepts trustId, exactSubject, principalId; active unique same-tenant exact subject, eligible Principal through Identity query. Retire trust invalidates links logically; retire/create for remap/key replacement, history retained. No automatic upstream authentication activation.

ADR-0079: `POST /api/v1/federation-trusts/{id}:bind-client` requires `federation-trust:bind-client`, If-Match, Idempotency-Key and body `{ "clientId": "..." }`. Resolves the current same-tenant interactive Catalog registration, binds once and advances trust revision. One active trust per registration; retirement/recreation for replacement. Binding requires mandatory Audit evidence. Public callback only enabled by deployment opt-in and allowed origin.

## Reconciliation scheduling (ADR-0081/V41)

`POST /api/v1/connector-bindings/{id}:reconcile` requires `connector-binding:reconcile`, strong binding revision `If-Match` and causal `Idempotency-Key`. The typed request contains `objectClass` (PRINCIPAL, ENTITLEMENT or GRANT), `scopeMode` (FULL or INCREMENTAL) and optional `checkpoint`. FULL requires no checkpoint; INCREMENTAL requires a bounded nonblank checkpoint (positive startIndex for SCIM). SOURCE_SYSTEM requires PRINCIPAL and exactly one current active source binding. Server validation owns the final decision.

The command schedules durable work without provider I/O and returns 202 with a Location referencing the run, correlation ID and data-minimized run status. An identical retry returns the same run; a changed fingerprint conflicts. Status reads require separate reconciliation-run:read authority. The operational run projection includes scopeMode, never the checkpoint itself. Accepted scheduling is not provider completion or verified coverage; INCREMENTAL never implies absence. Source imports are delivered via bounded Identity-owned semantic commands and do not create governed Principal authority.

The Console's first scheduling action deliberately supports FULL only; it loads the binding revision before submission, uses a stable causal key for retries and reports the resulting run ID separately from the binding. Provider-specific checkpoint entry remains an explicit API operation.

## Provider-effect recovery visibility (ADR-0080)

Provisioning attempt list/detail reads include nullable `effectRecoveryState`, `effectRecoveryReason` and `effectRecoveryUpdatedAt` from the distinct Integration-owned recovery projection. Existing attempt read authorization and tenant scope apply. These fields do not mutate or reinterpret the immutable attempt outcome: PENDING is recovery awaiting evaluation, RESOLVED matches current demand, PLANNED means a corrective plan was created (not completed), and MANUAL requires evidence-based investigation. No recovery record is represented by null fields. Only normalized recovery codes are exposed; provider payloads, identifiers, lease data and completion fingerprints remain excluded. An uncertain mutation with no successful attempt remains visible as a BLOCKED task with its failure code; this read surface is not permission to retry, acknowledge, or override it.

## Verified provisioning recovery (ADR-0083/V42)

POST `/api/v1/provisioning-tasks/{id}:recover` requires explicit `provisioning-task:recover` on the task and `reconciliation-run:read` on its evidence run, strong task revision If-Match and causal Idempotency-Key. The body carries a reconciliationRunId, executionQuiescedAt and optional governed principalId. Operator attestation requires actual cessation of external execution before a new FULL scan; lease expiry alone is not quiescence. The server validates the blocked task, exact historical route pins, fresh complete first-lease materialized scan, current generation and current desired cause/demand, and exact provider correlation. Incomplete/stale scans, changed config, competing plans and ambiguous mappings reject. One immutable decision per original task; identical causal replay returns it. A separate corrective job uses current desired revision and normal admission. Original task and attempts remain unchanged; no provider calls in the command. A correct observed effect creates MATCHES_DESIRED, not a fabricated successful attempt. An uncertain create requires a current explicit Principal mapping and positive exact account evidence; absence of an unidentified account is never safe replay evidence. Provider credentials, raw observation and native IDs are excluded. Audit success is mandatory in the command transaction.
