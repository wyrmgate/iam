# ADR-0076: Current provisioning admission and pinned plan context

Status: Accepted

## Decision

V36 pins binding/connector/configuration revisions in newly created provisioning jobs. No historical revision is invented. Unpinned or changed plans, missing exact route provenance, unsupported operation, wrong target, ambiguous mapping or conflicting current mapping block execution. New work uses explicit governed Principal IDs for account operations. Local and remote claim share a semantic admission port: read current desired cause and revision, exact technical route, same-tenant Principal/Identity context and active Catalog/Tenant for privilege increases. Reduction does not consult unrelated Governance evaluation; it requires current technical demand and safe known provider route. A still-required grant may leave an obsolete route only after successful exact replacement ADD for the same current desired cause. Unavailable reads retry, invalid plans become BLOCKED; neither calls a provider.

Local execution repeats admission immediately before taking the compatible immutable execution configuration snapshot and renewing its lease. The provider call remains outside the transaction. Remote workers must renew immediately before each mutation using the current lease; renewal repeats admission. Remote configuration must correspond to the pinned registered binding; worker attestation/config distribution and explicit provider call fencing remain follow-up. No claim that admission eliminates the external in-flight race. Existing unpinned pending jobs require explicit recovery/replanning, not guessed backfill. Late/uncertain effects and operator recovery remain subsequent slices.

## Consequences

No authoritative cross-capability writes. Shared admission guard is mandatory in production composition. Existing tests focused on worker mechanics use explicit test-only admission; separate admission tests verify security decisions.
