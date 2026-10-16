# Physical Data Model and Persistence Design

## Status and scope

This document is the implementation-facing physical persistence contract for the current Wyrmgate IAM v2 PostgreSQL target. It resolves OD-002 at the current implementation scope while preserving the framework-neutral architecture and accepted ADRs.

PostgreSQL, JDBC, Flyway and JPA are implementation choices. They do not redefine capability ownership, aggregate boundaries, lifecycle semantics, tenancy, observation/evidence/projection classification, public contracts or cross-capability collaboration.

The governing rules are:

- only an owning capability mutates its authoritative state;
- Authoritative State, Observation, Evidence and Projection remain distinct;
- business/governance state, temporal validity, technical fulfillment and provider observation remain separate dimensions;
- external/provider calls execute outside authoritative transactions;
- asynchronous delivery is at-least-once and duplicate/replay/out-of-order tolerant;
- secrets/private credential material are excluded from ordinary persistence payloads, APIs, events, audit, task payloads and errors;
- Tenant is the isolation boundary; Organization is tenant-local business structure;
- capability is not equivalent to package, Maven module, process, service or database schema.

ADR-0048 replaced the former development-history migration chain with the capability-organized current-state Flyway baseline. The active baseline is V1-V10; future semantic persistence deltas begin at V11. Historical development migration numbers are not part of this living physical-model contract.

## 1. Physical database and schema topology

The initial deployment uses one PostgreSQL database with capability-oriented schemas:

- `platform`
- `identity`
- `catalog`
- `access`
- `governance`
- `credential`
- `integration`
- `administration`
- `audit`

Schemas make physical ownership visible and allow grants and migrations to be organized coherently. They do **not** mean capability == schema, schema == Maven module, or schema == future service/database. A later extraction must preserve semantic ownership rather than table location.

The active baseline layout is:

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

The version order is an installation dependency order, not a capability-authority hierarchy. Historical V70 remains reserved under ADR-0048.

### Storage classification

| Class | Mutability | Examples | Physical rule |
| --- | --- | --- | --- |
| Authoritative | mutable under aggregate/process invariants | Identity, Role, AccessAssignment, Policy, Credential, Connector configuration, AdministrativeGrant | normalized relational state; optimistic revision where concurrent mutation matters |
| Observation | append/current/staging from source/provider reports | SourceRecord, ObservedPrincipal, ObservedEntitlement, ObservedGrant | provenance/as-of/run semantics; never silently authorizes or overwrites IAM intent |
| Evidence | immutable/append-oriented | ApprovalDecision, ReviewDecision, ProvisioningAttempt, AuditRecord, EvidenceSnapshot | no ordinary update/delete; correction is successor evidence |
| Projection | derived/rebuildable | EffectiveAccess, DesiredGrantState, AssignmentFulfillment | may lag; carries computation/source generation rather than authoritative revision |

A physical schema may contain more than one class, but each table has one declared class and mutation model.

## 2. Identifier strategy

Persisted domain identifiers use application-generated UUIDv7 values stored in PostgreSQL `uuid` columns.

Reasons:

- globally unique and opaque at the domain/API level;
- better insertion locality than fully random UUIDv4 for large B-tree-backed tables;
- available before persistence, so an aggregate ID can participate in causal facts/outbox rows in the same transaction;
- portable as a 128-bit UUID value even when another database does not generate UUIDv7 natively.

The application ID-generation port owns generation. Database UUID generation is not required for domain records. The canonical contract remains **opaque stable ID**; consumers must not infer business creation time, chronology or authorization semantics from UUID bits. Persisted timestamps and revisions carry those meanings.

Business identifiers such as employee number, username, application code, entitlement native key and provider display name remain scoped business keys and never replace the stable ID.

## 3. Tenant isolation

`platform.tenant` is the physical isolation root. Every tenant-owned row carries non-null `tenant_id uuid`. Even a single-tenant deployment uses one configured Tenant ID rather than nullable tenancy.

Tenant-owned authoritative/current rows normally expose:

```text
PRIMARY KEY (id)
UNIQUE (tenant_id, id)
```

Same-capability tenant-owned relationships use composite foreign keys where practical:

```text
FOREIGN KEY (tenant_id, identity_id)
  REFERENCES identity.identity (tenant_id, id)
```

This prevents a foreign-tenant ID from becoming a valid relationship merely because that global UUID exists.

All tenant-scoped business uniqueness includes tenant context, for example:

```text
UNIQUE (tenant_id, source_system_id, native_key)
UNIQUE (tenant_id, application_id, code)
UNIQUE (tenant_id, role_id, version_number)
```

Ordinary business rows never use `tenant_id IS NULL` to mean global. Platform-global records, if introduced, must be explicit types with explicit authorization rules.

### Cross-capability references

Same-capability relationships normally use tenant-safe relational constraints. Cross-capability domain references normally use stable typed IDs without database foreign keys. The explicit exception is the Platform Tenant isolation anchor where a relational FK materially enforces isolation without transferring domain ownership.

A cross-capability reference never authorizes direct repository/table mutation across the capability boundary.

### RLS is defense in depth

PostgreSQL Row Level Security may be introduced only after connection/session tenant propagation and administrative-support semantics are proven safe. RLS does not replace tenant predicates, authorization, composite reference constraints or cross-tenant negative tests.

## 4. Concurrency and persistence metadata

There is no universal `BaseEntity`.

Common physical conventions include:

- `id uuid` — stable identity;
- `tenant_id uuid` — isolation context;
- `revision bigint` — mutable authoritative aggregate/process revision, starting at 1;
- `created_at timestamptz` / `updated_at timestamptz` — record timestamps where meaningful;
- `valid_from timestamptz` / `valid_until timestamptz` — semantic validity windows where defined by the domain;
- `correlation_id uuid` / `causation_id uuid` — causal trace metadata where applicable.

Authoritative optimistic writes use a predicate such as:

```text
UPDATE ...
SET ..., revision = revision + 1, updated_at = :now
WHERE tenant_id = :tenantId
  AND id = :id
  AND revision = :expectedRevision
```

A zero-row result is a stale-write conflict; the operation re-reads and re-evaluates rather than applying last-writer-wins.

Immutable evidence normally has occurrence/recording timestamps but no mutable aggregate revision. Projection rows use `computed_at`, source revision or projection generation rather than pretending to own business truth.

## 5. Table ownership matrix

| Schema | Table family | Owning capability | Class |
| --- | --- | --- | --- |
| `platform` | tenant, outbox, inbox/dedup, idempotency, scheduled delivery/work claim, projection checkpoint | Platform | Technical infrastructure |
| `identity` | identity, typed profiles, organization, manager/organization relationships, principal | Identity | Authoritative |
| `identity` | source system, schema/mapping/authority config, identity links, merge/split operations | Identity | Authoritative/process |
| `identity` | source records/import staging/canonical candidates | Identity | Observation/candidate |
| `identity` | canonical attribute state and override | Identity | Authoritative resolved state |
| `catalog` | application, target, entitlement, role, role version/composition | Catalog | Authoritative |
| `access` | access assignment, identity access reduction | Access | Authoritative/process |
| `access` | effective access/support, desired principal/grant state, assignment fulfillment | Access | Projection |
| `governance` | requests, approval process, reviews/remediation, policy/version, exception, finding | Governance | Authoritative/process |
| `governance` | approval/review decisions and policy/risk/SoD evaluation results | Governance | Evidence/result |
| `credential` | credential and credential rotation | Credential | Authoritative/process |
| `integration` | connector configuration, worker/session state, provisioning job/task, reconciliation run | Integration | Authoritative/process |
| `integration` | provisioning attempts | Integration | Evidence |
| `integration` | provider observations and reconciliation staging | Integration | Observation |
| `integration` | provider-observation mappings to canonical stable IDs | Integration | Authoritative technical interpretation/provenance |
| `administration` | administrative permission/role/grant/delegation/elevation/break-glass | Administration | Authoritative/process |
| `audit` | audit record, evidence snapshot, export/archive, legal hold, purge | Audit | Evidence/process |

Platform infrastructure may reference a domain subject to deliver work, but it does not own that subject's lifecycle/process state.

## 6. Identity and Principal

Representative authoritative shape:

```text
identity.identity
- id uuid PRIMARY KEY
- tenant_id uuid NOT NULL
- identity_type varchar(...) NOT NULL
- lifecycle_state varchar(...) NOT NULL
- display_name varchar(...) NOT NULL
- revision bigint NOT NULL CHECK (revision > 0)
- created_at timestamptz NOT NULL
- updated_at timestamptz NOT NULL
- UNIQUE (tenant_id, id)
```

Typed profiles remain relational and compatible with `identity_type`; they do not become a generic JSON profile.

```text
identity.principal
- id uuid PRIMARY KEY
- tenant_id uuid NOT NULL
- identity_id uuid NULL
- application_target_id uuid NOT NULL
- principal_kind varchar(...) NOT NULL
- native_principal_key varchar(...) NOT NULL
- lifecycle_state varchar(...) NOT NULL
- revision bigint NOT NULL CHECK (revision > 0)
- UNIQUE (tenant_id, id)
- UNIQUE (tenant_id, application_target_id, native_principal_key)
- tenant-safe FK to identity.identity when identity_id is populated
```

`application_target_id` is a cross-capability stable ID, not a Catalog database FK. Identity validates material Catalog facts through semantic contracts when changing Principal state.

A Principal may begin uncorrelated and later correlate once to a same-tenant Identity using optimistic revision semantics. Ordinary correlation does not silently reassign an already correlated Principal. Provider `ObservedPrincipal` is Integration-owned observation and does not automatically insert authoritative `identity.principal` rows.

Merge/split preserves stable IDs and historical reference meaning. Foreign-capability references are not mass-rewritten by direct cross-capability SQL.

### Source import and lifecycle evidence

Source import stores explicit run/checkpoint/completeness/trust information. Positive facts may be processed from incomplete runs, but inferred destructive absence requires trusted COMPLETE coverage according to ADR-0024.

Source-caused suspension restoration uses immutable provenance evidence tied to the exact source, accepted link and Identity revision. Relink, cross-source changes or intervening authoritative revision invalidate stale restoration evidence.

## 7. Governed dynamic attributes

Dynamic canonical attributes are governed typed state, not a universal EAV/JSON replacement for IAM semantics.

The stable attribute definition is separate from immutable semantic content in an activated schema version. Type, cardinality, classification and query/search/policy contracts are versioned. Activated definition-version content is immutable; semantic shape changes require a successor version.

Normalized typed values use a bounded relational shape such as:

```text
value_string text NULL
value_boolean boolean NULL
value_integer bigint NULL
value_decimal numeric(38,12) NULL
value_date date NULL
value_datetime timestamptz NULL
value_enum_key varchar(...) NULL
```

A constraint enforces exactly one populated typed value compatible with the referenced definition type. MULTI values are normalized child rows, not JSON arrays.

Candidate state preserves source/mapping/provenance. Canonical state preserves resolution outcome and selected provenance. Overrides are separate authoritative records with explicit reason and optional validity. Provider/source recency never becomes implicit last-write-wins authority.

Only definitions explicitly declared queryable/searchable/policy-addressable receive those contracts. Arbitrary provider-native JSON paths are not public policy/query surfaces.

## 8. Catalog

Catalog authoritative tables include Application, ApplicationTarget, Entitlement, Role, RoleVersion and normalized role composition.

Representative constraints include:

- tenant-scoped business uniqueness;
- tenant-safe same-capability FKs between Application, target, entitlement and Role children;
- at most one ACTIVE RoleVersion per Role;
- immutable activated/superseded RoleVersion content;
- positive optimistic revision for mutable validation/activation process state;
- normalized role members with mutually exclusive typed member references.

The currently accepted role graph is intentionally bounded rather than an arbitrary cyclic graph. Graph/type/current-member validation remains a Catalog invariant rather than an ORM cascade.

## 9. Access

`access.access_assignment` is authoritative business access intent. Representative fields include tenant, Identity stable ID, typed Role/Entitlement target, Principal constraint, typed provenance, lifecycle state, validity window and optimistic revision.

Identity, Principal, Role and Entitlement are cross-capability stable IDs. Access validates material current facts through semantic queries when an assignment mutation requires them.

Principal constraints are typed (`ANY` or `SPECIFIC`). Semantic effectiveness is evaluated from authoritative lifecycle plus validity timestamps; scheduler delay cannot extend expired authority or delay a reached start instant.

Request-caused and lifecycle-policy-caused assignments retain typed provenance and causal uniqueness so retry does not create duplicate authority.

### EffectiveAccess

EffectiveAccess is rebuildable projection, never authoritative access intent.

```text
access.effective_access
- id uuid PRIMARY KEY
- tenant_id uuid NOT NULL
- identity_id uuid NOT NULL
- entitlement_id uuid NOT NULL
- principal_constraint_key varchar(...) NOT NULL
- support_count integer NOT NULL CHECK (support_count > 0)
- computed_at timestamptz NOT NULL
- projection_generation bigint NOT NULL
- UNIQUE (tenant_id, identity_id, entitlement_id, principal_constraint_key)
```

Normalized support/path rows preserve explainability. The effective row disappears only when its last support disappears. Role-derived supports record stable RoleVersion path IDs without Access-owned Catalog foreign keys.

### Desired technical state

`desired_principal_state` and `desired_grant_state` are retained PRESENT/ABSENT projections with stable IDs, positive desired revision, source generation and computation time.

`desired_revision` is the freshness identity carried into Integration provisioning. Recomputing the same externally meaningful result may advance generation without changing desired revision. Integration consumes a semantic desired-state query rather than reading Access tables directly.

If desired-state verification is unavailable, privilege-increasing provider work remains unclaimed. A stale revision supersedes stale provider work before the external call.

### IdentityAccessReduction

Lifecycle-driven privilege reduction uses an Access-owned durable process keyed by tenant + Identity + source Identity revision. It scans assignments with deterministic bounded keyset continuation and commits authoritative reductions plus process progress safely under retry.

## 10. Governance

Governance tables include:

- AccessRequest and scalable RequestItem;
- reusable ApprovalCase mutable process state;
- immutable approval plan/stage/approver snapshots and ApprovalDecision evidence;
- ReviewCampaign, high-cardinality ReviewItem, immutable ReviewDecision and remediation state;
- Policy and immutable activated PolicyVersion;
- typed SoD rules and immutable evaluation/risk/conflict evidence;
- GovernanceException;
- GovernanceFinding;
- immutable lifecycle-access evaluation evidence.

ReviewItem is its own scalable consistency boundary; a campaign repository does not require loading every item as an aggregate child collection.

Approval plans/decisions and policy evidence are immutable once established. Policy revalidation creates successor context rather than rewriting completed evidence.

GovernanceException binds typed scope, reusable approval evidence and mandatory validity. Exception coverage never erases the underlying SoD conflict evidence.

Lifecycle-access predicate storage remains a closed typed model, including exact scalar predicates, typed MULTI membership and bounded relational CONTAINS_ANY/CONTAINS_ALL expected sets. Arbitrary JSON expression payloads are not accepted policy semantics.

## 11. Credential

Credential authoritative state stores stable Principal reference, credential kind, lifecycle/validity and an **opaque external SecretReference**.

No table stores raw password, API-key value, private key, bearer/refresh token, authorization code or client secret as ordinary credential material.

Credential rotation is a durable Credential-owned process with stable old/replacement credential references, initiator stable ID, revision, checkpoint/failure state and terminal completion metadata. Terminal process state cannot be silently rewritten.

Public Credential listing uses deterministic immutable continuation fields rather than mutable lifecycle state.

## 12. Integration, provisioning and reconciliation

Connector configuration is an adapter/native edge. Validated provider configuration JSON may exist there, but governance-critical IAM semantics remain typed. Secrets are external references.

Provisioning Job/Task are Integration-owned process state. Generic worker leases are Platform technical mechanics and do not become business/task authority.

Provisioning tasks carry desired revision and causal idempotency. Before an external provider mutation, Integration revalidates current semantic desired state. Stale work becomes superseded; unavailable mandatory verification leaves privilege-increasing work unclaimed.

ProvisioningAttempt is immutable, data-minimized evidence. It may record normalized provider request/idempotency references where safe, but never secret/private values.

### Remote connector workers

Integration owns worker registration, binding scope/runtime permission and negotiated session state. Platform owns only technical lease/fencing metadata. Lease epoch increments on reclaim so a stale completion cannot overwrite a newer execution generation.

### Observation and reconciliation

Current provider observations include Principal, Entitlement, Grant and Credential observation families. Each carries tenant, binding/target, provider stable identity, normalized state, observation times, run/provenance information and bounded/redacted provider-native metadata where retained.

Reconciliation run stores execution outcome separately from coverage/completeness. A PARTIAL run may add/refresh positive observations but cannot infer destructive absence. Absence inference requires trustworthy COMPLETE coverage plus compatible binding/configuration/runtime/contract checks.

Raw provider/source payload, when operationally required, is stored separately with explicit provenance, strict size limits, secret filtering, encryption policy and short retention. It never becomes canonical governance truth by storage alone.

## 13. Administration

AdministrativeRole/Grant/Delegation/Elevation/BreakGlass remain Administration-owned semantic state.

AdministrativeGrant stores typed scope and explicit grantable/delegable rights. Those rights are not implied merely by possession of a broad management permission.

AdministrativeDelegation is source-dependent authority: it references the actual delegator/delegate and one direct source grant. Evaluation re-reads the current source, so source revocation/expiry/loss of delegability ends delegated authority even if historical delegation process state remains recorded.

AdministrativeElevation is a finite revisioned process carrying beneficiary/initiator stable Identity IDs, direct authority basis, target role, typed scope, validity, immutable request fingerprint and optional Governance approval evidence references. Cross-capability Governance evidence is not a database FK.

Break-glass operation stores finite validity, strong-assurance evidence metadata, typed scope, reason/incident evidence and durable obligations such as security notification and post-use review. Obligation delivery state is operational evidence and never authority by itself.

## 14. Audit and evidence lifecycle

`audit.audit_record` is append-only evidence with tenant, occurred/recorded timestamps, actor/action/resource/outcome, correlation/causation and governed material/integrity metadata.

`material_snapshot` and `integrity_metadata` are closed versioned shapes, not extension bags. Integrity metadata is derived inside Audit and consumers fail closed on present-but-invalid integrity data.

Audit export is durable process state whose final artifact bytes live outside relational authoritative storage behind the typed Audit artifact adapter.

Retention Policy Version and Archive Segment are immutable/revisioned Audit-owned state. Archive segments pin exact policy/cutoff/range inputs and only become successful after post-write verification records integrity and retention evidence.

Legal Hold and Purge Operation implement explicit fail-closed destructive evidence controls. AuditRecord delete is database-fenced and permitted only for the exact authorized running purge when the selected verified archive evidence, retention requirements and legal-hold checks all succeed.

EvidenceSnapshot is immutable relational decision-time evidence.

## 15. Durable asynchronous infrastructure

### Transactional outbox

`platform.outbox_event` records event identity, tenant, type/version, resource/aggregate identity and revision where applicable, occurred time, correlation/causation, minimized payload and publication state.

The authoritative mutation and its outbox fact commit atomically. Publication delivery occurs later and assumes at-least-once behavior.

### Inbox/deduplication

`platform.inbox_message` uses tenant-aware consumer identity, normally:

```text
UNIQUE (tenant_id, consumer_name, message_id)
```

Where a side effect requires durable deduplication, inbox acceptance/result and the local side effect commit atomically where practical.

### Causal idempotency

`platform.idempotency_record` is keyed by tenant + operation namespace + idempotency key and stores request fingerprint, operation state, resource/result reference and retention timestamps. Reuse with a different fingerprint is an idempotency conflict.

Idempotency deduplicates one causal operation; it does not collapse all semantically similar IAM access.

### Scheduled delivery and work claiming

Platform scheduled/claim storage owns timers, retry eligibility, short leases and fencing. The referenced capability still owns business/process state. A worker claims technical work, re-reads current domain state, validates applicability/revision, then invokes a semantic application command.

Provider/network calls occur only after the transaction committing authoritative governance/process state has completed.

## 16. Indexing and high-cardinality access

Every materially used FK/reference receives a supporting index unless an existing key already covers the required prefix.

Required patterns include:

- tenant + business-key uniqueness;
- `(tenant_id, id)` candidate keys for tenant-safe references;
- deterministic cursor indexes with immutable/stable tie-breakers;
- selective active/open-state partial indexes;
- provider stable-ID uniqueness/lookup indexes;
- due/retry queue indexes;
- EffectiveAccess identity- and entitlement-centric indexes;
- same-capability FK support and bounded high-cardinality scans.

Lease-expiry comparisons use query predicates, not volatile current-time partial-index expressions.

Large collections use keyset/cursor pagination. Large OFFSET scans and unbounded aggregate child loading are not acceptable high-cardinality designs.

Dynamic-attribute indexes are definition/type-specific and exist only for governed queryable/searchable attributes. Arbitrary native JSON paths do not become public indexed query surfaces by default.

## 17. Partitioning, retention and purge

Ordinary transactional tables are not partitioned merely because PostgreSQL supports it. Partition only when measured scale/retention/backup behavior justifies the operational complexity.

Likely candidates are high-volume append/history families such as AuditRecord, EvidenceSnapshot, ProvisioningAttempt, retained reconciliation history and archived delivery history.

Time-range partitioning is preferred for append/evidence/history where retention aligns with time. Tenant hash partitioning is not the default because tenant sizes can be highly skewed and platform maintenance may need cross-tenant operations.

Retention principles:

| Data | Treatment |
| --- | --- |
| Authoritative business/process state | retain while active plus governed history; terminal state represents business end; physical purge only when references/evidence/policy permit |
| Governance/Audit evidence | long-lived append-oriented; archive and purge only through explicit governed evidence lifecycle |
| Current observations | retain current normalized observation plus provenance; optional bounded history separately |
| Provisioning attempts | retain for operational/security evidence horizon |
| Raw/native payload | shortest practical retention, bounded, encrypted as required and aggressively secret-filtered |
| Outbox/inbox/idempotency | retain through replay/dedup contract and governed diagnostic horizon |
| Rebuildable projection | may be rebuilt; historical retention only when deliberately captured as evidence |

No physical purge performs cross-capability business cascade deletion.

## 18. Transaction boundaries

Transactions are bounded by aggregate/process invariants, not HTTP breadth or ORM graph reach.

1. Same-aggregate authoritative state and required same-capability invariant rows commit atomically.
2. Same-capability multi-aggregate work shares a transaction only when a documented invariant requires atomicity.
3. A semantic fact/outbox record commits atomically with the authoritative mutation that produced it.
4. Cross-capability propagation uses semantic commands, queries and facts and may be eventually consistent.
5. No distributed transaction is required across capabilities/providers.
6. External/provider calls never execute inside the authoritative transaction.
7. Long-running operations persist typed process/checkpoint state and use forward recovery/compensation.
8. Projection updates are replayable/rebuildable independent transactions.

A downstream provider failure cannot roll back or rewrite a valid upstream governance decision.

## 19. Constraint philosophy

The database enforces high-value local structural invariants that are clear and stable:

- PK, NOT NULL and UNIQUE;
- same-tenant composite FKs;
- same-capability ownership/cardinality FKs;
- CHECK constraints for mutually exclusive fields, typed value shape, simple closed codes and time ordering;
- append-only/evidence mutation rejection where defense in depth is valuable.

Do not encode cross-capability governance policy in database triggers.

Cross-capability `ON DELETE CASCADE` is forbidden. Business history uses explicit retirement/revocation/expiry/decommission states. Same-capability cascade is allowed only for truly implementation-owned non-historical children that cannot validly outlive an uncommitted/draft parent.

Small stable state/type sets use constrained textual codes plus application typed values. PostgreSQL native ENUM is not the default. Large/configurable code sets use governed/reference tables.

ORM mappings conform to aggregate ownership, transaction boundaries and delete semantics; ORM convenience does not define them.

## 20. Migration strategy after ADR-0048

Flyway is the current repository migration runner.

Rules:

- the V1-V10 capability baseline is the immutable current pre-release baseline checkpoint;
- ordinary persistence evolution is expressed as focused forward semantic deltas beginning at V11;
- do not regenerate/rewrite V1-V10 for normal feature work;
- historical V70 remains reserved and must not be reused;
- released/applied migrations are append-only;
- production-compatible destructive changes use expand → migrate/backfill → contract where needed;
- high-volume backfills are bounded, restartable and checkpointed rather than one giant transaction;
- large live-table indexes use operationally safe PostgreSQL creation patterns when required;
- migration file names describe the semantic persistence change, not framework/ORM churn;
- capability ownership remains visible, but a Flyway version boundary never transfers state ownership;
- production correction is normally a new forward migration rather than editing prior applied SQL.

An old database carrying the pre-ADR-0048 V1-V69 Flyway history must not be pointed blindly at the reset namespace. Disposable DEV is recreated. Retained historical installations require an explicit reviewed compatibility/cutover mechanism under OD-006.

## 21. Representative difficult cases

### Revocation while provider observation is still present

Access revokes the authoritative AccessAssignment and records its semantic fact. EffectiveAccess support is recomputed. Integration attempts provider removal outside the authoritative transaction. If provider removal fails, immutable attempt evidence and observation may still show PRESENT. Governance may produce drift/remediation state. The AccessAssignment never returns to ACTIVE because the provider failed.

### Partial reconciliation

A run completes execution but has PARTIAL coverage. Positive observations may be added/refreshed. Previously known unseen objects remain current; they are not marked absent. No absence-derived destructive action is inferred from omission.

### Multiple supports for one EffectiveAccess

Two different assignments support the same entitlement. Removing one support leaves the effective row with the remaining support. Only removal of the last support removes effective access and drives desired absence.

### Stale provisioning task

A task was planned for desired revision N. Before external execution, desired state advances to N+1. Integration revalidates, detects stale work and supersedes/no-ops the old task. No stale provider privilege increase executes.

### Expired authority before scheduler materialization

A time-bounded GovernanceException, AccessAssignment, Delegation, Elevation or BreakGlass authority becomes semantically ineffective at its validity boundary even if a timer is delayed. Scheduler execution later materializes only still-applicable process/notification consequences; delay never extends authority.

## 22. Verification expectations

Persistence verification must include:

- a fresh empty PostgreSQL database applying the complete current Flyway chain;
- exact expected capability schemas and Flyway history;
- non-null tenant ownership for tenant-owned application tables;
- tenant-safe same-capability relationships;
- no accidental cross-capability domain FK graph beyond the explicit Platform Tenant isolation anchor;
- cross-tenant negative tests;
- optimistic concurrency tests for mutable authoritative state;
- idempotent/replay-safe async processing tests;
- append-only/evidence mutation protection tests;
- absence-inference tests distinguishing COMPLETE from PARTIAL/UNKNOWN coverage;
- privilege-increase fail-closed tests when mandatory semantic verification is unavailable;
- authoritative revocation/reduction tests proving unrelated evaluator/provider failure cannot block valid reduction;
- secret-boundary tests preventing private material from ordinary persistence/events/audit/errors.

Schema-equivalence evidence for the ADR-0048 rebaseline is retained in the merged PR/CI history; the obsolete development migration SQL is intentionally not retained as a second persistence definition.
