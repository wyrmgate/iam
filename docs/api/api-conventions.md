# API Conventions

## Purpose

Public APIs expose governed IAM resources and semantic operations. They are not persistence/entity APIs.

## Resource and operation rules

Use ordinary create/read/update semantics for non-lifecycle metadata where safe. Use explicit operations for business transitions such as suspend, revoke, activate, submit, approve, rotate, merge and retire.

Examples:

- `POST /access-requests`
- `POST /access-requests/{id}:submit`
- `POST /access-assignments/{id}:revoke`
- `POST /roles/{id}/versions/{versionId}:activate`
- `POST /credentials/{id}:rotate`

Arbitrary `PATCH status=...` is not a business-state API.

## Revisions and concurrency

Mutable authoritative resources expose `revision`. Mutations that depend on current state provide `expectedRevision`; REST may map this to `ETag`/`If-Match`. Stale mutation produces a semantic concurrency error and must not use last-writer-wins.

## Idempotency

Externally retryable mutations that can create duplicate effects accept a causal idempotency key. The server persists an operation/request fingerprint. Reusing the key with a different request is an idempotency conflict.

## Error model

Errors expose stable machine-readable `code`, human-readable `message`, `correlationId`, and optional structured details/field errors. Database/framework exception names are not public API contracts.

Typical categories: validation, not-found, conflict, forbidden, authentication-required, policy-denied, temporarily-unavailable, rate-limited, dependency-failure and internal-failure.

## Pagination and filtering

Large mutable collections use deterministic cursor pagination with stable tie-break ordering. Dynamic-attribute filtering is allowed only for governed attributes declared filterable/queryable. Public query APIs do not expose arbitrary SQL or native-provider JSON expressions.

## Bulk and long-running work

Large mutations/exports/simulations use durable operation resources and normally return accepted/in-progress state rather than holding one HTTP request open. Bulk execution does not bypass per-item authorization, validation, concurrency, audit or domain invariants.

## Consistency/freshness

API documentation identifies whether a result is authoritative, projected or observed. Observed resources expose observation/run timing where relevant; projections may expose computed/source revision metadata.

## Sensitive data

Ordinary API responses, errors and bulk results never return raw passwords, private keys, connector secrets, refresh tokens or equivalent secret material.
