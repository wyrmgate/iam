# ADR-0057: Bounded Role composition and expansion

Status: Accepted

Date: 2026-11-13

## Context

The public RoleVersion request already limits direct members to 1,000, but BUSINESS composition can multiply paths through APPLICATION Roles. Synchronous current-state security evaluation and Access projection writes must not load or materialize an unbounded graph.

## Decision

Catalog owns a fixed maximum of 1,000 direct members per RoleVersion and 1,000 expanded entitlement derivation paths per complete current Role expansion. A path, not a distinct Entitlement, is counted: two child Roles reaching the same Entitlement contribute two paths. The supported graph remains BUSINESS -> APPLICATION -> Entitlement, BUSINESS -> Entitlement, and APPLICATION -> Entitlement.

Draft creation validates direct cardinality. DRAFT -> READY and READY -> ACTIVE revalidate complete current child expansion and reject an expansion above the bound without changing the version state. An APPLICATION Role can later activate its own valid successor without scanning or locking all BUSINESS parents. If that change makes a parent's current expansion exceed the bound, the parent is temporarily unavailable (INVALID expansion with no paths), never partly assignable. Existing assignments retain their business intent; current semantic reads suppress support, and ordinary bounded Role-expansion repair converges projections. A later valid composition restores availability. Terminal retirement semantics remain ADR-0052. Privilege increases fail closed.

Catalog enforces direct bounds at command and member-domain boundaries, with a database ordinal range plus existing unique ordinal constraint. Runtime expansion checks cardinality before collecting excess paths; it does not return a truncated AVAILABLE result. The fixed limit bounds the synchronous expansion result and per-assignment support write; high-cardinality assignment fan-out remains durable and paged.

## Consequences

- An oversized parent cannot grant partial or stale access, even when child changes race with validation.
- No new aggregate, generic graph engine, cross-capability mutation or parent-wide transaction is introduced.
- Organizations needing larger roles must use separate smaller roles or a later explicitly accepted paged expansion contract.
- V21 rejects invalid retained direct ordinals rather than deleting members. Formal v0.8 is not silently revised.

## Rejected alternative

An unbounded or silently truncated expansion would either violate transaction/resource limits or redefine valid authority. Paged expansion is deferred until a concrete larger-role requirement justifies its impact on admission, governance and projection completeness.
