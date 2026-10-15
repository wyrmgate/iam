# ADR-0048 Pre-release persistence baseline and migration-history governance

- Status: Proposed
- Date: 2026-10-16
- Preserves: [ADR-0010 Physical data model and persistence boundaries](0010-physical-data-model-and-persistence-boundaries.md)
- Preserves: [ADR-0005 Administrative authorization and tenant isolation](0005-administrative-authorization-and-tenant-isolation.md)
- Preserves: [ADR-0047 Sealed standalone tenant-owner recovery](0047-sealed-standalone-tenant-owner-recovery.md)

## Context

Wyrmgate IAM v2 remains pre-release. Repository persistence history currently contains Flyway versioned migrations V1 through V69. Those migrations are useful historical upgrade evidence, but a large part of the sequence consists of incremental construction, strengthening, indexing and correction performed while the v2 model was still taking shape.

A disposable DEV database can be dropped and recreated, but database reset must not be used as an excuse to rewrite deployed migration history, erase the historical meaning of a migration version, or redesign domain semantics around persistence convenience.

A short-lived pre-release V70 previously performed automatic sealed first-party bootstrap authority expansion. It was deliberately withdrawn because sealed authority must not increase implicitly. ADR-0047 documents compatibility for databases that may have recorded that historical V70. Reusing version 70 for an unrelated migration would make migration history ambiguous and is therefore unsafe.

The current physical model already defines the tenancy contract: `platform.tenant` is the isolation root, every tenant-owned row carries a non-null tenant ID, same-capability tenant-owned relationships use tenant-safe references where practical, and Organization remains tenant-local business structure rather than the tenant boundary. A persistence consolidation must preserve those semantics rather than introduce a new generic Tenant business model.

## Decision

### Preserve versioned migration history

V1 through V69 remain immutable versioned upgrade history. They are not renumbered, squashed in place, deleted, or edited merely because a disposable environment can be rebuilt.

V70 is permanently reserved as a withdrawn historical version. No future migration may reuse version 70.

Future incremental versioned migrations begin at V71.

### Add a Flyway baseline migration for empty databases

The repository adds a Flyway baseline migration representing the reviewed current physical schema at the consolidation checkpoint. A new empty database may start from that baseline and then apply later versioned migrations. A database with existing versioned migration history continues to use its recorded history and does not have its past rewritten by the baseline.

The baseline is a source-controlled migration, not a runtime schema dump. Its candidate SQL is generated from a PostgreSQL database migrated through the repository's historical path, then normalized and reviewed before commit.

The baseline must not contain `flyway_schema_history`, environment-specific ownership/ACL statements, secrets, provider data, application data, or installation/bootstrap records.

### Prove baseline equivalence

Repository verification must prove that the baseline path and the historical versioned path establish the same application schema at the checkpoint, excluding Flyway's own history metadata and PostgreSQL metadata that is not semantically part of Wyrmgate persistence.

The comparison covers application schemas, tables, columns, nullability/defaults, keys/constraints, indexes and sequences that are part of the physical contract. A baseline is not accepted merely because application startup succeeds.

### Tenant design remains semantic, not migration-driven

Persistence consolidation does not create a new Tenant lifecycle, arbitrary tenant metadata bag, tenant CRUD framework, or tenant-as-Organization model.

The existing canonical semantics remain:

- Tenant is the hard isolation/customer boundary and is Platform-owned supporting state.
- Organization is tenant-local business structure owned by Identity semantics.
- `TenantContext` is an explicit non-null isolation context passed through application and persistence boundaries.
- tenant-owned rows use non-null `tenant_id`;
- tenant-scoped business uniqueness includes tenant context;
- same-capability relationships use tenant-safe composite references where practical;
- cross-capability relationships remain stable-ID/semantic-boundary relationships and are not coupled merely to gain database convenience;
- deployment-scoped Platform state, when present, is modeled explicitly rather than represented by nullable tenancy on ordinary business rows.

The consolidation checkpoint includes an automated schema audit against those invariants. Any actual schema defect discovered by that audit is corrected by an explicit post-V70 versioned migration and is also reflected directly in the baseline. Existing databases therefore receive the same correction without history rewriting.

### DEV reset is explicit deployment work

This ADR permits a disposable DEV database to be dropped/recreated only as an explicit operator action after the new baseline is merged and verified. Application startup, Flyway, login, bootstrap status checks and ordinary requests never drop/reset a database.

A reset database is a fresh installation. It uses the current ADR-0044/0045/0046 bootstrap path and creates the finite `STANDALONE_TENANT_OWNER` authority directly. ADR-0047 remains supported for historical sealed databases that are retained rather than reset.

Resetting a database destroys its IAM state and evidence. The operator must confirm the environment is disposable/non-production and preserve any required diagnostic evidence before destruction.

### Formal specification treatment

The controlled v0.8 formal package predates this persistence-baseline checkpoint. ADR-0048 and the resulting current persistence checkpoint are folded into the next meaningful controlled formal revision. This consolidation alone does not require an ad-hoc DOCX/XLSX revision because it preserves accepted domain semantics; any separate semantic tenancy change would require the normal requirements/design review path.

## Consequences

- New empty databases have a concise reviewed schema-establishment path instead of replaying every pre-release construction step.
- Existing databases retain truthful immutable migration history.
- V70 cannot acquire a second meaning.
- A disposable DEV reset can remove the need for ADR-0047 recovery for that particular database because the recreated installation bootstraps with current authority, while ADR-0047 remains necessary compatibility support elsewhere.
- Baseline drift becomes a CI-detectable failure rather than documentation debt.
- Persistence cleanup does not silently broaden Tenant semantics.

## Rejected alternatives

### Delete or renumber V1-V69

Rejected. Existing databases and historical evidence may already know those versions, and changing them would manufacture a new history rather than provide a clean forward-compatible baseline.

### Reuse V70

Rejected. Version 70 already has documented historical meaning as the withdrawn automatic authority-expansion migration.

### Concatenate V1-V69 blindly

Rejected. A baseline is the current schema definition, not a replay script with obsolete intermediate operations. The generated current-state schema must be normalized, reviewed and equivalence-tested.

### Reset every environment automatically

Rejected. Database destruction is an explicit operator/deployment action and is never an application migration behavior.

### Redesign Tenant because the database is being rebuilt

Rejected. Database convenience is not a source of domain requirements. Tenant semantics change only through separately justified architecture/domain decisions.
