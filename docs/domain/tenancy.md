# Tenancy Capability

## Purpose

This is the living implementation-facing contract for ADR-0051. Tenancy answers one business question: what Tenant isolation realm exists, and is that realm currently permitted to operate?

## Ownership

Tenancy owns the deployment-owned `Tenant` aggregate only. It owns Tenant identity, canonical display metadata, lifecycle, revision and lifecycle timestamps.

Tenancy does not own tenant-local Identity, Organization, Catalog, Access, Governance, Credential, Integration, Administration or Audit state. It also does not own `TENANT_OWNER` or `PLATFORM_OWNER` authority; those remain Administration-owned.

## Tenant lifecycle

```text
PROVISIONING -> ACTIVE <-> SUSPENDED -> DECOMMISSIONING -> DECOMMISSIONED
```

`DECOMMISSIONED` is terminal.

Activation requires a viable `TENANT_OWNER` through an Administration semantic guard. Suspension/decommissioning transitions also run required owner-continuity checks before commit.

## Operation scope

Application/runtime work is dispatched under trusted server-derived scope:

```text
TENANT(TenantId)
DEPLOYMENT
```

Browser headers, route values, UI selection or untrusted claims never establish authoritative scope by themselves.

Every authoritative aggregate is explicitly tenant-owned or deployment-owned. Deployment ownership is never modeled as a nullable Tenant owner on an ordinary tenant-owned aggregate.

## Isolation

Tenant is the hard isolation/customer realm. Organization is business structure inside a Tenant.

Ordinary tenant-owned references and mutations cannot cross Tenant boundaries. Any legitimate cross-Tenant construct must be explicit, typed, deployment-owned and separately authorized.

## Suspension and shutdown

Suspension/decommissioning blocks ordinary privilege increase/use according to owner contracts but never blocks security-reducing work such as revocation, credential disabling, deprovisioning, investigation or Audit evidence capture.

Tenant decommissioning is a Tenancy-owned durable orchestration with resumable checkpoints. It invokes typed commands on owning capabilities. It never mutates foreign repositories/tables and never performs provider calls inside its authoritative transaction.

Downstream provider failure does not roll back a valid Tenant lifecycle decision.

## Owner continuity

Administration supplies Tenant Owner and Platform Owner viability through typed semantic guards.

A lifecycle transition must fail closed if it would remove the final viable Tenant Owner required for activation/operation or make the final viable Platform Owner unusable where that owner's current authentication path depends on the affected Tenant.

This check never creates a parallel deployment User/SsoUser model.

## Retention

`DECOMMISSIONED` is business lifecycle, not physical deletion. Any later erasure must be explicit and respect retention, legal hold, immutable Audit evidence and historical-reference requirements.

## Deferred implementation details

ADR-0051 does not by itself fix:

- public Tenant endpoint names/payloads;
- physical schema/migration numbering;
- concrete worker/package/module topology;
- commercial subscription/licensing semantics;
- generic branding/locale/timezone/settings ownership.
