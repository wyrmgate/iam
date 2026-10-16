# Platform Authority and Application Tenancy

This living architecture note applies ADR-0049 to the current implementation. It supplements the controlled v0.8 formal specifications until the next controlled revision.

## Authority layers

Wyrmgate has two distinct administrative ownership layers:

| Authority | Owner | Scope | Required continuity |
| --- | --- | --- | --- |
| `PLATFORM_OWNER` | Administration | deployment | at least one viable owner on an initialized deployment |
| `TENANT_OWNER` | Administration | exactly one Tenant | at least one viable owner for every ACTIVE Tenant |

A tenant-scoped `AdministrativeGrant` with `GLOBAL` scope is still global **inside that Tenant only**. It never implies deployment authority.

`PLATFORM_OWNER` is stored as an explicit deployment-owned Administration aggregate. `actorTenantId` locates its governed Identity and is not an authority scope. `TENANT_OWNER` is an explicit Administration designation over a same-Tenant direct `AdministrativeGrant` whose finite permission set is the reviewed tenant-owner authority.

The historical `STANDALONE_TENANT_OWNER` permission-set name remains a compatibility artifact for ADR-0046/ADR-0047 code and persisted lineage. It must not be interpreted as deployment authority.

## Viability

A Platform Owner is viable when:

- its Platform Owner authority is ACTIVE and temporally effective;
- its governed Identity is administratively eligible in its home Tenant; and
- when the owner has a first-party Principal anchor, that Principal is still authentication-eligible for that Identity.

A Tenant Owner is viable when:

- its Tenant Owner designation is ACTIVE;
- its underlying direct AdministrativeGrant is ACTIVE and temporally effective; and
- its governed Identity is administratively eligible in that Tenant.

Administration owns authority state. Identity continues to own Identity and Principal lifecycle. Identity lifecycle commands therefore call semantic Administration continuity guards before making the final owner non-viable; they never mutate Administration tables directly.

Database guards additionally prevent direct loss of the final currently effective owner authority/grant. They are defense in depth, not a replacement for cross-capability viability checks.

## Fresh installation

The first-run wizard remains one deployment-scoped, resumable bootstrap operation:

1. create the first Tenant;
2. create the governed administrator Identity;
3. create/register the Wyrmgate Console application/target and SSO client;
4. create the first-party Principal;
5. enroll the password Credential;
6. in one Administration transaction create the finite first-Tenant grant, designate it `TENANT_OWNER`, and create deployment-scoped `PLATFORM_OWNER` authority for the same Identity/Principal;
7. verify Identity, Principal, Credential, tenant-owner authority and platform-owner authority from current authoritative state;
8. seal bootstrap permanently.

Retrying the same durable installation plan is idempotent. A pre-ADR-0049 bootstrap marker is never silently upgraded into Platform Owner authority by retry or Flyway.

## Existing initialized installations

V11 is intentionally schema-only. It does not infer or grant Platform Owner authority from an existing tenant-global grant, bootstrap marker, role name or operator input.

An already initialized installation must use a dedicated audited ownership-adoption operation before platform-scoped administration is enabled. ADR-0047 sealed standalone-owner recovery remains limited to its historical tenant-owner compatibility case and is not a Platform Owner adoption path.

## Application tenancy

`Application` remains the tenant-owned Catalog aggregate for `TENANT_LOCAL` applications. Existing ApplicationTarget, Entitlement, Role and SSO registration state stays tenant-owned.

Platform-owned definitions use `PlatformApplicationOffering` with one of:

- `PLATFORM_SHARED` — one platform definition may be admitted to multiple Tenants;
- `PLATFORM_INTERNAL` — first-party/platform-internal definition, with tenant-facing use still expressed through explicit Tenant bindings.

A platform offering is never represented by `catalog.application.tenant_id = NULL`.

## Tenant admission

Each platform offering has exactly one admission mode:

- `ALL_TENANTS`;
- `SELECTED_TENANTS`, backed by explicit Catalog admission rows; or
- `GOVERNED_POLICY`, backed by a stable typed policy reference and semantic governance evaluation.

Mandatory governed-policy admission fails closed when evaluation is unavailable.

A `PlatformApplicationBinding` links one platform offering to one existing tenant-local `Application`. It does not share tenant governance state. Identity, access assignment, approval, policy, target, entitlement and SSO state remain isolated per Tenant.

## SSO routing

Shared application tenancy does not change the trusted routing rule. Every SSO `client_id` resolves server-side to exactly one Tenant. A physical SaaS application shared by several Tenants therefore has tenant-local SSO registrations/bindings as required; no browser-controlled tenant header, query parameter or untrusted claim becomes authoritative routing context.

## Persistence boundary

The following V11 tables are explicitly platform/deployment owned and therefore are exceptions to the older blanket assumption that every capability table is tenant-owned:

- `administration.platform_owner_authority`;
- `catalog.platform_application_offering`;
- `catalog.platform_application_tenant_admission`;
- `catalog.platform_application_binding`.

Tenant IDs inside admission/binding rows and `actor_tenant_id` on Platform Owner authority are typed references, not ownership columns. All ordinary tenant-owned capability tables continue to require non-null tenant ownership and same-tenant composite foreign keys where applicable.
