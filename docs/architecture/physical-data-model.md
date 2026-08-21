# Physical Data Model and Persistence Design

## Status and scope

This document resolves OD-002 for the initial Wyrmgate IAM v2 implementation. It is the implementation-facing physical persistence contract for a PostgreSQL target while preserving the framework-neutral domain model.

PostgreSQL, JDBC, JPA and any migration library are implementation choices. They do not redefine capability ownership, aggregate boundaries, lifecycle semantics, tenancy, observation/evidence/projection classification or cross-capability contracts.

The accepted architecture remains authoritative: only the owning capability mutates its authoritative state; Authoritative State, Observation, Evidence and Projection remain distinct; external provider calls remain outside authoritative transactions; and asynchronous delivery is at-least-once.

## 1. Physical database topology

The initial deployment uses one PostgreSQL database with capability-oriented schemas to make physical ownership visible:

- `identity`
- `catalog`
- `access`
- `governance`
- `credential`
- `integration`
- `administration`
- `audit`
- `platform`

This is a physical organization choice, not a statement that capability == PostgreSQL schema. A later service split, separate database, archive store or search store must preserve the same semantic ownership.

### Storage classes

| Class | Mutability | Typical schemas/tables | Rule |
| --- | --- | --- | --- |
| Authoritative | mutable under aggregate invariants | Identity, Role, AccessAssignment, Policy, Credential, Connector configuration, AdministrativeGrant | normalized relational state; optimistic revision where concurrency matters |
| Observation | append/current/staging, provider/source-owned truth only | SourceRecord, ObservedPrincipal, ObservedGrant, reconciliation staging | never silently authorizes or overwrites IAM intent |
| Evidence | append-only/immutable | ApprovalDecision, ReviewDecision, ProvisioningAttempt, AuditRecord, EvidenceSnapshot | no ordinary update/delete; corrections are successor evidence |
| Projection | derived/rebuildable | EffectiveAccess, DesiredGrantState, AssignmentFulfillment, Identity360 support | may lag; can be rebuilt from authoritative facts/evidence/observations as defined |

A schema may contain more than one storage class, but each table has exactly one declared class.

## 2. Identifier strategy

### Decision

Canonical persisted IDs use application-generated UUIDv7 values stored in PostgreSQL `uuid` columns.

Reasons:

- opaque and globally unique;
- naturally time-sortable enough to improve B-tree locality compared with random UUIDv4;
- generation can occur before persistence, allowing IDs to participate in domain facts/outbox records in the same transaction;
- native `uuid` storage is compact and widely portable even if another database lacks UUIDv7 generation;
- the canonical contract remains “opaque stable ID”, not “PostgreSQL sequence” or “UUIDv7 timestamp semantics”.

The application ID-generation port owns generation. Database-generated UUIDs are not required. Consumers must never infer business time or ordering from an ID; persisted timestamps/revisions are authoritative for those semantics.

Business identifiers such as usernames, employee numbers, application codes and entitlement native keys remain scoped mutable/natural keys and are never primary keys.

## 3. Tenant isolation

Every tenant-owned row carries non-null `tenant_id uuid`. The initial database has a `platform.tenant` table even for single-tenant deployment; a single-tenant installation uses one configured tenant ID rather than nullable tenancy.

### Tenant-safe reference pattern

Tenant-owned tables use:

```text
PRIMARY KEY (id)
UNIQUE (tenant_id, id)
```

Same-capability tenant-owned references use composite foreign keys where practical:

```text
FOREIGN KEY (tenant_id, identity_id)
  REFERENCES identity.identity (tenant_id, id)
```

This makes an accidental cross-tenant reference fail in the database instead of relying only on application predicates.

All tenant-scoped business uniqueness includes `tenant_id`, for example:

```text
UNIQUE (tenant_id, source_system_id, native_key)
UNIQUE (tenant_id, application_id, code)
UNIQUE (tenant_id, role_id, version_number)
```

Global/platform rows are exceptional and explicitly documented. Ordinary business rows never use a null tenant as a shortcut for “global”.

### RLS

PostgreSQL Row Level Security may be added as defense in depth after connection/session tenant propagation is proven safe. RLS is not the sole isolation mechanism and does not replace tenant predicates, authorization, composite constraints or cross-tenant negative tests.

## 4. Common persistence metadata without a universal BaseEntity

There is no universal domain `BaseEntity` and no requirement that every table has identical columns.

Conventions used where semantically appropriate:

- `id uuid` — stable object identity;
- `tenant_id uuid` — isolation context for tenant-owned rows;
- `revision bigint` — mutable authoritative aggregate revision, starting at 1 and increasing exactly once per committed aggregate mutation;
- `created_at timestamptz` — database-record creation time;
- `updated_at timestamptz` — last authoritative mutation time where meaningful;
- `correlation_id uuid`, `causation_id uuid` — causal tracing where useful;
- `valid_from`, `valid_until` — temporal validity where the domain has a validity window.

Evidence tables generally have `recorded_at`/`occurred_at` and no `revision` because evidence is immutable. Projection tables use `computed_at`, `source_revision` or a projection generation token rather than pretending to be authoritative aggregates.

Application logic performs optimistic updates with predicates such as `WHERE id=? AND tenant_id=? AND revision=?`; successful updates set `revision = revision + 1`. A zero-row update is a stale-write conflict.

## 5. Table ownership matrix

The following names are concrete initial physical names. Additional subordinate tables may be introduced without ADR changes when they preserve these ownership/classification rules.

| Schema | Table family | Owner | Class |
| --- | --- | --- | --- |
| `identity` | `identity`, typed profile tables, `organization`, relationship tables, `principal` | Identity | Authoritative |
| `identity` | `source_system`, mapping/authority/schema configuration, `identity_link`, merge/split operations | Identity | Authoritative/process |
| `identity` | `source_record`, canonical attribute candidates | Identity | Observation/derived candidate |
| `identity` | canonical attribute state/override | Identity | Authoritative resolved state |
| `catalog` | `application`, `application_target`, `entitlement`, `role`, `role_version`, composition | Catalog | Authoritative |
| `access` | `access_assignment` | Access | Authoritative |
| `access` | `effective_access`, support/path tables, `desired_principal_state`, `desired_grant_state`, `assignment_fulfillment` | Access | Projection |
| `governance` | requests/items/plans, reviews, policy/version, exceptions, findings | Governance | Authoritative/process |
| `governance` | approval/review decisions, policy/risk/SoD evaluation results | Governance | Evidence/result |
| `credential` | `credential`, binding, rotation process | Credential | Authoritative/process |
| `integration` | connector instance/binding, provisioning job/task, reconciliation run | Integration | Authoritative/process |
| `integration` | provisioning attempt | Integration | Evidence |
| `integration` | observed principal/entitlement/grant/credential and staging/checkpoint | Integration | Observation |
| `administration` | administrative permission/role/grant/delegation/elevation | Administration | Authoritative/process |
| `audit` | audit record, evidence snapshot | Audit | Evidence |
| `platform` | tenant, outbox, inbox, idempotency, scheduled work, worker lease, projection checkpoint | Platform | Infrastructure |

## 6. Representative authoritative table definitions

The definitions below are representative physical contracts, not ORM classes.

### Identity

```text
identity.identity
- id uuid PK
- tenant_id uuid NOT NULL
- identity_type varchar(16) NOT NULL CHECK PERSON|SERVICE|WORKLOAD
- lifecycle_state varchar(24) NOT NULL
- display_name varchar(512) NOT NULL
- revision bigint NOT NULL CHECK revision > 0
- created_at timestamptz NOT NULL
- updated_at timestamptz NOT NULL
- UNIQUE (tenant_id, id)
```

Typed profile tables use the same ID as the owning Identity and enforce one compatible profile through domain transaction rules plus constrained profile-specific FKs/checks. They are not collapsed into JSON.

```text
identity.principal
- id uuid PK
- tenant_id uuid NOT NULL
- identity_id uuid NULL
- application_target_id uuid NOT NULL       -- stable cross-capability reference
- principal_kind varchar(32) NOT NULL
- native_principal_key varchar(512) NOT NULL
- lifecycle_state varchar(24) NOT NULL
- revision bigint NOT NULL
- created_at/updated_at timestamptz NOT NULL
- UNIQUE (tenant_id, id)
- UNIQUE (tenant_id, application_target_id, native_principal_key)
- same-capability composite FK to Identity when identity_id is non-null
```

`application_target_id` is a cross-capability reference. It is normally stored as a stable typed ID without a database FK; Identity validates it through Catalog contracts at mutation time. This preserves future database extractability and avoids making direct Catalog-table reads a domain contract.

### Catalog

```text
catalog.application
- id, tenant_id
- code varchar(128) NOT NULL
- name varchar(512) NOT NULL
- lifecycle_state varchar(24) NOT NULL
- revision bigint NOT NULL
- created_at/updated_at
- UNIQUE (tenant_id, id)
- UNIQUE (tenant_id, code)
```

```text
catalog.entitlement
- id, tenant_id
- application_id uuid NOT NULL
- application_target_id uuid NULL
- code varchar(256) NOT NULL
- native_key varchar(1024) NULL
- entitlement_type varchar(64) NOT NULL
- lifecycle_state varchar(24) NOT NULL
- revision bigint NOT NULL
- UNIQUE (tenant_id, id)
- UNIQUE (tenant_id, application_id, code)
- composite same-capability FK application_id -> catalog.application
- composite same-capability FK target_id -> catalog.application_target
```

```text
catalog.role_version
- id, tenant_id
- role_id uuid NOT NULL
- version_number bigint NOT NULL
- state varchar(24) NOT NULL
- content_hash varchar(128) NOT NULL
- activated_at timestamptz NULL
- created_at timestamptz NOT NULL
- UNIQUE (tenant_id, id)
- UNIQUE (tenant_id, role_id, version_number)
```

Activated/superseded RoleVersion content rows are immutable. Composition is normalized in `catalog.role_version_member` with typed `member_kind` and exactly one target ID column populated, plus checks enforcing permitted BUSINESS/APPLICATION composition.

### Access

```text
access.access_assignment
- id uuid PK
- tenant_id uuid NOT NULL
- identity_id uuid NOT NULL
- target_kind varchar(16) NOT NULL CHECK ROLE|ENTITLEMENT
- role_id uuid NULL
- entitlement_id uuid NULL
- principal_constraint_kind varchar(16) NOT NULL
- specific_principal_id uuid NULL
- provenance_kind varchar(32) NOT NULL
- provenance_ref_id uuid NULL
- lifecycle_state varchar(24) NOT NULL
- valid_from timestamptz NULL
- valid_until timestamptz NULL
- revision bigint NOT NULL
- created_at/updated_at timestamptz NOT NULL
- CHECK exactly one of role_id/entitlement_id is set and matches target_kind
- CHECK SPECIFIC principal constraint iff specific_principal_id is set
- CHECK valid_until IS NULL OR valid_from IS NULL OR valid_until > valid_from
- UNIQUE (tenant_id, id)
```

Identity/Catalog/Principal references are cross-capability stable IDs and are not used as mutation shortcuts. The Access transaction validates relevant current facts through semantic queries before committing intent.

### Governance

Key families include:

- `access_request`, `request_item`;
- `approval_plan`, `approval_plan_stage`, `approval_plan_participant`;
- `approval_decision` (immutable evidence);
- `review_campaign`, `review_item`, `review_decision`, `review_remediation`;
- `policy`, `policy_version`;
- `governance_exception`;
- `governance_finding`;
- immutable `risk_assessment`, `policy_evaluation`, `sod_conflict` result/evidence rows.

High-cardinality `review_item` is its own consistency boundary and is not modeled as an unbounded child collection on `review_campaign`.

### Credential

```text
credential.credential
- id, tenant_id
- principal_id uuid NOT NULL                 -- cross-capability stable reference
- credential_type varchar(32) NOT NULL
- lifecycle_state varchar(24) NOT NULL
- secret_reference varchar(1024) NULL
- provider_reference varchar(1024) NULL
- valid_from/valid_until timestamptz NULL
- compromised_at timestamptz NULL
- revision bigint NOT NULL
- created_at/updated_at
```

`secret_reference` is opaque locator metadata only. Raw passwords, private keys, tokens or equivalent secret material are structurally prohibited from this table and ordinary persistence payloads.

### Integration

```text
integration.connector_instance
- id, tenant_id
- connector_type varchar(128) NOT NULL
- configuration_version bigint NOT NULL
- configuration_json jsonb NOT NULL
- secret_reference varchar(1024) NULL
- lifecycle_state varchar(24) NOT NULL
- revision bigint NOT NULL
```

Connector configuration JSON is allowed because it is an adapter/native edge; governance-critical lifecycle/access semantics remain columns/typed tables. Secret values remain external.

```text
integration.provisioning_task
- id, tenant_id
- provisioning_job_id uuid NOT NULL
- operation_type varchar(64) NOT NULL
- subject_kind varchar(32) NOT NULL
- subject_id uuid NOT NULL
- desired_revision bigint NOT NULL
- idempotency_key varchar(256) NOT NULL
- state varchar(32) NOT NULL
- attempt_count integer NOT NULL DEFAULT 0
- next_attempt_at timestamptz NULL
- lease_owner varchar(256) NULL
- lease_until timestamptz NULL
- failure_code varchar(128) NULL
- created_at/updated_at
- UNIQUE (tenant_id, idempotency_key)
```

Task state is Integration process state, not AccessAssignment or Credential state.

### Administration

`administration.administrative_grant` stores actor reference, administrative role, strongly typed scope type/reference, validity, lifecycle state and revision. Scope references are tenant-scoped typed IDs, not arbitrary JSON selectors. Delegation has separate delegator/delegate references and validity.

### Audit

```text
audit.audit_record
- id uuid PK
- tenant_id uuid NOT NULL
- occurred_at timestamptz NOT NULL
- recorded_at timestamptz NOT NULL
- actor_id uuid NULL
- action_type varchar(128) NOT NULL
- resource_type varchar(128) NOT NULL
- resource_id uuid NULL
- outcome varchar(32) NOT NULL
- correlation_id uuid NULL
- causation_id uuid NULL
- material_snapshot jsonb NULL
- integrity_metadata jsonb NULL
```

Audit rows are append-only. Snapshots are data-minimized, secret-filtered and used only when stable IDs/current names are insufficient to explain historical evidence.

## 7. Dynamic/custom attributes

Dynamic canonical attributes are typed, governed and physically queryable. Wyrmgate does not use one giant universal EAV table or arbitrary JSONB as canonical attribute state.

Each owning subject family may have its own attribute-definition/value tables using a common physical convention. Identity canonical attributes are the initial reference model:

```text
identity.attribute_definition
- id, tenant_id
- canonical_key varchar(256) NOT NULL
- subject_type varchar(32) NOT NULL
- data_type varchar(24) NOT NULL
- cardinality varchar(16) NOT NULL CHECK SINGLE|MULTI
- classification varchar(32) NOT NULL
- queryable boolean NOT NULL
- searchable boolean NOT NULL
- policy_addressable boolean NOT NULL
- lifecycle_state varchar(24) NOT NULL
- revision bigint NOT NULL
- UNIQUE (tenant_id, canonical_key)
```

Activated schema versions and definition-version content are immutable.

### Typed value representation

Candidate and canonical value tables use typed columns rather than one text column:

```text
value_string text NULL
value_boolean boolean NULL
value_integer bigint NULL
value_decimal numeric(38,12) NULL
value_date date NULL
value_datetime timestamptz NULL
value_enum_key varchar(256) NULL
```

A CHECK constraint requires exactly one compatible typed value column for the definition type. Multi-valued attributes use one row per element with `value_ordinal` and a stable element/value ID; they are not serialized as JSON arrays.

`identity.canonical_attribute_candidate` stores identity ID, attribute definition/version, typed value, source system/record, source path, mapping-version ID, source update time, observed time and candidate revision/hash.

`identity.canonical_attribute_state` stores the resolved value set, resolution status (`RESOLVED`, `OVERRIDDEN`, `CONFLICT`, `UNRESOLVED`, `NO_VALUE`), authority-rule-version ID, selected candidate/provenance reference, and `value_revision`.

`identity.canonical_attribute_override` is authoritative, explicit, reasoned and optionally time-bounded. It never rewrites candidates/source observations.

Queryable attributes receive type-appropriate partial indexes only when `AttributeDefinition.queryable=true`; arbitrary native/provider JSON paths never receive contractual public query semantics. Searchable text may additionally feed a rebuildable search projection.

## 8. Access projections

### EffectiveAccess

```text
access.effective_access
- id uuid PK
- tenant_id uuid NOT NULL
- identity_id uuid NOT NULL
- entitlement_id uuid NOT NULL
- principal_constraint_key varchar(512) NOT NULL
- support_count integer NOT NULL CHECK support_count > 0
- computed_at timestamptz NOT NULL
- projection_generation bigint NOT NULL
- UNIQUE (tenant_id, identity_id, entitlement_id, principal_constraint_key)
```

Supporting provenance is normalized:

```text
access.effective_access_support
- tenant_id
- effective_access_id
- access_assignment_id
- role_version_id NULL
- path_hash varchar(128)
- path_depth integer
- UNIQUE (tenant_id, effective_access_id, access_assignment_id, path_hash)
```

`support_count` is a denormalized projection value equal to the number of current support rows. Projection writes update the support set and count atomically. A row disappears only when support count becomes zero. This preserves the valid case where two assignments independently grant the same entitlement.

### Desired state

`access.desired_principal_state` is keyed by tenant + identity/principal-resolution context + target and carries a desired revision/generation, desired existence/enabled state and normalized mapped attribute-state reference.

`access.desired_grant_state` is keyed by tenant + principal/target context + entitlement and carries desired presence, desired revision/generation and causally relevant EffectiveAccess references.

Large support collections are normalized in supporting tables rather than embedded arrays.

`access.assignment_fulfillment` is a rebuildable projection of current technical realization state derived from Integration facts/attempts/observations. Integration never directly mutates Access authoritative state; an Access-owned projector consumes semantic Integration facts.

All Access projections are rebuildable. Corruption is repaired by projection rebuild, never by altering AccessAssignment history.

## 9. Observation, reconciliation and import storage

Provider current observations live in `integration.observed_principal`, `observed_entitlement`, `observed_grant` and `observed_credential`.

Each row includes at minimum:

- `tenant_id`;
- connector/binding/target reference;
- provider stable object ID/native key;
- normalized observation state;
- `first_observed_at`, `last_observed_at`;
- `last_complete_run_id` where applicable;
- current provider version/etag/change token when available;
- provenance mapping reference;
- optional bounded/redacted native metadata.

Uniqueness uses provider identity, for example:

```text
UNIQUE (tenant_id, connector_binding_id, provider_stable_id)
```

### Reconciliation run

`integration.reconciliation_run` stores independently:

- run state/outcome;
- coverage/completeness (`COMPLETE`, `PARTIAL`, `UNKNOWN`);
- discovery mode;
- started/completed times;
- connector/binding configuration revision;
- checkpoint/change token;
- object/error counters;
- reason for partial/unknown coverage.

Staging tables are run-scoped, e.g. `integration.recon_observed_grant_stage(run_id, ...)`. A completed stage is promoted/compared only according to run coverage semantics.

Absence inference is permitted only when all of the following are true:

1. coverage is `COMPLETE` for the relevant object class/scope;
2. the connector discovery contract supports trustworthy absence detection;
3. the run used the expected binding/configuration scope;
4. no checkpoint/gap condition invalidated coverage.

A PARTIAL run can add/update positive observations but must not mark unseen current observations absent merely because they were missing from the run.

Raw provider payloads, when retained, are stored separately from normalized observation rows, size-bounded, encrypted according to deployment policy, redacted/secret-filtered and subject to short retention. They are diagnostic evidence, not canonical state.

Identity source import follows the same run/checkpoint/completeness pattern around `identity.source_record` and source-import staging.

## 10. Durable asynchronous infrastructure

### Transactional outbox

`platform.outbox_event` contains event ID, tenant, event type/version, aggregate reference/revision, correlation/causation, occurred time, serialized data-minimized payload, publication state, attempt count and next-attempt time.

The authoritative state change and its outbox row are committed in the same database transaction.

### Inbox/deduplication

`platform.inbox_message` is keyed by `(consumer_name, message_id)` with first-seen/completed timestamps and processing outcome. Consumers that create non-idempotent side effects record inbox acceptance/result in the same local transaction as the effect when practical.

### Causal idempotency

`platform.idempotency_record` is scoped by tenant + operation namespace + idempotency key and stores request fingerprint, status, resource/result reference, created/expiry timestamps. Reusing a key with a different fingerprint is a conflict.

### Scheduled work and leases

`platform.scheduled_work` contains handler type, typed subject reference, due time, payload version, expected subject revision when relevant, attempt, next eligible time, lease owner/until and idempotency key.

Generic workers own only delivery/lease/retry mechanics. The handler re-reads current domain state and issues a semantic command. The scheduler never directly updates business-state tables.

Claims may use short transactions with `FOR UPDATE SKIP LOCKED` or equivalent lease updates. External provider calls occur after the claim/state transaction has committed.

## 11. Indexing strategy

Every FK/reference used for joins/deletes/validation receives a supporting index unless the leftmost columns of an existing unique/PK index already cover it.

Core patterns:

- tenant + business key unique indexes;
- `(tenant_id, id)` unique candidate keys for tenant-safe composite references;
- cursor indexes matching API order, typically `(tenant_id, updated_at, id)` or immutable-history `(tenant_id, occurred_at, id)`;
- partial indexes for active/open states where selectivity justifies them;
- provider-stable-ID indexes on observation tables;
- queue indexes on due/retry state;
- EffectiveAccess lookup indexes.

Representative PostgreSQL indexes:

```text
access_assignment(tenant_id, identity_id, lifecycle_state, valid_until)
  WHERE lifecycle_state IN ('SCHEDULED','ACTIVE','SUSPENDED')

effective_access(tenant_id, identity_id, entitlement_id)
effective_access(tenant_id, entitlement_id, identity_id)

desired_grant_state(tenant_id, application_target_id, desired_revision, id)

observed_grant(tenant_id, connector_binding_id, provider_stable_id) UNIQUE
observed_grant(tenant_id, principal_provider_id, entitlement_provider_id)

provisioning_task(tenant_id, state, next_attempt_at, id)
  WHERE state IN ('PENDING','READY','FAILED_RETRYABLE')

platform.scheduled_work(due_at, id)
  WHERE lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP
```

Because PostgreSQL predicates containing `CURRENT_TIMESTAMP` are not immutable index predicates, the actual scheduled-work index uses stable state columns plus `due_at`; the claim query applies lease-time comparison separately. Do not attempt a volatile-time partial-index predicate.

High-cardinality queries are designed around bounded keyset/cursor scans, not offset pagination or loading whole child collections.

## 12. Partitioning strategy

Do not partition ordinary transactional tables initially. Identity, Application, Role, AccessAssignment, Credential, ConnectorInstance, AdministrativeGrant and normal governance state remain non-partitioned until measured evidence requires otherwise.

Initial candidates for time/range partitioning are:

- `audit.audit_record`;
- `audit.evidence_snapshot` if volume is high;
- `integration.provisioning_attempt`;
- reconciliation staging/history and observation-history tables if history is retained at high volume;
- outbox/inbox history after active rows are separated/archived.

Current observation tables are generally indexed current-state tables, not time partitions, unless a provider/object cardinality demonstrates a need for hash subpartitioning.

Introduce partitioning only when at least one measured trigger exists, such as:

- table size approaches operationally material hundreds of GB;
- sustained row counts move into hundreds of millions and vacuum/index maintenance becomes problematic;
- retention routinely deletes large time ranges and partition drop materially reduces cost;
- query plans repeatedly scan irrelevant historical ranges despite correct indexing;
- backup/restore/archive objectives require bounded chunks.

Use time-range partitions for append/evidence/history tables because retention aligns with time. Tenant hash partitioning is not the default because tenant sizes are skewed and cross-tenant operational queries become harder.

## 13. Retention, archive and purge

Retention durations are deployment/policy configuration; semantics are fixed here.

| Data class | Default treatment |
| --- | --- |
| Authoritative business state | retain while active plus policy-defined historical period; business “deletion” uses terminal states; purge only when references/evidence/legal policy allow |
| Immutable governance/audit evidence | long-lived, append-only; archive to cheaper immutable/searchable storage when needed; purge only by explicit retention/legal policy |
| Provider/source observations | retain current state plus bounded history needed for drift/explainability; older history may archive/purge |
| Provisioning attempts | retain as execution evidence for operational/security policy period; archive before purge if required |
| Raw/native provider payload | shortest practical retention; size bounded, secret filtered; purge aggressively |
| Outbox | retain until published and replay/debug retention satisfied, then purge/archive |
| Inbox/dedup | retain at least through maximum replay/redelivery horizon and side-effect idempotency horizon |
| Idempotency records | retain through client retry/replay contract; extend for operations with long asynchronous completion |
| Rebuildable projections | no historical retention requirement unless explicitly used as evidence; can be truncated/rebuilt |

No physical purge may cascade across capabilities to erase business history. Purge jobs are explicit, authorized operations with audit evidence.

## 14. Transaction boundaries

A transaction is bounded by aggregate/process invariants, not by HTTP request breadth or ORM graph reach.

Rules:

1. Same-aggregate authoritative state and required same-capability invariant rows commit atomically.
2. Same-capability multi-aggregate operations use one transaction only when an explicitly documented invariant requires it; otherwise coordinate semantically.
3. An emitted semantic fact/outbox row commits atomically with the authoritative mutation that produced it.
4. Cross-capability propagation is via commands/facts/queries and may be eventually consistent.
5. No distributed database transaction is required across capabilities or providers.
6. Provider/network calls never execute inside the authoritative transaction.
7. Long-running operations persist process/checkpoint state and use forward recovery/compensation.
8. Projection updates may have their own transactions and replay independently.

## 15. Constraint philosophy

Use the database to enforce high-value structural invariants that are local and unambiguous:

- PK, NOT NULL, UNIQUE;
- same-tenant composite FKs;
- same-capability ownership/cardinality FKs;
- CHECK constraints for mutually exclusive columns, basic state/value shape and time-window ordering;
- immutable-table permissions/triggers where deployment policy benefits from defense in depth.

Do not encode cross-capability business policy in database triggers.

### Cross-capability FK policy

Default: stable typed ID with no FK. This is preferred when the reference crosses a capability ownership boundary and existence/lifecycle validation belongs to a semantic contract.

Selective cross-capability composite FKs are allowed only when all are true:

- both tables are guaranteed to remain in the same physical database for the supported deployment;
- the reference is fundamental/stable rather than lifecycle-coupled;
- the FK materially prevents corruption;
- it has no `ON DELETE CASCADE` and does not enable foreign mutation;
- extraction cost is understood.

The initial model does not depend on cross-capability FKs for correctness.

### Delete behavior

No cross-capability `ON DELETE CASCADE`. Business history is retired/revoked/expired/decommissioned rather than cascaded away. Same-capability subordinate rows may use cascade only for truly non-historical implementation-owned children that cannot outlive an uncommitted/draft parent; evidence/history never cascades.

### Enum/code storage

Canonical state/type values use constrained text codes (`varchar`) with application enums plus CHECK constraints for small stable sets when valuable. PostgreSQL native ENUM is not the default because it couples rolling schema evolution to database-specific DDL. Large/configurable code sets use reference/configuration tables.

ORM mappings must follow these decisions; they do not define aggregate boundaries, cascade semantics or fetch graphs as architecture.

## 16. Schema migration strategy

Database changes use versioned, forward migrations stored in the repository and reviewed with the owning capability changes.

Rules:

- migrations are append-only after release;
- destructive changes use expand/migrate/contract when rolling compatibility is required;
- new nullable/compatible columns or new tables are introduced before code depends on them;
- backfills are bounded/restartable for large tables and do not hold unbounded transactions;
- indexes on large live tables are created with operationally safe PostgreSQL mechanisms where required;
- capability ownership is visible in migration naming/location even if one migration runner applies them;
- rollback means forward corrective migration, not assuming irreversible production DDL can always be undone safely.

The JVM build remains Maven. No Gradle build is introduced.

This phase intentionally does not mandate Flyway, Liquibase or another migration framework. The first implementation migration work should select a tool based on actual needs: Maven integration, ordered SQL support, checksum/history, repeatable/nonrepeatable migration behavior, multi-schema handling and operational compatibility. Tool choice is an implementation decision unless it imposes architectural constraints.

## 17. Difficult-case walkthroughs

### Revoked assignment while provider grant remains present

1. Access transaction changes `access_assignment.lifecycle_state` to `REVOKED`, increments revision and writes an outbox fact.
2. EffectiveAccess projector removes that assignment's support path; if support count reaches zero, desired grant becomes absent.
3. Integration receives desired-state change and creates/revalidates a revoke task.
4. Provider removal fails; immutable ProvisioningAttempt records failure.
5. `access.assignment_fulfillment` projects failed/remediation-needed state.
6. Reconciliation still finds `integration.observed_grant=PRESENT`.
7. Governance may create/update a drift finding.

At no point is AccessAssignment changed back to ACTIVE because the provider failed.

### Partial reconciliation

A run completes technically but has `coverage=PARTIAL`. Newly seen provider objects may refresh/add observations. Objects not seen in the run retain previous current observation state; they are not marked absent. No destructive absence-derived remediation is created. The run records why completeness was partial.

### Typed dynamic attribute

`costCenter` is defined as STRING, SINGLE, policy-addressable. Workday and HR-feed candidates are stored in typed string columns with source/mapping provenance. Authority rule v8 selects Workday. CanonicalAttributeState references the selected candidate and authority version. A time-bound manual override creates a separate override/value row; it does not rewrite Workday observation. When the override expires, semantic evaluation immediately falls back to authority resolution even if cleanup materialization is late.

### EffectiveAccess support counting

Assignment A grants Role R1 -> Entitlement E; Assignment B directly grants E. EffectiveAccess `(Identity I, E)` has two support rows and `support_count=2`. Revoking A removes one support row but leaves EffectiveAccess and desired grant present with count 1. Revoking B removes the final support row; only then does desired grant become absent.

### Stale provisioning task

A grant task was created for desired revision 41. Before execution, Access changes desired state to revision 42 with grant absent. Integration claims the task, re-queries/revalidates current desired revision outside any provider call, detects staleness and marks the task `SUPERSEDED`/no-op. No provider grant call is made.

### Expired governance object before scheduler execution

A GovernanceException has `valid_until=10:00`. At 10:00 it is semantically ineffective even if the scheduler is delayed. A worker at 10:07 claims the timer, re-reads the exception and current revision, then materializes any terminal/notification/finding work still applicable. Authorization/policy checks between 10:00 and 10:07 must already treat the exception as expired from time comparison.

## 18. Implementation sequencing

After this design is accepted, implementation should proceed in bounded steps:

1. choose the migration runner and establish schema/version conventions;
2. create `platform.tenant` and capability schemas;
3. implement ID/revision/tenant-safe persistence primitives without a universal domain base class;
4. implement capability-owned authoritative tables in vertical slices;
5. add outbox/idempotency and tests with the first mutation slice;
6. add projections/observation/staging structures when their owning use cases are implemented;
7. add measured indexes and performance tests for high-cardinality paths;
8. introduce partitioning only when the documented triggers are observed.

The next architecture phase can address concrete OpenAPI/AsyncAPI contracts (OD-003) without waiting for every migration/entity to exist.