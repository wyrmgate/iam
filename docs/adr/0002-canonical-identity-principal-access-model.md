# ADR-0002: Canonical identity, principal, role and access model

Status: Accepted

## Context

Legacy terminology mixed identities, accounts, runtime principals, resources, roles and assignments. The new model must be explainable, scalable and provider-neutral.

## Decision

- `Identity` is the governed subject and is typed as PERSON, SERVICE or WORKLOAD.
- `Principal` is a technical representation/account for an Identity on an ApplicationTarget and may be temporarily uncorrelated.
- `Application` is the governable business application; `ApplicationTarget` is its technical target/environment.
- `Entitlement` is the canonical technical access unit.
- `Role` is BUSINESS or APPLICATION; activated RoleVersions are immutable.
- `AccessAssignment` is identity-centric authoritative access intent targeting exactly one Role or Entitlement with provenance, validity and principal constraints.
- `EffectiveAccess` is a rebuildable projection that expands assignments/roles and preserves supporting assignment and derivation paths.

Role composition initially permits BUSINESS -> APPLICATION_ROLE, BUSINESS -> ENTITLEMENT and APPLICATION_ROLE -> ENTITLEMENT, with no cycles.

## Consequences

- Identity and Principal are not interchangeable.
- Runtime accounts do not become the authorization source of truth.
- Multiple assignments may legitimately support the same effective entitlement.
- Cross-aggregate references use stable IDs rather than ORM object graphs.
