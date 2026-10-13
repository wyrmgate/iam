# ADR-0044: Fresh-install bootstrap and first-party initial administrator establishment

- Status: Accepted
- Date: 2026-10-13
- Amends: ADR-0011 burn-once initial-administrator bootstrap for fresh standalone installation
- Depends on: ADR-0042 first-party identity provider and single sign-on

## Context

ADR-0011 established a burn-once, operator-only initial-administrator bootstrap for a Tenant that already exists and already has an administratively eligible governed Identity. Its implemented adapter originally provisioned an external authentication subject binding plus the initial Administration role and grant.

ADR-0042 broadened Wyrmgate into a standalone IAM + IdP/SSO + IGA platform and allows the initial administrator to authenticate through an existing governed Principal with an active Credential. However, a completely fresh standalone installation still has a circular dependency: normal administrative APIs require Administration authority, while the first governed Tenant, Identity, Principal, PASSWORD Credential and Wyrmgate Console SSO registration do not yet exist.

Solving that circular dependency with a permanent built-in administrator, reusable bootstrap password, unauthenticated HTTP endpoint, direct cross-capability table writes or a single giant transaction would violate existing security and capability-ownership rules.

The installation path therefore needs one explicit pre-normal-operation mechanism that can establish the minimum governed state required for first-party username/password login and then become permanently unavailable.

## Decision

### Deployment-scoped installation bootstrap

Wyrmgate introduces a deployment-scoped `InstallationBootstrap` process used only for a fresh installation. It is operator-invoked through a trusted installation adapter and is not a normal public control-plane HTTP operation.

The process orchestrates semantic commands owned by the existing capabilities. It does not own or directly mutate Tenant, Identity, Principal, Credential, Catalog or Administration business state.

The logical sequence is:

1. durably allocate the installation plan and all stable cross-step identifiers without storing password material;
2. create the first Tenant isolation root through Platform-owned tenant semantics;
3. create the first governed PERSON Identity and make it ACTIVE through Identity-owned semantics;
4. create/register the Wyrmgate Console as a governed Catalog Application and ACTIVE ApplicationTarget;
5. register an ACTIVE public SSO client for the Console with a server-generated globally unique `client_id`, exact redirect URI registration and mandatory S256 PKCE;
6. create/register the first ACTIVE Principal for the Identity, targeted at the Console ApplicationTarget, with a native login key suitable for the local authentication contract;
7. enroll an ACTIVE PASSWORD Credential for that Principal through the Credential-owned secret/verifier boundary;
8. invoke Administration's tenant-scoped burn-once initial-administrator bootstrap for the first governed Identity using the first-party Principal as its authentication anchor;
9. verify current semantic state required for normal first-party login and Administration authorization;
10. permanently seal the installation bootstrap.

Catalog ApplicationTarget creation precedes Principal creation because a Principal is authoritative Identity state for a concrete technical target and the existing invariant requires that target to be active at creation time.

The sequence is implementation-neutral. Physical package/module names and framework adapters do not define the canonical process.

### Two different burn-once security records

The deployment-scoped installation seal and the tenant-scoped Administration bootstrap marker solve different problems and remain distinct.

`InstallationBootstrap` answers whether the deployment has consumed its one fresh-install path. Its permanent terminal state is `SEALED`.

`InitialAdminBootstrap` remains Administration-owned tenant historical security state proving that the Tenant consumed its one initial-administrator establishment path.

Sealing the deployment does not replace or weaken the tenant marker. The tenant marker remains permanent even if the initial grant, role, Principal, Identity or authentication configuration later changes or is removed.

### Durable, resumable orchestration

The fresh-install bootstrap is a durable process because it spans multiple capability-owned transactions and includes secret/verifier work that must not be folded into one distributed transaction.

The implemented semantic progression is:

```text
PLANNED
  -> TENANT_CREATED
  -> IDENTITY_CREATED
  -> CONSOLE_REGISTERED
  -> PRINCIPAL_CREATED
  -> CREDENTIAL_ENROLLED
  -> ADMIN_BOOTSTRAPPED
  -> VERIFIED
  -> SEALED
```

`PLANNED` is committed before the first capability mutation and contains the stable Tenant, Identity, Catalog, Principal, Credential and correlation identifiers plus non-secret installation inputs. It never contains a password or verifier.

Each capability mutation is causally idempotent for the exact preallocated identifier and expected semantics. If a capability transaction commits but the following checkpoint write fails, replay confirms the already-created authoritative object rather than silently creating a second first object.

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

The seal is persisted in deployment-local authoritative security state and is not exposed through ordinary control-plane mutation. A deployment restore must preserve the seal consistently with the restored authoritative state.

### First-party authentication anchor

For the standalone fresh-install path, Administration does not need an external `ControlPlaneActorBinding` merely to make the first-party administrator usable. The first-party authentication anchor is the governed Principal/Identity relationship plus an effective Credential that can establish a server-side first-party session.

The existing external-subject binding path remains supported for deployments choosing external authentication. ADR-0011 remains authoritative for external `(issuer, subject)` binding semantics.

Administration bootstrap continues to create Administration-owned role/grant historical state only. It does not create the Identity, Principal, Credential, Catalog Application, SSO client or browser session.

The first-party tenant bootstrap is replay-safe only when the existing immutable marker names the exact same Identity and Principal. Any other existing bootstrap marker fails closed.

### PASSWORD enrollment and secret boundary

The operator supplies the initial password only to the trusted installation adapter for immediate Credential-owned enrollment. The durable installation process never persists it.

The startup adapter may accept the password from exactly one trusted deployment-local secret source: a temporary secret configuration value or an operator-controlled secret file. Those are implementation/operations choices at the adapter boundary, not IAM state. Managed platforms may prefer a temporary secret variable; environments with ephemeral secret mounts may prefer a file. Configuring both sources fails closed.

Credential owns a private local verifier store behind `CredentialSecretVerifier`/enrollment semantics. The baseline local provider persists salted PBKDF2-HMAC-SHA-256 verifier material under a system-managed opaque `SecretReference`; the ordinary Credential creation path cannot select that provider type.

Raw password material must never appear in ordinary IAM persistence, `InstallationBootstrap` state, domain/public events, AuditRecord payloads, logs, task payloads, command result objects, or exception/error payloads. Private verifier material remains confined to the Credential secret boundary and is not exposed through ordinary IAM APIs or events.

A retry after verifier creation uses the stable preallocated Credential/reference key. If the same verifier already exists, enrollment verifies the supplied password and resumes; a different password fails closed. This makes replay safe without retaining plaintext in durable process state.

### Wyrmgate Console registration

The Wyrmgate Console is represented through ordinary governed Catalog semantics rather than a hidden OAuth framework client.

The initial Console registration produces an active public client with a server-generated globally unique `client_id`, exact configured redirect URI, mandatory Authorization Code + S256 PKCE semantics, no bootstrap client secret, and parent Application/ApplicationTarget references owned by Catalog.

The Console SSO client does not itself grant Administration authority. The resulting `client_id` is the normal first-party login Tenant-routing anchor defined by ADR-0042; each protected operation still passes Administration authorization.

### Verification before sealing

The deployment may be sealed only after semantic current-state verification confirms at least:

- the created Tenant is the intended first Tenant;
- the first Identity and Principal are ACTIVE, correctly related and resolvable through the normal first-party authentication subject query;
- the exact PASSWORD Credential verifies the supplied installation password through the normal Credential authentication service;
- the Console SSO client is ACTIVE, resolves globally, and routes to the same Tenant/Application;
- the tenant-scoped `InitialAdminBootstrap` marker names the first-party Identity/Principal anchor;
- the corresponding initial Administration grant is currently effective for that Identity.

Verification reads current semantic state; it does not infer success merely because prior commands returned successfully and it does not create a browser session as a side effect.

### Bootstrap adapter boundary

The fresh-install entrypoint is an explicit deployment/operator adapter. The baseline implementation is a conditional startup `ApplicationRunner` enabled only by `iam.bootstrap.installation.enabled=true`. Before `SEALED`, it resolves exactly one configured trusted password source and invokes the durable service.

It is not exposed as a reusable unauthenticated remote endpoint. Any future remote bootstrap mechanism requires a separate accepted security design covering trusted operator authentication, anti-replay, network exposure, secret handling and seal enforcement.

After `SEALED`, the adapter checks the durable seal before resolving any password source and invokes no capability mutations. Operators should disable the bootstrap setting and remove the temporary deployment secret or external password file after successful installation. Normal post-seal startup must not require bootstrap secret material.

### Audit and evidence

Bootstrap operations produce data-minimized semantic evidence appropriate to each owning capability. The deployment process retains stable IDs, correlation identifiers, non-secret installation parameters, timestamps and checkpoint state only.

Audit failure must not retroactively rewrite a successfully committed capability-owned result. Sealing verification requires current semantic security state rather than successful audit delivery.

## Consequences

- A standalone Wyrmgate installation can reach its first normal username/password login without an external IdP or permanent built-in administrator.
- Administration remains the sole owner of control-plane authority; the broader installer does not become a super-capability.
- Administration supports both external-subject and first-party-Principal initial authentication anchors while preserving one immutable tenant bootstrap marker.
- Platform owns the narrow creation semantic for the first Tenant isolation root; this is not a new business capability or a generic unauthenticated tenant-creation API.
- Identity, Catalog and Credential expose owner-local idempotent semantics used with preallocated installation identifiers so recovery does not depend on a distributed transaction.
- The local PASSWORD verifier provider is system-managed and private to Credential.
- The trusted startup adapter supports mutually exclusive deployment-secret and secret-file password sources without changing durable bootstrap semantics.
- The initial Console Application/Target/client values are explicit installation outputs rather than hidden framework defaults.
- Normal login begins only after the process verifies current semantic postconditions and seals itself.
- Losing all administrators after sealing remains a separate governed recovery feature and cannot reuse installation bootstrap.
- Formal v0.8 specifications predate this fresh-install orchestration. This ADR amends them until the next controlled formal revision updates BRD/FRD/SRS/DDD/SAD/Data/Security/Integration/RTM as appropriate.

## Rejected alternatives

- **Permanent built-in `admin/admin` or root account:** creates an enduring bypass and contradicts scoped default-deny Administration.
- **Reusable bootstrap password:** creates a durable unauthenticated/elevated recovery path and secret-management liability.
- **Reopen bootstrap whenever no active administrator exists:** turns ordinary revocation or misconfiguration into a privilege-escalation trigger.
- **One cross-capability database transaction:** violates capability ownership and couples installation semantics to one physical persistence topology.
- **Direct inserts into capability tables from an installer:** bypasses invariants, events, idempotency and ownership boundaries.
- **Create a target-less first Principal as a bootstrap exception:** weakens the Principal/ApplicationTarget invariant and creates a special account model that normal authentication does not use.
- **Expose `/api/bootstrap` unauthenticated until first use:** enlarges the remote attack surface and makes trusted operator establishment ambiguous.
- **Create a separate bootstrap User/admin model:** duplicates Identity/Principal and creates competing authentication/authorization authority.
- **Persist plaintext password in bootstrap process state for retry:** violates the Credential secret boundary.
- **Permit ordinary Credential creation to select the built-in local-password verifier reference:** permits copying or misbinding system-managed private verifier references.
- **Treat the Console as a hidden OAuth framework client:** creates competing Catalog/client authority and weakens tenant-routing governance.
