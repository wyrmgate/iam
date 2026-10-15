# ADR-0048 Pre-release persistence rebaseline

- Status: Accepted
- Date: 2026-10-16
- Preserves: [ADR-0010 Physical data model and persistence boundaries](0010-physical-data-model-and-persistence-boundaries.md)
- Preserves: [ADR-0005 Administrative authorization and tenant isolation](0005-administrative-authorization-and-tenant-isolation.md)
- Preserves: [ADR-0047 Sealed standalone tenant-owner recovery](0047-sealed-standalone-tenant-owner-recovery.md)

## Context

Wyrmgate IAM v2 remains pre-release. The former Flyway V1-V69 sequence recorded development chronology: initial schemas, aggregate construction, corrections, stronger constraints, indexes and later feature slices. Replaying that chronology for every empty database no longer represented a useful current-state persistence baseline.

The disposable DEV database can be recreated, so this is the controlled point to reset the pre-release Flyway namespace without changing accepted IAM semantics.

Tenant remains the Platform-owned isolation/customer boundary. Organization remains tenant-local Identity business structure. Capability ownership, authoritative/observation/evidence/projection classification, tenant-scoped uniqueness, same-capability tenant-safe references and cross-capability semantic boundaries remain unchanged.

A short-lived historical V70 once expanded sealed first-party bootstrap authority and was deliberately withdrawn. ADR-0046/0047 retain that historical meaning. Version 70 remains reserved and is not reused.

## Decision

### Establish a capability-organized current-state baseline

The active runtime migration location contains only the reviewed current-state baseline:

1. `V1__create_capability_schemas.sql`
2. `V2__platform_baseline.sql`
3. `V3__identity_baseline.sql`
4. `V4__catalog_baseline.sql`
5. `V5__access_baseline.sql`
6. `V6__governance_baseline.sql`
7. `V7__credential_baseline.sql`
8. `V8__integration_baseline.sql`
9. `V9__administration_baseline.sql`
10. `V10__audit_baseline.sql`

Future persistence changes continue as focused semantic deltas from V11 onward. The baseline is not regenerated for ordinary changes.

The split is by physical capability schema rather than by individual entity/table. This keeps the baseline reviewable without turning each aggregate or table into an artificial Flyway version boundary. The file layout does not imply capability == schema, module, service or database.

`platform` is applied first because it owns the Tenant isolation root and shared technical persistence anchors. Cross-capability domain references remain stable IDs rather than relational foreign keys except for the explicit Platform Tenant isolation anchor, so capability files do not create a hidden cross-capability ownership model.

The baseline contains schema objects only. It contains no Tenant rows, installation/bootstrap rows, application data, provider observations, secrets, environment-specific owners/ACLs or Flyway history rows.

### Remove the development-era migration SQL after equivalence proof

The former V1-V69 SQL is not retained as runtime migrations or repository test fixtures after the rebaseline is proven.

Before deletion, CI constructed the current schema from the verified pre-rebaseline checkpoint and the proposed capability-organized V1-V10 baseline, produced normalized PostgreSQL schema dumps, and required an exact diff. The successful equivalence run is retained in GitHub CI/PR history; the obsolete SQL files are not retained as a competing persistence definition.

Git history remains available for archaeology. Retained historical installations must use an explicit compatibility/cutover plan appropriate to their actual Flyway history; the application never forges or rewrites `flyway_schema_history` automatically.

Historical V70 remains absent and reserved. If the new active sequence eventually reaches V69, it skips to V71.

### Preserve tenant-isolation invariants

The rebaseline preserves the canonical tenancy model:

- `platform.tenant` is the isolation root and does not point to itself through `tenant_id`;
- tenant-owned rows carry non-null `tenant_id`;
- tenant-scoped uniqueness includes tenant context where required;
- same-capability tenant-owned foreign keys carry tenant context on both sides where represented relationally;
- cross-capability domain references remain stable IDs without database foreign keys unless the reference is solely the Platform Tenant isolation anchor;
- Organization remains tenant-local business structure and is not Tenant;
- deployment/bootstrap state remains explicit and does not make ordinary business rows globally scoped through nullable tenancy.

Persistence integration tests enforce the structural tenant-isolation rules against databases created from the active baseline.

### DEV reset is explicit

A disposable DEV database may be recreated only as an explicit operator/deployment action after this rebaseline is merged and verified. Wyrmgate startup, Flyway, authentication, setup status and ordinary APIs never drop databases.

The recreated database is a fresh installation and follows ADR-0044/0045/0046, including the finite `STANDALONE_TENANT_OWNER` bootstrap authority. ADR-0047 remains compatibility support for retained historical sealed installations and is not part of a fresh reset.

### Formal specification treatment

The controlled formal package remains v0.8. ADR-0048 is an accepted post-v0.8 persistence checkpoint and is folded into the next meaningful controlled formal revision with the other accepted amendments rather than triggering an ad-hoc DOCX/XLSX revision.

## Consequences

- Fresh databases execute ten coherent current-state baseline migrations instead of 69 development-history migrations.
- The runtime migration directory describes the current product rather than development chronology.
- Capability/schema grouping makes ownership and review boundaries visible without creating per-table migration noise.
- The obsolete V1-V69 SQL is removed after equivalence was proven; Git history and CI evidence provide archaeology without competing active definitions.
- Disposable DEV environments must be recreated when moving to the new namespace.
- Retained pre-rebaseline databases require an explicit compatibility plan.
- Future semantic deltas begin at V11 and V70 remains reserved.
- Tenant isolation remains an architectural invariant rather than a convenience inferred from migration ordering.

## Rejected alternatives

### Keep V1-V69 active and add a baseline beside them

Rejected. That keeps development chronology as the normal runtime migration surface.

### Keep the old V1-V69 SQL as repository fixtures indefinitely

Rejected after the equivalence checkpoint passed. Once the replacement schema is proven equivalent, retaining the obsolete chain creates duplicate persistence definitions and unnecessary maintenance burden.

### Use one giant current-state migration

Rejected. A monolithic dump-style file is difficult to review and obscures the physical capability/schema organization that already exists in the canonical persistence model.

### Split the baseline by individual entity or table

Rejected. Per-table versioning would create a new artificial migration chronology and imply ordering boundaries that do not represent domain ownership.

### Automatically rewrite existing Flyway history

Rejected. Disposable environments are recreated; retained environments use an explicit reviewed compatibility plan.

### Reuse V70

Rejected. Reuse would assign two meanings to the same historical migration number in project archaeology and operator compatibility analysis.

### Redesign Tenant during persistence cleanup

Rejected. Rebaseline work preserves the accepted Tenant/Organization semantics. Any future Tenant lifecycle or product-semantics change requires a separate architecture/domain decision.
