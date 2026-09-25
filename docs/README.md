# Wyrmgate IAM Documentation

This directory contains the repository-local, implementation-facing documentation for Wyrmgate IAM.

## Documentation policy

Wyrmgate IAM uses three document classes with different purposes:

1. **Repository Markdown** is the living source of truth for architecture, domain semantics, ADRs, implementation contracts, operational runbooks, migration notes, and developer documentation. It changes with code and is reviewed through pull requests.
2. **Formal specifications** are controlled stakeholder/application deliverables maintained in the IAM `Formal Specifications` Drive folder. The current v0.2 package contains the documentation register, BRD, FRD, SRS, DDD, System Architecture & Design, Data Architecture, Security & Governance, Integration & Interface, and the Requirements Traceability Matrix.
3. **Collaborative Google Docs** are working notes only. A working note is not authoritative after its decisions have been promoted. The former Drive ADR/working-baseline collection is superseded by the repository ADRs and formal specification set.

A concept must not have competing authoritative definitions in multiple formats. Formal specifications define reviewed requirements/design baselines; repository Markdown defines the current implementable technical contract. Accepted ADRs may intentionally amend a formal specification between releases and must be folded into the next formal revision.

## Documentation map

- [`PROJECT_INSTRUCTIONS.md`](PROJECT_INSTRUCTIONS.md) — stable project-level instructions for AI-assisted work and fresh project sessions.
- [`architecture/overview.md`](architecture/overview.md) — framework-neutral system architecture and capability ownership.
- [`architecture/repository-boundaries.md`](architecture/repository-boundaries.md) — repository/module dependency rules and implementation boundary.
- [`architecture/implementation-topology-and-persistence.md`](architecture/implementation-topology-and-persistence.md) — initial runtime and persistence direction.
- [`architecture/physical-data-model.md`](architecture/physical-data-model.md) — concrete PostgreSQL-target physical persistence contract, ownership matrix, constraints, indexes, partitioning, retention and transaction rules for OD-002.
- [`architecture/workflow-orchestration.md`](architecture/workflow-orchestration.md) — domain-owned workflow, timers, retries and durable orchestration boundary.
- [`domain/canonical-model.md`](domain/canonical-model.md) — canonical IAM concepts, ownership of truth, observations, evidence, and projections.
- [`domain/state-and-invariants.md`](domain/state-and-invariants.md) — lifecycle, concurrency, structural, temporal, and failure invariants.
- [`security/administrative-authorization.md`](security/administrative-authorization.md) — IAM control-plane authorization and scoped administration.
- [`security/control-plane-authentication.md`](security/control-plane-authentication.md) — external bearer authentication, governed actor binding and burn-once first-admin bootstrap.
- [`adr/README.md`](adr/README.md) — canonical Architecture Decision Record index.
- [`api/api-conventions.md`](api/api-conventions.md) — public/internal API contract conventions.
- [`api/event-model.md`](api/event-model.md) — domain/internal/public event contract semantics.
- [`api/identity-contracts.md`](api/identity-contracts.md) — first OD-003 machine-readable Identity OpenAPI/AsyncAPI implementation slice and runtime authorization boundary.
- [`api/catalog-contracts.md`](api/catalog-contracts.md) — authoritative Application/ApplicationTarget/Entitlement runtime contract, revisions, retirement, pagination and provider-observation boundary.
- [`api/connector-worker-protocol.md`](api/connector-worker-protocol.md) — OD-004 remote connector-worker v1 session, leasing, fencing, result, observation, and compatibility contract.
- [`api/integration-administration.md`](api/integration-administration.md) — governed ConnectorInstance/Binding/worker control-plane management API, permissions, revisions, idempotency and secret boundary.
- [`operations/scim-principal-connector.md`](operations/scim-principal-connector.md) — SCIM 2.0 principal and Group provider adapter, observation/membership semantics, secret-resolution edge, reconciliation coverage and provider-error semantics.
- [`engineering/dev-cd.md`](engineering/dev-cd.md) — active managed DEV deployment topology: Cloudflare Pages, Railway, and Neon.
- [`operations/dev-managed-activation.md`](operations/dev-managed-activation.md) — managed DEV activation checklist and recovery verification.
- [`operations/backup-recovery.md`](operations/backup-recovery.md) — managed Neon DEV and standalone PostgreSQL recovery boundaries.
- [`operations/public-event-webhook.md`](operations/public-event-webhook.md) — signed public-event webhook activation, receiver, retry, rotation, and failure runbook.
- [`engineering/edge.md`](engineering/edge.md) — standalone-host Caddy reference edge.
- [`engineering/public-demo.md`](engineering/public-demo.md) — current DEV/demo usage and standalone DEMO reference status.
- [`engineering/observability.md`](engineering/observability.md) — vendor-neutral telemetry contract and current managed DEV posture.
- `operations/` — deployment, backup/restore, monitoring, incident, connector, and reconciliation runbooks as those capabilities are implemented.

## Authority hierarchy

When documentation conflicts, resolve it in this order:

1. accepted ADRs and current formal requirements/specifications;
2. current repository architecture/domain/interface contracts;
3. implementation and tests;
4. explicitly retained historical material.

A newly accepted ADR may temporarily be newer than a formal document; that is controlled specification-update debt, not a competing permanent source of truth.

## Framework-neutral architecture rule

The architecture is not defined by Java, Spring, JPA, PostgreSQL, REST, Kafka, Cloudflare, Railway, Neon, OCI, Docker, or a particular build layout. Those are implementation/deployment choices. Canonical capability ownership, aggregate boundaries, state semantics, security invariants, and cross-capability contracts must remain meaningful if an implementation or deployment technology changes.

## Current status

IAM v2 is pre-release and under active design. The v0.2 formal specification set plus accepted ADR-0001 through ADR-0015 are the current architecture checkpoint. ADR-0011 through ADR-0015 are controlled post-v0.2 amendments and must be folded into the next formal Security/SAD/Integration/RTM revision.

The v0.2 formal RTM predates ADR-0010 and still lists OD-002 as open; ADR-0010 and [`architecture/physical-data-model.md`](architecture/physical-data-model.md) are the current controlled amendment, and the next formal-specification revision must fold them into the Data Architecture/SAD/RTM package.

The first real DEV/testing/demo environment is activated on the managed-service topology documented in [`engineering/dev-cd.md`](engineering/dev-cd.md): Cloudflare Pages/Functions, Railway Serverless, and Neon PostgreSQL. The deployment path, same-origin API proxy, Railway health path, database connectivity, and Flyway startup migration path have been validated. This DEV/demo deployment choice does not settle production HA/DR or change canonical IAM capability architecture. Grafana/OTLP remains intentionally disabled in managed DEV pending deliberate serverless-idle validation.

OD-003 is **partially implemented**. The first Identity OpenAPI/AsyncAPI slice is checked in, Administration has a persisted default-deny direct-grant evaluator, and the control plane now has provider-neutral JWT bearer validation, an Administration-owned server-side issuer+subject binding to tenant + governed Identity, and a burn-once first-administrator bootstrap path. OAuth/OIDC claims do not become Wyrmgate administrative permissions.

The first runtime Identity HTTP slice is now implemented for the checked-in contract: create/read/list, display-name update, and canonical-attribute metadata reads. Each operation consumes the trusted authenticated actor context and re-evaluates `AdministrativeAuthorizationService`; bearer possession alone is never authorization. Mutations use causal idempotency and revision semantics. Collection cursors are deterministic, integrity-protected, tenant/context-bound, time-bounded transport tokens under ADR-0012. Canonical values remain fail-closed/redacted until classification-aware value visibility exists.

The first authoritative Catalog runtime slice now implements Application, ApplicationTarget and Entitlement with tenant-safe relational invariants, optimistic revision, causal idempotency, explicit terminal retirement, default-deny administrative permissions and signed deterministic collection cursors. ApplicationTarget belongs to one Application; target-scoped Entitlements are structurally constrained to the same Application. Provider discovery remains Integration observation and cannot silently create or mutate Catalog authority.

The curated Identity integration-event slice now has a durable transport-neutral runtime dispatcher. Internal Identity facts remain distinct from public `iam.identity.created.v1` and `iam.identity.metadata-changed.v1` events; publication uses outbox leasing, curated mapping, normalized retry/terminal failure state, and an explicit external publisher port outside the authoritative transaction. A concrete broker/webhook/cloud transport is still a deployment/integration choice and is not selected by the canonical event contract.

ADR-0013 fixes the first external event transport boundary as one deployment-configured signed HTTPS webhook destination, with exact-version public-event compatibility and explicit deferral of multi-subscriber fan-out until per-destination delivery state or a broker exists. The corresponding runtime adapter and operations runbook are now implemented; environment activation still requires an operator-provided receiver URL and deployment secret.

OD-004 now has its first runtime implementation. ADR-0014 and the checked-in OpenAPI v1 contract are backed by Integration-owned connector configuration, worker registration/scope/session state, ProvisioningJob/ProvisioningTask/immutable ProvisioningAttempt persistence, ReconciliationRun plus PRINCIPAL observation staging/current state, and Platform-owned technical lease fencing. The worker endpoints use dedicated bearer authentication and server-side Integration authorization. Reconciliation is remotely executable. Provisioning freshness is now backed by the Access-owned `DesiredPrincipalState` / `DesiredGrantState` projection store and the semantic `DesiredAccessStateQuery`: matching revision permits claim, a missing or different desired state supersedes stale work, and Access query unavailability leaves work unclaimed.

The first governed Integration administration slice now exposes semantic connector, connector-binding and connector-worker create/read/update/disable operations with explicit default-deny Administration permissions, optimistic revision, causal idempotency, write-only secret references, worker scope/runtime compatibility validation, and immediate session invalidation when worker authority changes.

The concrete SCIM 2.0 provider edge now covers PRINCIPAL discovery plus principal create/update/disable and the first Group slice: `/Groups` discovery materializes Integration-owned ENTITLEMENT observations, user memberships materialize GRANT observations, and desired-grant `ADD_GRANT`/`REMOVE_GRANT` work uses SCIM membership PATCH. Provider Groups do not become Catalog Entitlements and observed memberships do not become AccessAssignments. PRINCIPAL, ENTITLEMENT and GRANT reconciliation have independent COMPLETE/PARTIAL semantics and independent binding completeness capabilities. The remote connector-worker v1 runtime accepts those three observation classes without a protocol-major change. Local durable SCIM execution remains behind the opt-in scheduler, revalidates Access desired state before provider mutation, executes SCIM HTTP outside authoritative transactions, and commits observations/attempt completion afterward.

ADR-0015 now fixes the provider-observation mapping and Governance drift boundary. Integration may explicitly map a present provider entitlement observation to an existing active Catalog Entitlement for the same ApplicationTarget; the mapping is technical provenance and never mutates Catalog authority. Governance owns deterministic observation-derived findings for unmapped provider entitlements/grants and, until canonical Principal correlation exists, mapped grants whose provider principal remains unresolved. Mapping or finding state never creates AccessAssignment authority. ENTITLEMENT/GRANT reconciliation completion and entitlement map/unmap now append a data-minimized internal observed-access-input fact atomically with Integration state; Governance leases that fact later and evaluates findings in its own retryable transaction, so Governance failure cannot roll back provider observation or mapping state.

The first Identity-owned Principal runtime slice now materializes the accepted Principal model without making provider observation authoritative. Principals are explicit tenant-scoped technical accounts on an active ApplicationTarget, may begin uncorrelated, and can be correlated once to a canonical Identity through optimistic Identity-owned mutation. The semantic `PrincipalResolutionQuery` resolves target + native principal key for foreign capabilities without exposing Identity persistence. Governance now uses that resolution when evaluating mapped observed grants, and a data-minimized principal-correlation fact causes asynchronous reevaluation of active Integration bindings for the affected ApplicationTarget. Provider observation still does not auto-create Principal or AccessAssignment authority.

The first Access-owned authoritative AccessAssignment runtime slice now materializes entitlement-target business access intent independently from desired state, technical fulfillment and provider observation. V19 implements tenant-scoped AccessAssignment persistence, optimistic revision, MANUAL provenance, ANY/SPECIFIC principal constraints, temporal validity and the formal SCHEDULED/ACTIVE/SUSPENDED/REVOKED/EXPIRED/CANCELLED lifecycle. Creation validates Identity, active target-scoped Entitlement and any specific Principal through semantic capability queries. The first slice intentionally excludes Role targets until the Catalog Role runtime exists and excludes EffectiveAccess/desired-state derivation until the next Access projection slice.

The first EffectiveAccess runtime slice now materializes the Access-owned entitlement-level projection for direct Entitlement assignments. V20 adds normalized effective rows and support paths, deterministic direct-path hashing, support counting, replay-safe projection generations, asynchronous assignment-change facts and technical validity-boundary timers. `EffectiveAccessQuery` filters support against authoritative assignment lifecycle and clock validity at read time, so timer delay cannot report not-yet-valid or expired access as semantically effective. Role expansion and desired-state derivation remain later slices.

The first real desired-state derivation slice now replaces manual desired-grant population for direct entitlement access. V21 strengthens desired-state tuple identity with `principal_constraint_key`, derives DesiredGrantState and DesiredPrincipalState from EffectiveAccess, preserves stable projection IDs, advances `desired_revision` only when externally meaningful desired content changes, and advances `source_generation` on recomputation. `ANY` access selects a Principal only when Identity reports exactly one active correlated Principal for the Identity + ApplicationTarget; zero or multiple Principals leave the desired grant intentionally unresolved rather than choosing arbitrarily. Principal create/correlation emits a separate Access-specific internal trigger so desired ANY grants can be re-resolved without competing with Governance's correlation consumer.

The first automatic grant-provisioning planning slice now connects Access desired grant revisions to Integration ProvisioningJob/ProvisioningTask creation. Access emits a data-minimized desired-grant planning fact only when `desired_revision` changes; Integration re-queries the current DesiredGrantState, resolves canonical Principal and provider entitlement targets through semantic/owned state, and creates idempotent ADD_GRANT/REMOVE_GRANT work with the current desired revision. Privilege-increasing ADD planning fails closed on unresolved or ambiguous Principal/provider mapping. Desired absence targets every safely known current/prior technical grant and becomes a no-op when none is known. V22 adds deterministic planner job identity without changing existing manual/runtime job semantics. DesiredPrincipalState-to-account provisioning remains a separate later slice.

Remaining open design areas therefore include completion of OD-003 coverage beyond this Identity runtime slice, further OD-004 connector capabilities/provider adapters, broader connector lifecycle/registration operations as required, later multi-subscriber/broker evolution when justified, operations/HA/DR (OD-005), and legacy migration/cutover (OD-006). Migration entities/repositories and concrete SQL migrations should follow the persistence semantics in OD-002 as the implementation contract.
