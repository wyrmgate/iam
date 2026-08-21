# Wyrmgate IAM Agent Instructions

This file is the repository entry point for AI/code agents. Do not treat prior chat history as the primary project memory.

## Startup

Before substantial design or implementation work, read:

1. `docs/PROJECT_INSTRUCTIONS.md`
2. `docs/README.md`
3. `docs/adr/README.md`

Then read the task-relevant architecture/domain/security/API documents and accepted ADRs.

For requirements or controlled design baselines, use the current Wyrmgate IAM `Formal Specifications` package in Google Drive as referenced by `docs/PROJECT_INSTRUCTIONS.md`.

Do not ask the user to restate decisions that are available in these canonical sources.

## Source-of-truth rules

- Accepted ADRs and current formal requirements/specifications govern.
- A newer accepted ADR may intentionally amend an older formal specification until the next formal revision.
- Current repository architecture/domain/interface Markdown governs implementation detail.
- Existing code does not silently redefine architecture.
- Historical/legacy material is reference only.
- Never create competing authoritative definitions for the same concept.

## Architecture guardrails

- Keep canonical architecture framework-neutral.
- Logical capabilities are Identity, Catalog, Access, Governance, Credential, Integration, Administration and Audit, with Platform as supporting infrastructure.
- Capability ownership is not equivalent to package, Maven module, process, service or database schema.
- Only an owning capability mutates its authoritative state.
- Cross-capability collaboration uses semantic commands, queries and facts; do not mutate another capability through its repository/tables.
- Keep Authoritative State, Observation, Evidence and Projection distinct.
- Keep governance/business state, temporal validity, technical fulfillment and provider observation separate.
- Do not introduce a universal `IamObject`, giant EAV model, generic CRUD domain model or arbitrary JSON in place of typed IAM semantics.
- Dynamic/custom attributes use governed typed schemas; provider-native data remains observation until explicitly mapped.
- First-class relationships such as manager, organization, ownership and role composition stay first-class relationships.
- Identity is the governed subject; Principal is the technical representation/account; Credential belongs to Principal.
- AccessAssignment is authoritative business access intent; EffectiveAccess and desired provider state are derived projections.
- Partial import/reconciliation never implies destructive absence.
- Privilege increase fails closed when mandatory governance evaluation is unavailable; authoritative privilege reduction/revocation must not be blocked by unrelated evaluator failure.
- External/provider calls never run inside the authoritative transaction that commits governance state.
- Async processing assumes at-least-once delivery and must tolerate retry, duplication, replay and out-of-order events.
- Secrets/private credential material must not appear in ordinary APIs, events, audit, logs, tasks or errors.
- Tenant is an isolation boundary; Organization is business structure.
- Avoid premature microservices.

## Workflow/API rules

- Domain capabilities own business/process state machines.
- Generic worker/scheduler infrastructure owns only timers, leases, retry and delivery mechanics.
- Do not make a generic BPM engine mandatory in the core.
- Long-running processes are durable/resumable and use forward recovery/compensation rather than distributed rollback.
- Public APIs expose semantic resources and explicit business operations, not persistence entities or arbitrary status mutation.
- Mutable authoritative resources use optimistic revision semantics.
- Retryable mutations use causal idempotency where duplicate effects are harmful.
- Commands request actions; facts/events describe completed semantic changes.
- Internal domain events are not automatically public integration events.

## Implementation workflow

For a major change:

1. Inspect current `main` and relevant canonical docs.
2. Classify whether the change affects requirements, domain semantics, architecture, interfaces, data model or only implementation.
3. Resolve material model/invariant/state questions before coding.
4. Record durable architecture changes in an ADR.
5. Update living Markdown beside the implementation.
6. Update formal specifications/RTM only at appropriate controlled checkpoints.
7. Use a feature branch and PR for writes.
8. Verify PR coherence/checks before merge.
9. Remove or clearly supersede obsolete duplicate documentation.

## Coding discipline

- Do not let ORM/framework convenience decide aggregate boundaries.
- Prefer stable IDs across aggregate/capability boundaries rather than deep ORM object graphs.
- Design high-cardinality data for bounded transactions, pagination and asynchronous processing.
- Keep provider-specific behavior behind Integration/adapter boundaries.
- Preserve Maven for the JVM build unless an explicit architectural/implementation decision changes it; do not introduce Gradle alongside it.
- Treat legacy conflicts as migration/refactoring work rather than changing accepted architecture to fit legacy code.

## Current phase

Do not infer the current phase from this file. Read `docs/README.md`, the ADR index, current issues/PRs and current formal specification checkpoint. Keep this file stable as the project evolves.
