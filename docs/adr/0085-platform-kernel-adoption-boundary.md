# ADR-0085: Shared Platform Kernel adoption boundary

- Status: Accepted (G2 core-first policy; draft PR #544, not yet effective on main)
- Date: 2026-11-16
- Product direction: [#539 approval](https://github.com/wyrmgate/iam/issues/539#issuecomment-6905749487).
- Preserves: ADR-0001, 0005, 0008–0010, 0051, 0053, 0055, 0059, 0083.
- Related: [Kernel #41](https://github.com/vyrnforge/platform-kernel/issues/41), #541, #540; [experiment plans](../architecture/proposals/vf-fnd-engineering-experiments.md).

## Context
Kernel e107dbd, published 0.3.2 (Java 21 core/JDBC/testkit); IAM acaf766 Java25 Spring Boot4.1.1 JDBC and internal LifecycleEngine/JdbcIdempotencyRepository/JdbcOutboxRepository; Storage 8ea998f consumes Kernel. Kernel's historical FS-110A gate does not establish current Storage or IAM conformance.

## Decision
Kernel is preferred shared technical library for JVM products, not mandatory runtime service. Adopt in order: exact published core/testkit and trusted context/correlation/IDs with minimal adapter; generic lifecycle/reliability gaps only with two-consumer evidence; optional JDBC only after transaction equivalence. Retain IAM's existing locally monotonic UUIDv7 generator; Kernel 0.3.2 random intra-ms UUIDv7 is not equivalent. Keep Maven and Java21 kernel compatibility, no forced product runtime downgrade. All dependencies pinned, resolve Maven Central with checksums and dependency tree, no moving main artifact. Product-owned verifier establishes trusted actor; Kernel cannot authenticate unverified claims or decide IAM Administration/Storage grants. TENANT, IAM DEPLOYMENT, explicit consumer TENANTLESS are distinct: missing tenant never implies deployment authority. Do not force tenantless routes through tenant resource access.

Each product owns its data and transaction; Kernel technical event/audit intent does not replace Audit evidence. JDBC adoption must use the same connection/transaction as authoritative mutation, revision, idempotency fingerprint, required event and evidence intent; no external calls before commit; reauthorization before replay; tenant scope; at-least-once and fenced leases; no new cross-capability writes. No two authoritative outboxes/idempotency stores for same operation. Keep current adapters if equivalence fails. Candidate changes must be general-purpose; no IAM domain model, provider-specific claim conventions or network dependency in core.

## Alternatives
Keep duplicated implementations (fallback if compatibility fails); adopt all kernel modules wholesale (reject: transaction/authority risk); selective shared mechanisms with tests (preferred). Evaluate generic lifecycle extraction only with actual second use; no speculative modules. Shared implementation can be optional where adapters differ.

## G2 approval and evidence
[Approved 2026-11-16](https://github.com/wyrmgate/iam/issues/538#issuecomment-6906844883). E1a exact Kernel 0.3.2 artifact, actual IAM source bridge and Storage Java21 conformance passed in #545 / Storage #87; runtime adoption is not yet verified. Preserve explicit IAM DEPLOYMENT and existing ID semantics. No new Kernel API/release required by this decision. E1b JDBC comparison remains unauthorized; keep existing IAM JDBC reliability. 

## Evidence gates
K1 exact Java21 Storage and Java25 IAM builds, dependency convergence, architecture/security tests, context and error compatibility; K2 independent disposable PostgreSQL comparison across rollback, concurrent retry/fingerprint conflict, lost response, stale lease, restore/replay, tenant separation, audit/event atomicity. See experiment plan. Kernel release exact artifact + both consumer revisions/CI; migration plan drain old writers and verify no dual authority. If K2 fails retain IAM JDBC; core adoption independent. Kernel #41 owns reusable changes, IAM #541 adapter; Storage #50 optional adapter not automatically mandated. Approval by Architecture/Security and both consumer owners before merge or adoption. No kernel release issued here.
