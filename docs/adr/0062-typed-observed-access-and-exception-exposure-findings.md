# ADR-0062: Typed observed access and exception exposure findings

Status: Accepted

## Decision

Governance findings describe actionable conditions, not grants, decisions or provider mutations. Keep existing unmapped observation findings and add:
- EXCESS_OBSERVED_GRANT: an exact present grant with explicit Entitlement and Principal mapping and no current authoritative technical demand for the mapped target/entitlement/principal. Access answers the bounded current-intent query; a stale projection or unknown dependency is not negative evidence.
- OBSERVED_SOD_CONFLICT: two exact present mapped grant witnesses linked to the same governed Identity and an active typed SoDRule, including cross-target/binding and different Principals. Effective exception coverage never erases the observed violation. One deterministic witness per counterpart entitlement per anchor grant is sufficient to establish a conflict; identity/rule/exact witness IDs are typed evidence. Multiple grants may yield multiple actionable findings.
- EXPIRED_EXCEPTION_EXPOSURE: an expired approved exception's exact Identity/SoDRule retains both current authoritative entitlement intents under its active policy, without replacement effective exception coverage. This finding is independent of provider observation and does not automatically revoke access.

New finding types retain typed stable references to Identity, Principal, Entitlement, SoDRule, counterpart binding/Principal/Entitlement and exception as appropriate. Legacy observation keys remain unchanged. New keys include exact evidence identity. Governance never writes Access or Integration authority. Unknown observations, partial coverage, ambiguous Principal selection, bounded query exhaustion and unavailable dependencies cannot prove resolution. Exact trusted absence, explicitly changed mapping/current policy, restored exact demand, removal of authoritative conflict or renewed exact waiver can resolve the corresponding condition. Positive observations can establish findings even in partial reconciliation. Provider payload and secrets are excluded.

Bounded observation evaluation remains generation-fenced with durable per-binding checkpoints. Periodic re-evaluation of completed checkpoints allows policy/Access/Identity and other binding changes to converge without inventing new Integration observations; existing observation generation changes still supersede in-progress scans. Exception exposure uses bounded due-exception keyset scans with current semantic re-evaluation. These are eventually convergent findings, not a continuous enforcement or snapshot guarantee. API exposes tenant-scoped paged findings and exact read with dedicated permission, signed cursor, typed context and no arbitrary lifecycle mutation.

## Consequences

V29 adds typed finding context, constraints and supporting indexes. Historical observation findings remain readable. The standalone owner permission set deliberately adds governance-finding:read; deployed authorities must be explicitly granted that permission through Administration. No provider operation or automatic cleanup is introduced.
