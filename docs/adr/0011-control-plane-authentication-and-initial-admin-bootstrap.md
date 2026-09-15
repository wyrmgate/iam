# ADR-0011: Control-plane authentication and initial administrator bootstrap

- Status: Accepted
- Date: 2026-09-15

## Context

ADR-0005 and the formal Security/SRS requirements require default-deny IAM control-plane authorization, tenant isolation, governed actors and stronger controls for administrative authority. The first Administration persistence slice now evaluates semantic permissions and typed scopes, but a public HTTP request still needs a trusted way to become a tenant-scoped governed actor. The same system also needs a safe way to establish the first administrative grant without creating a permanent superuser or self-escalation bypass.

External OAuth/OIDC authentication and IAM Administrative Authorization are different security decisions. Provider token roles/scopes cannot redefine Wyrmgate's Administration-owned authority model.

## Decision

### Provider-neutral bearer authentication

Wyrmgate's first control-plane transport authentication target is externally issued JWT bearer tokens validated as an OAuth/OIDC resource server. The implementation is provider-neutral and configured with a trusted issuer and required audience. Signature, issuer, token validity window and audience are validated before actor resolution.

Transport token scopes/roles are not converted into Wyrmgate AdministrativePermissions. A valid token establishes only an external authentication subject identified by exact `issuer + subject`.

When bearer authentication is not configured, protected `/api/v1/**` control-plane routes remain closed. Health/runtime metadata may remain publicly readable where explicitly configured.

### Server-side governed actor binding

Administration owns `ControlPlaneActorBinding`, which maps one validated external `issuer + subject` to exactly one tenant and governed Identity for the ordinary control plane. Tenant is never accepted from a client-writable tenant header. The binding is authoritative control-plane security configuration but grants no permission by itself.

The actor's current governed Identity state and AdministrativeGrant remain operation-time authorization inputs. Suspending or otherwise making the Identity administratively ineligible therefore prevents use of otherwise valid bindings/grants.

Ordinary subject bindings do not cross tenants. Future platform-operator/support access is a separate explicitly elevated mechanism and must not weaken this rule.

### Burn-once initial administrator bootstrap

The first administrator is established by an explicit one-shot operator command, not an HTTP endpoint and not a hard-coded account. Inputs identify an existing tenant, an existing administratively eligible governed Identity, and the external issuer+subject that will authenticate as that Identity.

The bootstrap transaction atomically:

1. proves the tenant has no existing AdministrativeGrant;
2. claims an immutable tenant-unique `InitialAdminBootstrap` marker;
3. creates the external-subject actor binding;
4. ensures the explicit semantic permissions implemented for the first control-plane surfaces;
5. creates a tenant initial-administrator AdministrativeRole;
6. creates one GLOBAL AdministrativeGrant for that role and governed Identity.

The initial role contains only explicit permissions: `administration:manage-authorization`, `identity:read`, `identity:create`, and `identity:update`. GLOBAL scope broadens only those listed permissions; there is no wildcard permission or universal superuser semantic.

The tenant-unique bootstrap marker is permanent historical security state. Bootstrap is not reopened if grants are later revoked, roles change, or current administrative authority disappears. The marker is claimed inside the same transaction as the created binding/role/grant so any failed bootstrap rolls the claim back. Same-capability marker references use deferred integrity constraints so the marker can be the concurrency guard while the referenced rows are created later in that transaction.

No access token, refresh token, credential, private key or provider secret is persisted by bootstrap. The bootstrap marker is durable Administration evidence/pointer state but does not replace the Audit capability's future append-only security evidence contract.

### Authentication assurance

This ADR establishes bearer validation and governed actor resolution only. Authentication assurance (`acr`/`amr`, step-up, maker-checker) is not fabricated by this first slice. High-impact operations that require stronger assurance remain unavailable until their concrete policy/evaluation contracts are implemented.

## Consequences

- Identity HTTP controllers can next be activated only by combining this authenticated actor context with Administration's semantic operation-time evaluator.
- External IdPs remain replaceable without changing IAM permission semantics.
- OAuth scopes cannot accidentally become a second administration model.
- A tenant can never regain the first-admin bootstrap path merely by deleting/revoking current grants.
- Losing all administrators after bootstrap requires an explicit future governed recovery mechanism, not reuse of bootstrap.
- Formal v0.2 Security/DDD/SAD/Integration specifications are amended by this ADR until the next controlled revision.

## Rejected alternatives

- **Permanent bootstrap/superuser account:** creates an enduring bypass and conflicts with scoped/default-deny administration.
- **Bootstrap whenever no active grant exists:** revocation/deletion could reopen privilege escalation.
- **Trust tenant or Identity IDs from ordinary request headers/JWT custom claims directly:** lets provider/client configuration redefine the IAM isolation boundary instead of using server-side governed binding.
- **Map OAuth roles/scopes directly to AdministrativePermission:** creates competing authorization authority outside Administration.
- **Create a separate IAM User aggregate for administrators:** duplicates Identity instead of using the governed subject model.
