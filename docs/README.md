# Wyrmgate IAM Documentation

This directory contains the repository-local, implementation-facing documentation for Wyrmgate IAM.

## Documentation policy

Wyrmgate IAM uses three document classes with different purposes:

1. **Repository Markdown** is the living source of truth for architecture, domain semantics, ADRs, implementation contracts, operational runbooks, migration notes, and developer documentation. It changes with code and is reviewed through pull requests.
2. **Formal DOCX specifications** are controlled application deliverables for product/system requirements, security/governance specification, architecture/design specification, and integration/interface specification. They are published separately from the repository and are suitable for stakeholder review and sign-off.
3. **Collaborative Google Docs** are working notes only. A working note is not authoritative after its decisions have been promoted into repository Markdown and/or a formal specification.

A concept must not have competing authoritative definitions in multiple formats. Where a formal specification and repository documentation cover the same subject, the formal specification defines externally reviewed requirements while repository Markdown defines the current implementable technical contract. Material deviation requires an ADR.

## Documentation map

- [`architecture/overview.md`](architecture/overview.md) — framework-neutral system architecture and capability ownership.
- [`architecture/repository-boundaries.md`](architecture/repository-boundaries.md) — repository/module dependency rules and implementation boundary.
- [`domain/canonical-model.md`](domain/canonical-model.md) — canonical IAM concepts, ownership of truth, observations, evidence, and projections.
- [`domain/state-and-invariants.md`](domain/state-and-invariants.md) — lifecycle, concurrency, structural, temporal, and failure invariants.
- [`security/administrative-authorization.md`](security/administrative-authorization.md) — IAM control-plane authorization and scoped administration.
- `adr/` — architecture decision records promoted into repository-local form as implementation begins.
- `operations/` — deployment, backup/restore, monitoring, incident, connector, and reconciliation runbooks as those capabilities are implemented.
- `api/` — public/internal API conventions, versioning, error model, pagination, idempotency, and generated/OpenAPI references.

## Authority hierarchy

When documentation conflicts, resolve it in this order:

1. accepted ADRs and current formal requirements;
2. current repository architecture/domain contracts;
3. implementation and tests;
4. historical design baselines and collaborative notes.

Conflicts between implementation/tests and accepted architecture are defects or deliberate architecture changes that require an ADR; they are not silently resolved in favor of code.

## Framework-neutral architecture rule

The architecture is not defined by Java, Spring, JPA, PostgreSQL, REST, Kafka, or a particular build layout. These are implementation choices. Canonical capability ownership, aggregate boundaries, state semantics, security invariants, and cross-capability contracts must remain meaningful if an implementation technology changes.

## Current status

IAM v2 is pre-release and under active design/implementation. The repository documentation is being promoted from the earlier design-baseline material into this structured documentation set.
