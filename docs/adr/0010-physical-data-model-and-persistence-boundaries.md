# ADR-0010: Physical data model and persistence boundaries

Status: Accepted

## Context

ADR-0001 through ADR-0009 and the v0.2 formal specifications define framework-neutral capability ownership, tenant isolation, typed dynamic schema, desired-state integration, immutable evidence, optimistic concurrency and durable orchestration. OD-002 remained open because those decisions did not yet define the concrete physical database topology, identifier representation, tenant-safe references, high-cardinality projection/observation storage, indexes, partitioning or migration rules for the initial implementation.

The physical model must make those semantics enforceable without allowing PostgreSQL, JPA, Spring or ORM convenience to redefine aggregate/capability boundaries.

## Decision

The initial implementation uses one PostgreSQL database with capability-oriented schemas (`identity`, `catalog`, `access`, `governance`, `credential`, `integration`, `administration`, `audit`, `platform`) as an ownership-visibility mechanism. Schema placement is an implementation mapping, not a canonical capability definition.

Persisted domain IDs are application-generated UUIDv7 values stored as PostgreSQL `uuid`. The domain contract remains opaque stable identity: callers must not derive ordering or business time from the encoded identifier. Business/natural keys are tenant-scoped and are never primary identity.

Every tenant-owned row carries non-null `tenant_id`. Tenant-owned tables expose `UNIQUE (tenant_id, id)` in addition to their stable primary key. Same-capability tenant-owned relationships use composite `(tenant_id, reference_id)` foreign keys where practical so cross-tenant references fail structurally. PostgreSQL RLS may be added only as defense in depth.

Authoritative State, Observation, Evidence and Projection remain separate physical classes. Mutable authoritative aggregates use optimistic `revision`; immutable evidence does not pretend to be a versioned aggregate; projections carry computation/source-generation metadata and are rebuildable.

Cross-capability references default to stable typed IDs without database foreign keys. Selective cross-capability FKs are permitted only when co-location is guaranteed for the supported deployment, the relationship is fundamental and stable, corruption prevention materially benefits, extraction cost is accepted, and no cross-capability cascade or mutation shortcut is introduced. The initial model does not depend on such FKs for correctness.

Dynamic canonical attributes use governed definitions and typed physical value columns/tables. Multi-valued attributes use one row per typed element. Canonical candidates, resolved state, authority provenance and explicit overrides remain distinct. Arbitrary JSONB/native provider paths do not become governance-critical canonical attributes or policy/query contracts.

`EffectiveAccess`, support/derivation paths, `DesiredPrincipalState`, `DesiredGrantState` and fulfillment views are Access-owned rebuildable projections. Effective access stores normalized support rows and a derived support count so one assignment can disappear without removing access still supported by another assignment.

Provider/source observations use normalized current rows plus explicit run/checkpoint/staging provenance. Destructive absence is allowed only from trustworthy complete coverage for the relevant scope/object class. Partial runs may contribute positive observations but cannot erase unseen current truth. Raw/native payload retention is separate, bounded and secret-filtered.

Authoritative mutations and their outbox facts commit atomically. Inbox/deduplication and causal idempotency records provide duplicate-effect protection. Generic Platform scheduler/worker storage owns delivery metadata, leases and claims; domain capabilities retain business/process state. External/provider calls are never executed in the authoritative transaction.

Ordinary transactional tables are not partitioned initially. Time-range partitioning is reserved for measured high-volume append/history/evidence tables such as audit records, provisioning attempts and reconciliation history when operational thresholds justify it. Retention/archive/purge is based on semantic data class; business history is never removed by cross-capability cascade.

Database constraints enforce local structural invariants with PK/NOT NULL/UNIQUE/FK/CHECK constraints where unambiguous. PostgreSQL native ENUM is not the default; constrained textual codes plus application semantics are preferred for evolvability. ORM mappings must conform to the model rather than define it.

Schema evolution uses versioned forward migrations with expand/migrate/contract for rolling compatibility and bounded restartable backfills for large tables. Maven remains the JVM build. This ADR does not mandate Flyway, Liquibase or another migration framework; the implementation phase will select a runner only after evaluating ordered SQL, checksums/history, multi-schema support and Maven/operational fit.

The detailed table ownership matrix, representative table/column definitions, indexes, partition thresholds, retention rules and difficult-case walkthroughs are maintained in `docs/architecture/physical-data-model.md`.

## Consequences

- OD-002 is resolved at the living architecture level without making PostgreSQL canonical domain architecture.
- Tenant-crossing mistakes are prevented in both application logic and same-capability relational constraints.
- Capability extraction remains possible because correctness does not depend on cross-capability table mutation or mandatory cross-schema FKs.
- High-cardinality observations, evidence and projections can scale independently from ordinary transactional aggregates.
- Stale workers, partial reconciliation and provider failure remain representable without corrupting governance intent.
- The v0.2 formal Data/SAD/RTM package predates this ADR; its next controlled revision must incorporate this decision and mark OD-002 resolved.