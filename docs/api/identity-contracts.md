# Identity API and Integration Event Contracts

## Status

This document is the living implementation contract for the first OD-003 machine-readable API/event slice.

The checked-in artifacts are:

- `apps/server/src/main/resources/contracts/openapi/identity-v1.json`
- `apps/server/src/main/resources/contracts/asyncapi/identity-events-v1.json`

This slice is now **runtime-exposed when control-plane authentication is enabled**. Provider-neutral bearer validation resolves the external subject through the Administration-owned tenant/governed-actor binding, and every Identity HTTP operation evaluates the required `identity:*` permission through `AdministrativeAuthorizationService` at operation time. When bearer authentication is not configured, `/api/v1/**` remains closed.

The machine-readable files describe implementation contracts; they do not replace the canonical Identity domain model, ADR-0008, formal requirements, or capability ownership.

## Scope of the first slice

The OpenAPI contract defines:

- `GET /api/v1/identities` — deterministic cursor-paginated authoritative Identity listing;
- `POST /api/v1/identities` — typed PERSON/SERVICE/WORKLOAD Identity creation;
- `GET /api/v1/identities/{identityId}` — authoritative Identity read;
- `PATCH /api/v1/identities/{identityId}` — non-lifecycle metadata maintenance, initially `displayName` only;
- `GET /api/v1/identities/{identityId}/canonical-attributes` — governed canonical resolution state/provenance view.

The first slice intentionally does **not** define generic lifecycle/status PATCH. Lifecycle changes remain explicit business operations and will be added when their application commands and transition invariants are implemented. Identity merge/split remains a later durable operation under FR-IDM-004.

The Principal foundation is implemented as an internal Identity capability contract but is not added to the public Identity HTTP v1 surface in this slice. Principal authority is created explicitly against an active Catalog ApplicationTarget, may be temporarily uncorrelated, and supports one-way correlation to a canonical Identity. Cross-capability consumers use `PrincipalResolutionQuery` by ApplicationTarget + native principal key; provider observations do not become Principal authority automatically. A later public Principal administration API requires its own governed operations and authorization contract rather than exposing persistence rows.

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

ADR-0012 strengthens the runtime cursor transport: newly issued cursors use a signed `v2` envelope, are bound to the authenticated tenant, and expire after the configured bounded cursor lifetime (15 minutes by default, maximum 24 hours). Canonical-attribute cursors are additionally bound to the Identity resource ID. Tampering, unknown/retired key IDs outside the verification set, expiry, tenant mismatch, resource mismatch, and the former unsigned `v1` format all produce the same semantic `invalid_cursor` validation result. The payload is signed but not encrypted and therefore contains only data safe for opaque API transport.

When control-plane authentication is enabled, application signing must also be configured. `iam.signing` selects the active asymmetric signing key and optional retired public verification keys; private key material remains behind the platform signing adapter. `IAM_SIGNING_VERIFICATION_KEYS` uses semicolon-separated `keyId=/path/to/public.pem` entries. Retired public keys must be retained at least for the configured cursor lifetime. The pre-release v1-to-v2 transition intentionally invalidates cursors issued before the signed format is deployed rather than accepting unsigned cursors indefinitely.

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

A `CONFLICT` or `UNRESOLVED` state may still expose a compatible prior trusted value where the domain resolution rules permit it; the degraded resolution outcome remains explicit. A classified value can be redacted while its governed metadata remains visible. In this first runtime slice, canonical values and provenance are deliberately returned as `REDACTED` metadata-only views until a classification-aware value-visibility policy is implemented; `identity:read` alone does not imply permission to read every classified canonical value. Effective read evaluation honors override validity and current authority/candidate state without turning GET into a mutating resolution command.

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

The authentication, trusted actor/tenant resolution, burn-once bootstrap, direct-grant evaluator, and Identity HTTP adapters now form one enforced chain. Collection operations require a grant that can authorize the collection; exact resource operations evaluate that resource ID. Relationship/ownership context, policy/assurance requirements and sensitive authorization-decision audit hooks are added as corresponding governed operations require them.

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

The runtime now includes a transport-neutral publication dispatcher for these two curated event types. Identity mutations still commit only their internal semantic facts atomically with authoritative state. A short-lived outbox lease then selects supported Identity facts, maps them into the independent AsyncAPI v1 envelopes, and invokes an injected `IntegrationEventPublisher` outside the authoritative transaction.

Successful delivery marks the internal outbox row `PUBLISHED`. Retryable external-delivery failures leave it `PENDING` with bounded exponential backoff and a normalized error code. A permanent internal mapping/contract defect moves the technical publication record to terminal `FAILED` rather than retrying forever. Crash/restart after claiming is recovered by lease expiry and therefore remains at-least-once.

The created internal fact captures Identity type and lifecycle state at mutation time so the public `identity.created` event does not reconstruct historical event data from later current state. Display names and other richer values remain excluded.

ADR-0013 selects the first runtime delivery adapter as one deployment-configured signed HTTPS webhook destination while keeping `IntegrationEventPublisher` transport-neutral. Delivery is disabled by default. Activation requires `IAM_INTEGRATION_EVENTS_ENABLED=true`, `IAM_INTEGRATION_EVENTS_TRANSPORT=webhook`, an HTTPS endpoint, and a deployment secret of at least 32 UTF-8 bytes.

Each webhook POST sends the AsyncAPI JSON bytes unchanged and carries event ID, versioned event address, Unix delivery timestamp, and an HMAC-SHA-256 signature over `<timestamp>.<raw-body>`. Redirects are not followed. 2xx succeeds; 408/425/429/5xx and transient I/O retry; 3xx and other 4xx become terminal technical delivery failures with normalized error codes. Remote response bodies and exception detail are not persisted.

The current outbox has one publication result per event, so this adapter supports exactly one destination per deployment. Multiple independent subscribers require later per-destination delivery state or a broker/cloud fan-out transport; they are not simulated by posting to an arbitrary list of URLs.

The current closed v1 schemas are exact compatibility surfaces. Any payload/envelope shape or semantic change—including an optional field addition while `additionalProperties: false` remains in force—creates a new event version. A successor version coexists with an actively consumed predecessor until an explicit controlled deprecation/removal step. Retries never transform an event into another version.

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

This first slice materially advances OD-003 but does not close it globally. The Identity surface now has concrete OpenAPI/AsyncAPI contracts, a controlled outbox publication pipeline, an ADR-governed signed HTTPS webhook adapter, and an explicit exact-version compatibility/deprecation policy. OD-003 remains open for broader implemented public capability coverage and later multi-subscriber/broker evolution when concrete demand requires it.

A later formal-specification checkpoint should fold the accepted ADR amendments and completed OD-003 slices into the Integration/SAD/RTM package rather than updating v0.2 for every incremental contract commit.
