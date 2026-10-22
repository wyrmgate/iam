# Platform Authority and Application Tenancy

This living architecture note applies ADR-0049 and ADR-0051 to the current architecture. It supplements the controlled v0.8 formal specifications until the next controlled revision.

## Authority layers

Wyrmgate has two distinct administrative ownership layers:

| Authority | Owner | Scope | Required continuity |
| --- | --- | --- | --- |
| `PLATFORM_OWNER` | Administration | deployment | at least one viable owner on an initialized deployment |
| `TENANT_OWNER` | Administration | exactly one Tenant | at least one viable owner for every ACTIVE Tenant |

A tenant-scoped `AdministrativeGrant` with `GLOBAL` scope is still global **inside that Tenant only**. It never implies deployment authority.

`PLATFORM_OWNER` is stored as an explicit deployment-owned Administration aggregate. `actorTenantId` locates its governed Identity and is not an authority scope. `TENANT_OWNER` is an explicit Administration designation over a same-Tenant direct `AdministrativeGrant` whose finite permission set is the reviewed tenant-owner authority.

The historical `STANDALONE_TENANT_OWNER` permission-set name remains a compatibility artifact for ADR-0046/ADR-0047 code and persisted lineage. It must not be interpreted as deployment authority.

## Tenant ownership after ADR-0051

Tenancy owns canonical Tenant identity, display metadata and lifecycle. `Tenant` is itself deployment-owned authoritative state. Platform may continue to host the current physical `platform.tenant` table and technical query implementation during migration, but table/schema location no longer defines logical ownership.

Tenant lifecycle is `PROVISIONING -> ACTIVE`, `ACTIVE <-> SUSPENDED`, and `ACTIVE|SUSPENDED -> DECOMMISSIONING -> DECOMMISSIONED`. Tenant lifecycle is an operational/security gate and does not transfer Identity, Access, Credential, Administration, Catalog, Governance, Integration or Audit authority into Tenancy.

Trusted operation scope is server-derived as either tenant-scoped or deployment-scoped. Browser routes, headers, query parameters and untrusted claims never establish authoritative Tenant context.

## Viability

A Platform Owner is viable when:

- its Platform Owner authority is ACTIVE and temporally effective;
- its governed Identity is administratively eligible in its home Tenant;
- the accepted authentication path for that actor remains usable under the home Tenant lifecycle; and
- when the owner has a first-party Principal anchor, that Principal is still authentication-eligible for that Identity.

A Tenant Owner is viable when:

- its Tenant Owner designation is ACTIVE;
- its underlying direct AdministrativeGrant is ACTIVE and temporally effective; and
- its governed Identity is administratively eligible in that Tenant.

Administration owns authority state. Identity continues to own Identity and Principal lifecycle. Tenancy owns Tenant lifecycle. Mutations in any of those owners use semantic Administration continuity guards before making the final viable owner non-viable; they never mutate another capability's tables directly.

Database guards additionally prevent direct loss of the final currently effective owner authority/grant. They are defense in depth, not a replacement for cross-capability viability checks.

A Tenant suspension/decommissioning operation must fail atomically if it would make the final viable Platform Owner unusable. Likewise, an ACTIVE Tenant must retain at least one viable Tenant Owner.

## Fresh installation

The first-run wizard remains one deployment-scoped, resumable bootstrap operation:

1. create the first Tenancy-owned Tenant;
2. create the governed administrator Identity;
3. create/register the Wyrmgate Console application/target and SSO client;
4. create the first-party Principal;
5. enroll the password Credential;
6. in one Administration transaction create the finite first-Tenant grant, designate it `TENANT_OWNER`, and create deployment-scoped `PLATFORM_OWNER` authority for the same Identity/Principal;
7. verify Tenant, Identity, Principal, Credential, tenant-owner authority and platform-owner authority from current authoritative state;
8. seal bootstrap permanently.

Retrying the same durable installation plan is idempotent. A pre-ADR-0049 bootstrap marker is never silently upgraded into Platform Owner authority by retry or Flyway.

## Existing initialized installations

V11 is intentionally schema-only. It does not infer or grant Platform Owner authority from an existing tenant-global grant, bootstrap marker, role name or operator input.

An already initialized installation must use a dedicated audited ownership-adoption operation before platform-scoped administration is enabled. ADR-0047 sealed standalone-owner recovery remains limited to its historical tenant-owner compatibility case and is not a Platform Owner adoption path.

ADR-0051 does not require an immediate physical move of `platform.tenant`; existing Tenant persistence/query code is migration/refactoring work so long as semantic ownership is Tenancy and no competing Platform business lifecycle is introduced.

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

A `PlatformApplicationBinding` links one platform offering to one existing tenant-local Application. It does not share tenant governance state. Identity, access assignment, approval, policy, target, entitlement and SSO state remain isolated per Tenant.

## SSO routing

Shared application tenancy does not change the trusted routing rule. Every SSO `client_id` resolves server-side to exactly one Tenant. A physical SaaS application shared by several Tenants therefore has tenant-local SSO registrations/bindings as required; no browser-controlled tenant header, query parameter or untrusted claim becomes authoritative routing context.

ADR-0051 adds a current Tenant operational-eligibility gate after trusted routing. A resolved Tenant that is not operationally eligible cannot be used to establish ordinary new tenant authentication/session/token authority merely because client routing succeeds.

## Console tenant context

The authenticated Console exposes `/tenant` as a current-Tenant management and navigation surface. It is not browser-side authority and does not transfer Tenant ownership to Administration or Platform.

- The Tenant identifier and governed actor come from server-derived current administrative-authority/routing context; the browser does not supply an authoritative Tenant selector.
- Canonical Tenant display metadata (`id`, `displayName`, `revision`, `createdAt`, `updatedAt`) is read through a Tenancy semantic query, even if the current adapter still reads the physical `platform.tenant` table during migration.
- `/api/v1/console/iam-operations` remains a read-only Console composition endpoint over capability-owned semantic queries. It may combine Tenancy metadata with Identity, Catalog, Administration, Integration and Audit summaries without taking ownership of them.
- Source operational presentation keeps Identity SourceSystem inventory distinct from Integration connector/reconciliation observation. A SourceSystem is not a Connector, and connector/reconciliation failure does not rewrite Identity-owned source authority.
- Tenant Owner presentation keeps effective Administration authority distinct from Identity administrative eligibility and Tenancy lifecycle.
- Per-application access aggregation remains unavailable until Access and Catalog expose an exact bounded semantic aggregation contract. The Console must not approximate it from truncated collections.
- Tenant management actions navigate to owning capability workspaces. Tenancy owns Tenant lifecycle; Identity owns governed subjects/Principals; Catalog Applications; Access business access; Governance approval/review/policy; Integration provider execution; Administration control-plane authority; Audit evidence.
- The Console does not model generic `UserTenantMembership` or `UserApplication` authority. Tenant ownership of Identity expresses normal subject tenancy, while individual application access remains an `AccessAssignment` to a Role or Entitlement.
- Platform-shared/internal offering definitions remain Catalog deployment-owned state. Tenant-facing application use remains represented by explicit Catalog binding to a tenant-local Application.

Operation-time server authorization and Tenant operational eligibility remain final for every linked workspace and command.

## Platform Tenant inventory and inspection

The authenticated Console exposes `/platform/tenants` as a deployment-scoped, read-only inventory surface. It is distinct from `/tenant` and is visible only when the server-derived current administrative-authority projection reports the actor as a current viable `PLATFORM_OWNER`.

- Navigation is not itself authority. Every request re-evaluates viable `PLATFORM_OWNER` authority.
- Each selected Tenant is addressable as a deployment-scoped resource; the requested identifier never becomes authoritative current-Tenant context.
- Tenant inventory/detail resolves through Tenancy semantic queries. The current physical Platform adapter may remain during migration but is not the logical owner.
- Collection reads are bounded and deterministically ordered, with integrity-protected continuation where exposed.
- Opening, deep-linking to or paging platform Tenant inventory does not change current Tenant context.
- Platform Owner inspection does not automatically expose tenant-owned Identity, Principal, Access, Governance, Credential, Integration or Audit data. Any such cross-Tenant inspection requires an explicit semantic query and authorization contract from the owning capability.
- `PLATFORM_OWNER` remains deployment authority owned by Administration. The surface does not create a generic `SUPER_ADMIN` role.

## Persistence boundary

ADR-0049 V11 deployment-owned tables remain deployment-owned:

- `administration.platform_owner_authority`;
- `catalog.platform_application_offering`;
- `catalog.platform_application_tenant_admission`;
- `catalog.platform_application_binding`.

ADR-0051 additionally makes the existing canonical Tenant row Tenancy-owned authoritative state even while it remains physically stored as `platform.tenant` during migration.

Tenant IDs inside admission/binding rows and `actor_tenant_id` on Platform Owner authority are typed references, not ownership columns. All ordinary tenant-owned capability tables continue to require non-null tenant ownership and same-tenant reference protection where applicable.
