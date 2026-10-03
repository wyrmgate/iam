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
- `POST /api/v1/identities/{identityId}:activate` — PENDING/SUSPENDED/INACTIVE -> ACTIVE;
- `POST /api/v1/identities/{identityId}:suspend` — ACTIVE -> SUSPENDED;
- `POST /api/v1/identities/{identityId}:deactivate` — PENDING/ACTIVE/SUSPENDED -> INACTIVE;
- `POST /api/v1/identities/{identityId}:decommission` — PENDING/ACTIVE/SUSPENDED/INACTIVE -> terminal DECOMMISSIONED;
- `GET /api/v1/identities/{identityId}/canonical-attributes` — governed canonical resolution state/provenance view.

Generic lifecycle/status PATCH remains prohibited. ADR-0021 lifecycle changes are exposed only as explicit semantic operations. A same-state command is a no-op only with the current revision, and DECOMMISSIONED remains terminal. Identity merge/split is exposed as the explicit bounded ADR-0026 operations described below; it is not generic lifecycle mutation.

Public Identity create and display-name metadata-update attempts append data-minimized ADR-0031 AuditRecord evidence after the Identity transaction commits or rolls back. Audit retains only the governed actor, exact semantic action, target Identity when known, normalized SUCCESS/DENIED/FAILURE outcome, occurrence time, and request correlation ID. Display names, Identity profile/type payloads, canonical attributes, source data, request payloads, and exception detail are excluded. Audit persistence failure is logged operationally and never rewrites the Identity business outcome.

The Principal foundation is now exposed through a bounded public administration surface without changing its Identity-owned authority. `GET /api/v1/principals` and `GET /api/v1/principals/{principalId}` read authoritative Principal state; `POST /api/v1/principals` explicitly registers one known uncorrelated ACCOUNT Principal against an active Catalog ApplicationTarget; and `POST /api/v1/principals/{principalId}:correlate` performs one-way correlation to one canonical Identity. Provider observations do not become Principal authority automatically, and provider-driven ACTIVE/DISABLED realization remains an internal provisioning-result path rather than a public lifecycle operation.

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

A `CONFLICT` or `UNRESOLVED` state may still expose a compatible prior trusted value where the domain resolution rules permit it; the degraded resolution outcome remains explicit. A classified value can be redacted while its governed metadata remains visible. ADR-0030 now implements classification-aware value visibility: `identity:read` authorizes the metadata row, while typed canonical values require the separate `canonical-attribute-value:read` permission with either GLOBAL scope or an exact `CANONICAL_ATTRIBUTE_CLASSIFICATION` scope matching the active definition version's classification key. Classification keys are opaque tenant-local strings with no ordering or wildcard semantics. Missing, expired, revoked or mismatched value-read authority returns `visibility=REDACTED` and omits `values`. Effective read evaluation honors override validity and current authority/candidate state without turning GET into a mutating resolution command. Detailed provenance remains redacted/deferred.

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
- `identity:update`;
- `identity:activate`;
- `identity:suspend`;
- `identity:deactivate`;
- `identity:decommission`;
- `principal:read`;
- `principal:register`;
- `principal:correlate`;
- `canonical-attribute-value:read` for classification-authorized canonical values in addition to `identity:read` metadata authority.

The first Administration persistence/evaluator slice now supplies operation-time default-deny matching for semantic permissions plus tenant-scoped `GLOBAL` and exact `SPECIFIC_RESOURCE` grants. It also revalidates the governed actor's current Identity state and temporal grant validity for every decision. Other canonical scope types remain deliberately fail-closed until their hierarchy/population semantics exist.

The authentication, trusted actor/tenant resolution, burn-once bootstrap, direct-grant evaluator, and Identity HTTP adapters now form one enforced chain. Collection operations require a grant that can authorize the collection; exact resource operations evaluate that resource ID. Relationship/ownership context, policy/assurance requirements and sensitive authorization-decision audit hooks are added as corresponding governed operations require them.

## Identity merge and split

ADR-0026 adds two explicit administrative correction operations to the Identity v1 control plane:

- POST /identities/{identityId}:merge uses the path Identity as survivor and requires strong If-Match for its revision, an absorbedIdentityId plus absorbedRevision, a bounded reason, causal Idempotency-Key, and identity:merge authority on both Identities. The response is immutable merge-operation evidence; the absorbed Identity remains readable and becomes DECOMMISSIONED through normal lifecycle semantics.
- POST /identities/{identityId}:split uses the path Identity as source and requires strong If-Match, a new display name, explicit bounded sourceRecordIds/principalIds selections, reason, causal idempotency and identity:split authority. It creates one same-type PENDING Identity and returns immutable split-operation evidence plus the new Identity ID.

These operations move only Identity-owned current source correlation and Principal ownership. They never move or clone AccessAssignment/Governance authority. Source-link history, old Identity IDs and operation evidence remain preserved. Merge/split permissions are default-deny and are not included in INITIAL_TENANT_ADMIN.

## Principal administration boundary

Principal is Identity-owned technical authority, not a provider observation record and not a synonym for Identity. Public registration accepts only `applicationTargetId` and `nativePrincipalKey`; the server fixes the initial public shape to uncorrelated `ACCOUNT` + `ACTIVE`. The API therefore cannot use registration to choose an Identity, force a lifecycle value, inject provider payload, or invent an arbitrary Principal kind.

Principal listing is deterministic by stable Principal ID and uses the same ADR-0012 signed, time-bounded, tenant-bound cursor transport as other high-cardinality collections. Principal IDs remain opaque; their UUID representation is not a client-visible creation-time contract.

Correlation requires the target Principal and Identity to exist in the same tenant, a strong `If-Match` revision and causal `Idempotency-Key`. Correlation is one-way in this slice: after `identityId` is set, the public API cannot reassign or clear it. A successful correlation emits the existing minimized `identity.principal-correlated` and `identity.principal-access-projection-input-changed` internal facts so Access can re-evaluate Principal-dependent projections without transferring ownership.

The Principal API does not expose provider observations, connector identities, desired state, fulfillment state, credentials, outbox state, or a public ACTIVE/DISABLED mutation. Provider provisioning and provider-result lifecycle realization remain behind the existing Integration/Identity collaboration boundary.

Public Principal register/correlate attempts append data-minimized ADR-0031 AuditRecord evidence after the Identity-owned Principal transaction commits or rolls back. Audit retains only the governed actor, exact semantic action, target Principal when known, normalized SUCCESS/DENIED/FAILURE outcome, occurrence time, and request correlation ID. ApplicationTarget IDs, native principal keys, correlated Identity IDs, provider observation data, Credential references, request payloads, and exception detail are excluded. Audit persistence failure is logged operationally and never rewrites the Principal business outcome.

## Lifecycle command boundary

Every public lifecycle command requires a strong revision ETag through `If-Match` and a causal `Idempotency-Key`. Retry replay uses the same durable platform idempotency record pattern as other control-plane mutations. Reusing a key with a different normalized identity/target-state/revision fingerprint is a conflict.

Identity alone mutates authoritative lifecycle. The HTTP adapter invokes the existing Identity-owned lifecycle command; it does not update Access state directly. When access eligibility changes, the existing internal `identity.access-eligibility-changed` fact is still emitted atomically with the Identity revision. EffectiveAccess semantic reads become empty immediately for an ineligible Identity, while Access owns the durable bounded reduction of non-terminal AccessAssignments.

The public API does not expose internal reduction state as Identity lifecycle state and does not wait for provider revocation. Public lifecycle, merge and split mutation attempts now append data-minimized ADR-0031 AuditRecord evidence after the Identity transaction has completed or rolled back: the governed actor, exact semantic action, path/source-survivor Identity, normalized SUCCESS/DENIED/FAILURE outcome and request correlation ID are retained. Merge/split audit deliberately excludes the absorbed Identity ID, moved relationship IDs, display names, reason text and request payload. Audit persistence never mutates or rewrites the Identity outcome, and audit-write failure does not turn an already committed Identity result into a different business decision. Existing public Identity event v1 compatibility is unchanged.

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

The Identity surface now includes governed Identity create/read/list, non-lifecycle metadata update, canonical attribute reads, explicit ADR-0021 lifecycle operations, plus authoritative Principal list/register/read/one-way-correlation administration with signed pagination, optimistic revision where correlation depends on current state, and causal idempotency. The AsyncAPI/publication pipeline remains limited to the existing Identity created/metadata-changed v1 events; Principal correlation remains an internal fact only. OD-003 therefore remains partially implemented only for broader still-deferred JML/policy and public capability surfaces. Explicit Identity merge/split is implemented under ADR-0026. Source-driven Joiner/Mover/Leaver slices through ADR-0029 remain in place, ADR-0038 narrows the protected SUSPENDED -> ACTIVE boundary only for the exact current suspension revision proven to have been caused by the same source lifecycle relationship, ADR-0039 extends the internal Identity lifecycle-access semantic query to exact DECIMAL/DATE/DATETIME SINGLE-value inputs, and ADR-0040 adds separately typed governed MULTI inputs for membership evaluation without exposing canonical persistence, and ADR-0041 reuses that same Identity semantic input for Access-owned bounded CONTAINS_ANY/CONTAINS_ALL expected sets; operator/IAM suspension remains non-restorable by source observation. Later multi-subscriber/broker evolution remains demand-driven.

A later formal-specification checkpoint should fold the accepted ADR amendments and completed OD-003 slices into the Integration/SAD/RTM package rather than updating v0.2 for every incremental contract commit.
