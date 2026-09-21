# SCIM 2.0 Principal Connector

## Status

This is the first concrete provider adapter behind the existing Integration boundary. It implements a bounded SCIM 2.0 principal slice only; it does not redefine canonical Identity, Access, Governance, or connector-worker semantics.

Runtime identity:

- connector type: `SCIM_2`
- runtime ID: `scim-2`
- runtime version: `1.0`
- principal contract ID: `scim-2.principal`
- contract version: `1`

The adapter is currently a server-side provider implementation available to Integration composition. The existing remote connector-worker v1 protocol remains unchanged.

## Scope

Implemented provider operations:

- SCIM `/Users` discovery with `startIndex` / `count` paging;
- normalized PRINCIPAL observations;
- principal create with `POST /Users`;
- principal update with SCIM PATCH;
- principal disable/deactivate by replacing `active=false`;
- provider object ID and version preservation;
- conditional mutation through `If-Match` when a provider version is known;
- optional provider-specific idempotency header configuration;
- normalized provider failure categories.

Not implemented in this slice:

- Groups;
- entitlement discovery/provisioning;
- grant/access discovery/provisioning;
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
- `idempotencyHeader` — optional provider-supported idempotency header name.

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
- update users for profile mutation and deactivate/disable.

Do not grant group, entitlement, credential, administrative, or unrelated directory permissions unless a future governed connector capability requires them.

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
