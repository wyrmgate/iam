# SCIM 2.0 Principal and Group Connector

## Status

This is the first concrete provider adapter behind the existing Integration boundary. It implements bounded SCIM 2.0 principal plus Group observation/membership slices; it does not redefine canonical Identity, Access, Governance, or connector-worker semantics.

Runtime identity:

- connector type: `SCIM_2`
- runtime ID: `scim-2`
- runtime version: `1.0`
- principal contract ID: `scim-2.principal`
- Group contract ID: `scim-2.group`
- contract version: `1`

The adapter is a server-side provider implementation available to Integration composition. When `iam.integration.scim-local.enabled=true`, an Integration-owned local executor claims durable SCIM work from the same ProvisioningTask/ReconciliationRun and connector-work lease model used by remote execution. The existing remote connector-worker v1 protocol remains unchanged.

## Scope

Implemented provider operations:

- SCIM `/Users` discovery with `startIndex` / `count` paging;
- normalized PRINCIPAL observations;
- SCIM `/Groups` discovery as Integration-owned ENTITLEMENT observations;
- SCIM user membership discovery as Integration-owned GRANT observations;
- desired-grant membership add/remove with SCIM PATCH;
- principal create with `POST /Users`;
- principal update with SCIM PATCH;
- principal disable/deactivate by replacing `active=false`;
- provider object ID and version preservation;
- conditional mutation through `If-Match` when a provider version is known;
- optional provider-specific idempotency header configuration;
- normalized provider failure categories.

Not implemented in this slice:

- automatic provider-Group adoption into Catalog Entitlement authority;
- AccessAssignment creation from observed memberships;
- automatic adoption or revocation decisions from governed drift findings;
- SCIM Group create/delete as Catalog management;
- credentials;
- SCIM Bulk;
- SCIM change log/incremental synchronization;
- provider-specific extensions as canonical IAM semantics.

## Connector configuration

Ordinary ConnectorInstance configuration may contain non-secret execution settings such as:

- `baseUri` — SCIM service base URI ending at the SCIM root, for example `https://provider.example/scim/v2/`;
- `pageSize` — discovery page size, 1–1000;
- `maxPagesPerExecution` — a safety bound for one discovery execution;
- `requestTimeout` — provider HTTP request timeout;
- `idempotencyHeader` — optional provider-supported idempotency header name;
- `principalUserNameTemplate` — bounded technical account naming template for IAM-created accounts. The first automatic planner requires the literal `{identityId}` token and substitutes only the canonical Identity ID; this is connector configuration, not a canonical Identity username or a generic mapping language.

Provider tokens are not configuration fields.

## Secret setup

The ConnectorInstance `secretReference` remains opaque and write-only through the administration API. The first execution-edge secret provider supports:

`env:<VARIABLE_NAME>`

Example reference:

`env:WYRM_SCIM_ACME_TOKEN`

Only the provider adapter resolves the reference. The resulting bearer token is used to construct the outbound Authorization header and is never placed in connector-worker tasks, ordinary API responses, outbox facts, audit, errors, observations, or ProvisioningAttempt metadata.

Environment-backed resolution is the smallest correct first provider boundary. Vault/KMS/HSM or other secret adapters can implement the same `ConnectorSecretProvider` port later without changing SCIM or canonical IAM semantics.

## Provider permissions

The token must have only the provider permissions needed for the configured slice:

- read/list users for reconciliation;
- create users for provisioning create;
- update users for profile mutation and deactivate/disable;
- read/list groups for entitlement/grant observation;
- update group membership for desired-grant add/remove.

Do not grant group create/delete, credential, administrative, or unrelated directory permissions unless a future governed connector capability requires them.

## Reconciliation semantics

SCIM discovery maps provider users to Integration PRINCIPAL observations.

The normalized observation currently includes only bounded non-secret fields:

- `userName`;
- `displayName`;
- `externalId`;
- `active`;
- `name`;
- `emails`.

Provider `id` becomes the provider stable ID. `meta.version`, when present, is preserved as provider version evidence. These are provider-edge observations, not authoritative Identity or Access state.

A discovery that starts at SCIM `startIndex=1` and successfully reaches the end may report `COMPLETE`. A resumed discovery or one stopped by the execution page bound reports `PARTIAL` and carries the next start index as a checkpoint.

Integration remains authoritative for effective reconciliation completeness. Existing reconciliation persistence is responsible for ensuring unseen principals are marked absent only after trustworthy COMPLETE coverage. PARTIAL/UNKNOWN runs may refresh positive observations but never infer destructive absence.

## Provisioning and idempotency

Create, update, and disable operations preserve the Wyrmgate stable operation/idempotency key. SCIM itself does not standardize an idempotency header, so the adapter sends one only when a provider-supported header name is explicitly configured.

When a provider version is available, update/disable sends `If-Match`. Provider ETag/version values remain provider evidence and do not become IAM aggregate revisions.

Stale desired-state revalidation remains the responsibility of the existing Integration claim path before provider execution. A stale or absent desired revision must be superseded before the adapter is called; Access unavailability must leave work unclaimed.

## Retry and error normalization

Provider responses are normalized without copying provider error detail into IAM error text:

- HTTP 429 -> `RATE_LIMITED`, with numeric `Retry-After` when provided;
- HTTP 5xx -> `TRANSIENT`;
- HTTP 401 -> `AUTHENTICATION`;
- HTTP 403 -> `AUTHORIZATION`;
- HTTP 400/409/412/422 -> `VALIDATION`;
- HTTP 501 -> `UNSUPPORTED`;
- other non-success responses -> `PROVIDER`;
- network/interruption failure -> `TRANSIENT`.

The provider's SCIM `scimType` or status may be retained as a bounded provider error code. Provider response detail is intentionally not surfaced in exception messages because it may contain sensitive values.

Retries continue to be governed by Integration durable task/run state and lease/fencing semantics rather than an in-adapter unbounded retry loop.

## Safe disable and rotation

To rotate a provider token:

1. provision the replacement token in the approved secret store/environment;
2. update ConnectorInstance with a new opaque `secretReference` using normal revision/idempotency semantics;
3. verify provider connectivity with the new reference;
4. revoke the old provider token.

To disable execution, use the governed ConnectorInstance/ConnectorBinding/worker disable operations. Do not delete provider credentials first if doing so would strand authoritative revocation work.

## Local deterministic tests

`ScimPrincipalProviderAdapterTest` uses a local in-process HTTP fixture and does not require a live SaaS account. It covers:

- paged discovery and normalized observations;
- COMPLETE vs resumed/bounded PARTIAL coverage;
- create/update/disable request behavior;
- stable provider IDs/versions and conditional mutation;
- optional idempotency header forwarding;
- rate limit, transient, authentication, authorization, and validation classification;
- secret/provider-detail non-leakage.

The existing Integration persistence suite remains the authority for tenant isolation, lease/fencing, immutable attempts, desired-state freshness, and COMPLETE/PARTIAL observation materialization behavior.


## Local durable execution

Local execution is disabled by default. Enable it with:

`IAM_SCIM_LOCAL_EXECUTION_ENABLED=true`

Optional controls:

- `IAM_SCIM_LOCAL_EXECUTION_POLL_INTERVAL` — scheduler delay, default `PT1S`;
- `IAM_SCIM_LOCAL_EXECUTION_LEASE_DURATION` — technical work lease, default `PT30S`, constrained to 5 seconds–10 minutes;
- `IAM_SCIM_LOCAL_EXECUTION_BATCH_SIZE` — maximum claimed work per scheduler pass, default 10.

The local executor selects only active ConnectorBindings/ConnectorInstances whose runtime and contract exactly match:

- runtime `scim-2 / 1.0`;
- contract `scim-2.principal / 1`.

It does not bypass durable Integration state. Provisioning and reconciliation are claimed under the existing fenced connector-work lease. Provider calls happen only after the claim transaction has committed, and observations/completion are written in later bounded transactions. A stale lease generation cannot append observations or commit completion.

### Provisioning payload contract

For `UPSERT_PRINCIPAL`, the connector-edge task payload may contain:

- `providerStableId` — omit to create; include to update/reactivate;
- `providerVersion` — optional provider version/ETag for conditional update;
- `userName`;
- `displayName`;
- `externalId`;
- `active`.

Automatic DesiredPrincipalState planning uses the active `scim-2.principal` binding for the ApplicationTarget. First-time create fails closed unless exactly one eligible route exists and its `principalUserNameTemplate` is valid. Existing account reactivation/disable resolves the technical route from current provider observation and/or prior successful IAM-created provisioning evidence. Provider success is recorded in Integration first and then handed to Identity through an internal fact so Identity remains the sole owner of authoritative Principal state.

For `DISABLE_PRINCIPAL` or `DEACTIVATE_PRINCIPAL`:

- `providerStableId` is required;
- `providerVersion` is optional.

For `ADD_GRANT` / `REMOVE_GRANT`, planner-created payloads use:

- `providerEntitlementId` — provider-native Group/entitlement stable ID;
- `providerEntitlementVersion` — optional provider version/ETag;
- `providerPrincipalId` — provider-native Principal stable ID.

The local SCIM executor still accepts the earlier `providerGroupId` / `providerGroupVersion` aliases for backward compatibility, but new Integration planning uses the provider-neutral entitlement field names.

These fields are provider-edge execution data and do not redefine canonical Principal or Identity semantics.

Before claim, and again immediately before provider mutation, the executor checks Access-owned `DesiredAccessStateQuery`. A missing or changed desired revision is superseded without a provider mutation. If Access is unavailable before claim, the work remains unclaimed. If availability is lost after claim but before the provider call, the attempt is normalized as retryable without making the provider call.

### Local versus remote execution

Local SCIM execution and remote connector workers are alternative execution placements over the same Integration-owned business/process state. They must not execute the same active lease simultaneously. The shared lease ID/epoch fencing, desired-state freshness, immutable ProvisioningAttempt, reconciliation completeness, and secret rules remain authoritative regardless of placement.

Provider credentials are resolved only by the execution environment that performs the outbound SCIM call. Local execution currently uses the `ConnectorSecretProvider` implementation documented above; remote workers continue to receive only opaque configuration/reference information permitted by the connector-worker protocol and never raw secret material.
