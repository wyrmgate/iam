# Legacy-to-v2 Migration Discovery and Cutover Contract

## Purpose

This document defines the first implementation-facing OD-006 contract for discovering, mapping,
rehearsing and cutting over legacy Wyrmgate IAM data into the v2 model.

It deliberately does **not** define a legacy schema, a production migration mapping, a migration
execution topology, or an irreversible retirement procedure. Those decisions require observed legacy
inventory and measured rehearsal evidence.

The controlled formal checkpoint remains v0.7. This document is living repository guidance and
should be folded into the next meaningful formal revision only after the migration plan and evidence
are sufficiently mature.

## Status

OD-006 remains open.

The first required step is evidence-driven discovery. No production converter, bulk loader, dual-write
path or cutover mechanism should be treated as canonical until the source inventory and mapping
register described here exist.

## Non-negotiable architecture boundaries

Migration is a translation into the accepted v2 model, not an opportunity for legacy structures to
redefine it.

All migration work must preserve these existing rules:

- capability ownership remains authoritative; only the owning capability mutates its authoritative
  state;
- Authoritative State, Observation, Evidence and Projection remain distinct;
- Identity is the governed subject, Principal is its technical representation, and Credential belongs
  to Principal;
- AccessAssignment is authoritative access intent; EffectiveAccess and desired provider state are
  derived projections;
- first-class relationships remain first-class and are not flattened into generic extension
  attributes;
- dynamic/custom values use governed typed schemas; provider/source-native data remains observation
  until explicitly mapped;
- partial or incomplete source material must never be interpreted as destructive absence;
- historical governance/evidence remains historical evidence and is never rewritten to look like
  current authority;
- raw secret/private credential material must not appear in ordinary migration reports, logs,
  evidence exports, task payloads or errors;
- tenant is the isolation boundary and must be preserved throughout extraction, staging, rehearsal,
  validation and cutover;
- external/provider calls remain outside authoritative database transactions;
- asynchronous migration or repair work is assumed retryable and must tolerate duplicate delivery,
  replay and restart.

## 1. Legacy inventory evidence

Before any mapping is approved, create an observed inventory for every migration source.

Each source inventory must identify:

| Evidence | Required content |
| --- | --- |
| Source identity | System/database/export name, version/build, environment and owning team |
| Extraction contract | Read method, snapshot/export mechanism, consistency guarantees and cutoff semantics |
| Entity inventory | Tables, collections, objects or files relevant to migration |
| Isolation model | Tenant/account/realm/organization partitioning and any shared/global records |
| Identifiers | Primary/stable/natural identifiers, uniqueness scope, mutability and known reuse behavior |
| Data classification | Which records appear authoritative, observational, historical/evidentiary or derived |
| Volumes | Approximate row/object counts, high-cardinality relationships and payload sizes |
| Relationships | Foreign references, composition, manager/organization/ownership links and orphan behavior |
| Temporal model | Creation/update times, validity windows, lifecycle history and tombstone/deletion semantics |
| Secret boundary | Passwords, keys, private material, tokens or provider credentials that require exclusion/redaction |
| Data quality | Null/invalid/duplicate/orphaned/inconsistent values and known anomalies |
| Retention obligations | Audit, legal, governance or operational history that must remain explainable after cutover |

Inventory records must distinguish verified observation from assumptions. Unknowns remain explicit
unknowns; do not infer missing legacy semantics from current v2 code.

## 2. Mapping register

After inventory exists, maintain a reviewed mapping register from each observed legacy concept to the
v2 model.

Each mapping entry must record at least:

- source system and source entity/field;
- source semantic description and confidence;
- target capability;
- target semantic concept;
- target state category: Authoritative State, Observation, Evidence or Projection;
- type/cardinality conversion;
- identifier/reference strategy;
- lifecycle/temporal conversion;
- provenance retained in v2;
- validation rule;
- anomaly/rejection behavior;
- whether the mapping is reversible or lossy;
- approval status and owner.

### Mapping rules

The register must not:

- map multiple unrelated legacy concepts into a generic `IamObject`/EAV abstraction;
- write another capability's authoritative state through a foreign repository/table;
- convert provider/source-native payload directly into current canonical authority without an explicit
  governed mapping;
- turn historical assignments, approvals, audit rows or observations into current authorization;
- replace manager, organization, ownership, role composition or Principal relationships with generic
  attributes;
- migrate rebuildable projections merely because a legacy table looks similar.

When a legacy concept cannot be represented without changing accepted v2 semantics, stop and record
the gap. Resolve it through requirements/ADR work rather than silently weakening the v2 model.

## 3. Stable ID and crosswalk decision gate

A migration must not assume that legacy identifiers are valid v2 aggregate IDs.

Before implementation, decide per concept whether to:

1. preserve an existing identifier as the v2 stable ID;
2. generate a new v2 stable ID and retain the legacy identifier as migration provenance/crosswalk;
3. reject or manually resolve the record.

The decision must consider:

- uniqueness scope and collision history;
- identifier mutability/reuse;
- tenant boundaries;
- externally referenced identifiers;
- historical evidence references;
- merge/split/correlation semantics;
- future import/reconciliation keys.

A crosswalk must be deterministic and queryable for verification. It is migration provenance, not a
universal runtime identity model.

If the observed source inventory requires a durable identifier strategy that changes canonical
architecture, record that decision in an ADR before production migration implementation.

## 4. Execution-model decision gate

Do not choose the migration mechanism before inventory and mappings are reviewed.

Possible implementation patterns may include:

- semantic application commands/import interfaces;
- capability-owned offline loaders;
- staged ETL into typed capability-owned import tables;
- controlled direct relational loading where invariants and ownership can still be proven;
- coexistence/delta synchronization during a transition window.

No pattern is canonical yet.

Whichever execution model is selected must:

- invoke or enforce the same high-value invariants as normal v2 state creation;
- preserve capability ownership;
- be restartable and causally idempotent;
- keep failed/partial batches distinguishable from complete migration;
- retain deterministic provenance from source record to v2 result;
- make anomaly/rejection outcomes explicit;
- prevent a partial batch from implying absence or revocation;
- avoid external provider calls inside authoritative load transactions;
- avoid hidden cross-capability repair SQL.

## 5. Rehearsal contract

A production cutover requires at least one isolation-safe full rehearsal using the reviewed mapping
and transformation version.

Each rehearsal must record:

- immutable extraction/snapshot identity and cutoff;
- migration tool/build/version and configuration fingerprint;
- target v2 main/application revision and Flyway head;
- start/end times and phase durations;
- per-capability input, accepted, rejected, transformed and skipped counts;
- crosswalk counts and collision/anomaly counts;
- referential/invariant validation results;
- tenant-isolation checks;
- representative access/governance explanation samples;
- evidence/history preservation samples;
- projection rebuild results for rebuildable state;
- secret/private-material exclusion checks;
- restart/retry behavior from an interrupted run;
- final anomaly register and disposition.

A rehearsal is not successful merely because all rows loaded.

## 6. Verification model

Verification must combine structural counts with semantic checks.

### Required structural checks

- source-to-target count reconciliation within explicitly documented transformation rules;
- no cross-tenant references;
- no unresolved required foreign semantic references;
- typed attribute/cardinality validity;
- lifecycle and temporal validity constraints;
- uniqueness and collision checks;
- audit/evidence immutability expectations;
- crosswalk determinism.

### Required semantic checks

Sample and automated checks should prove, as applicable:

- each migrated Identity remains explainable from its source/provenance;
- Principals point to the intended governed Identity or intentionally remain uncorrelated;
- migrated AccessAssignment authority has valid target/provenance/validity semantics;
- historical access or approval evidence did not become current authority;
- Role/RoleVersion and policy immutability boundaries are respected;
- Governance evidence remains evidence only;
- Credential rows contain only permitted metadata/opaque references and no raw private material;
- observations remain observations;
- EffectiveAccess/desired state and other documented projections can be rebuilt from authoritative
  inputs rather than manually repaired.

## 7. Cutover gates

The production cutover plan must explicitly define all of the following before approval.

### Source freeze/coexistence

Document whether the legacy system becomes read-only, whether a bounded coexistence period exists, and
which system owns writes for each migrated concept at every point in the transition.

There must never be an ambiguous two-writer authority window.

### Final delta

If changes can occur after the rehearsal snapshot, define:

- how the final delta is identified;
- ordering/cutoff semantics;
- duplicate/replay handling;
- reconciliation with the full snapshot;
- what happens when the delta contains a record that failed initial migration.

### Go/no-go

At minimum, go-live requires:

- approved inventory and mapping register;
- successful full rehearsal;
- accepted anomaly disposition;
- migration tool/version frozen;
- expected v2/Flyway revision verified;
- backup/restore and operational rollback prerequisites verified for the target environment;
- tenant isolation checks green;
- semantic verification thresholds satisfied;
- no unresolved secret/private-material exposure;
- external integration re-entry plan approved.

### Rollback versus forward-fix

The cutover plan must define when to:

- abort before v2 write authority begins;
- restore the pre-cutover v2 target;
- reopen legacy write authority;
- retain v2 and forward-fix a bounded migration defect.

Database down-migration is not the default recovery strategy.

Once new v2-only authoritative writes occur, rollback must account for those writes explicitly rather
than assuming the legacy system can simply be restarted as if nothing happened.

## 8. Post-cutover reconciliation

After authority moves to v2:

- rebuild designated projections;
- re-run source/provider reconciliation under ordinary completeness rules;
- verify no partial import is treated as destructive absence;
- revalidate a representative sample of effective access and governance evidence;
- monitor migration-specific anomaly/failure metrics;
- retain the crosswalk and migration evidence for the approved retention period;
- keep legacy data read-only or otherwise protected until the rollback/retention decision permits
  retirement.

Provider observation after cutover may identify drift; it must not silently rewrite migrated
AccessAssignment or other authoritative state.

## 9. Legacy retirement

Legacy retirement is a separate controlled step after successful cutover.

Initial cutover must not combine first-use replacement with irreversible source deletion.

Retirement requires explicit decisions for:

- rollback window expiration;
- legal/audit/evidence retention;
- crosswalk retention;
- credential/secret destruction handling;
- archive/export ownership;
- decommissioning access to legacy infrastructure;
- evidence that no supported operational dependency still reads the legacy system.

## 10. Decisions blocked on observed inventory

The following remain intentionally unresolved until real legacy evidence exists:

- exact entity/field mappings;
- stable-ID preservation versus generated-ID policy by concept;
- crosswalk persistence shape;
- migration execution mechanism;
- whether coexistence/dual-run is required;
- final-delta strategy;
- authoritative-history migration depth;
- legacy audit/evidence retention format;
- handling of unsupported legacy concepts;
- volume-driven batching/checkpoint design;
- production cutover window and rollback duration;
- legacy retirement date/process.

These are not implementation gaps to guess around. They are explicit migration decisions.

## 11. Required artifacts for the next OD-006 slice

The next implementation slice should begin only when at least one real legacy source can be inspected.

It should produce:

1. an observed inventory for that source;
2. a typed mapping register;
3. an anomaly/data-quality report;
4. any required ADR for stable IDs/execution semantics;
5. a bounded migration-tooling design derived from the observed data;
6. rehearsal fixtures/tests that prove restartability, tenant isolation and invariant preservation.

Until then, OD-006 is correctly in discovery/planning rather than production implementation.
