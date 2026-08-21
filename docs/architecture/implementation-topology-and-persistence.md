# Implementation Topology and Persistence Baseline

## Status

Current architecture baseline. This document maps the canonical IAM domain into an initial implementation topology without making runtime/framework choices part of the domain model.

## Initial topology

Wyrmgate IAM starts as a modular monolith. Logical capabilities remain explicit even when deployed in one runtime:

- Identity
- Catalog
- Access
- Governance
- Credential
- Integration
- Administration
- Audit
- Platform

A logical capability is not automatically a process, Maven module, package, database schema, or microservice.

Recommended execution roles:

- **API** — synchronous application commands and queries.
- **WORKER** — event consumers and projection/recalculation work.
- **CONNECTOR_WORKER** — provisioning, reconciliation, source discovery, provider calls.
- **SCHEDULER** — expiry, campaign, reconciliation, rotation, and other due-time triggers.
- **ALL** — combined small-deployment role.

Likely future extraction candidates are connector workers, audit/search/archive, high-volume reconciliation/import, notification delivery, and indexing. Identity, Catalog, Access, and Governance should not be split into services merely for architectural fashion.

## Persistence direction

PostgreSQL is the initial recommended authoritative store, but PostgreSQL is an implementation mapping rather than a canonical domain dependency.

Persistence is logically owned by capability. Cross-capability repositories must not directly mutate another capability's authoritative state.

State classes are stored according to their semantics:

- **Authoritative state** — normalized transactional relational structures.
- **Source/provider observations** — high-volume ingestion/current/staging structures optimized for comparison.
- **Evidence** — append-oriented immutable records.
- **Projections** — rebuildable read/materialized structures such as EffectiveAccess and Identity360.

## Database ownership

Within one physical database, capability-specific schemas/tables are recommended because they make ownership visible. Reporting/read projections may intentionally compose across capability data; domain repositories may not use that as a mutation shortcut.

Cross-capability foreign keys may be used selectively for fundamental stable references, but cross-capability `ON DELETE CASCADE` is forbidden.

Tenant-owned rows should carry `tenant_id` from the beginning. Single-tenant deployments resolve one default tenant. Tenant isolation remains an application/domain security concern even if PostgreSQL RLS is later added as defense in depth.

## IDs and concurrency

Canonical IDs are opaque, stable, globally unique identifiers and are never derived from business codes, email addresses, usernames, or provider display values.

Mutable authoritative aggregates carry a revision/version when concurrent changes matter. Stale writes are rejected and re-evaluated rather than silently applying last-writer-wins.

## JSON and relational data

JSON/JSONB is appropriate for extensible/native edges such as provider metadata, connector configuration, raw source attributes, extension values, evidence snapshots, and event payloads.

Core lifecycle, authorization, role composition, approval state, tenant identity, assignment provenance, and other fundamental IAM semantics remain strongly modeled and queryable rather than arbitrary JSON.

## Effective and desired state

`EffectiveAccess`, `DesiredPrincipalState`, and `DesiredGrantState` are materialized/rebuildable projections at scale. Detailed provenance/support paths can be stored separately to avoid large opaque arrays on every effective-access row.

Integration consumes normalized desired state rather than traversing Access/Catalog repositories directly.

## Observations and reconciliation

Large reconciliation/source imports use run provenance and staging/completeness semantics. A partial run must not replace complete current observation state or infer absence.

Run outcome and run completeness are separate concepts.

## Audit, outbox, and asynchronous work

A transaction that changes authoritative state and emits a semantic fact should persist the fact/outbox record atomically. Initial delivery may use database polling; a broker can be introduced later without changing producer semantics.

Consumers assume at-least-once delivery and are idempotent/revision-aware. Exactly-once business effects are achieved through idempotency, deduplication, and concurrency rules rather than transport guarantees.

Durable orchestration is required for merge/split, credential rotation, provisioning, reconciliation, source import, bulk operations, and other long-running processes.

## HTTP/runtime guidance

API requests should normally authorize, validate, commit the authoritative operation or durable orchestration record, and return. Provider calls and large recalculation do not remain on the HTTP request thread.

Schedulers trigger semantic application commands; schedulers do not directly update business-state tables.

## Module boundary rule

A future build layout may use modules such as `iam-identity`, `iam-catalog`, `iam-access`, `iam-governance`, `iam-integration`, and similar names, but build dependency direction must follow semantic capability contracts. Cross-aggregate references use typed IDs, not ORM object graphs.

The shared kernel stays deliberately small. Avoid generic `BaseEntity`, `GenericRepository`, `GenericIamObject`, or other abstractions that collapse domain distinctions.