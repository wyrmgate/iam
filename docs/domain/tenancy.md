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

## Current implementation slice

The first backend slice materializes the accepted ownership boundary without exposing an unsafe partial lifecycle API:

- V12 moves the stable Tenant table object from `platform.tenant` to `tenancy.tenant` and adds the canonical lifecycle constraint.
- Existing pre-V12 Tenant rows migrate to `ACTIVE` because they already represented operating isolation realms.
- Fresh installation creates the first Tenant as `PROVISIONING`; after first-party Administration bootstrap establishes viable Tenant/Platform Owner authority, Tenancy performs the guarded `PROVISIONING -> ACTIVE` transition before installation verification/seal.
- Tenant transition mechanics use the shared framework-neutral lifecycle kernel, while Tenancy retains the state/action definitions, optimistic revision and business mutation.
- Deployment/current-Tenant read composition consumes a Tenancy-owned query. The existing deployment inspection route does not transfer Tenant ownership back to Platform.
- Suspension/decommissioning are represented in the domain command model but are not yet exposed as public operations until the downstream privilege-use/increase gating and durable decommissioning orchestration are implemented coherently.
- `platform.tenant.TenantContext` remains a trusted runtime scope value; it is not the authoritative Tenant aggregate.

## Deferred implementation details

ADR-0051 does not by itself fix:

- public Tenant endpoint names/payloads;
- physical schema/migration numbering;
- concrete worker/package/module topology;
- commercial subscription/licensing semantics;
- generic branding/locale/timezone/settings ownership.
