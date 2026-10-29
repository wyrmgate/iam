# ADR-0051: First-class Tenancy capability and operation-scope boundary

Status: Accepted

## Context

The accepted v2 architecture treats Tenant as the hard isolation boundary and ADR-0049 introduces deployment-owned platform authority plus tenant-scoped owner continuity, but the canonical Layer-4 capability map still leaves Tenant identity/lifecycle under Platform support semantics. The agreed v3 architecture images resolve that mismatch by making Tenancy a first-class business capability while keeping Platform purely technical.

This decision must not turn Tenancy into a generic settings container, a super-capability that owns tenant-local IAM state, or a reason to collapse deployment scope and tenant scope into nullable ownership columns.

## Decision

### Capability ownership

Wyrmgate has nine canonical Layer-4 business capabilities:

- Tenancy;
- Identity;
- Catalog;
- Access;
- Governance;
- Credential;
- Integration;
- Administration;
- Audit.

Platform supports them with replaceable technical infrastructure and is not a business capability owner.

Tenancy owns the deployment-owned `Tenant` aggregate: Tenant identity, canonical display metadata, lifecycle, revision and lifecycle timestamps. Resources inside a Tenant remain owned by their respective capabilities.

Authentication/IdP/SSO remains a composed product service across existing owners and is not an additional Layer-4 capability.

### Tenant aggregate and lifecycle

`Tenant` has a stable opaque immutable `tenantId`, mutable display metadata, optimistic revision and the lifecycle:

```text
PROVISIONING -> ACTIVE <-> SUSPENDED -> DECOMMISSIONING -> DECOMMISSIONED
```

`DECOMMISSIONED` is terminal. Business decommissioning is not a physical-delete instruction.

Activation requires at least one viable `TENANT_OWNER`, determined through an Administration-owned semantic viability/continuity guard. Tenancy never owns or mutates `TENANT_OWNER` or `PLATFORM_OWNER` authority.

### Isolation and operation scope

Tenant is the hard customer/security isolation realm. Organization remains business structure inside a Tenant.

Every authoritative aggregate is explicitly `TENANT_OWNED` or `DEPLOYMENT_OWNED`. Deployment ownership is not represented by pretending an ordinary tenant-owned row has a null tenant owner.

Cross-layer application/runtime dispatch uses explicit trusted operation scope:

```text
OperationScope = TENANT(TenantId) | DEPLOYMENT
```

Trusted TenantContext/OperationScope is established server-side. Browser headers, route parameters, untrusted claims or UI tenant selectors never establish authoritative scope by themselves.

Ordinary tenant-owned relationships may not cross Tenant boundaries. Any legitimate cross-Tenant construct must be explicit, typed, deployment-owned and separately authorized.

### Suspension and decommissioning

`SUSPENDED` and `DECOMMISSIONING` gate ordinary authentication/use and privilege increase according to the owning capability's semantic contract, but they must not block authoritative privilege reduction, credential disabling, deprovisioning, security investigation or Audit evidence work.

Tenant decommissioning is a Tenancy-owned durable, resumable orchestration. It coordinates typed commands/queries/facts with owning capabilities; it never directly mutates their repositories/tables. Owning capabilities commit their own reductions and shutdown state.

External/provider cleanup runs outside authoritative transactions. Provider failure does not roll back a valid Tenant lifecycle decision; fulfillment and observation remain separate from business lifecycle.

### Platform Owner continuity

Before a Tenant suspension/decommissioning transition is accepted, Administration must evaluate any affected owner-continuity invariant, including whether the transition would make the final viable `PLATFORM_OWNER` unusable when that owner's authentication path depends on the Tenant being operational.

The lifecycle mutation fails closed if a mandatory owner-continuity decision is unavailable. This rule does not create a parallel deployment user/SSO-user authority model.

### Explicit exclusions

Tenancy does not own:

- Tenant Owner or Platform Owner authority;
- Identities, Organizations, Principals or relationships;
- Applications, roles, entitlements or SSO client registrations;
- AccessAssignment or access projections;
- Governance requests, approvals, policies, exceptions or findings;
- Credentials;
- connectors/provider observations;
- Audit evidence;
- arbitrary per-capability settings, branding, licensing, locale or timezone unless a future accepted owner is defined.

## Consequences

- ADR-0001's capability map is amended to include Tenancy as the ninth Layer-4 business capability.
- Platform no longer acts as the logical owner of Tenant business lifecycle merely because it hosts persistence/runtime mechanics.
- Existing tenant-aware capability semantics remain tenant-owned; no cross-capability ownership transfer occurs.
- Public Tenant APIs, exact persistence schema/migrations and detailed provisioning workflow remain implementation/interface work after this architectural decision.
- Formal BRD/FRD/SRS/DDD/SAD/Data/Security/Integration/RTM promotion occurs only at the next controlled checkpoint; this ADR intentionally may be newer than v0.8.
