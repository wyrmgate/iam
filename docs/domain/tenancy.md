# Tenancy Capability and Tenant Lifecycle

## Purpose

This document is the living implementation-facing contract for ADR-0051. It defines Tenant ownership, lifecycle, operation-scope semantics and cross-capability shutdown boundaries without fixing a framework, module, service or physical schema layout.

## Ownership of truth

Tenancy owns the canonical `Tenant` aggregate and answers:

> What Tenant isolation realm exists, and is that realm currently permitted to operate?

`Tenant` is `DEPLOYMENT_OWNED` authoritative state. It is not Organization, a subscription record, a generic settings bag, or a container for other capabilities' policy.

Minimum canonical shape:

- stable opaque `tenantId`;
- mutable `displayName`;
- `lifecycleState`;
- positive optimistic `revision`;
- `createdAt` / `updatedAt`.

Administration continues to own `TENANT_OWNER` and `PLATFORM_OWNER`. Identity owns Organization and governed subjects. Catalog, Access, Governance, Credential, Integration and Audit retain their existing authority.

## Ownership realm and operation scope

Authoritative resources are explicitly classified as:

```text
TENANT_OWNED(TenantId)
DEPLOYMENT_OWNED
```

Trusted application/runtime operation scope is likewise explicit:

```text
TENANT(TenantId)
DEPLOYMENT
```

The two concepts are related but not interchangeable. A deployment-scoped command may reference a Tenant without becoming tenant-scoped or gaining access to tenant-owned capability state.

Operation scope is server-derived. Browser route state, query parameters, arbitrary headers, untrusted claims and nullable tenant IDs are not authority.

## Tenant lifecycle

```text
PROVISIONING -> ACTIVE
ACTIVE -> SUSPENDED
SUSPENDED -> ACTIVE
ACTIVE -> DECOMMISSIONING
SUSPENDED -> DECOMMISSIONING
DECOMMISSIONING -> DECOMMISSIONED
```

`DECOMMISSIONED` is terminal.

Lifecycle is Tenancy authority and is separate from Identity lifecycle, AccessAssignment lifecycle/validity, Administration authority state, Credential lifecycle, provider fulfillment/observation and Audit retention.

## Activation and owner continuity

`ACTIVE` requires at least one viable Administration-owned `TENANT_OWNER`.

Tenancy obtains viability through an Administration semantic guard. It does not create or mutate Administration authority.

Tenant lifecycle changes also preserve ADR-0049 deployment owner continuity. A suspension/decommission transition that would make the final viable `PLATFORM_OWNER` unusable through the accepted authentication path fails atomically.

## PROVISIONING

`PROVISIONING` permits only accepted bootstrap/setup, repair, security reduction and evidence work needed to establish a usable Tenant.

Fresh installation remains deployment-scoped durable orchestration. Tenancy owns the Tenant state; Identity, Catalog, Credential and Administration still own the bootstrap-created resources within their capabilities.

## SUSPENDED

`SUSPENDED` is a reversible operational gate, not a request to rewrite every tenant-owned aggregate.

While suspended:

- ordinary new privilege increase fails closed;
- ordinary first-party tenant authentication/session establishment and new token issuance fail closed;
- Wyrmgate-side current sessions revalidate Tenant operational eligibility and become unusable;
- security reduction, revocation, credential disabling, evidence capture, investigation, repair and provider-removal work may continue when separately authorized;
- historical/authoritative foreign-capability state is not rewritten merely to mirror Tenant lifecycle.

Current ADR-0042 self-contained relying-party tokens are not universally revocable by Tenant suspension because introspection/revocation is not yet an accepted protocol baseline. Suspension prevents new issuance/Wyrmgate-side use; stronger immediate RP token invalidation requires a later accepted contract.

## DECOMMISSIONING

`DECOMMISSIONING` is the durable shutdown phase. Tenancy owns orchestration/checkpoints and Platform supplies only timers, leases, retries and delivery mechanics.

Shutdown collaboration uses typed semantic commands/queries. Each capability mutates only its own state.

During decommissioning:

- ordinary authentication/use and new privilege increase remain denied;
- Access converges tenant-governed desired provider privilege to absence;
- Integration refuses new privilege-increasing provider work and continues removal/disable work;
- Administration cannot use ordinary tenant authority to create new privilege;
- Governance evidence/approval cannot bypass the Tenant lifecycle gate;
- Audit/evidence and required retention/legal-hold work remain available.

No distributed rollback spans capabilities or external providers.

## DECOMMISSIONED completion

Terminal completion is based on Wyrmgate authoritative closure, not perfect provider cleanup.

Before `DECOMMISSIONED`, Tenancy revalidates the current shutdown revision and obtains current semantic closure results that show:

- no tenant-governed desired technical privilege remains present for provider realization;
- no claimable privilege-increasing Integration work remains for the Tenant;
- ordinary tenant-scoped Administration authority is non-usable under the lifecycle gate;
- Authentication/IdP will not establish/continue ordinary Wyrmgate Tenant sessions or issue new tenant tokens;
- required shutdown Audit evidence is recorded or is in the accepted retry/remediation path.

Provider residue may remain after terminal Tenant closure. It is technical failure/observation subject to retry/manual remediation and evidence; it never reactivates the Tenant.

## Historical data and erasure

`DECOMMISSIONED` is not physical deletion. Tenant-owned historical state and evidence remain governed by their owning capability's retention rules.

A future erasure operation must be explicit and preserve legal-hold, archive/integrity and required historical evidence. There is no cross-capability business cascade delete.

## Cross-Tenant rule

Ordinary tenant-owned relationships do not cross Tenants.

Any legitimate cross-Tenant concept must be explicit, typed, deployment-owned and separately authorized. ADR-0049 `PlatformApplicationOffering` / admission / binding is an accepted example.

`PLATFORM_OWNER` does not automatically grant unrestricted tenant-owned Identity, Access, Governance, Credential, Integration or Audit data access.

## Persistence migration boundary

The existing physical `platform.tenant` table may remain in the Platform schema while implementation catches up. Physical location does not define logical ownership.

After ADR-0051, code that treats Platform as the business owner of Tenant lifecycle/metadata is migration/refactoring work. Any later physical move must preserve stable IDs, references and revisions and must not reclassify ADR-0049 deployment-owned Administration/Catalog state.

## Deferred

- public Tenancy API names/payloads;
- physical table/module migration;
- subscription/licensing/commercial plans;
- custom domains/DNS;
- arbitrary tenant settings;
- generic cross-Tenant support impersonation;
- provider-specific tenant provisioning/deletion;
- physical Tenant erasure;
- stronger relying-party token revocation/introspection semantics.
