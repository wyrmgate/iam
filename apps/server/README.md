# IAM Server

Java/Spring modular-monolith backend for Wyrmgate IAM.

## Toolchain

- Java 25 LTS
- Spring Boot 4.1.x
- Apache Maven 3.9.x
- PostgreSQL
- Flyway
- Spring JDBC
- Spring Boot Actuator
- OpenAPI via springdoc-openapi
- JUnit 5, ArchUnit, and PostgreSQL Testcontainers

The server preserves the documented dependency direction:

`domain -> application/use-case -> repository/port -> infrastructure/adapter -> API`

Bounded contexts own their persistence and must not reach directly into another context's repositories. Cross-context collaboration occurs through explicit application services, durable domain events, or intentionally designed read models.

Initial capability areas include identity, catalog, access, governance, credential, integration, administration, audit, and platform. New Java code uses the `io.wyrmgate.iam` namespace.

Business rules must not be hidden in controllers, persistence adapters, or UI-facing DTOs.

## Persistence foundation

The initial PostgreSQL physical topology follows ADR-0010. Capability schemas are created by Flyway while capability-specific aggregate tables are added only with their owning use cases.

Platform infrastructure currently provides:

- explicit `platform.tenant` isolation roots;
- application-side UUIDv7 ID generation with opaque ID semantics;
- explicit `TenantContext` values rather than thread-local tenant state;
- revision-guarded JDBC update support that rejects stale writes;
- a transaction executor for bounded authoritative state + outbox commits;
- transactional outbox, inbox/deduplication, causal idempotency, and technical scheduled-work leasing;
- PostgreSQL integration tests that run the real Flyway history from an empty database.

The Platform persistence package is infrastructure, not a generic domain repository layer. Do not add `BaseEntity`, `GenericRepository`, cross-capability DAOs, or framework types to domain contracts.

## Identity persistence slices

The Identity capability now has three vertical persistence slices built on the shared foundation.

### Canonical Identity

- `IdentityType` is `PERSON`, `SERVICE`, or `WORKLOAD` and is paired with exactly one compatible typed profile by the aggregate transaction;
- `IdentityLifecycleState` is persisted independently from technical fulfillment or provider observation;
- profile tables are typed relational boundaries and intentionally contain no invented profile-specific business fields until governed requirements define them;
- all Identity reads and writes are explicitly tenant-scoped;
- mutable Identity state uses optimistic revision predicates and rejects stale writes;
- authoritative Identity mutations and internal semantic facts commit through the same transaction and shared outbox.

### Source observation and correlation

- `SourceSystem` is Identity-owned authoritative configuration with tenant-scoped stable identity and business code uniqueness;
- `SourceImportRun` keeps execution state and coverage completeness separate; completed imports must resolve coverage as `COMPLETE` or `PARTIAL`;
- `SourceRecord` is current positive source observation, keyed by tenant + source system + native key, and remains independent from canonical Identity state;
- positive observations are upserted without treating records missing from an import as absent;
- a `PARTIAL` import can refresh records it saw but cannot erase unseen records or replace their prior complete-run provenance;
- a `COMPLETE` import advances `lastCompleteImportRunId` only for records positively observed by that run; destructive absence handling remains a separate future operation with stronger coverage checks;
- `IdentityLink` preserves authoritative correlation history, with a database-enforced maximum of one active accepted link per SourceRecord;
- replacing a correlation supersedes the prior accepted link rather than rewriting/deleting history;
- same-capability composite tenant foreign keys prevent cross-tenant SourceSystem, SourceRecord, import-run, Identity and IdentityLink relationships;
- source observation/correlation facts use the internal transactional outbox and are not automatically public integration events.

### Canonical attribute mapping, authority and resolution

- `AttributeDefinition` provides a stable tenant-scoped canonical key; semantic type/cardinality/classification/query-policy flags live in immutable `AttributeDefinitionVersion` rows attached to an activatable `CanonicalSchemaVersion`;
- only draft schema versions accept new definition-version content; activating a replacement schema supersedes the prior active version instead of mutating it;
- `AttributeMappingVersion` records which source field/path maps to one canonical definition version, while `AttributeAuthorityRuleVersion` independently ranks trusted sources; mapping never implies authority;
- canonical values are strongly typed as STRING, BOOLEAN, INTEGER, DECIMAL, DATE, DATETIME or ENUM. MULTI values use one normalized typed row per element and are never represented as JSON arrays;
- `CanonicalAttributeCandidate` remains source-derived candidate/observation state with explicit SourceRecord and mapping provenance; importing or mapping a candidate does not by itself make the value canonical;
- `CanonicalAttributeState` is authoritative resolved state with explicit `RESOLVED`, `OVERRIDDEN`, `CONFLICT`, `UNRESOLVED` and `NO_VALUE` outcomes plus optimistic `valueRevision` semantics;
- resolution uses explicit authority priority and never source recency as implicit last-write-wins. Equal top authority with differing values produces `CONFLICT`; a compatible prior trusted source value may remain readable while the conflict is exposed;
- `CanonicalAttributeOverride` is an explicit reasoned, optionally time-bounded authoritative override. Expiry is effective by time even if no scheduler runs, and override history never rewrites SourceRecords or candidates;
- recomputation that produces the same canonical outcome does not increment `valueRevision` or emit a new canonical-state fact;
- internal canonical-attribute facts are data-minimized and exclude resolved/candidate/override values.

The first implementation deliberately does not introduce a generic validation-rule DSL or arbitrary JSON policy surface. Classification remains a governed nonblank classification key; richer validation/configuration syntax belongs in a later controlled design when concrete requirements justify it.

The Identity domain/application packages remain free of Spring and JDBC dependencies; persistence details stay isolated in `identity.persistence`.

## Identity API/event contract slice

OD-003 now has a first checked-in, contract-first Identity interface slice:

- `src/main/resources/contracts/openapi/identity-v1.json` defines semantic Identity create/read/list/non-lifecycle metadata update and governed canonical-attribute reads;
- `src/main/resources/contracts/asyncapi/identity-events-v1.json` defines curated, minimized public `identity.created` and `identity.metadata-changed` integration events;
- `docs/api/identity-contracts.md` records the implementation-facing semantics and completion boundary;
- `scripts/verify-api-contracts.py` runs in Core CI to protect project-specific API/event invariants.

These contracts are intentionally **not runtime-exposed yet**. The Administration/default-deny control-plane authorization layer required by FR-ADM-001 and SRS-SEC-001 is not implemented, so publishing mutating Identity endpoints now would create an insecure temporary administration surface. Internal outbox facts are likewise not automatically external events; publication wiring remains a separate adapter/integration concern.

Not yet implemented in Identity: source-import destructive absence processing, organizations/manager/owner relationships, principals, merge/split, runtime public Identity controllers, control-plane authorization enforcement, or external integration-event publication.

## Commands

From the repository root:

```bash
make server-build
make server-test
make server-run
```

`server-build` and `server-test` require Docker because the PostgreSQL persistence contract is verified with Testcontainers. `server-run` requires the local PostgreSQL stack; the root Makefile starts it automatically through `dev-up`.
