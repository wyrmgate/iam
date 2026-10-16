# ADR-0049 Platform authority and shared application tenancy

- Status: Accepted
- Date: 2026-10-16
- Supersedes in part: [ADR-0046 Standalone tenant-owner bootstrap authority](0046-standalone-tenant-owner-bootstrap-authority.md)
- Preserves: [ADR-0044 Fresh-install bootstrap and first-party initial admin](0044-fresh-install-bootstrap-and-first-party-initial-admin.md)
- Preserves: [ADR-0045 Secured first-run web setup adapter](0045-secured-first-run-web-setup-adapter.md)
- Preserves: [ADR-0047 Sealed standalone tenant-owner recovery](0047-sealed-standalone-tenant-owner-recovery.md)
- Extends: [ADR-0048 Pre-release persistence rebaseline](0048-pre-release-persistence-baseline.md)

## Context

Wyrmgate previously had only tenant-scoped administrative authority. Fresh first-party bootstrap granted the finite `STANDALONE_TENANT_OWNER` profile inside the first Tenant. That model cannot represent deployment-wide ownership, cannot guarantee a viable owner for the deployment or an active Tenant, and cannot model one platform-owned Application offering admitted to multiple Tenants without duplicating the offering as unrelated tenant-local Applications.

Tenant remains the isolation boundary. A deployment-scoped resource therefore must not be represented as an ordinary tenant-owned row with `tenant_id = NULL`, and deployment authority must not be smuggled into the existing tenant-scoped `AdministrativeGrant` model by interpreting tenant `GLOBAL` scope as cross-tenant authority.

The existing SSO contract is useful: a globally unique `client_id` resolves server-side to exactly one authoritative Tenant. Shared application tenancy must retain that property; a browser or relying party never chooses tenant context by supplying an arbitrary tenant selector.

## Decision

### Deployment authority is explicit

Administration owns deployment-scoped `PLATFORM_OWNER` authority separately from tenant-scoped `AdministrativeGrant` authority.

A Platform Owner is a governed Identity anchored in one home Tenant and, for the first-party bootstrap path, the same eligible Principal used by installation bootstrap. The home Tenant locates and validates the governed subject; it does not scope the Platform Owner's authority to that Tenant.

Platform Owner authority is never inferred from `GLOBAL` tenant scope, role names, application access, provider groups, or transport claims.

The deployment invariant is:

```text
initialized deployment => at least one viable PLATFORM_OWNER
```

Viability requires current active Platform Owner authority and a currently administratively eligible governed Identity. First-party bootstrap also requires the recorded authentication Principal to remain eligible for that Identity.

Operations that would revoke, disable, decommission or otherwise make the final viable Platform Owner non-viable must fail atomically. The owning capability performs the mutation; cross-capability checks use semantic queries rather than direct table mutation.

### Tenant owner is the tenant authority root

`TENANT_OWNER` is the finite strongest ordinary authority inside one Tenant. The existing reviewed ADR-0046 permission set becomes the `TENANT_OWNER` profile; `STANDALONE_TENANT_OWNER` remains a compatibility name for historical code/data and must not be interpreted as deployment authority.

The tenant invariant is:

```text
ACTIVE Tenant => at least one viable TENANT_OWNER
```

A viable Tenant Owner has current active tenant-owner authority and a currently administratively eligible governed Identity in that same Tenant. Mutations that would remove the final viable Tenant Owner fail atomically.

Platform Owner and Tenant Owner are distinct. Bootstrap may establish both on the same Identity, but neither is derived from the other.

### Fresh installation establishes both authority layers

The ADR-0044 one-shot bootstrap remains deployment-scoped and resumable. Its Administration step now establishes, in one Administration transaction:

1. the first Tenant's finite `TENANT_OWNER` authority; and
2. the initial deployment-scoped `PLATFORM_OWNER` authority anchored to the same bootstrap Identity and Principal.

Installation verification requires both authorities before the bootstrap plan may advance to `SEALED`. Retries with the same durable bootstrap lineage are idempotent.

The web setup adapter remains only an adapter over that bootstrap operation. Its completion copy and API semantics describe both deployment ownership and first-Tenant ownership; it does not introduce a second authority path.

### Existing installations are not silently elevated

Flyway V11 creates schema only. It does not create Platform Owner rows, rewrite tenant grants, or infer owners from existing role names.

An installation that was already initialized before ADR-0049 requires an explicit, auditable ownership-adoption command before platform-scoped administration is enabled. That command must be one-time/idempotent, must prove a qualified existing governed administrator and installation lineage, and must fail closed on ambiguous/custom state.

ADR-0047 remains a narrowly fenced compatibility recovery for the historical tenant-owner permission expansion. It is not a Platform Owner adoption mechanism and is not widened into a generic privilege-escalation path.

### Application tenancy is explicit

Catalog recognizes three semantic application-tenancy modes:

- `TENANT_LOCAL` — the existing tenant-owned `Application`; its targets, entitlements, roles and SSO registrations remain tenant-isolated.
- `PLATFORM_SHARED` — one platform-owned application offering that may be admitted to multiple Tenants through explicit tenant bindings.
- `PLATFORM_INTERNAL` — a platform-owned first-party/internal offering. Tenant-facing use, when present, still occurs through an explicit tenant binding rather than nullable tenant ownership.

Existing `catalog.application` remains tenant-owned and non-null `tenant_id` is preserved. Platform offerings use separate Catalog-owned aggregates/tables.

### Shared-app admission is explicit and fail closed

A `PLATFORM_SHARED` offering has one admission mode:

- `ALL_TENANTS`;
- `SELECTED_TENANTS`; or
- `GOVERNED_POLICY`.

`SELECTED_TENANTS` uses explicit Catalog-owned admission rows. `GOVERNED_POLICY` stores a typed stable policy reference; it does not store arbitrary policy JSON. If mandatory policy evaluation is unavailable, new privilege/admission fails closed.

A tenant binding is authoritative Catalog state linking one platform offering to one tenant-local Application. Binding does not merge Tenant state. Identity, access, governance policy, approvals, targets, entitlements and SSO routing remain tenant-scoped unless a later accepted decision explicitly introduces another platform-owned aggregate.

A platform offering may not directly mutate or own tenant-local access state. Catalog creates/changes its own offering and binding state; other capabilities collaborate semantically.

### SSO routing remains tenant-authoritative

SSO registration remains tenant-local. A shared physical SaaS application can therefore have multiple Wyrmgate SSO registrations, one per admitted Tenant binding. Each globally unique `client_id` continues to resolve server-side to exactly one Tenant. No browser-controlled tenant header, query parameter or claim becomes authoritative tenant routing.

## Persistence

V11 introduces deployment-scoped Administration ownership state and Catalog platform-offering/admission/binding state. These rows are intentionally not ordinary tenant-owned rows. Tenant references appearing in deployment/platform-owned rows are typed subject/admission/binding references, not ownership columns.

The migration is schema-only and contains no privilege grant or inferred data migration.

## Consequences

- Fresh bootstrap produces a real deployment owner and a first-Tenant owner before sealing.
- Historical `STANDALONE_TENANT_OWNER` no longer means highest deployment authority.
- Platform and tenant survivability rules become explicit invariants instead of operator convention.
- Shared SaaS offerings no longer require unrelated duplicate platform definitions while tenant governance remains isolated.
- Existing tenant-local Application/Target/Entitlement and SSO contracts stay valid.
- Existing initialized installations require deliberate Platform Owner adoption; startup/Flyway never grants it.
- Platform-scoped APIs must authorize against deployment authority, not tenant `GLOBAL` grants.
- Formal v0.8 specifications remain the controlled baseline; this accepted amendment is folded into the next meaningful formal revision.

## Rejected alternatives

### Treat one tenant's GLOBAL administrative grant as platform authority

Rejected. `GLOBAL` is global only inside the grant's Tenant and reinterpreting it would violate existing tenant isolation semantics.

### Make deployment rows global by setting tenant_id to NULL

Rejected. Nullable tenancy would blur ownership and contradict the accepted physical tenancy model. Platform-owned aggregates are explicit.

### Automatically promote the bootstrap/current tenant owner during V11

Rejected. A schema migration must not silently increase authority on an initialized deployment.

### Share tenant-owned Application rows directly across tenants

Rejected. A tenant-owned aggregate cannot acquire cross-tenant ownership without destroying the isolation model. A platform offering plus tenant bindings preserves both reuse and isolation.

### Use arbitrary JSON for sharing policy

Rejected. Admission is typed: all tenants, explicit selected tenants, or a stable governed-policy reference.
