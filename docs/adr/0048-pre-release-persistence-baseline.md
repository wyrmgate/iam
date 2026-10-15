# ADR-0048 Pre-release persistence rebaseline

- Status: Proposed
- Date: 2026-10-16
- Preserves: [ADR-0010 Physical data model and persistence boundaries](0010-physical-data-model-and-persistence-boundaries.md)
- Preserves: [ADR-0005 Administrative authorization and tenant isolation](0005-administrative-authorization-and-tenant-isolation.md)
- Preserves: [ADR-0047 Sealed standalone tenant-owner recovery](0047-sealed-standalone-tenant-owner-recovery.md)

## Context

Wyrmgate IAM v2 remains pre-release. The original Flyway sequence V1 through V69 records the incremental construction of the current persistence model: initial capability schemas, successive aggregate tables, corrections, stronger constraints, indexes and later feature slices. Replaying that development history for every new empty database is no longer useful and makes the active migration surface harder to review.

The shared DEV database is disposable and may be recreated. This creates a controlled opportunity to establish a clean pre-release migration baseline without changing accepted IAM semantics.

A short-lived historical V70 automatically expanded sealed first-party bootstrap authority and was deliberately withdrawn. Its historical meaning remains documented by ADR-0046/0047 and must not be silently reintroduced.

The persistence rebaseline is not a domain redesign. Tenant remains the Platform-owned isolation/customer boundary. Organization remains tenant-local Identity business structure. Capability ownership, authoritative/observation/evidence/projection classification, tenant-scoped uniqueness, same-capability tenant-safe references, and cross-capability semantic boundaries remain unchanged.

## Decision

### Reset the active Flyway history for fresh pre-release databases

The runtime migration location is re-established as a concise current-state baseline:

- `V1__prepare_current_schema_baseline.sql` contains only PostgreSQL restore prerequisites required by the generated current-state schema;
- `V2__current_schema_baseline.sql` contains the reviewed schema state produced by applying the historical V1-V69 path;
- future persistence changes continue incrementally from V3.

A fresh database therefore establishes the current schema directly instead of replaying 69 development migrations.

The current-state baseline contains schema objects only. It must not contain application rows, Tenant rows, installation/bootstrap rows, provider observations, secrets, environment-specific owners/ACLs, or Flyway schema-history rows.

### Preserve old migrations as historical verification fixtures, not runtime migrations

The original V1-V69 files move to:

`apps/server/src/test/resources/db/history/pre-rebaseline/`

They are retained for equivalence verification, archaeology and historical compatibility analysis. They are no longer included in the ordinary runtime Flyway location and are not replayed by a fresh installation.

Historical V70 remains absent. Its withdrawn meaning is retained in ADR-0046/0047 and must never be copied into the archived V1-V69 fixture set as if it were accepted schema history.

This is an explicit pre-release migration-history reset. A database carrying the pre-rebaseline Flyway history must not be pointed blindly at the new runtime migration location and expected to upgrade in place. Disposable development databases are recreated. Any retained historical installation follows an explicit compatibility/migration plan appropriate to that installation rather than manufacturing Flyway history.

### Prove current-state equivalence in CI

Repository verification creates two empty PostgreSQL databases:

1. a historical database using only the archived V1-V69 migration fixtures;
2. a baseline database using only the active runtime baseline migrations.

CI dumps both resulting application schemas, excludes `flyway_schema_history` and non-semantic PostgreSQL dump metadata, normalizes the dumps, and requires an exact schema diff.

Equivalence includes schemas, functions, tables, columns, defaults/nullability, keys, constraints, indexes, views, triggers and foreign keys represented in the PostgreSQL schema dump. Startup success alone is not sufficient.

The equivalence workflow is read-only with respect to the repository. Baseline SQL is reviewed source, not self-modifying CI output.

### Keep the baseline physically aligned with capability boundaries

The current database continues to use the capability-oriented schemas:

- `platform`
- `identity`
- `catalog`
- `access`
- `governance`
- `credential`
- `integration`
- `administration`
- `audit`

A single current-state SQL file is intentionally used for the schema body because PostgreSQL functions, views, triggers and foreign keys have ordering dependencies across object classes. Capability ownership remains visible through schema-qualified objects; splitting the baseline into artificial migration numbers merely to obtain one file per schema would create ordering coupling without improving domain ownership.

Later migrations should be focused deltas named for the semantic persistence change they implement. The baseline is not regenerated for every ordinary change.

### Tenant-isolation invariants are verified at the checkpoint

The rebaseline preserves the canonical tenancy model:

- `platform.tenant` is the isolation root and does not carry a `tenant_id` pointing to itself;
- tenant-owned application tables carry non-null `tenant_id`;
- tenant-scoped uniqueness includes tenant context where required;
- same-capability tenant-owned foreign keys carry tenant context on both sides where represented relationally;
- cross-capability domain references remain stable IDs without database FKs unless the reference is solely the Platform Tenant isolation anchor;
- Organization is not Tenant and remains tenant-local business structure;
- deployment-local bootstrap state remains explicit and never makes ordinary business rows globally scoped through nullable tenancy.

Automated persistence integration tests enforce these structural isolation rules against the baseline-produced schema.

### DEV reset is explicit

A disposable DEV database may be dropped/recreated only as an operator/deployment action after this rebaseline and its CI checks are merged. Wyrmgate application startup, normal Flyway execution, login, setup status and ordinary APIs never drop databases.

The recreated database is a fresh installation. It runs the current ADR-0044/0045/0046 setup path and creates the finite `STANDALONE_TENANT_OWNER` authority directly. ADR-0047 remains compatibility support for historical sealed installations that are retained rather than recreated.

### Formal specification treatment

The controlled formal package remains v0.8. ADR-0048 records a pre-release persistence-history checkpoint while preserving current domain semantics. It is folded into the next meaningful controlled formal revision together with the other accepted post-v0.8 ADR amendments; this change does not trigger an ad-hoc DOCX/XLSX revision.

## Consequences

- Fresh databases execute two baseline migrations instead of 69 historical construction migrations.
- The active migration directory describes the current product rather than the chronology of development.
- Historical V1-V69 SQL remains available for automated equivalence proof and archaeology without being shipped as normal migration work.
- Disposable DEV environments must be recreated when switching to the rebaselined runtime history.
- Retained pre-rebaseline databases require an explicit compatibility plan; Flyway history is never forged automatically.
- Tenant-isolation defects become CI failures at the persistence checkpoint.
- Future migrations begin at V3 and remain small semantic deltas from the current-state baseline.

## Rejected alternatives

### Keep V1-V69 active and add a B69 baseline beside them

Rejected. That still leaves the development-era chain as the normal migration surface and does not achieve the requested pre-release cleanup.

### Hand-combine V1-V69 sequentially

Rejected. Concatenating intermediate ALTER/correction steps preserves obsolete construction rather than describing the current schema.

### Split the baseline into one artificial Flyway version per capability

Rejected for this checkpoint. Capability ownership is already represented by PostgreSQL schemas, while object-class dependency ordering spans schemas. Artificial version boundaries would imply an ordering/ownership relationship that is not part of the domain architecture.

### Automatically rewrite existing Flyway history

Rejected. A runtime migration must not delete, renumber or manufacture historical Flyway rows. Disposable environments are recreated; retained environments use an explicit controlled compatibility plan.

### Redesign Tenant as part of migration cleanup

Rejected. Persistence cleanup is not authority to redefine the domain. Any future Tenant lifecycle/product-semantics change requires its own architecture/domain decision.
