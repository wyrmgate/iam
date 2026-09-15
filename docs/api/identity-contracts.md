# Identity API and Integration Event Contracts

## Status

This document is the living implementation contract for the first OD-003 machine-readable API/event slice.

The checked-in artifacts are:

- `apps/server/src/main/resources/contracts/openapi/identity-v1.json`
- `apps/server/src/main/resources/contracts/asyncapi/identity-events-v1.json`

This slice is **contract-first and not yet runtime-exposed**. The Administration capability now has a persisted default-deny direct-grant evaluator, but Wyrmgate still does not expose Identity mutation endpoints until transport authentication can resolve a caller to a governed actor/tenant and an initial administrator can be provisioned through a governed, non-self-escalating path. This avoids creating a temporary unauthenticated or unbootstrappable administration surface.

The machine-readable files describe implementation contracts; they do not replace the canonical Identity domain model, ADR-0008, formal requirements, or capability ownership.

## Scope of the first slice

The OpenAPI contract defines:

- `GET /api/v1/identities` — deterministic cursor-paginated authoritative Identity listing;
- `POST /api/v1/identities` — typed PERSON/SERVICE/WORKLOAD Identity creation;
- `GET /api/v1/identities/{identityId}` — authoritative Identity read;
- `PATCH /api/v1/identities/{identityId}` — non-lifecycle metadata maintenance, initially `displayName` only;
- `GET /api/v1/identities/{identityId}/canonical-attributes` — governed canonical resolution state/provenance view.

The first slice intentionally does **not** define generic lifecycle/status PATCH. Lifecycle changes remain explicit business operations and will be added when their application commands and transition invariants are implemented. Identity merge/split remains a later durable operation under FR-IDM-004.

The contract does not expose persistence entities such as canonical candidate rows, mapping tables, authority tables, outbox rows, or provider-native source payloads.

## Identity representation

`Identity` remains the canonical governed subject and carries:

- stable opaque UUID identifier;
- `PERSON`, `SERVICE`, or `WORKLOAD` type;
- exactly one compatible typed profile;
- lifecycle state;
- display name;
- semantic authoritative revision;
- created/updated timestamps.

The v0.2 controlled package does not define profile-specific business fields, so the API does not invent any. The profile shape is typed by `kind` only until formal/domain requirements add concrete profile fields.

Tenant is not a writable resource attribute. Tenant/isolation context is resolved from authenticated control-plane context before normal authorization.

## Concurrency and idempotency

Mutable authoritative resources use semantic revision concurrency.

- reads return an `ETag` derived from `revision`, for example `"rev-7"`;
- revision-sensitive mutation requires `If-Match`;
- stale mutation returns a semantic stale-revision error rather than last-writer-wins;
- retryable mutations require `Idempotency-Key`;
- reuse of an idempotency key with a different normalized request fingerprint is a conflict.

Idempotency identifies one causal operation. It does not collapse independent semantically similar Identity changes.

## Pagination

Identity and canonical-attribute collections use opaque deterministic cursor pagination.

The first Identity contract orders by immutable `createdAt` and then `id`. Canonical attributes order by stable attribute `key` and then `definitionId`. Clients treat cursors as opaque and must not construct them.

The contract defaults to 50 items and caps a page at 200 items. These are API implementation limits, not aggregate boundaries.

## Canonical attributes

Canonical attribute API state remains distinct from SourceRecord observation and provider-native data.

Each canonical attribute view exposes governed semantic metadata:

- stable definition/version identifiers and key;
- governed classification;
- scalar type and cardinality;
- resolution outcome: `RESOLVED`, `OVERRIDDEN`, `CONFLICT`, `UNRESOLVED`, or `NO_VALUE`;
- `valueRevision`;
- value visibility (`VALUE` or `REDACTED`);
- typed canonical values when authorized/readable;
- minimized provenance identifiers when authorized.

Values are strongly typed rather than arbitrary JSON. `MULTI` values are arrays of typed scalar values. Provider-native raw payloads and internal candidate/mapping rows are never substituted for canonical state.

A `CONFLICT` or `UNRESOLVED` state may still expose a compatible prior trusted value where the domain resolution rules permit it; the degraded resolution outcome remains explicit. A classified value can be redacted while its governed metadata remains visible.

## Error contract

Public errors are semantic and contain:

- stable machine-readable `code`;
- human-readable `message`;
- `correlationId`;
- optional structured field errors.

Framework, SQL, repository, constraint, stack-trace, or secret data is not a public error contract.

## Administrative authorization boundary

Transport authentication and IAM Administrative Authorization are separate layers.

The OpenAPI document models bearer transport authentication as the first implementation target, but bearer possession alone never authorizes an IAM control-plane action. Each operation declares the semantic administrative permission it requires, such as:

- `identity:read`;
- `identity:create`;
- `identity:update`.

The first Administration persistence/evaluator slice now supplies operation-time default-deny matching for semantic permissions plus tenant-scoped `GLOBAL` and exact `SPECIFIC_RESOURCE` grants. It also revalidates the governed actor's current Identity state and temporal grant validity for every decision. Other canonical scope types remain deliberately fail-closed until their hierarchy/population semantics exist.

That evaluator is necessary but not sufficient to expose the HTTP endpoints. Runtime activation still requires transport authentication and trusted actor/tenant resolution, plus a safe initial-administrator provisioning/management path that cannot become a self-escalation bypass. Relationship/ownership context, policy/assurance requirements and sensitive authorization-decision audit hooks are added as the corresponding governed operations require them.

## Public Identity integration events

The AsyncAPI contract defines two first public event types:

- `iam.identity.created.v1`;
- `iam.identity.metadata-changed.v1`.

These are curated integration contracts, not aliases of internal outbox fact names.

The event envelope preserves:

- `eventId`;
- semantic `eventType` and `eventVersion`;
- `occurredAt`;
- `tenantId`;
- Identity resource ID and revision;
- `correlationId`;
- optional `causationId`;
- minimized semantic payload.

`identity.created` includes Identity type and lifecycle state. `identity.metadata-changed` identifies the changed public field but does not publish its value. Display names, canonical attribute values, source/provider payloads, credentials, secrets, mappings, authority rows, and candidate rows are excluded. Consumers requiring richer current data query an authorized API.

Delivery semantics are at-least-once. Consumers tolerate duplicate, replay, and out-of-order delivery. There is no total global ordering guarantee; Identity revision provides local ordering/gap context where applicable.

No broker, webhook product, or connector transport is selected by this contract. External publication wiring remains future implementation work.

## Verification

`scripts/verify-api-contracts.py` runs in Core CI and uses only Python's standard library. It verifies JSON syntax and project-specific invariants including:

- approved specification versions for this slice;
- required semantic paths;
- cursor pagination;
- mutation idempotency and revision requirements;
- exclusion of generic lifecycle mutation from PATCH;
- exclusion of secret-shaped fields;
- canonical-state rather than persistence/observation leakage;
- standard public event envelope/causal fields;
- minimized Identity event payloads.

The lightweight verifier does not pretend to be a complete OpenAPI/AsyncAPI standards validator. A repository-wide contract-lint/generation toolchain may be selected later without changing these semantic rules.

## OD-003 completion boundary

This first slice materially advances OD-003 but does not close it globally. OD-003 remains open until the repository has concrete machine-readable contracts for the implemented public capability surfaces and a controlled runtime publication/compatibility process.

A later formal-specification checkpoint should fold the accepted ADR amendments and completed OD-003 slices into the Integration/SAD/RTM package rather than updating v0.2 for every incremental contract commit.
