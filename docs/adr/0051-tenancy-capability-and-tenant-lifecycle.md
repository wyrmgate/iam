# ADR-0051: Tenancy capability and Tenant lifecycle

Status: Accepted

- Date: 2026-10-22
- Supersedes in part: [ADR-0001 Framework-neutral capability architecture](0001-framework-neutral-capability-architecture.md)
- Extends: [ADR-0005 Administrative authorization and tenant isolation](0005-administrative-authorization-and-tenant-isolation.md)
- Extends: [ADR-0009 Domain-owned workflow and durable orchestration](0009-domain-owned-workflow-and-durable-orchestration.md)
- Preserves: [ADR-0042 First-party identity provider and single sign-on](0042-first-party-identity-provider-and-sso.md)
- Extends and preserves: [ADR-0049 Platform authority and shared application tenancy](0049-platform-authority-and-shared-application-tenancy.md)

## Context

Tenant is already Wyrmgate's hard customer/isolation boundary, but the accepted capability map in ADR-0001 treats Platform as the technical owner of the existing Tenant record. The current physical baseline persists `platform.tenant`, and the current Console reads canonical Tenant metadata through a Platform-owned query. That arrangement was sufficient while Tenant had little domain meaning beyond isolation identity.

ADR-0049 changed the surrounding architecture. Wyrmgate now has explicit deployment-owned `PLATFORM_OWNER` authority, explicit tenant-scoped `TENANT_OWNER` authority, and deployment-owned Catalog application offerings that bind to tenant-local Applications. The platform/tenant distinction is therefore a first-class semantic boundary rather than merely a nullable/non-null storage convention.

The v3 Layer-4 reconciliation also requires explicit Tenant operational lifecycle. Wyrmgate needs to distinguish a Tenant that is being provisioned, active, administratively suspended, undergoing durable shutdown, or terminally decommissioned. Those states affect authentication, privilege increase, owner continuity, provider convergence and evidence retention. Treating that lifecycle as Platform scheduler/configuration state would give technical infrastructure business authority it should not own.

A Tenant must also remain distinct from `Organization`. Organization is tenant-local business structure; Tenant is the isolation/customer realm. Likewise, deployment-owned resources must not be represented as ordinary tenant-owned rows with `tenant_id = NULL`.

## Decision

### Tenancy becomes a canonical logical capability

The canonical logical capabilities are now:

- Tenancy;
- Identity;
- Catalog;
- Access;
- Governance;
- Credential;
- Integration;
- Administration;
- Audit;

with Platform remaining supporting technical infrastructure.

This supersedes ADR-0001 only in the capability list and the ownership of canonical Tenant business state. ADR-0001's framework-neutrality, ownership and semantic-contract rules remain unchanged.

Tenancy answers one domain question:

> What Tenant isolation realm exists, and is that realm currently permitted to operate?

Tenancy does not become a generic configuration/settings capability and does not absorb tenant-local policy, Identity, access, owner authority, application governance, credentials, provider execution or evidence.

### Tenant is a deployment-owned Tenancy aggregate

`Tenant` is Tenancy-owned authoritative state and is itself `DEPLOYMENT_OWNED`.

Its canonical minimum shape is:

- stable opaque immutable `tenantId`;
- mutable `displayName`;
- `lifecycleState`;
- positive optimistic `revision`;
- `createdAt` and `updatedAt` lifecycle metadata.

Tenant does not own `TENANT_OWNER` or `PLATFORM_OWNER`; both remain Administration authority. Tenant does not own Organization; Organization remains Identity-owned tenant-local business structure.

Capability-specific configuration remains with the capability that understands and enforces it. Tenant is not an arbitrary JSON/EAV settings bag.

### Ownership realm and trusted operation scope are explicit

Authoritative resources are classified explicitly as either:

```text
TENANT_OWNED(TenantId)
DEPLOYMENT_OWNED
```

This classification is semantic ownership, not a persistence superclass and not an instruction to add nullable tenant columns everywhere.

Application/runtime execution likewise uses trusted server-derived operation scope:

```text
TENANT(TenantId)
DEPLOYMENT
```

A deployment-scoped command may reference a Tenant as a subject, admission target, binding target or actor home-Tenant reference without switching into that Tenant or acquiring authority over its tenant-owned capability state.

A browser route, query parameter, header, untrusted token claim or nullable `tenant_id` never establishes authoritative operation scope.

### Tenant lifecycle is explicit

The Tenant lifecycle is:

```text
PROVISIONING -> ACTIVE
ACTIVE -> SUSPENDED
SUSPENDED -> ACTIVE
ACTIVE -> DECOMMISSIONING
SUSPENDED -> DECOMMISSIONING
DECOMMISSIONING -> DECOMMISSIONED
```

`DECOMMISSIONED` is terminal. There is no ordinary transition from `DECOMMISSIONED` back to an operational state.

Lifecycle is authoritative Tenancy business state and is separate from:

- tenant-owned Identity lifecycle;
- AccessAssignment lifecycle and temporal validity;
- Administration grant/elevation lifecycle;
- Credential lifecycle;
- technical provider fulfillment and observation;
- Audit/evidence retention.

Mutable Tenant lifecycle operations use optimistic revision semantics and causal idempotency where retry could duplicate a durable transition/process.

### Activation and Tenant Owner viability

A Tenant may become `ACTIVE` only when the current Administration semantic guard proves at least one viable `TENANT_OWNER` for that Tenant.

The accepted ADR-0049 invariant remains:

```text
ACTIVE Tenant => at least one viable TENANT_OWNER
```

Tenancy never creates, grants, revokes or mutates Tenant Owner authority. It asks Administration through a semantic viability query/guard.

`SUSPENDED` remains a recoverable state. Removing the final viable Tenant Owner while suspended is denied unless the Tenant is concurrently entering the explicit decommission path under authority that is permitted to complete shutdown. A suspension must not silently create an owner-recovery dead end.

### Platform Owner continuity includes usable actor context

`PLATFORM_OWNER` remains deployment-owned Administration authority and is not moved into Tenancy.

A Platform Owner's governed Identity remains tenant-owned in its home Tenant. A Platform Owner is viable only when the current authority and governed actor can actually participate in an accepted control-plane authentication path. When that path depends on the home Tenant being operational, the home Tenant lifecycle therefore participates in viability.

A Tenant lifecycle mutation that would make the final viable Platform Owner unusable must fail atomically. In the current architecture there is no parallel deployment `User`/`SsoUser` authority model that can be invented to bypass this invariant.

This extends ADR-0049's owner-continuity guard without reinterpreting tenant `GLOBAL` scope as deployment authority.

### PROVISIONING is bootstrap/setup state, not ordinary operation

`PROVISIONING` permits only explicitly accepted installation/bootstrap/setup work, security reduction, repair and evidence capture required to establish an operational Tenant.

Ordinary business access, ordinary tenant control-plane use and new tenant privilege increase are not authorized merely because the Tenant row exists.

Fresh-install bootstrap remains the ADR-0044/ADR-0049 deployment-scoped durable operation. It may create the first Tenant and establish the first Tenant Owner and Platform Owner through their owning capabilities; Tenancy ownership does not move those Administration mutations into the Tenant transaction.

### SUSPENDED is an operational gate, not mass foreign mutation

`SUSPENDED` is reversible Tenancy authority saying the Tenant is not currently permitted ordinary operation.

While suspended:

- new ordinary privilege increase fails closed;
- ordinary first-party tenant authentication/session establishment and new tenant token issuance fail closed;
- current Wyrmgate control-plane sessions revalidate Tenant operational eligibility and become unusable when the Tenant is not eligible;
- explicit security reduction, revocation, credential disabling, evidence capture, investigation, repair and provider-removal work remain permitted when separately authorized;
- existing tenant-owned aggregates are not automatically rewritten merely to mirror the Tenant state.

Suspension therefore does not mean Tenancy directly revokes every `AccessAssignment`, `AdministrativeGrant`, `Credential` or other foreign aggregate. Each owning capability retains its state and may apply its own accepted reduction semantics.

Already-issued self-contained OAuth/OIDC tokens at external relying parties cannot be universally recalled merely by changing Tenant state under the current ADR-0042 protocol baseline, because token introspection/revocation is not part of that baseline. Suspension prevents new issuance and Wyrmgate-side use, while stronger immediate relying-party token revocation requires a separately accepted protocol contract. This limitation must not be hidden by claiming stronger runtime revocation than the current protocol supports.

### DECOMMISSIONING owns durable shutdown orchestration, not foreign state

Entering `DECOMMISSIONING` is the authoritative Tenant shutdown decision.

Tenancy owns durable, resumable decommission orchestration/checkpoint state. Generic Platform workers may provide timers, leases, retries and delivery mechanics, but they do not own shutdown meaning.

The decommission process collaborates through typed semantic commands/queries. Identity, Catalog, Access, Governance, Credential, Integration, Administration and Audit continue to mutate only their own authoritative state. No distributed transaction, cross-capability repository mutation or cascade delete is introduced.

While decommissioning:

- ordinary authentication/use and new privilege increase remain denied;
- security-reducing and provider-removal work remains executable;
- Access must converge current desired technical state to absence for tenant-governed access that can create provider privilege;
- Integration must not claim new privilege-increasing provider work for the Tenant and may continue revoke/disable/removal work;
- Administration operation-time authorization must not permit ordinary tenant-scoped control-plane authority to create new privilege;
- Governance approval/evidence cannot bypass the Tenant lifecycle gate to create new authority;
- Audit/evidence capture and required retention/legal-hold processing remain available.

### Terminal completion is based on Wyrmgate authoritative closure, not provider perfection

`DECOMMISSIONED` may be committed only after the durable decommission process has current acknowledgements that Wyrmgate's internal authority gates are closed for the Tenant:

1. Tenancy still records the Tenant as `DECOMMISSIONING` at the expected revision and no newer lifecycle decision supersedes the shutdown;
2. Access reports no tenant-governed desired technical privilege remaining `PRESENT` for provider realization;
3. Integration reports no claimable privilege-increasing work for the Tenant under current desired state;
4. Administration reports that ordinary tenant-scoped administrative authority is non-usable under the Tenant lifecycle gate;
5. Authentication/IdP current-state checks will not establish or continue ordinary Wyrmgate tenant sessions or issue new tenant tokens;
6. required Audit evidence for the shutdown decision/process has been recorded or placed into the accepted retry/remediation path without rewriting the valid Tenant decision.

These are semantic closure conditions, not permission for Tenancy to update foreign rows directly.

Successful provider cleanup is **not** a prerequisite for `DECOMMISSIONED`. External/provider failure after Wyrmgate authority has been closed is separately observable technical residue. It remains eligible for retry/manual remediation and evidence, and it never rolls the Tenant back to an operational lifecycle state.

### Historical state and evidence remain truthful

`DECOMMISSIONED` is a terminal business lifecycle state, not a database-delete instruction.

Tenant-owned historical Identities, Catalog objects, assignments, Governance decisions, credentials, Integration evidence and Audit evidence are retained according to their owning capability's retention semantics. No cross-capability business cascade delete is allowed.

Any later privacy/retention erasure is a separate explicitly governed operation and must respect Audit legal hold, archive/integrity and other required historical evidence.

### Cross-Tenant references remain exceptional and explicit

Ordinary tenant-owned aggregates and relationships may not cross Tenant boundaries.

A legitimate cross-Tenant concept must be explicit, typed, deployment-owned and separately authorized. ADR-0049 `PlatformApplicationOffering`/admission/binding is an accepted example. Cross-Tenant `AccessAssignment`, generic `UserTenantMembership` and implicit Platform Owner access to tenant-owned capability data are not introduced by this ADR.

Platform Owner authority may authorize deployment-scoped Tenant lifecycle operations, but it does not automatically authorize unrestricted tenant-owned Identity, Access, Governance, Credential, Integration or Audit inspection/mutation. Those capabilities require their own explicit semantic contracts where cross-Tenant access is justified.

### Interface and persistence boundary

This ADR defines ownership, lifecycle and collaboration semantics. It does not require a public Tenancy API in the same change.

Implementation-facing contracts should provide narrow semantic operations/queries such as current Tenant lifecycle/operational eligibility, activation/suspension/reactivation/decommission commands, and owner-continuity guards. Public endpoints, if introduced, must expose those semantic operations rather than generic CRUD/status mutation.

The existing `platform.tenant` physical table may remain in the `platform` schema during migration. Physical schema location does not define logical capability ownership. After this ADR, the row is Tenancy-owned authoritative business state even if the implementation has not yet moved the table or module. Existing Platform-owned Tenant query code is migration/refactoring work and must not be treated as a competing authority definition.

Any physical migration must preserve stable Tenant IDs, current revisions and references and must not rewrite deployment-owned ADR-0049 authority/application state into tenant ownership.

### Explicit non-goals

This ADR does not define:

- subscription/billing/plan semantics;
- custom domains, DNS ownership or tenant vanity identifiers;
- arbitrary tenant settings/configuration JSON;
- Organization hierarchy or business membership;
- generic cross-Tenant support impersonation;
- a deployment-wide user/account model separate from Identity/Principal;
- cross-Tenant business access;
- provider-specific tenant provisioning/deletion protocols;
- physical erasure of a decommissioned Tenant;
- immediate revocation of already-issued self-contained relying-party tokens without a future accepted token-revocation/introspection contract.

## Consequences

- Tenancy becomes the ninth canonical logical domain capability while Platform remains supporting infrastructure.
- Canonical Tenant lifecycle and isolation ownership no longer depend on where the current table happens to be stored.
- Tenant lifecycle becomes a typed security/operational gate shared through semantic queries rather than duplicated status flags in every capability.
- ADR-0049 Tenant Owner and Platform Owner authority remains Administration-owned and gains explicit lifecycle continuity interaction.
- Suspension is reversible and does not require destructive mass mutation of tenant-owned history.
- Decommissioning is durable and forward-recovering; internal authority closure is required, but provider cleanup failure does not roll back a valid shutdown decision.
- Tenant decommissioning does not physically delete historical IAM/evidence state.
- Existing code that treats Platform as Tenant business owner becomes migration/refactoring work.
- The current controlled v0.8 formal specification package remains the controlled baseline; this ADR is an accepted amendment until the next controlled revision folds it in.

## Rejected alternatives

### Keep Tenant lifecycle in Platform because `platform.tenant` already exists

Rejected. Table/schema placement is implementation detail and must not give technical infrastructure ownership of a business/security lifecycle.

### Model Tenant as Organization

Rejected. Tenant is the hard customer/isolation realm; Organization is tenant-local business structure and may have multiple meanings/hierarchies inside one Tenant.

### Represent deployment ownership with `tenant_id = NULL`

Rejected. Nullable tenancy blurs ownership and is already rejected by ADR-0049. Deployment-owned state is explicit and typed.

### Suspend or decommission by directly rewriting every foreign capability table

Rejected. Tenancy may gate/orchestrate, but only the owning capability mutates its authoritative state. Shutdown uses semantic collaboration and forward recovery.

### Treat decommission as a distributed transaction that waits for every provider

Rejected. Provider/network failure must not roll back a valid Wyrmgate governance/lifecycle decision. Provider residue is separate technical state and evidence.

### Allow Tenant suspension/decommission to strand the final Platform Owner

Rejected. Deployment owner continuity is a security/recoverability invariant and must be checked before the lifecycle mutation commits.

### Trust browser-provided Tenant selection as operation scope

Rejected. Tenant routing/operation scope remains server-derived. Browser state may select a view but never establishes authority.
