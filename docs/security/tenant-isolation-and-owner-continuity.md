# Tenant Isolation and Owner Continuity

## Purpose

This document consolidates the security consequences of ADR-0051 and ADR-0053 without changing the ownership of Administration authority or tenant-local business state.

## Tenant isolation

Tenant is the hard customer/security isolation realm. Organization is business structure inside a Tenant.

Ordinary tenant-owned reads, relationships and mutations must remain within one Tenant. Cross-Tenant concepts are permitted only when they are explicit, typed, deployment-owned and separately authorized.

Trusted operation scope is established server-side as `TENANT(TenantId)` or `DEPLOYMENT`. Browser headers, route values, UI selectors and untrusted claims are not sufficient authority.

## Authority separation

- Tenancy owns Tenant lifecycle.
- Administration owns `TENANT_OWNER`, `PLATFORM_OWNER`, grants, delegation, elevation and break-glass.
- Platform provides technical runtime only.
- Platform/deployment authority does not automatically grant tenant-owned Identity, Catalog, Access, Governance, Credential, Integration or Audit data access.

A tenant-scoped GLOBAL Administration scope is global only inside that Tenant.

## Activation and Tenant Owner continuity

A Tenant may become ACTIVE only when Administration confirms at least one viable `TENANT_OWNER` under current actor/Identity/Tenant conditions.

Removal or invalidation of the final viable Tenant Owner remains an Administration/Identity continuity concern; Tenancy consumes the semantic guard and never mutates owner grants itself.

## Platform Owner continuity during Tenant lifecycle

Before accepting a Tenant suspension/decommissioning transition, Tenancy asks Administration whether the transition would make the final viable `PLATFORM_OWNER` unusable.

This check matters when the current Platform Owner is a governed actor whose authentication/administrative eligibility depends on the affected home Tenant remaining operational.

If the transition would remove the final viable Platform Owner and no separately accepted deployment recovery authority exists, the transition fails closed.

This rule does not introduce a deployment-global User/SsoUser or bypass Identity/Principal ownership.

## Suspension and decommissioning safety

SUSPENDED and DECOMMISSIONING deny ordinary privilege increase/use according to owner semantics but must leave security-reducing operations available, including:

- Access revocation/reduction;
- Credential compromise/revocation/disablement;
- provider deprovisioning/removal;
- security investigation;
- Audit evidence capture/lifecycle operations required for safety and compliance.

Provider or evaluator failure cannot roll back a valid authoritative reduction or Tenant lifecycle decision.

Tenant decommissioning uses durable forward recovery and owner-specific semantic commands. No distributed rollback or direct foreign-table mutation is allowed.

## Audit scope

Tenant-owned operations emit `TENANT(ownerTenantId)` Audit evidence. Deployment-owned operations emit `DEPLOYMENT` evidence.

A deployment event may carry actor home-Tenant or affected-Tenant references as provenance, but those references do not transfer evidence ownership or grant cross-Tenant Audit visibility.

`PLATFORM_OWNER` does not automatically grant tenant Audit read/export/hold/purge authority.
