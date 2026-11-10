# Wyrmgate IAM Project Instructions

These instructions are intended for ChatGPT Project settings and for any AI-assisted work on the Wyrmgate IAM repository.

## Mission

Design and implement Wyrmgate IAM as an enterprise **Identity and Access Management platform with integrated Identity Provider (IdP), Single Sign-On (SSO), and Identity Governance and Administration (IGA)** capabilities, with strong domain semantics, explainable governance, provider-neutral integration, durable workflows, strict Tenant isolation and a framework-neutral architecture.

Wyrmgate must be able to operate as a first-party authentication/SSO provider as well as federate external identity providers. OIDC/OAuth protocol scopes and claims remain distinct from Wyrmgate business access and Administration authority. Preserve valid business semantics and migration needs, but prefer the accepted canonical architecture when legacy code conflicts with it.

## Canonical sources of truth

Do not depend on old chat history as the primary source of truth. At the beginning of a new design/implementation phase, inspect the current canonical sources first.

### GitHub

Repository: `wyrmgate/iam`
Default branch: `main`

Read these indexes first:

- `docs/README.md`
- `docs/adr/README.md`

Then read the documents relevant to the current task, especially:

- `docs/architecture/overview.md`
- `docs/architecture/layered-architecture.md`
- `docs/architecture/repository-boundaries.md`
- `docs/architecture/workflow-orchestration.md`
- `docs/domain/canonical-model.md`
- `docs/domain/state-and-invariants.md`
- `docs/domain/tenancy.md`
- `docs/domain/access-target-availability.md`
- `docs/domain/principal-observation-mapping.md`
- `docs/domain/audit-evidence-scope.md`
- `docs/security/administrative-authorization.md`
- `docs/security/control-plane-authentication.md`
- `docs/security/identity-provider-sso.md`
- `docs/api/api-conventions.md`
- `docs/api/event-model.md`

Accepted ADRs in `docs/adr/` are durable architecture decisions and must be respected unless explicitly superseded by a newer ADR.

### Formal specifications in Google Drive

Canonical folder:

`https://drive.google.com/drive/folders/1ShF6_OZm5-iCX9MFQBFRp-SzhPm3dvnM`

The controlled specification package contains:

- 00 Documentation Register & Governance
- 01 Business Requirements Document (BRD)
- 02 Functional Requirements & Use Cases (FRD)
- 03 Software Requirements Specification (SRS)
- 04 Domain-Driven Design Specification (DDD)
- 05 System Architecture & Design Specification (SAD)
- 06 Data Architecture & Information Model
- 07 Security & Governance Specification
- 08 Integration & Interface Specification
- 09 Requirements Traceability Matrix (RTM)

Drive working notes are not authoritative after their decisions have been promoted into accepted ADRs/repository Markdown or a controlled formal specification revision.

## Authority and conflict resolution

When sources conflict:

1. Accepted ADRs and current formal requirements/specifications govern.
2. A newer accepted ADR may intentionally amend an older formal specification until the next formal revision folds it in.
3. Current repository architecture/domain/interface documentation governs implementation detail.
4. Implementation and tests must conform to the accepted architecture; code does not silently become the architecture merely because it exists.
5. Explicitly retained historical/legacy material is reference only.

Never create two competing authoritative definitions for the same concept.

## Layered architecture

The reconciled architecture is described by `docs/architecture/layered-architecture.md`:

- Layer 1: Experience surfaces.
- Layer 2: Delivery/protocol edge.
- Layer 3: Application/use-case orchestration.
- Layer 4: Canonical business capabilities and authoritative state.
- Layer 5: Provider-specific external adapters/anti-corruption edges.
- Layer 6: Platform/infrastructure mechanics.

A layer is not automatically a package, Maven module, process, microservice, schema or deployment unit.

## Architecture rules

- Architecture is framework-neutral. Java, Spring, OAuth/OIDC libraries, JPA, PostgreSQL, REST, Kafka, Maven and similar technologies are implementation choices, not domain architecture.
- Canonical Layer-4 business capabilities are **Tenancy, Identity, Catalog, Access, Governance, Credential, Integration, Administration and Audit**. Platform is Layer-6 supporting technical infrastructure.
- Authentication/IdP/SSO is a composed product/application service across those owners and technical layers, not an additional Layer-4 capability.
- Tenant is the hard security/isolation/customer realm; Organization is business structure inside a Tenant.
- Tenancy owns deployment-owned Tenant identity/lifecycle. It does not own Tenant Owner/Platform Owner authority or tenant-local capability state.
- Every authoritative aggregate is explicitly `TENANT_OWNED` or `DEPLOYMENT_OWNED`; nullable tenant ownership is not a substitute for this classification.
- Trusted operation context is server-derived and explicit: `TENANT(TenantId)` or `DEPLOYMENT`. Browser/UI selectors and untrusted claims are not authoritative scope.
- Logical capability is not the same thing as package, Maven module, process, microservice or database schema.
- Only an owning capability mutates its authoritative state. Cross-capability collaboration uses semantic commands/queries/facts, never shared repository mutation.
- Keep authentication, runtime authorization, business governance intent, technical fulfillment and provider observation distinct.
- Keep Authoritative State, Observation, Evidence and Projection distinct.
- Do not introduce a universal `IamObject`, giant EAV model, generic CRUD domain service/repository or arbitrary JSON as a replacement for typed IAM semantics.
- Do not introduce a parallel `User`/`SsoUser` authority model for IdP/SSO. Identity is the governed subject; Principal is the technical representation/account; Credential belongs to Principal.
- Core semantics remain strongly typed. Dynamic attributes are governed schema-driven extensions; provider-native data remains observation until explicitly mapped.
- First-class relationships such as manager, organization, ownership and role composition must not be modeled as arbitrary extension attributes.
- AccessAssignment represents authoritative business access intent. EffectiveAccess and desired technical state are derived projections.
- Catalog owns governed access-target availability. Terminal target retirement immediately suppresses affected semantic EffectiveAccess and drives Access-owned durable `AccessTargetReduction`; Catalog never edits AccessAssignment.
- Integration may own typed provider-observation interpretation such as EntitlementObservationMapping and PrincipalObservationMapping, but Identity remains the sole owner of Principal authority and observation never silently becomes business authority.
- Audit evidence has explicit ownership scope: `TENANT(ownerTenantId)` or `DEPLOYMENT`. Platform authority does not imply unrestricted tenant Audit visibility.
- OAuth/OIDC scopes and token claims are curated protocol projections and never become Wyrmgate AdministrativePermission automatically.
- Governance decision, temporal validity, technical fulfillment and provider observation are separate dimensions.
- External/provider failure never rewrites an otherwise valid governance/security/lifecycle decision.
- Partial source imports or reconciliation runs never imply destructive absence.
- Privilege increases and new governed platform admission fail closed when mandatory policy evaluation is unavailable; authoritative privilege reductions must be allowed to proceed.
- External calls never execute inside the authoritative transaction that commits business state.
- Assume at-least-once asynchronous delivery; consumers must be idempotent/revision-aware and tolerate replay, duplicates and out-of-order delivery.
- Secrets/private credential material, passwords, authorization codes, bearer/refresh tokens, raw client secrets and signing private keys must not appear in ordinary APIs, events, Audit, logs, task payloads or error payloads.
- Avoid premature microservices. Start from a modular-monolith-compatible design and extract only for concrete operational/security/scaling reasons.

## Workflow/orchestration rules

- Domain capabilities own business/process state machines.
- Tenancy owns Tenant decommissioning orchestration; Access owns IdentityAccessReduction and AccessTargetReduction; other domain processes remain with their semantic owners.
- Generic scheduler/worker infrastructure may own timers, leases, retries and delivery mechanics, but not business meaning.
- Do not introduce a mandatory generic BPM engine into the core.
- Approval/review/policy workflows use constrained typed models unless a future accepted requirement justifies something more general.
- Long-running processes must be durable and resumable.
- Cross-capability long-running work uses semantic commands/facts and forward recovery/compensation rather than distributed rollback.

## API and event rules

- Public APIs expose semantic resources and explicit business operations, not persistence entities or arbitrary status mutation.
- OAuth/OIDC protocol endpoints are protocol contracts, not persistence APIs, and must preserve the same secret/data-minimization boundaries.
- Mutable authoritative resources use revision-based optimistic concurrency.
- Retryable mutation operations use causal idempotency where duplicate effects are harmful.
- Use deterministic cursor pagination for large mutable collections.
- Heavy bulk/export/simulation operations are durable asynchronous operation resources.
- Commands request actions; facts/events describe completed semantic changes.
- Internal domain events are not automatically public integration events.
- Public events are curated, independently versioned and data-minimized.
- Correlation and causation identifiers preserve end-to-end causal chains.

## Working method

For major design or implementation work:

1. Inspect `main` and relevant canonical documentation before proposing changes.
2. Identify whether the task changes requirements, domain semantics, architecture, interfaces, data model or only implementation detail.
3. Resolve semantic/model questions before coding when they materially affect aggregate ownership, invariants, state transitions, persistence or contracts.
4. Prefer concrete models, state machines, invariants, schemas and examples over vague framework patterns.
5. Record durable architecture decisions as ADRs.
6. Update living repository Markdown with the implementation-facing contract.
7. Update formal DOCX specifications only at a meaningful controlled checkpoint rather than for every intermediate architecture edit.
8. Update RTM when requirements or verification mappings materially change at that checkpoint.
9. Use a feature branch for GitHub writes. Open a PR, verify it, and merge only when coherent/clean.
10. Remove or clearly supersede obsolete duplicate documents that could be mistaken for current truth.

## Documentation format policy

- Markdown beside code: architecture/DDD details, ADRs, API/event conventions, implementation contracts, migration notes and runbooks.
- DOCX: controlled business/software/security/architecture/integration specifications intended for review/sign-off.
- XLSX: requirements traceability and structured matrices.
- Google Docs: working collaboration only; promote accepted decisions and supersede duplicate working context.
- OpenAPI/AsyncAPI/JSON Schema: machine-readable contracts once concrete API/event schemas are implemented.

Do not generate DOCX for content that should be living Markdown, and do not leave formal requirements only in chat or ad-hoc Markdown.

## Implementation discipline

- Do not let ORM/framework/protocol-library convenience determine aggregate boundaries or relationships.
- Cross-aggregate/cross-capability references should generally be stable IDs, not deep ORM object graphs.
- Persistence schema must enforce high-value invariants where practical while keeping domain decisions explicit in application/domain code.
- High-cardinality data must be designed for bounded transactions and pagination; do not load giant child collections as aggregates.
- Keep provider-specific implementation behind Integration/adapter boundaries.
- Keep first-party IdP/SSO protocol/session/token infrastructure behind semantic ports so protocol framework entities do not redefine canonical IAM ownership.
- Preserve Maven for JVM builds unless an explicit accepted decision changes it; do not introduce Gradle alongside it.
- Optimize for semantic correctness and evolvability before framework cleverness.
- When the current repository differs from intended architecture, classify the work explicitly as migration/refactoring rather than changing the architecture to fit legacy code.

## Session behavior

- Do not ask the user to restate architecture already available in GitHub/Drive. Inspect canonical sources.
- Do not rely on hidden/project chat memory for an important architectural fact when it can be verified from canonical artifacts.
- Avoid unnecessary clarification when a safe architectural best-effort can proceed from existing specifications.
- Keep the user informed during long work, but do not narrate every low-level tool action.
- If a new decision contradicts a current canonical decision, call out the conflict explicitly and create/supersede an ADR rather than silently changing semantics.
- Before beginning a new major phase, verify that the previous phase's durable decisions have been documented.

## Current-phase discovery

Do not hard-code the next development phase into these instructions. Determine current status from `docs/README.md`, the ADR index, current issues/PRs and the latest controlled specification checkpoint.

The current controlled formal package remains v0.8. ADR-0042 through ADR-0053 are newer accepted architecture amendments and must be folded into the next controlled BRD/FRD/SRS/DDD/SAD/Data/Security/Integration/RTM revision at the deliberate checkpoint. Repository Markdown is the current implementation-facing contract in the meantime.
