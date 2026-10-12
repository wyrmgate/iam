# ADR-0044: Fresh-install bootstrap and first-party initial administrator establishment

- Status: Accepted
- Date: 2026-10-13
- Amends: ADR-0011 burn-once initial-administrator bootstrap for fresh standalone installation
- Depends on: ADR-0042 first-party identity provider and single sign-on

## Context

ADR-0011 established a burn-once, operator-only initial-administrator bootstrap for a Tenant that already exists and already has an administratively eligible governed Identity. Its implemented adapter currently provisions an external authentication subject binding plus the initial Administration role and grant.

ADR-0042 broadened Wyrmgate into a standalone IAM + IdP/SSO + IGA platform and allows the initial administrator to authenticate through an existing governed Principal with an active Credential. However, a completely fresh standalone installation still has a circular dependency: normal administrative APIs require Administration authority, while the first governed Tenant, Identity, Principal, PASSWORD Credential and Wyrmgate Console SSO registration do not yet exist.

Solving that circular dependency with a permanent built-in administrator, reusable bootstrap password, unauthenticated HTTP endpoint, direct cross-capability table writes or a single giant transaction would violate existing security and capability-ownership rules.

The installation path therefore needs one explicit pre-normal-operation mechanism that can establish the minimum governed state required for first-party username/password login and then become permanently unavailable.

## Decision

### Deployment-scoped installation bootstrap

Wyrmgate introduces a deployment-scoped `InstallationBootstrap` process used only for a fresh installation. It is operator-invoked through a trusted installation adapter and is not a normal public control-plane HTTP operation.

The process orchestrates semantic commands owned by the existing capabilities. It does not own or directly mutate Tenant, Identity, Principal, Credential, Catalog or Administration business state.

The logical sequence is:

1. create the first Tenant;
2. create the first governed PERSON Identity and make it ACTIVE through Identity-owned semantics;
3. create/register the first ACTIVE Principal for that Identity with a native login key suitable for the local authentication contract;
4. enroll an ACTIVE PASSWORD Credential for that Principal through the Credential-owned secret/verifier boundary;
5. create/register the Wyrmgate Console as a governed Catalog Application and ApplicationTarget;
6. register an ACTIVE public SSO client for the Console with a server-generated globally unique `client_id`, exact redirect URI registration and mandatory S256 PKCE;
7. invoke Administration's tenant-scoped burn-once initial-administrator bootstrap for the first governed Identity using the first-party Principal as its authentication anchor;
8. verify that all minimum postconditions required for normal first-party login and Administration authorization hold;
9. permanently seal the installation bootstrap.

The sequence is implementation-neutral. Physical package/module names and framework adapters do not define the canonical process.

### Two different burn-once security records

The deployment-scoped installation seal and the tenant-scoped Administration bootstrap marker solve different problems and remain distinct.

`InstallationBootstrap` answers whether the deployment has consumed its one fresh-install path. Its permanent terminal state is `SEALED`.

`InitialAdminBootstrap` remains Administration-owned tenant historical security state proving that the Tenant consumed its one initial-administrator establishment path.

Sealing the deployment does not replace or weaken the tenant marker. The tenant marker remains permanent even if the initial grant, role, Principal, Identity or authentication configuration later changes or is removed.

### Durable, resumable orchestration

The fresh-install bootstrap is a durable process because it spans multiple capability-owned transactions and includes secret/verifier work that must not be folded into one distributed transaction.

Recommended process progression is:

```text
NOT_STARTED
  -> TENANT_CREATED
  -> IDENTITY_CREATED
  -> PRINCIPAL_CREATED
  -> CREDENTIAL_ENROLLED
  -> CONSOLE_REGISTERED
  -> ADMIN_BOOTSTRAPPED
  -> VERIFIED
  -> SEALED
```

The exact persistence representation may differ, but these semantic checkpoints must remain distinguishable enough to resume safely after interruption.

Each step must be causally idempotent. Re-running the same installation operation after a crash resumes or confirms the prior result; it must not silently create duplicate first objects or alternate initial administrators.

A failure after one committed capability step uses forward recovery/resume. Cross-capability distributed rollback is not required and must not be simulated by directly deleting foreign capability state.

### Irreversible seal

`SEALED` is terminal. There is no transition from `SEALED` back to an installable state.

The installation path must not reopen because:

- all AdministrativeGrants are later revoked or deleted;
- the initial administrator loses eligibility;
- the first Credential is revoked, expired or compromised;
- the Console SSO registration is retired;
- the first Tenant is otherwise left without an active administrator.

Loss of all administrative authority after sealing is a governed recovery problem, not an installation-bootstrap condition.

The seal must be persisted in deployment-local authoritative security state and protected against ordinary control-plane mutation. A deployment restore must preserve the seal consistently with the restored authoritative state.

### First-party authentication anchor

For the standalone fresh-install path, Administration does not need an external `ControlPlaneActorBinding` merely to make the first-party administrator usable. The first-party authentication anchor is the governed Principal/Identity relationship plus an effective Credential that can establish a server-side first-party session.

The existing external-subject binding path remains supported for deployments choosing external authentication. ADR-0011 remains authoritative for external `(issuer, subject)` binding semantics.

Administration bootstrap continues to create Administration-owned role/grant historical state only. It does not create the Identity, Principal, Credential, Catalog Application, SSO client or browser session.

### PASSWORD enrollment and secret boundary

The operator supplies the initial password only to the installation adapter for immediate Credential-owned enrollment. Raw password material is ephemeral input.

The Credential capability creates verifier/private material behind the accepted secret/verifier boundary and persists only governed Credential metadata plus an opaque secret/verifier reference where required by the implementation.

Raw password or verifier material must never appear in ordinary IAM persistence, InstallationBootstrap state, domain/public events, AuditRecord payloads, logs, task payloads, command result objects, or exception/error payloads.

A retry after verifier creation must use causal idempotency rather than retaining or replaying plaintext from durable process state. If the adapter cannot safely prove prior enrollment completion, operator re-entry of the password may be required by the concrete installation UX, but the durable process must never persist the plaintext to make retries convenient.

### Wyrmgate Console registration

The Wyrmgate Console is represented through ordinary governed Catalog semantics rather than a hidden OAuth framework client.

The initial Console registration must produce an active public client with a server-generated globally unique `client_id`, exact configured redirect URI(s), mandatory Authorization Code + S256 PKCE semantics, no bootstrap client secret, and parent Application/ApplicationTarget references owned by Catalog.

The resulting `client_id` becomes the normal first-party login Tenant-routing anchor defined by ADR-0042.

### Verification before sealing

The deployment may be sealed only after semantic read-side verification confirms at least:

- the created Tenant is the intended first Tenant;
- the first Identity and Principal are ACTIVE and correctly related;
- an effective PASSWORD Credential exists for the Principal;
- the Console SSO client is ACTIVE and resolves to the same Tenant;
- the tenant-scoped `InitialAdminBootstrap` marker exists;
- the initial Administration grant is effective for the first Identity;
- the normal first-party authentication path can resolve the created Principal/Credential configuration without relying on bootstrap authority.

Verification reads current semantic state; it must not infer success merely because prior commands returned successfully.

### Bootstrap adapter boundary

The fresh-install entrypoint is an explicit deployment/operator adapter, such as a dedicated startup/CLI installation command executed with local deployment authority. It is not exposed as a reusable unauthenticated remote endpoint.

Any future remote bootstrap mechanism would require a separate accepted security design covering trusted operator authentication, anti-replay, network exposure, secret handling and seal enforcement.

After `SEALED`, the bootstrap adapter must fail closed before invoking capability commands.

### Audit and evidence

Bootstrap operations produce data-minimized semantic evidence appropriate to each owning capability. The deployment process may retain stable IDs, correlation/causation identifiers, timestamps, checkpoint state and failure diagnostics that contain no secret/private material.

Audit failure must not retroactively rewrite a successfully committed capability-owned result. Sealing verification should require only the security evidence explicitly designated as mandatory by the concrete implementation contract.

## Consequences

- A standalone Wyrmgate installation can reach its first normal username/password login without an external IdP or permanent built-in administrator.
- Administration remains the sole owner of control-plane authority; the broader installer does not become a super-capability.
- The existing `InitialAdminBootstrap` implementation needs a first-party variant that does not require an external subject binding while preserving the permanent tenant marker and initial explicit role/grant semantics.
- A new deployment-scoped bootstrap process/state store and trusted operator adapter are required.
- Tenant/Identity/Principal/Credential/Catalog commands used by installation need causal idempotency suitable for resume.
- The initial Console Application/Target/client values become explicit installation outputs/configuration rather than hidden framework defaults.
- Normal login begins only after the process verifies current semantic postconditions and seals itself.
- Losing all administrators after sealing remains a separate governed recovery feature and cannot reuse installation bootstrap.
- Formal v0.8 specifications predate this fresh-install orchestration. This ADR amends them until the next controlled formal revision updates BRD/FRD/SRS/DDD/SAD/Data/Security/Integration/RTM as appropriate.

## Rejected alternatives

- **Permanent built-in `admin/admin` or root account:** creates an enduring bypass and contradicts scoped default-deny Administration.
- **Reusable bootstrap password:** creates a durable unauthenticated/elevated recovery path and secret-management liability.
- **Reopen bootstrap whenever no active administrator exists:** turns ordinary revocation or misconfiguration into a privilege-escalation trigger.
- **One cross-capability database transaction:** violates capability ownership and couples installation semantics to one physical persistence topology.
- **Direct inserts into capability tables from an installer:** bypasses invariants, events, idempotency and ownership boundaries.
- **Expose `/api/bootstrap` unauthenticated until first use:** enlarges the remote attack surface and makes trusted operator establishment ambiguous.
- **Create a separate bootstrap User/admin model:** duplicates Identity/Principal and creates competing authentication/authorization authority.
- **Persist plaintext password in bootstrap process state for retry:** violates the Credential secret boundary.
- **Treat the Console as a hidden OAuth framework client:** creates competing Catalog/client authority and weakens tenant-routing governance.
