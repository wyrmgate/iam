# Wyrmgate IAM Project Instructions

These instructions are intended for ChatGPT Project settings and for any AI-assisted work on the Wyrmgate IAM repository.

## Mission

Design and implement Wyrmgate IAM v2 as an enterprise Identity Governance and Administration platform with strong domain semantics, explainable governance, provider-neutral integration, durable workflows, and a framework-neutral architecture.

The goal is not to preserve legacy implementation structure. Preserve valid business semantics and migration needs, but prefer the cleaner canonical v2 model when legacy code conflicts with accepted architecture.

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
- `docs/architecture/repository-boundaries.md`
- `docs/architecture/workflow-orchestration.md`
- `docs/domain/canonical-model.md`
- `docs/domain/state-and-invariants.md`
- `docs/security/administrative-authorization.md`
- `docs/api/api-conventions.md`
- `docs/api/event-model.md`

Accepted ADRs in `docs/adr/` are durable architecture decisions and must be respected unless explicitly superseded by a newer ADR.

### Formal specifications in Google Drive

Canonical folder:

`https://drive.google.com/drive/folders/1ShF6_OZm5-iCX9MFQBFRp-SzhPm3dvnM`

Current controlled specification package contains:

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

Drive working notes and the former Drive ADR library are not authoritative after promotion into the formal specifications or repository Markdown.

## Authority and conflict resolution

When sources conflict:

1. Accepted ADRs and current formal requirements/specifications govern.
2. A newer accepted ADR may intentionally amend an older formal specification until the next formal revision folds it in.
3. Current repository architecture/domain/interface documentation governs implementation detail.
4. Implementation and tests must conform to the accepted architecture; code does not silently become the architecture merely because it exists.
5. Explicitly retained historical/legacy material is reference only.

Never create two competing authoritative definitions for the same concept.

## Architecture rules

- Architecture is framework-neutral. Java, Spring, JPA, PostgreSQL, REST, Kafka, Maven and similar technologies are implementation choices, not the domain architecture.
- Canonical logical capabilities are Identity, Catalog, Access, Governance, Credential, Integration, Administration and Audit, with Platform as supporting technical capability.
- Logical capability is not the same thing as package, Maven module, process, microservice or database schema.
- Only the owning capability mutates its authoritative state. Cross-capability collaboration uses semantic commands/queries/facts, never shared repository mutation.
- Keep Authoritative State, Observation, Evidence and Projection distinct.
- Do not introduce a universal `IamObject`, giant EAV model, generic CRUD domain service, generic repository abstraction or arbitrary JSON as a replacement for typed IAM semantics.
- Core semantics remain strongly typed. Dynamic attributes are governed schema-driven extensions; provider-native data remains observation until explicitly mapped.
- First-class relationships such as manager, organization, ownership and role composition must not be modeled as arbitrary extension attributes.
- Identity is the governed subject. Principal is a technical representation/account. Credential belongs to a Principal.
- AccessAssignment represents authoritative business access intent. EffectiveAccess and desired technical state are derived projections.
- Governance decision, temporal validity, technical fulfillment and provider observation are separate dimensions.
- External/provider failure never rewrites an otherwise valid governance decision.
- Partial source imports or reconciliation runs never imply destructive absence.
- Privilege increases fail closed when mandatory policy evaluation is unavailable; authoritative privilege reductions must be allowed to proceed.
- External calls never execute inside the authoritative transaction that commits governance state.
- Assume at-least-once asynchronous delivery; consumers must be idempotent/revision-aware and tolerate replay/duplicates/out-of-order delivery.
- Secrets/private credential material must not appear in ordinary APIs, events, audit, logs, task payloads or error payloads.
- Tenant is an isolation boundary; Organization is business structure. Do not conflate them.
- Avoid premature microservices. Start from a modular-monolith-compatible design and extract only for concrete operational/security/scaling reasons.

## Workflow/orchestration rules

- Domain capabilities own business/process state machines.
- Generic scheduler/worker infrastructure may own timers, leases, retries and delivery mechanics, but not business meaning.
- Do not introduce a mandatory generic BPM engine into the core.
- Approval/review/policy workflows use constrained typed models unless a future requirement justifies something more general.
- Long-running processes must be durable and resumable.
- Cross-capability long-running work uses semantic commands/facts and forward recovery/compensation rather than distributed rollback.

## API and event rules

- Public APIs expose semantic resources and explicit business operations, not persistence entities or arbitrary status mutation.
- Mutable authoritative resources use revision-based optimistic concurrency.
- Retryable mutation operations use causal idempotency where duplicate effects are harmful.
- Use deterministic cursor pagination for large mutable collections.
- Heavy bulk/export/simulation operations are durable asynchronous operation resources.
- Commands request actions; facts/events describe completed semantic changes.
- Internal domain events are not automatically public integration events.
- Public events are curated, independently versioned and data-minimized.
- Correlation and causation identifiers should preserve an end-to-end causal chain.

## Working method

For major design or implementation work:

1. Inspect `main` and the relevant canonical documentation before proposing changes.
2. Identify whether the task changes requirements, domain semantics, architecture, interfaces, data model or only implementation detail.
3. Resolve semantic/model questions before coding when they materially affect aggregate ownership, invariants, state transitions, persistence or contracts.
4. Prefer concrete models, state machines, invariants, schemas and examples over vague framework patterns.
5. Record durable architecture decisions as ADRs.
6. Update living repository Markdown with the implementation-facing contract.
7. Update formal DOCX specifications when a checkpoint materially changes reviewed requirements/design; do not create a duplicate formal document for every small change.
8. Update RTM when requirements or verification mappings materially change.
9. Use a feature branch for GitHub writes. Open a PR, verify it, and merge only when coherent/clean.
10. Do not leave obsolete duplicate documents or superseded working copies that could be mistaken for current truth.

## Documentation format policy

Use the correct artifact for the job:

- Markdown beside code: architecture details, DDD details, ADRs, API/event conventions, implementation contracts, migration notes and runbooks.
- DOCX: controlled business/software/security/architecture/integration specifications intended for review/sign-off.
- XLSX: requirements traceability and structured matrices where spreadsheet interaction is useful.
- Google Docs: working collaboration only; promote accepted decisions and remove/supersede duplicate working context.
- OpenAPI/AsyncAPI/JSON Schema: machine-readable contracts once concrete API/event schemas are implemented.

Do not generate DOCX for content that should be living Markdown, and do not leave formal requirements only in chat or ad-hoc Markdown.

## Implementation discipline

- Do not let ORM/framework convenience determine aggregate boundaries or relationships.
- Cross-aggregate/cross-capability references should generally be stable IDs, not deep ORM object graphs.
- Persistence schema must enforce high-value invariants where practical while keeping domain decisions explicit in application/domain code.
- High-cardinality data must be designed for bounded transactions and pagination; do not load giant child collections as aggregates.
- Keep provider-specific implementation behind Integration/adapter boundaries.
- Optimize for semantic correctness and evolvability before framework cleverness.
- When the current repository differs from the intended architecture, explicitly classify the work as migration/refactoring rather than silently adapting the architecture to legacy code.

## Session behavior

- Do not ask the user to restate architecture already available in GitHub/Drive. Inspect the canonical sources.
- Do not rely on hidden/project chat memory for an important architectural fact when it can be verified from the canonical artifacts.
- Avoid unnecessary clarification when a safe architectural best-effort can proceed from the existing specifications.
- Keep the user informed during long work, but do not narrate every low-level tool action.
- If a new decision contradicts a current canonical decision, call out the conflict explicitly and create/supersede an ADR rather than silently changing semantics.
- Before beginning a new major phase, verify that the previous phase's durable decisions have been documented.

## Current-phase discovery

Do not hard-code the next development phase into these project instructions. Determine current status from `docs/README.md`, the ADR index, open design documents/issues/PRs and the latest formal specification checkpoint.

The project instructions themselves should remain stable while the project evolves; current-phase details belong in repository documentation and planning artifacts.
