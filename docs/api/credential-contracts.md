# Credential Public Control-Plane API

## Status and authority

The first public Credential control-plane slice implements the still-open Credential portion of OD-003 under accepted ADR-0020, ADR-0005, ADR-0008 and ADR-0012.

Credential remains authoritative for authentication-instrument metadata and durable CredentialRotation process state. Principal remains Identity-owned. This HTTP surface does not make Credential a secret vault and does not expose provider execution as a synchronous API concern.

Machine-readable contract: apps/server/src/main/resources/contracts/openapi/credential-v1.json.

## Public resources

A public Credential resource contains its stable ID, Identity-owned Principal ID, typed kind, opaque SecretReference(providerType, referenceKey), lifecycle state, optional validFrom/validUntil, optimistic revision, and lifecycle timestamps.

The API never dereferences SecretReference. It contains no field for raw password values, API-key values, private keys, certificate private keys, OAuth client secrets, tokens or equivalent secret/private material.

A public CredentialRotation read model contains the stable rotation ID, old Credential ID, optional replacement Credential ID once attached internally, initiator governed Identity ID, durable process state, checkpoint/failure code, optimistic revision, and timestamps. Rotation process state is not Credential lifecycle and is not provider fulfillment.

## Operations

| Operation | Permission | Concurrency / retry semantics |
| --- | --- | --- |
| GET /api/v1/credentials?principalId=... | credential:read | signed deterministic cursor; collection authorization |
| POST /api/v1/credentials | credential:create | required Idempotency-Key |
| GET /api/v1/credentials/{credentialId} | credential:read | returns revision ETag |
| POST /api/v1/credentials/{credentialId}:revoke | credential:revoke | required If-Match + Idempotency-Key |
| POST /api/v1/credentials/{credentialId}:compromise | credential:compromise | required If-Match + Idempotency-Key |
| POST /api/v1/credentials/{credentialId}:rotate | credential:rotate | required Idempotency-Key; creates a new rotation aggregate |
| GET /api/v1/credentials/{credentialId}/rotations | credential-rotation:read | signed deterministic cursor |
| GET /api/v1/credential-rotations/{rotationId} | credential-rotation:read | returns revision ETag |

These permissions are default-deny and are intentionally not added to INITIAL_TENANT_ADMIN.

There is no public arbitrary lifecycle/status patch. There is no public manual activate operation: future activation is governed by validFrom; semantic effectiveness does not wait for the scheduler, while Platform scheduled work may materialize the state boundary.

There are no public operations that advance CREATING_REPLACEMENT, DISTRIBUTING, VERIFYING, CUTOVER_COMPLETE, REVOKING_OLD or failure process steps. Those states exist so later typed SecretProvider/Integration execution can resume safely. Exposing manual process-state mutation now would pretend deferred provider work had occurred.

## Principal boundary

Credential creation validates the referenced Principal through the Identity-owned semantic Principal query. Credential does not read or mutate Identity persistence and V32 deliberately has no cross-capability database foreign key to identity.principal.

A missing or foreign-tenant Principal is rejected. Provider observations never create Credential or Principal authority through this API.

## SecretReference boundary

SecretReference is governed opaque metadata. A representative value is providerType=vault with referenceKey=services/payments/api-key. It is not the secret value and no retrieval endpoint exists.

Create request schemas are closed with additionalProperties=false, and the runtime performs exact-field validation. Raw-secret-shaped extra fields are therefore rejected rather than ignored. API error handling never echoes request bodies or domain/provider exception detail.

## Revisions and idempotency

Credential lifecycle mutations depend on current authoritative state and require a strong revision ETag such as If-Match: "rev-7". A stale revision returns HTTP 412 with stable code stale_revision.

Retryable state-changing operations use a causal Idempotency-Key. The durable platform idempotency record stores a request fingerprint, not the request payload. Replaying the same key and fingerprint returns the same resulting resource. Reusing a key with materially different input returns HTTP 409 idempotency_conflict.

Starting routine rotation creates a separate CredentialRotation aggregate and therefore does not use the old Credential's If-Match. The operation re-reads the old Credential, requires it to be semantically effective ACTIVE, permits only one open routine rotation, derives the initiator from the authenticated governed actor, and is protected by causal idempotency plus the existing database uniqueness invariant.

## Pagination

Credential listing is deliberately scoped to one Principal. Ordering is immutable: createdAt ASC, id ASC. V35 adds the supporting tenant_id + principal_id + created_at + id index.

Rotation history listing is scoped to one old Credential and ordered createdAt DESC, id DESC.

Both cursor families use the ADR-0012 signed v2 envelope, are tenant/context bound and time bounded, and reject tampering or cross-context replay.

## Error model

The API returns the standard control-plane error shape: code, human-readable message, correlationId and optional field errors. Stable categories used by this slice include validation_failed, forbidden, not_found, idempotency_conflict, idempotency_in_progress, credential_conflict, invalid_state, stale_revision and internal_failure.

Database/framework/provider details and secret/private material are never part of the public error contract.

## Explicitly deferred

This API does not add SecretProvider retrieval or execution, IAM-side raw secret generation/storage, provider-native credential creation/revocation, public internal rotation-step mutation, automatic rotation policy/scheduling, CredentialBinding concrete semantics, observed credential reconciliation, or Integration credential provisioning work classes.

Those require their own bounded implementation slices without changing Credential authority.
