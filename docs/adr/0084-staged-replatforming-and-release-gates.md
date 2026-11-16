# ADR-0084: Staged IAM replatforming and integrated release gates

- Status: Accepted
- Acceptance scope: G2 direction; draft PR #544, not yet effective on main
- Date: 2026-11-16
- G2 approval: [2026-11-16](https://github.com/wyrmgate/iam/issues/538#issuecomment-6906844883). Scope limited to architectural direction; implementation and merge gated.
- Preserves: ADR-0001, 0002, 0003, 0005, 0009, 0042, 0043, 0051–0083.
- Related: #538, #541, #542, #543; [foundation proposal](../architecture/proposals/vf-fnd-01-product-foundation.md).

## Context
IAM main acaf766 / V42 includes substantial domain behavior and regression evidence. One beta company may be migrated, but its actual deployment/data and business constraints are unknown. The owner approves a clean product and staged replatforming, not a mandatory rewrite. File Storage is the first reference consumer; no live integrated evidence yet.

## Decision
1. Keep one modular-monolith-compatible IAM product with nine authoritative capabilities; IdP is composed, Platform technical. Preserve domain meaning and stable business IDs, explicit Tenant/DEPLOYMENT scope, ownership and invariant tests. Rebuild a bounded component where measured maintainability/reuse/security or correctness benefit warrants it; no parallel User, generic entity or shared business database.
2. Use vertical slices, each with a bootable verified intermediate state, a regression oracle from current main, compatible or explicitly migrated public contracts, and a rollback/forward-recovery boundary. Keep/refactor/rebuild inventory in #541 is a discovery input, not approval to remove functionality. Each slice records owner, exact revisions, tests and residual risks. Keep existing source behavior only where it conforms to accepted semantics.
3. Sequence: approved interface/ADR -> isolated experiments -> candidate decision -> implementation PRs -> disposable staging -> actual IAM+Storage golden path and negative/recovery tests (#542) -> customer migration rehearsal and separately approved cutover (#543). No experiment code in production or merge without explicit approval. Stage independently deployable products; kernel published versions pinned.
4. Integrated gate requires real issuer/JWKS and provider, human PKCE and service call, resource grants, current revocation, cross-tenant denials, audit and durable failure/replay; use #542 and Storage #17–19, #42–44/#49 as applicable. A successful component CI run is not integrated acceptance. ADR-0043 remains binding for production (99.95%, RPO <=5m, RTO <=60m, recovery drills); nonproduction staging is not a waiver or proof. Any smaller service tier needs distinct accepted requirements/ADR.

## Alternatives / consequences
- Incremental patches only: least disruption but risks retaining duplicate foundations; acceptable for components with no material redesign benefit.
- Big-bang rewrite: high risk of losing security/governance invariants, migration and regressions. Reject whole-product rewrite absent new evidence; selective replacement is allowed.
- Staged replatform: selected; costs comparison and transition effort, but allows cleaner architecture and reversible slices. Do not maintain two authoritative writers during transition.

## Review / acceptance
G2 approves P01/P05 architectural direction; actual beta cohort, scope and service tier remain open. Architecture and Product review bounded implementation slices; QA maps A01–A08/C01–C12 to exact baseline/target evidence; Security reviews invariants; Delivery tracks no duplicate #503/#513/#517/#524/#537 work; Platform/Data confirm #543 inventory and rollback. Revisit if migration feasibility or critical invariants invalidate slice strategy. Formal #438 folds accepted amendments into controlled successor only after evidence. No merge/implementation authorization from G2; canonical main remains unchanged until separately authorized.
