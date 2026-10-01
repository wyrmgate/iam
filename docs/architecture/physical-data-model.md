# Physical Data Model and Persistence Design

## Status and scope

This document resolves OD-002 for the initial Wyrmgate IAM v2 implementation. It is the implementation-facing physical persistence contract for a PostgreSQL target while preserving the accepted framework-neutral domain architecture.

PostgreSQL, JDBC, JPA and any migration library are implementation choices. They do not redefine capability ownership, aggregate boundaries, lifecycle semantics, tenancy, observation/evidence/projection classification, public contracts or cross-capability collaboration.

The governing rules remain:

- only an owning capability mutates its authoritative state;
- Authoritative State, Observation, Evidence and Projection remain distinct;
- business/governance state, validity, fulfillment and provider observation remain separate dimensions;
- external/provider calls execute outside authoritative transactions;
- asynchronous delivery is at-least-once and duplicate/replay/out-of-order tolerant;
- secrets/private credential material are excluded from ordinary persistence payloads, APIs, events, audit and work/error records.

## 1. Physical database and schema topology

The initial deployment uses one PostgreSQL database with capability-oriented schemas:

- `identity`
- `catalog`
- `access`
- `governance`
- `credential`
- `integration`
- `administration`
- `audit`
- `platform`

Schemas make physical ownership visible and allow grants/migrations to be organized coherently. They do **not** mean capability == schema, schema == Maven module, or schema == future service/database. A later extraction must preserve semantic ownership rather than preserve table location.

### Storage classification

| Class | Mutability | Examples | Physical rule |
| --- | --- | --- | --- |
| Authoritative | mutable under aggregate/process invariants | Identity, Role, AccessAssignment, Policy, Credential, Connector configuration, AdministrativeGrant | normalized relational state; optimistic revision where concurrent mutation matters |
| Observation | append/current/staging based on source/provider reports | SourceRecord, ObservedPrincipal, ObservedEntitlement, ObservedGrant | provenance/as-of/run semantics; never silently authorizes or overwrites IAM intent |
| Evidence | immutable/append-oriented | ApprovalDecision, ReviewDecision, ProvisioningAttempt, AuditRecord, EvidenceSnapshot | no ordinary update/delete; correction is successor evidence |
| Projection | derived/rebuildable | EffectiveAccess, DesiredGrantState, AssignmentFulfillment, Identity360 | may lag; carries computation/source generation rather than authoritative revision |

A physical schema may contain more than one class, but each table has one declared class and mutation model.

## 2. Identifier strategy

### Decision: application-generated UUIDv7

Persisted domain identifiers use application-generated UUIDv7 values stored in PostgreSQL `uuid` columns.

Reasons:

- globally unique and opaque at the domain/API level;
- better insertion locality than fully random UUIDv4 for large B-tree-backed tables;
- available before persistence, so an aggregate ID can participate in causal facts/outbox rows in the same transaction;
- portable as a 128-bit UUID value even when another database does not generate UUIDv7 natively.

The application ID-generation port owns generation. Database UUID generation is not required for domain records. The canonical contract remains **opaque stable ID**; consumers must not infer business creation time, chronology or authorization semantics from UUID bits. Persisted timestamps and revisions are authoritative for those meanings.

Business identifiers such as employee number, username, application code, entitlement native key and provider display name remain scoped natural/business keys and never replace the stable ID.

## 3. Tenant isolation

Every tenant-owned row carries non-null `tenant_id uuid`. The initial database contains `platform.tenant`; even a single-tenant deployment uses one configured tenant ID rather than nullable tenancy.

### Tenant-safe candidate keys and references

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

This prevents an accidentally supplied foreign-tenant ID from producing a valid reference merely because the object exists globally.

All tenant-scoped business uniqueness includes tenant context, for example:

```text
UNIQUE (tenant_id, source_system_id, native_key)
UNIQUE (tenant_id, application_id, code)
UNIQUE (tenant_id, role_id, version_number)
```

Ordinary business rows never use `tenant_id IS NULL` to mean “global”. Platform-global rows, if any, are explicit types with explicit authorization rules.

### RLS is defense in depth

PostgreSQL Row Level Security may be introduced after connection/session tenant propagation and administrative-support semantics are proven safe. RLS does not replace tenant predicates, authorization, composite reference constraints or cross-tenant negative tests.

## 4. Concurrency and persistence metadata

There is no universal `BaseEntity`. Metadata is applied only where its semantics are appropriate.

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

A zero-row result is a stale-write conflict; the operation re-reads/re-evaluates rather than applying last-writer-wins.

Immutable evidence normally has `occurred_at`/`recorded_at` but no mutable aggregate revision. Projection rows use `computed_at`, `source_revision`, `projection_generation` or equivalent projection metadata rather than pretending to own business truth.

## 5. Table ownership matrix

The following is the initial physical table-family contract. Additional subordinate tables may be added without a new ADR when they preserve ownership, classification and invariants.

| Schema | Table family | Owning capability | Class |
| --- | --- | --- | --- |
| `identity` | `identity`, typed profile tables, `organization`, manager/organization/owner relationships, `principal` | Identity | Authoritative |
| `identity` | `source_system`, schema/mapping/authority config, `identity_link`, merge/split operations | Identity | Authoritative/process |
| `identity` | `source_record`, source-import staging, canonical attribute candidate | Identity | Observation/candidate |
| `identity` | canonical attribute state and override | Identity | Authoritative resolved state |
| `catalog` | `application`, `application_target`, `entitlement`, `role`, `role_version`, role composition | Catalog | Authoritative |
| `access` | `access_assignment` | Access | Authoritative |
| `access` | `identity_access_reduction` | Access | Authoritative/process |
| `access` | `effective_access`, support/path, `desired_principal_state`, `desired_grant_state`, `assignment_fulfillment` | Access | Projection |
| `governance` | request/item/plan, review campaign/item/remediation, policy/version, exception, finding | Governance | Authoritative/process |
| `governance` | approval/review decision, policy/risk/SoD evaluation result | Governance | Evidence/result |
| `credential` | `credential`, credential binding, rotation process | Credential | Authoritative/process |
| `integration` | connector instance/binding, provisioning job/task, reconciliation run | Integration | Authoritative/process |
| `integration` | provisioning attempt | Integration | Evidence |
| `integration` | observed principal/entitlement/grant/credential, reconciliation staging/checkpoint | Integration | Observation |
| `integration` | provider-observation mappings to canonical stable IDs | Integration | Authoritative technical interpretation/provenance |
| `governance` | observation-derived drift findings | Governance | Authoritative/process |
| `administration` | administrative permission/role/grant/delegation/elevation | Administration | Authoritative/process |

Migration V49 materializes `administration.administrative_elevation` as a distinct finite process/authority table. It stores stable beneficiary/initiator Identity IDs, one same-tenant direct authority-basis grant, target AdministrativeRole, typed scope columns, finite validity, immutable request fingerprint, optional Governance ApprovalCase/fingerprint evidence references, semantic process state/revision, activation/denial/cancellation/revocation timestamps and correlation/causation. The Governance case is deliberately not a database foreign key: cross-capability evidence is consumed through a semantic contract. Beneficiary/state and Role/state indexes support bounded authorization/process reads; time validity is still evaluated semantically rather than delegated to a cleanup scheduler.
| `audit` | audit record, evidence snapshot | Audit | Evidence |
| `platform` | tenant, outbox, inbox/dedup, idempotency, scheduled delivery/work claim, projection checkpoint | Platform | Technical infrastructure |

Platform infrastructure may reference a domain subject to deliver work, but it does not own the subject's lifecycle/process state.

## 6. Representative physical tables and constraints

These definitions are persistence contracts, not ORM classes. Column lengths are initial implementation bounds and may be tuned by migration when compatibility is preserved.

### Identity

```text
identity.identity
- id uuid PRIMARY KEY
- tenant_id uuid NOT NULL
- identity_type varchar(16) NOT NULL CHECK (identity_type IN ('PERSON','SERVICE','WORKLOAD'))
- lifecycle_state varchar(24) NOT NULL
- display_name varchar(512) NOT NULL
- revision bigint NOT NULL CHECK (revision > 0)
- created_at timestamptz NOT NULL
- updated_at timestamptz NOT NULL
- UNIQUE (tenant_id, id)
```

Typed profiles (`person_profile`, `service_profile`, `workload_profile`) use the owning Identity ID and remain relational/typed. The application transaction enforces exactly one profile compatible with `identity_type`; profile tables do not become a generic JSON profile.

```text
identity.principal
- id uuid PRIMARY KEY
- tenant_id uuid NOT NULL
- identity_id uuid NULL
- application_target_id uuid NOT NULL       -- cross-capability stable ID
- principal_kind varchar(32) NOT NULL
- native_principal_key varchar(512) NOT NULL
- lifecycle_state varchar(24) NOT NULL
- revision bigint NOT NULL CHECK (revision > 0)
- created_at timestamptz NOT NULL
- updated_at timestamptz NOT NULL
- UNIQUE (tenant_id, id)
- UNIQUE (tenant_id, application_target_id, native_principal_key)
- FOREIGN KEY (tenant_id, identity_id) -> identity.identity (tenant_id, id), when non-null
```

`application_target_id` is normally not a database FK because it crosses capability ownership. Identity validates the relevant Catalog fact through a semantic contract when changing authoritative Principal state.

The first runtime implementation of this contract is migration V18. New Principals are created only through Identity-owned commands after semantic validation that the ApplicationTarget is active. A Principal may be created with `identity_id = NULL` and later correlated once to an existing same-tenant Identity using optimistic revision semantics; ordinary correlation does not silently reassign an already correlated Principal. Provider `ObservedPrincipal` rows remain Integration observation and do not automatically insert `identity.principal`. Foreign capabilities resolve current Principal ownership through the semantic `PrincipalResolutionQuery`, not by reading this table.

### Catalog

```text
catalog.application
- id uuid PRIMARY KEY
- tenant_id uuid NOT NULL
- code varchar(128) NOT NULL
- name varchar(512) NOT NULL
- lifecycle_state varchar(24) NOT NULL
- revision bigint NOT NULL CHECK (revision > 0)
- created_at timestamptz NOT NULL
- updated_at timestamptz NOT NULL
- UNIQUE (tenant_id, id)
- UNIQUE (tenant_id, code)
```

```text
catalog.application_target
- id uuid PRIMARY KEY
- tenant_id uuid NOT NULL
- application_id uuid NOT NULL
- code varchar(128) NOT NULL
- lifecycle_state varchar(24) NOT NULL
- revision bigint NOT NULL
- UNIQUE (tenant_id, id)
- UNIQUE (tenant_id, application_id, code)
- FOREIGN KEY (tenant_id, application_id) -> catalog.application (tenant_id, id)
```

```text
catalog.entitlement
- id uuid PRIMARY KEY
- tenant_id uuid NOT NULL
- application_id uuid NOT NULL
- application_target_id uuid NULL
- code varchar(256) NOT NULL
- native_key varchar(1024) NULL
- entitlement_type varchar(64) NOT NULL
- lifecycle_state varchar(24) NOT NULL
- revision bigint NOT NULL
- UNIQUE (tenant_id, id)
- UNIQUE (tenant_id, application_id, code)
- same-capability composite FKs to application/target
```

```text
catalog.role_version
- id uuid PRIMARY KEY
- tenant_id uuid NOT NULL
- role_id uuid NOT NULL
- version_number bigint NOT NULL CHECK (version_number > 0)
- state varchar(24) NOT NULL
- content_hash varchar(128) NOT NULL
- revision bigint NOT NULL CHECK (revision > 0)
- activated_at timestamptz NULL
- created_at timestamptz NOT NULL
- UNIQUE (tenant_id, id)
- UNIQUE (tenant_id, role_id, version_number)
```

Activated/superseded RoleVersion content is immutable. Migration V23 implements `catalog.role`, `catalog.role_version` and `catalog.role_version_member`, including a partial unique index that permits at most one ACTIVE version per Role and database triggers that reject activated/superseded content mutation. Migration V25 adds the positive optimistic `revision` column used by the public validation/activation concurrency contract; validation and activation increment the selected version revision, and automatic supersession increments the previously active version revision. `catalog.role_version_member` normalizes composition and uses a typed `member_kind` plus mutually exclusive `member_role_id` / `member_entitlement_id`. The first runtime graph is intentionally shallow: BUSINESS -> APPLICATION Role / Entitlement and APPLICATION Role -> Entitlement. Graph/type/current-member validation remains a Catalog domain invariant rather than an ORM cascade.

### Access

```text
access.access_assignment
- id uuid PRIMARY KEY
- tenant_id uuid NOT NULL
- identity_id uuid NOT NULL                 -- cross-capability stable ID
- target_kind varchar(16) NOT NULL CHECK (target_kind IN ('ROLE','ENTITLEMENT'))
- role_id uuid NULL
- entitlement_id uuid NULL
- principal_constraint_kind varchar(16) NOT NULL
- specific_principal_id uuid NULL
- provenance_kind varchar(32) NOT NULL
- provenance_ref_id uuid NULL
- lifecycle_state varchar(24) NOT NULL
- valid_from timestamptz NULL
- valid_until timestamptz NULL
- revision bigint NOT NULL CHECK (revision > 0)
- created_at timestamptz NOT NULL
- updated_at timestamptz NOT NULL
- UNIQUE (tenant_id, id)
- CHECK exactly one of role_id/entitlement_id is populated and matches target_kind
- CHECK SPECIFIC principal constraint iff specific_principal_id is populated
- CHECK valid_until IS NULL OR valid_from IS NULL OR valid_until > valid_from
```

Identity, Role, Entitlement and Principal references are cross-capability stable IDs. Access uses semantic queries to validate material current facts when an assignment mutation requires them; the references never authorize direct foreign repository mutation.

The first runtime implementation of this authoritative contract is migration V19. The initial command surface creates Entitlement-target assignments only because canonical Role/RoleVersion runtime is not yet implemented; the relational shape retains both target kinds for the later Role slice. Public/manual provenance is typed as `MANUAL` with no provenance reference. V27 adds internal `REQUEST_ITEM` provenance whose reference is the originating Governance RequestItem ID, unique per tenant, while retaining no cross-capability foreign key. Principal constraints are `ANY` or `SPECIFIC`. A SPECIFIC Principal must resolve through Identity semantics to the same Identity and the Entitlement's active ApplicationTarget. Creation materializes `SCHEDULED` when `valid_from` is future and otherwise `ACTIVE`; semantic effectiveness is still evaluated directly from lifecycle plus the validity window, so scheduler delay cannot extend expired authority or delay a reached valid-from instant. The first termination command materializes `CANCELLED`, `REVOKED` or `EXPIRED` with optimistic revision. EffectiveAccess, desired-state derivation and fulfillment remain separate later projections.

The first Access desired-state implementation materializes `access.desired_principal_state` and `access.desired_grant_state` as rebuildable projections. Each row carries tenant scope, stable projection ID, technical target context, desired state, positive `desired_revision`, positive `source_generation`, and `computed_at`; grant rows additionally carry entitlement and optional principal context. Cross-capability references remain stable IDs without database foreign keys.

`desired_revision` is the freshness identity carried into Integration provisioning tasks. `source_generation` and `computed_at` describe projection provenance/rebuild generation rather than authoritative aggregate revision. Integration never reads these tables directly: it consumes framework-neutral `DesiredAccessStateQuery`, which returns `CURRENT(revision)`, `ABSENT`, or `UNAVAILABLE`. Missing or mismatched desired state supersedes stale provisioning; query unavailability leaves work unclaimed so provider-side privilege changes fail closed.

Migration V21 makes the desired tuples explicit. `desired_principal_state` is unique by tenant + Identity + ApplicationTarget. `desired_grant_state` is unique by tenant + Identity + ApplicationTarget + Entitlement + `principal_constraint_key`, where the current stable key is `ANY` or `SPECIFIC:<principal UUID>`. Desired rows are retained as PRESENT/ABSENT projection state rather than deleted so their stable IDs and revisions remain usable for Integration stale-work revalidation. Recomputing the same externally meaningful desired outcome advances `source_generation` but not `desired_revision`; changing presence or resolved Principal advances `desired_revision`. For `ANY`, Access requests a unique active correlated Principal from Identity. Exactly one Principal resolves to its ID; none or multiple keep `principal_id = NULL`. No arbitrary account selection is permitted.

### Governance

Initial table families include:

- `access_request`, scalable `request_item`;
- reusable `approval_case` mutable process state;
- immutable `approval_plan`, ordered `approval_stage`, resolved `approval_approver` snapshots;
- immutable `approval_decision`;
- `review_campaign`, high-cardinality `review_item`, immutable `review_decision`, `review_remediation`;
- `policy`, immutable activated `policy_version`;
- `governance_exception`;
- `governance_finding`;
- immutable/result-oriented `risk_assessment`, `policy_evaluation`, `sod_conflict`.

`review_item` is its own scalable consistency boundary; a campaign repository never requires loading all review items as an aggregate child collection.

Migration V26 implements the first reusable approval/request foundation. `approval_case` carries typed subject kind + stable subject ID, current stage ordinal and optimistic revision. It contains no AccessRequest-specific target data. Plan/stage/approver rows and decisions are protected by database update/delete rejection triggers. `request_item.approval_case_id` is a same-capability link from the first consumer into the reusable approval process.

Migration V27 adds explicit RequestItem principal-constraint and validity semantics plus `access_assignment_id` traceability. The assignment ID is intentionally a cross-capability stable reference with no Governance-to-Access database foreign key. Existing V26 RequestItems are migrated explicitly to `ANY`; new request creation must supply the typed principal constraint. V27 also extends AccessAssignment provenance with `REQUEST_ITEM`, requires a provenance reference for that kind, and adds a tenant-scoped unique partial index so one RequestItem can cause at most one authoritative AccessAssignment.

Migration V28 adds an approver-identity-first index over `approval_approver` to support the reusable pending approval inbox without scanning stage-first approval membership at high cardinality. The inbox remains a read model over existing approval authority/evidence tables; V28 introduces no new ApprovalTask authority.

Migration V29 adds Governance-owned `policy` / `policy_version`, typed symmetric `sod_rule`, immutable policy approval-stage/approver content, and immutable `risk_assessment`, `sod_conflict` and `policy_evaluation` evidence. At most one ACCESS_REQUEST PolicyVersion is ACTIVE per tenant/policy. SoD entitlement IDs are stable cross-capability references without Catalog foreign keys; canonical pair ordering prevents duplicate symmetric rules. Policy-version content is insert-only while DRAFT and cannot be changed after validation/activation; activated changes create a successor version.

Migration V30 adds Governance-owned `governance_exception` authoritative lifecycle state. The first scope binds a stable cross-capability Identity ID to one exact same-capability SoDRule, required reason, reusable ApprovalCase, mandatory validity window and optional predecessor exception. Identity IDs intentionally have no database FK. `sod_conflict.governance_exception_id` is immutable evidence of exact coverage and never removes the conflict. The effective lookup is bounded by tenant + Identity + rule set + APPROVED validity.

Migration V31 adds Governance-owned `review_campaign`, `review_item`, immutable `review_decision`, and `review_remediation`, plus Access-owned `review_remediation_application`. ReviewCampaign stores a bounded generation continuation and generated/decided counters; ReviewItem is unique by tenant + campaign + AccessAssignment and its snapshot fields are database-immutable. ReviewDecision update/delete is rejected. AccessAssignment/Identity IDs in review state are stable cross-capability references without database foreign keys. The Access remediation-application row is uniquely keyed by tenant + ReviewRemediation ID so cross-capability retry returns the same authoritative termination/no-op result.

Approval-plan content is immutable from creation in this slice. Decisions remain append-only evidence; policy revalidation may create successor approval context rather than mutating completed approval evidence.

### Credential

Migration V32 materializes the first Credential capability runtime:

```text
credential.credential
- id uuid PRIMARY KEY
- tenant_id uuid NOT NULL
- principal_id uuid NOT NULL                 -- cross-capability stable ID, no Identity FK
- credential_kind varchar(32) NOT NULL
- secret_provider_type varchar(64) NOT NULL
- secret_reference_key varchar(512) NOT NULL
- lifecycle_state varchar(24) NOT NULL
- valid_from timestamptz NULL
- valid_until timestamptz NULL
- revision bigint NOT NULL
- created_at timestamptz NOT NULL
- updated_at timestamptz NOT NULL
- compromised_at timestamptz NULL
- revoked_at timestamptz NULL
- expired_at timestamptz NULL
- UNIQUE (tenant_id, id)

credential.credential_rotation
- id uuid PRIMARY KEY
- tenant_id uuid NOT NULL
- old_credential_id uuid NOT NULL
- replacement_credential_id uuid NULL
- initiator_identity_id uuid NOT NULL        -- stable cross-capability ID, no Identity FK
- process_state varchar(32) NOT NULL
- checkpoint varchar(256) NULL
- failure_code varchar(128) NULL
- revision bigint NOT NULL
- created_at timestamptz NOT NULL
- updated_at timestamptz NOT NULL
- completed_at timestamptz NULL
```

`secret_provider_type + secret_reference_key` is an opaque external SecretReference only. V32 has no raw password, API-key value, private-key, token or client-secret material column. Credential ownership/kind/reference/validity is immutable in the first slice; lifecycle changes are revision guarded. Rotation subject/initiator and an attached replacement are immutable, and terminal rotation rows cannot be rewritten. V35 adds a tenant + Principal + immutable `created_at + id` index for deterministic bounded public Credential listing; lifecycle state is intentionally excluded from the public continuation tuple because lifecycle is mutable. CredentialBinding remains a canonical planned concept but no generic binding table is introduced until its provider/consumer semantics are concretely specified.

### Integration configuration and processes

```text
integration.connector_instance
- id uuid PRIMARY KEY
- tenant_id uuid NOT NULL
- connector_type varchar(128) NOT NULL
- configuration_version bigint NOT NULL
- configuration_json jsonb NOT NULL
- secret_reference varchar(1024) NULL
- lifecycle_state varchar(24) NOT NULL
- revision bigint NOT NULL
- UNIQUE (tenant_id, id)
```

Connector configuration is an adapter/native edge, so validated JSONB is acceptable. Governance-critical lifecycle/access semantics remain typed columns/tables, and secrets remain external.

```text
integration.provisioning_task
- id uuid PRIMARY KEY
- tenant_id uuid NOT NULL
- provisioning_job_id uuid NOT NULL
- operation_type varchar(64) NOT NULL
- subject_kind varchar(32) NOT NULL
- subject_id uuid NOT NULL
- desired_revision bigint NOT NULL
- idempotency_key varchar(256) NOT NULL
- state varchar(32) NOT NULL
- attempt_count integer NOT NULL DEFAULT 0
- next_attempt_at timestamptz NULL
- failure_code varchar(128) NULL
- created_at timestamptz NOT NULL
- updated_at timestamptz NOT NULL
- UNIQUE (tenant_id, id)
- UNIQUE (tenant_id, idempotency_key)
- same-capability composite FK to provisioning_job
```

Provisioning task state/retry eligibility belongs to Integration process semantics. Generic worker claim ownership (`lease_owner`, `lease_until`) is **not** stored as business/task authority here; Platform delivery/claim storage references the task when a worker needs a technical lease.

The first remote-worker implementation materializes that separation with:

- Integration-owned `connector_worker_registration`, binding-scope/runtime-permission and negotiated-session tables;
- Integration-owned `provisioning_job`, `provisioning_task`, immutable `provisioning_attempt`;
- Integration-owned `reconciliation_run`, idempotent observation-batch metadata, PRINCIPAL staging, and current `observed_principal`;
- Platform-owned `connector_work_lease` carrying only an opaque technical execution-owner ID, work/lease ID, monotonic lease epoch and expiry. Remote workers use negotiated session IDs as execution-owner IDs; in-process executors use stable runtime owner IDs.

`connector_work_lease` is not authoritative process state. Expiry/reclaim increments the fencing epoch; stale completions cannot overwrite a newer lease generation.

For the first reconciliation slice, `reconciliation_run.reported_coverage` is worker evidence while `effective_completeness` is Integration-owned. Positive PRINCIPAL observations may materialize from incomplete runs. Existing observations are marked absent only when effective completeness is `COMPLETE`, which requires successful worker completion plus current binding/configuration/runtime/contract checks and connector support for trustworthy complete principal discovery.

Provisioning work carries the desired revision. Remote claim requires a semantic current-revision verifier; a mismatch transitions the task to `SUPERSEDED`, while unavailable verification leaves the task unclaimed. This keeps stale desired-state execution fail-closed without making Integration authoritative for Access projections.

`integration.provisioning_attempt` is immutable evidence with task ID, attempt number, start/end time, normalized operation, provider request/idempotency reference where safe, outcome/failure category, correlation/causation and data-minimized evidence. Provider secret/private values are prohibited.

### Administration

`administration.administrative_grant` stores tenant, actor reference, administrative role, strongly typed scope type/reference, lifecycle, temporal validity and revision. Scope is not arbitrary JSON. V47 adds explicit `grantable` / `delegable` rights and nullable same-tenant `authority_basis_grant_id` creation provenance. Grantability/delegability are not implied by `administration:manage-authorization`; a created direct grant remains independent if its creation basis later expires or is revoked. The burn-once bootstrap root is explicitly grantable/delegable only for its existing role/scope/validity, while pre-V47 non-bootstrap grants remain non-grantable/non-delegable by default.

`administration.administrative_delegation` remains a separate later table/object that records delegator/delegate, direct source grant, bounded scope and finite validity so delegated authority can be invalidated when the delegator loses the source authority.

### Audit

```text
audit.audit_record
- id uuid PRIMARY KEY
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
- UNIQUE (tenant_id, id)
```

Audit rows are append-only. V46 implements `audit.audit_record` with normalized `SUCCESS`/`DENIED`/`FAILURE` outcomes, stable replay identity and bounded indexes for time/actor/resource/correlation queries. `material_snapshot` and `integrity_metadata` remain nullable and unused until their later governed contracts are implemented. Material snapshots are used only where needed to preserve historical explainability after rename/retirement; they are data-minimized and secret-filtered.

## 7. Dynamic/custom attribute physical model

Dynamic canonical attributes are governed typed state, not a universal EAV/JSON replacement for IAM semantics.

Identity is the initial reference model; another owning capability may use the same physical convention without introducing a universal `IamObject`.

The stable business identity of a canonical attribute is separated from the immutable semantic content used by an activated schema version:

```text
identity.attribute_definition
- id uuid PRIMARY KEY
- tenant_id uuid NOT NULL
- canonical_key varchar(256) NOT NULL
- subject_type varchar(32) NOT NULL CHECK (subject_type = 'IDENTITY')
- lifecycle_state varchar(24) NOT NULL
- revision bigint NOT NULL
- created_at timestamptz NOT NULL
- updated_at timestamptz NOT NULL
- UNIQUE (tenant_id, id)
- UNIQUE (tenant_id, canonical_key)

identity.canonical_schema_version
- id uuid PRIMARY KEY
- tenant_id uuid NOT NULL
- version_number bigint NOT NULL
- state varchar(24) NOT NULL CHECK (state IN ('DRAFT','ACTIVE','SUPERSEDED'))
- created_at timestamptz NOT NULL
- activated_at timestamptz NULL
- superseded_at timestamptz NULL
- UNIQUE (tenant_id, id)
- UNIQUE (tenant_id, version_number)
- at most one ACTIVE row per tenant

identity.attribute_definition_version
- id uuid PRIMARY KEY
- tenant_id uuid NOT NULL
- schema_version_id uuid NOT NULL
- attribute_definition_id uuid NOT NULL
- data_type varchar(24) NOT NULL
- cardinality varchar(16) NOT NULL CHECK (cardinality IN ('SINGLE','MULTI'))
- classification varchar(64) NOT NULL
- queryable boolean NOT NULL
- searchable boolean NOT NULL
- policy_addressable boolean NOT NULL
- created_at timestamptz NOT NULL
- UNIQUE (tenant_id, id)
- UNIQUE (tenant_id, schema_version_id, attribute_definition_id)
```

`AttributeDefinition` preserves the stable governed key/identity across schema revisions. Type, cardinality, classification and query/search/policy contracts belong to `attribute_definition_version`; they are not mutable properties of the stable definition row. A schema is assembled while `DRAFT`; once activated, its definition-version content is immutable. A change in semantic shape therefore requires a successor schema/definition version rather than editing activated content in place.

Source mapping and authority remain separately versioned concerns:

- `identity.attribute_mapping_version` binds one SourceSystem and one attribute-definition version to an explicit source path; replacing a mapping supersedes its predecessor rather than rewriting history;
- `identity.attribute_authority_rule_version` assigns authority priority for one SourceSystem and one attribute-definition version; mapping does not imply authority;
- only one active mapping and one active authority-rule version exist for a given tenant/source/definition-version tuple;
- a new schema/definition version does not silently inherit compatibility from mappings or authority rules for an older definition version.

### Typed values

Candidate, resolved and override value tables use an intentionally small typed column set:

```text
value_string text NULL
value_boolean boolean NULL
value_integer bigint NULL
value_decimal numeric(38,12) NULL
value_date date NULL
value_datetime timestamptz NULL
value_enum_key varchar(256) NULL
```

A CHECK constraint enforces exactly one populated typed value column and compatibility with the referenced definition type. For `MULTI`, each element is a normalized row with `value_ordinal`; values are not encoded as JSON arrays. The parent tuple and definition-version/type/cardinality references are constrained so a value row cannot silently claim a different semantic shape from its candidate/state/override.

`identity.canonical_attribute_candidate` stores:

- Identity + active attribute-definition-version context;
- source system + SourceRecord;
- source path/field;
- mapping-version ID;
- source-updated time and observed time;
- candidate revision;
- typed normalized values in child rows.

Candidate provenance is relationally constrained: the SourceRecord must belong to the stated SourceSystem, and the mapping version must refer to the same source and definition version as the candidate. A candidate is still Observation-derived state and is never canonical truth by itself.

`identity.canonical_attribute_state` stores the current authoritative resolution status (`RESOLVED`, `OVERRIDDEN`, `CONFLICT`, `UNRESOLVED`, `NO_VALUE`), active definition-version reference, authority-rule-version reference where applicable, selected candidate/provenance reference and `value_revision`; canonical values live in typed child value rows. Selected candidate and authority references are constrained to the same Identity/definition-version context as the state.

Resolution uses explicit active authority rules. Observation recency/source timestamps are provenance and do not become an implicit last-write-wins authority rule. Equal highest-priority sources with different typed values produce `CONFLICT`; absence of a usable authority rule produces `UNRESOLVED`. A degraded resolution may retain a compatible previously trusted source-resolved value while exposing the degraded status, but it does not silently preserve an expired override as trusted source truth. A recomputation that produces the same effective outcome does not advance `value_revision` or emit another state-change fact.

`identity.canonical_attribute_override` is authoritative, explicit, reasoned and optionally time-bounded, with its own typed value rows. It never modifies SourceRecord/candidate observation. Validity is semantic: an override stops governing at `valid_until` even if no scheduler has materialized a cleanup transition.

Canonical attribute state changes and override application emit data-minimized internal facts through the transactional outbox; canonical values are not copied into those ordinary event payloads.

Only definitions explicitly declared queryable/searchable/policy-addressable gain those contracts. Type-appropriate indexes/projections are created for declared queryable attributes; arbitrary provider-native JSON paths are not public query or policy surfaces.

## 8. Access projections

### EffectiveAccess and support counting

```text
access.effective_access
- id uuid PRIMARY KEY
- tenant_id uuid NOT NULL
- identity_id uuid NOT NULL
- entitlement_id uuid NOT NULL
- principal_constraint_key varchar(512) NOT NULL
- support_count integer NOT NULL CHECK (support_count > 0)
- computed_at timestamptz NOT NULL
- projection_generation bigint NOT NULL
- UNIQUE (tenant_id, id)
- UNIQUE (tenant_id, identity_id, entitlement_id, principal_constraint_key)
```

```text
access.effective_access_support
- id uuid PRIMARY KEY
- tenant_id uuid NOT NULL
- effective_access_id uuid NOT NULL
- access_assignment_id uuid NOT NULL
- role_version_id uuid NULL
- path_hash varchar(128) NOT NULL
- path_depth integer NOT NULL CHECK (path_depth >= 0)
- UNIQUE (tenant_id, effective_access_id, access_assignment_id, path_hash)
- same-capability composite FK to effective_access
```

Support/path rows preserve explainability without putting an unbounded opaque array on `effective_access`. `support_count` is a denormalized projection count of current support rows and is updated atomically with the support-set change. The effective row disappears only when the last support disappears.

Migration V24 adds `access.effective_access_support_role_version`, an ordered normalized path table for role-derived supports. A direct assignment has no RoleVersion path. An APPLICATION Role -> Entitlement support records one RoleVersion; BUSINESS -> Entitlement also records one; BUSINESS -> APPLICATION Role -> Entitlement records the BUSINESS and child APPLICATION RoleVersion IDs in order. These are cross-capability stable IDs and intentionally do not create Access-owned foreign keys into Catalog.

The first runtime implementation is migration V20 and currently projects direct Entitlement assignments only. The stable `principal_constraint_key` encoding is `ANY` or `SPECIFIC:<principal UUID>`. A direct support row has `role_version_id = NULL`, `path_depth = 0`, and a deterministic lowercase SHA-256 path hash over `DIRECT|<assignmentId>|<identityId>|<entitlementId>|<principalConstraintKey>`. Adding or removing a support advances `projection_generation`; replay of an already-present support is a no-op and does not advance generation. Assignment mutation facts and technical valid-from/valid-until work both re-read current authoritative assignment state before projection mutation. Semantic reads additionally filter support by current lifecycle and validity-window clock comparison, so scheduler delay cannot extend or prematurely start effective access.

### Desired state

`access.desired_principal_state` is keyed by tenant + target + identity/principal-resolution context and records desired existence/enabled state, desired revision/generation and a reference to normalized mapped attribute state.

`access.desired_grant_state` is keyed by tenant + principal/target context + entitlement and records desired presence plus desired revision/generation. Large causal support collections are normalized in support tables.

`access.assignment_fulfillment` is an Access-owned rebuildable projection of current technical realization, produced from Integration semantic facts/attempt summaries/observations. Integration never updates this table directly through an Access repository shortcut; an Access projector consumes the semantic feed.

Projection corruption is repaired by rebuild from authoritative inputs/facts, never by rewriting AccessAssignment history.

## 9. Observation, reconciliation and import

Current provider observations live in:

- `integration.observed_principal`;
- `integration.observed_entitlement`;
- `integration.observed_grant`;
- `integration.observed_credential`.

Each current observation carries at least:

- `tenant_id`;
- connector/binding/target reference;
- provider stable ID/native key;
- normalized observed state;
- `first_observed_at`, `last_observed_at`;
- `last_complete_run_id` where meaningful;
- provider version/etag/change token if available;
- mapping/provenance reference;
- optional bounded/redacted native metadata.

Provider identity uniqueness is explicit, for example:

```text
UNIQUE (tenant_id, connector_binding_id, provider_stable_id)
```

### Reconciliation run and staging

`integration.reconciliation_run` stores separately:

- run/process state and outcome;
- coverage/completeness (`COMPLETE`, `PARTIAL`, `UNKNOWN`);
- discovery mode/object scope;
- connector/binding configuration revision;
- started/completed times;
- checkpoint/change token;
- object/error counters;
- reason coverage is partial/unknown.

Run-scoped staging tables such as `integration.recon_observed_grant_stage` contain normalized staged observations keyed by run and provider identity. Staging is compared/promoted according to completeness semantics; completion of execution does not itself authorize absence inference.

Destructive absence inference is allowed only when all are true:

1. relevant scope/object-class coverage is `COMPLETE`;
2. the connector discovery contract supports trustworthy absence detection;
3. binding/configuration/scope revisions match the expected run contract;
4. no checkpoint gap or run condition invalidates coverage.

A `PARTIAL` run may add or refresh positive observations but cannot mark unseen current rows absent simply because they were missing from that run.

Identity source import uses the same run/checkpoint/completeness concept around `identity.source_record` and source-import staging. Positive source facts can be processed when trustworthy; inferred destructive absence requires complete coverage.

### Raw provider/source payload boundary

If native/raw payloads must be retained for troubleshooting, they are stored separately from normalized current observation state, with:

- explicit run/provider provenance;
- size limits;
- secret/private-material filtering;
- encryption according to deployment policy;
- short configurable retention;
- no status as canonical governance truth.

## 10. Durable asynchronous infrastructure

### Transactional outbox

`platform.outbox_event` contains:

- event ID;
- tenant context;
- event type/version;
- aggregate/resource type, ID and revision where applicable;
- occurred time;
- correlation/causation;
- data-minimized payload;
- publication attempt/state metadata.

The authoritative mutation and its outbox fact commit atomically in the same database transaction.

Publication state is technical delivery state, not domain state. The active outbox lifecycle is:

```text
PENDING -> PUBLISHED
       \-> FAILED
```

`PENDING` rows may carry attempt count, next-attempt time, last-attempt time and a normalized last error code. Retryable transport failures remain `PENDING` and are re-eligible after bounded backoff. `FAILED` is terminal technical/manual-remediation state for permanent mapping/contract defects and has no next-attempt time. Publication workers claim bounded batches with short leases/locking; external delivery occurs after the claim statement/transaction completes. A lease expiry after worker crash makes a still-`PENDING` row eligible again, preserving at-least-once semantics.

### Inbox/deduplication

`platform.inbox_message` uses tenant-aware consumer identity, normally:

```text
UNIQUE (tenant_id, consumer_name, message_id)
```

It records first-seen/completed timestamps and normalized processing outcome. For a side effect that requires durable deduplication, inbox acceptance/result and that local side effect commit atomically where practical.

### Causal idempotency

`platform.idempotency_record` is keyed by tenant + operation namespace + idempotency key and stores request fingerprint, operation state, resource/result reference and created/expiry times. Reuse of the same key with a different fingerprint is an idempotency conflict.

Idempotency deduplicates the same causal operation, not all semantically similar IAM access.

### Scheduled delivery and work claiming

`platform.scheduled_work`/`platform.work_claim` own technical scheduling and leasing metadata such as:

- delivery/work ID;
- handler type;
- typed subject kind/ID;
- due/next-eligible time;
- payload version;
- expected subject revision where useful;
- delivery attempt;
- lease owner / lease until;
- technical idempotency identity.

The referenced domain capability still owns the business/process object. A handler claims technical work, re-reads current domain state, validates revision/applicability and invokes a semantic application command. It does not update business-state tables by generic SQL.

Claims may use bounded transactions with `FOR UPDATE SKIP LOCKED` or an equivalent lease-update pattern. Provider/network calls occur only after the claim/domain transaction requiring atomicity has committed.

## 11. Indexing strategy

Every FK/reference used materially for joins/deletion validation receives a supporting index unless an existing PK/unique index already covers the leftmost key columns.

Required patterns include:

- tenant + business-key unique indexes;
- `(tenant_id, id)` candidate-key indexes for tenant-safe references;
- deterministic cursor indexes such as `(tenant_id, updated_at, id)` or append-history `(tenant_id, occurred_at, id)`;
- selective active/open-state partial indexes;
- provider stable-ID uniqueness/lookup indexes;
- due/retry queue indexes;
- EffectiveAccess identity- and entitlement-centric indexes;
- indexes supporting same-capability FKs and high-cardinality bounded scans.

Representative PostgreSQL indexes:

```text
access.access_assignment (tenant_id, identity_id, lifecycle_state, valid_until)
  WHERE lifecycle_state IN ('SCHEDULED','ACTIVE','SUSPENDED')

access.effective_access (tenant_id, identity_id, entitlement_id)
access.effective_access (tenant_id, entitlement_id, identity_id)

access.desired_grant_state (tenant_id, application_target_id, projection_generation, id)

integration.observed_grant (tenant_id, connector_binding_id, provider_stable_id) UNIQUE
integration.observed_grant (tenant_id, principal_provider_id, entitlement_provider_id)

integration.provisioning_task (tenant_id, state, next_attempt_at, id)
  WHERE state IN ('PENDING','READY','FAILED_RETRYABLE')

platform.scheduled_work (state, due_at, id)
  WHERE state IN ('PENDING','RETRY')
platform.work_claim (lease_until, work_id)
  WHERE lease_until IS NOT NULL
```

Lease-expiry comparison against `CURRENT_TIMESTAMP` belongs in the claim query; volatile current-time expressions are not used as PostgreSQL partial-index predicates.

High-cardinality public/internal collections use keyset/cursor pagination with deterministic tie-breaks, not large OFFSET scans or unbounded aggregate loading.

Dynamic-attribute indexes are definition/type-specific and introduced only for attributes declared queryable/searchable. Arbitrary native JSON paths are not indexed into a public query contract by default.

## 12. Partitioning strategy and thresholds

Do **not** partition ordinary transactional tables initially. Identity, Application, Role, AccessAssignment, request state, Credential, ConnectorInstance and AdministrativeGrant remain ordinary indexed tables until measurements justify a change.

Initial candidates for time-range partitioning are high-volume append/history data:

- `audit.audit_record`;
- `audit.evidence_snapshot` when snapshot volume is material;
- `integration.provisioning_attempt`;
- reconciliation/import staging/history if retained at very high volume;
- observation history tables, if a history stream is retained separately from current observation;
- archived outbox/inbox history after active processing rows are separated.

Current observation tables are normally current-state keyed tables, not time partitions. Provider/object cardinality may justify a later hash/subpartition strategy, but it is not the default.

Introduce partitioning only after a measured trigger such as:

- table size approaching operationally material hundreds of GB;
- sustained row count in the hundreds of millions with vacuum/index-maintenance pain;
- retention repeatedly deleting large contiguous time ranges where partition drop materially lowers cost;
- correct indexes still allow repeated scans across irrelevant historical ranges;
- backup/restore/archive objectives require bounded chunks.

Time-range partitioning is preferred for append/evidence/history because retention aligns with time. Tenant hash partitioning is not the default because tenant sizes can be highly skewed and platform operations may need cross-tenant maintenance.

## 13. Retention, archive and purge

Exact durations are policy/deployment configuration; semantic treatment is fixed.

| Data | Treatment |
| --- | --- |
| Authoritative business/process state | retain while active plus policy-defined history; represent business deletion with terminal state; purge only when references/evidence/legal policy permit |
| Governance/audit evidence | long-lived append-only; may archive to cheaper immutable/searchable storage; purge only by explicit retention/legal policy |
| Current source/provider observation | retain current truth plus provenance; bounded historical observation may archive/purge separately |
| Provisioning attempt evidence | retain for security/operational evidence horizon; archive before purge where required |
| Raw/native payload | shortest practical retention, size-bounded, encrypted as required and aggressively secret-filtered |
| Outbox | retain through publication plus replay/debug horizon, then purge/archive |
| Inbox/dedup | retain through maximum transport replay/redelivery and side-effect dedup horizon |
| Idempotency | retain through client retry contract and any longer async-operation replay horizon |
| Rebuildable projection | may be truncated/rebuilt; no historical-retention requirement unless deliberately captured as evidence |

No physical purge performs cross-capability business cascade deletion. Purge/archive operations are explicit, authorized and auditable.

## 14. Transaction boundaries

Transactions are bounded by aggregate/process invariants, not HTTP breadth or ORM graph reach.

1. Same-aggregate authoritative state and required same-capability invariant rows commit atomically.
2. Same-capability multi-aggregate work shares a transaction only when a documented invariant requires atomicity; otherwise it coordinates semantically.
3. A semantic fact/outbox record commits atomically with the authoritative mutation that produced it.
4. Cross-capability propagation uses commands/queries/facts and may be eventually consistent.
5. No distributed database transaction is required across capabilities/providers.
6. External/provider calls never execute inside the authoritative transaction.
7. Long-running operations persist typed process/checkpoint state and use forward recovery/compensation.
8. Projection updates use independent transactions and are replayable/rebuildable.

A downstream provider failure therefore cannot roll back or rewrite a valid upstream governance decision.

## 15. Constraint philosophy

The database enforces high-value local structural invariants that are clear and stable:

- PK, NOT NULL, UNIQUE;
- same-tenant composite FKs;
- same-capability ownership/cardinality FKs;
- CHECK constraints for mutually exclusive columns, typed value shape, simple permitted code sets and time-window ordering;
- append-only evidence permissions/triggers where defense in depth is operationally useful.

Do not encode cross-capability governance policy in database triggers.

### Same-capability vs cross-capability FKs

Same-capability tenant-owned relationships should normally use composite tenant-aware FKs.

Cross-capability references default to stable typed IDs **without** database FK. A selective cross-capability composite FK is permitted only when all are true:

- both sides are guaranteed co-located for the supported deployment;
- the reference is fundamental/stable rather than lifecycle-coupled;
- the FK materially prevents corruption;
- no `ON DELETE CASCADE` or foreign mutation shortcut is introduced;
- future extraction cost is understood and accepted.

The initial architecture never depends on a cross-capability FK for correctness.

### Delete and history

Cross-capability `ON DELETE CASCADE` is forbidden. Business history uses retirement/revocation/expiry/decommission states. Same-capability cascade is allowed only for truly implementation-owned non-historical children that cannot validly outlive a draft/uncommitted parent; evidence/history is not cascade-deleted.

### Enum/code storage

Small stable state/type sets use constrained textual codes (`varchar`) plus application enums/typed values and CHECK constraints where useful. PostgreSQL native ENUM is not the default because it couples rolling code-set evolution to database-specific DDL. Large/configurable code sets use governed/reference tables.

ORM mappings conform to aggregate ownership, transaction boundaries and delete semantics; ORM cascade/fetch convenience does not define them.

## 16. Schema migration strategy

Database changes use repository-controlled versioned forward migrations.

Rules:

- released migrations are append-only;
- destructive/rolling changes use expand → migrate/backfill → contract when compatibility is needed;
- introduce compatible tables/columns before new code requires them;
- high-volume backfills are bounded, restartable and checkpointed instead of one unbounded transaction;
- large live-table indexes use operationally safe PostgreSQL creation patterns when required;
- migration naming/location makes capability ownership visible even if one runner applies all schemas;
- production correction is normally a new forward migration rather than assuming every DDL operation is safely reversible.

The JVM build remains Maven; Gradle is not introduced.

This phase intentionally does not mandate Flyway, Liquibase or another migration runner. The implementation step should select one after comparing Maven integration, ordered SQL support, checksum/history behavior, multi-schema ownership, repeatable migration needs and deployment/operational compatibility. A migration tool is not allowed to redefine schema ownership or domain semantics.

## 17. Difficult-case walkthroughs

### AccessAssignment is REVOKED while ObservedGrant remains PRESENT

1. Access changes `access_assignment.lifecycle_state` to `REVOKED`, increments revision and atomically records an outbox fact.
2. EffectiveAccess recomputation removes that assignment support path. If another support remains, access stays effective; otherwise the desired grant becomes absent.
3. Integration observes/receives the new desired state and creates or revalidates revoke work.
4. Provider removal fails; immutable `provisioning_attempt` records the failure.
5. Access-owned `assignment_fulfillment` projects failed/remediation-needed technical state from Integration facts.
6. Reconciliation still reports `observed_grant=PRESENT`.
7. Governance may create/update a drift finding.

The assignment never returns to ACTIVE merely because the provider failed.

### Partial reconciliation

A reconciliation process finishes execution but records `coverage=PARTIAL`. Newly seen objects may add/refresh positive current observations. Previously known rows not seen in the run remain current; they are not marked absent. No absence-derived revoke/leaver/remediation decision is created. The run records the scope/reason that made completeness partial.

### Typed dynamic attribute

`costCenter` is a SINGLE STRING definition marked policy-addressable. Workday and another HR source produce typed candidate rows with their mapping/source provenance. Authority rule v8 selects Workday; canonical state references the selected candidate and authority version. A governed time-bound manual override creates a separate override/value record and never rewrites Workday observation. Once the override is no longer valid, semantic resolution falls back to authority rules even if a cleanup scheduler has not yet materialized a status change.

### EffectiveAccess support counting

Assignment A grants Role R1 → Entitlement E. Assignment B directly grants E. EffectiveAccess `(Identity I, E)` has two support rows and `support_count=2`. Revoking A removes only A's support; the effective row remains with count 1. Revoking B removes the final support; only then does the effective row disappear and desired grant become absent.

### Stale provisioning task

A grant task was created for desired revision 41. Before execution, Access publishes desired revision 42 with the grant absent. Platform delivery claims the task trigger; Integration re-reads/revalidates current desired state before an external call, sees revision 41 is stale and changes its task process state to `SUPERSEDED`/no-op. No provider grant request is sent.

The first automatic planner implementation is migration V22 plus the Integration grant planner. Planner-created ProvisioningJob rows carry a deterministic nullable `plan_key`; existing/manual jobs may leave it null. One desired grant revision may produce one job per ConnectorBinding because task dependencies never cross job/binding boundaries. Task subject is the stable DesiredGrantState ID and `desired_revision` is copied from Access. ADD_GRANT planning requires exactly one safe active provider entitlement target plus a resolved active Principal; unresolved or ambiguous privilege increase is retried without creating provider work. REMOVE_GRANT planning uses safely known current observed grants and/or prior successful ADD_GRANT task payloads and may fan out across bindings. Duplicate/replayed planning facts converge on the same plan keys and task idempotency identities.

### Governance object expires before scheduler execution

A GovernanceException has `valid_until=10:00`. At 10:00 it is semantically ineffective even if its timer does not run until 10:07. Authorization/policy evaluation from 10:00 onward uses clock comparison and no longer treats the exception as covering the violation. At 10:07 the Platform scheduler delivers a trigger; Governance re-reads current exception/revision and materializes any terminal notification/finding work still applicable. Scheduler delay never extends authority.

## 18. Implementation sequencing after OD-002

Persistence implementation should now proceed in bounded vertical slices rather than by generating every entity/table at once:

1. select the migration runner and establish migration/schema ownership conventions;
2. create `platform.tenant` and the capability schemas;
3. implement the application ID-generation port and tenant/revision persistence primitives without a universal domain base entity;
4. implement capability-owned authoritative tables with the first real use cases;
5. introduce outbox/idempotency with the first retryable authoritative mutation;
6. add observations/staging/projections only with their owning use cases and rebuild tests;
7. validate indexes against high-cardinality query plans/performance tests;
8. introduce partitioning only when the documented operational thresholds are observed.

OD-003 (concrete OpenAPI/AsyncAPI schemas) is the next unresolved architecture/interface area. It can proceed in parallel with early persistence implementation because public contracts remain semantic and must not expose these tables as APIs.

### Source absence inference persistence

Migration V38 extends the Identity-owned source import model with an explicit run-scoped `absence_trust` gate. Existing and ordinary completed imports default to `UNTRUSTED`; `TRUSTED` is valid only with COMPLETE coverage and a non-blank trust reason/evidence summary. This keeps coverage completeness separate from the stronger authority required for destructive absence inference.

`identity.source_absence_policy_version` stores immutable versioned SourceSystem-scoped absence policy. The first policy owns only the positive per-import `max_inferred_transitions` safety ceiling; inferred absence is fixed by ADR-0024 to the reversible Identity `INACTIVE` target rather than storing arbitrary lifecycle expressions.

`identity.source_absence_inference` is the Identity-owned durable process record keyed uniquely by tenant + SourceImportRun. It stores the selected policy version, frozen transition ceiling, `RUNNING|COMPLETED|SUPERSEDED|MANUAL_REQUIRED` process state, optimistic revision, candidate/transition counters and the paired `after_first_observed_at + after_source_record_id` keyset checkpoint. SourceRecord candidate scans use `(tenant_id, source_system_id, first_observed_at, id)`, are bounded, and re-read the current SourceRecord/link/Identity before destructive action. Lifecycle transition and process checkpoint/count update commit atomically so retry cannot undercount transitions and bypass the configured mass-Leaver ceiling.

### LifecycleAccess policy persistence

Migration V39 adds Access-owned immutable `access.lifecycle_access_policy_version` and typed child `access.lifecycle_access_policy_rule`. At most one policy version is ACTIVE per tenant. Rules carry a stable logical `rule_id`, bounded predicate shape (ALWAYS or exact canonical STRING/SINGLE key/value), typed ROLE/ENTITLEMENT target and no arbitrary JSON expression payload.

`access.access_assignment` adds provenance kind `LIFECYCLE_POLICY_RULE`. Its provenance reference is the stable logical rule UUID. A partial unique index on tenant + Identity + rule UUID applies only to non-terminal policy assignments, allowing one current rule-owned intent while retaining terminal assignment history and permitting a later new assignment after a prior policy assignment becomes terminal.

Migration V40 adds immutable Governance-owned `governance.lifecycle_access_evaluation` and `governance.lifecycle_access_sod_conflict` evidence. Each automatic privilege-increase guard attempt records the lifecycle rule/Identity/target, active Governance PolicyVersion when available, decision/code, matched SoD rules, exact exception coverage and causal correlation metadata. These rows are evidence only and never become Access authority.

### Identity merge/split persistence

Migration V41 adds immutable Identity-owned identity_merge_operation and identity_split_operation evidence. Merge evidence preserves survivor/absorbed stable Identity IDs, both pre-operation revisions, moved relationship counts, reason and causal metadata; absorbed Identity IDs are unique as completed merge subjects. Split evidence preserves source/new Identity IDs, source pre-operation revision and the exact selected SourceRecord/Principal IDs in normalized immutable child tables.

Merge/split never rewrites foreign-capability references. Current accepted IdentityLinks are replaced through normal correlation history, Principals retain stable IDs while identity_id changes under merge/split-only semantics, and historical canonical candidate/state/override rows retain their original Identity references. The new Identity produced by split is an ordinary same-type PENDING Identity row with its typed profile.

### IdentityAccessReduction persistence

`access.identity_access_reduction` is the Access-owned durable process record for lifecycle-driven privilege reduction. Its causal uniqueness is `(tenant_id, identity_id, source_identity_revision)`; the Identity ID is a semantic cross-capability reference rather than a database foreign key. The row stores the source lifecycle/revision and snapshot time, `RUNNING|COMPLETED` process state, optimistic revision, processed count, and an optional paired `after_created_at + after_assignment_id` continuation. The supporting AccessAssignment partial index is ordered by tenant, Identity, creation time and ID for bounded deterministic reduction pages over non-terminal assignments.

### Administration delegation persistence

Flyway V48 adds `administration.administrative_delegation` as distinct Administration-owned authoritative state. Each row retains the actual delegate Identity, delegator Identity, one direct source AdministrativeGrant, the exact source AdministrativeRole, typed scope, finite validity, optimistic revision, creation/revocation evidence and optional correlation/causation. Tenant-safe foreign keys bind only same-capability source grant/role state; governed Identity references remain stable cross-capability IDs without database foreign keys.

Delegation authority is source-dependent by semantics: persistence does not copy it into an independent direct grant. Operation-time evaluation re-reads the current direct source and therefore source revocation/expiry/loss of delegability ends delegated authority immediately even when the delegation row still records ACTIVE for historical/process purposes.



## V50 administrative break-glass

Flyway V50 adds Administration-owned `administrative_break_glass_operation` and `administrative_break_glass_obligation` tables. Operation rows carry tenant-safe Role ownership, typed scope columns, finite validity, strong-assurance evidence metadata, lifecycle/revision, revocation evidence and correlation/causation. Reason and incident/reference remain Administration evidence and are not copied into AuditRecord payloads.

Each operation has at most one durable obligation of each typed kind through the tenant + operation + obligation-type uniqueness constraint. Current obligation kinds are SECURITY_NOTIFICATION and POST_USE_REVIEW. Their PENDING/COMPLETED state is operational evidence only and has no authority semantics. Indexes support actor/state validity evaluation, Role/state lookup and bounded pending-obligation work.
