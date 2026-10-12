# Control-plane Authentication and Initial Administrator Bootstrap

## Purpose

This document defines the authentication boundary that precedes Wyrmgate Administrative Authorization. ADR-0011 established the external bearer implementation; ADR-0042 broadened Wyrmgate into an IAM/IdP/SSO platform and superseded the implication that an external IdP is the only canonical authentication source. ADR-0044 defines the trusted fresh-install path that establishes the first first-party administrator and then permanently closes itself.

Authentication answers **which governed subject authenticated and through which trusted mechanism**. Administration answers **what that governed actor may do to IAM state**. These remain separate decisions.

See [`identity-provider-sso.md`](identity-provider-sso.md) for first-party IdP/SSO and federation security details.

## Supported authentication sources

Wyrmgate supports two canonical authentication-source families for the control plane:

1. **First-party Wyrmgate authentication** — an existing governed Principal authenticates using Credential-owned authenticator semantics and a server-side browser session.
2. **Federated/external authentication** — a cryptographically validated external OAuth/OIDC subject, and future explicitly accepted upstream provider adapters, map server-side to governed Wyrmgate actor semantics.

Both paths produce provider-neutral actor context and both still require Administration authorization for every protected operation. Provider roles, groups, scopes, tenant claims, arbitrary claims, or library authorities are never converted automatically into `AdministrativePermission`.

## External bearer adapter

The implemented external JWT resource-server adapter is configured with one trusted issuer URI and one required Wyrmgate API audience. Normal JWT issuer/signature/time/audience validation occurs before exact `(issuer, subject)` is passed to `ControlPlaneActorResolver`. Administration-owned binding resolves that external subject to a Tenant and governed Identity.

A valid token grants no IAM authority by itself. Browser-supplied Tenant headers and token/provider claims do not select authoritative Tenant.

Configuration:

```text
IAM_AUTH_ENABLED=true
IAM_AUTH_ISSUER_URI=https://issuer.example
IAM_AUTH_AUDIENCE=wyrmgate-api
```

When first-party IdP and external bearer modes are both enabled, an explicit bearer header takes precedence for that request; otherwise the same-origin first-party session may authenticate the request.

## First-party adapter

When `iam.idp.enabled=true`, local login routes Tenant from a globally unique active Catalog SSO client ID, resolves an ACTIVE Principal/Identity within that Tenant, verifies Credential-owned password proof, and establishes a fresh opaque session. Possession of the resulting globally unique session-token digest lets the server recover Tenant from server-side state; client input never supplies authoritative Tenant.

Every cookie-authenticated `/api/v1/**` request revalidates current Principal/Identity and exact establishing Credential state/revision before creating provider-neutral actor context. The session contains no Administration authority. State-changing requests require CSRF proof. Logout revokes the server-side session.

## Federation extension boundary

The existing external JWT/OIDC adapter is the concrete federation-compatible control-plane source today. ADR-0042 checkpoint 6 also defines a typed `FederatedAuthenticationAdapter<R>` SPI for future upstream OIDC/SAML authentication into first-party flows.

Adapters are explicitly wired by stable provider key and concrete request type. They must cryptographically validate provider-specific exchanges before returning only exact external `(issuer, subject)` plus authentication time. `FederationAdapterRegistry` enables nothing by default and rejects duplicate provider keys. Provider network calls, metadata, signing keys, OIDC token exchange, SAML assertions, and native claims stay behind provider-specific Integration adapters.

Upstream groups, roles, scopes, tenant claims, arbitrary attributes, and provider assurance strings are intentionally absent from the normalized verified-subject result. They do not become business access, `AdministrativePermission`, or canonical assurance automatically. Any future provider activation must separately define server-side Principal/Identity/Tenant mapping and security tests.

## Provider-neutral assurance

The trusted control-plane actor context carries canonical `AuthenticationAssuranceContext` (`BASELINE` or `STRONG`) plus trusted timestamps where available. Provider-specific `acr`/`amr`, authenticator vendor fields, or raw upstream labels do not directly become canonical assurance. A trusted adapter must explicitly map them when such a mapping is accepted. Break-glass continues to fail closed when required strong assurance is unavailable, stale, or downgraded.

## Governed actor resolution

External bearer flow:

```text
validated issuer + subject
        |
        v
ControlPlaneActorBinding
        |
        +--> TenantContext
        +--> governed Identity ID
```

First-party flow:

```text
server-resolved opaque browser session
        |
        +--> TenantContext
        +--> governed Principal / Identity
        |
        v
provider-neutral authenticated actor
```

Neither path grants IAM permission by itself. The actor still passes current Administration checks for governed Identity eligibility, semantic permission, resource/scope, temporal validity, assurance, and Tenant isolation.

## Initial administrator bootstrap

A fresh Tenant has no administrative grant and cannot authorize a normal administrative API call. Its first grant is established through the explicit operator-only burn-once process from ADR-0011, not a permanent bootstrap account.

The selected administrator must already be an ACTIVE governed Identity in the target Tenant. ADR-0042 permits the authentication anchor to be an existing external issuer+subject binding or an existing first-party governed Principal able to authenticate through an active Credential.

External mode creates the Administration-owned external actor binding plus explicit role/grant state. First-party mode records the exact governed Principal as the immutable authentication anchor and creates no synthetic external binding. In either mode, the tenant-unique `InitialAdminBootstrap` marker remains burned even if the initial grant is later revoked; loss of all administrators requires a governed recovery mechanism, never re-running bootstrap.

Bootstrap creates no wildcard permission, universal OAuth scope, blanket application access, hidden superuser, or reusable password.

## Fresh-install bootstrap

ADR-0044 adds a separate **deployment-scoped** bootstrap for an entirely empty standalone installation. It is distinct from the tenant-scoped Administration marker.

The deployment bootstrap is a durable forward-recovery process with these checkpoints:

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

`PLANNED` is persisted before capability mutation and preallocates all stable IDs. Each subsequent mutation is performed by its owning semantic service and is idempotent for that exact ID. The orchestrator never inserts directly into Identity, Catalog, Credential or Administration tables.

`SEALED` is irreversible. Revoking the initial grant, retiring the Principal/SSO client, compromising the Credential, or otherwise losing all current administrators never reopens installation bootstrap.

The baseline trusted adapter is startup-only and enabled explicitly with `iam.bootstrap.installation.enabled=true`. It accepts non-secret installation parameters and reads the initial password from a local operator-controlled file configured by `iam.bootstrap.installation.password-file`. There is no unauthenticated bootstrap HTTP endpoint.

A typical first run supplies values equivalent to:

```text
IAM_BOOTSTRAP_INSTALLATION_ENABLED=true
IAM_BOOTSTRAP_INSTALLATION_TENANT_DISPLAY_NAME=Wyrmgate
IAM_BOOTSTRAP_INSTALLATION_IDENTITY_DISPLAY_NAME=Initial Administrator
IAM_BOOTSTRAP_INSTALLATION_USERNAME=admin
IAM_BOOTSTRAP_INSTALLATION_PASSWORD_FILE=/run/secrets/wyrmgate_initial_password
IAM_BOOTSTRAP_INSTALLATION_CONSOLE_REDIRECT_URI=https://wyrmgate.example/callback
```

The operator must also enable the accepted first-party IdP configuration needed for normal login. After the bootstrap reaches `SEALED`, disable the installation bootstrap setting and remove the external password file. If the process is already sealed, the startup adapter verifies the immutable non-secret plan input and returns before reading the password file or invoking capability mutations.

### Local PASSWORD verifier boundary

The built-in first-party password provider uses Credential-private salted PBKDF2-HMAC-SHA-256 verifier material. The ordinary Credential model still stores only an opaque `SecretReference`; verifier rows are private credential material and are not ordinary IAM state.

The provider type `wyrmgate-local-password-v1` is system-managed. Ordinary Credential creation is rejected if a caller attempts to choose that provider reference directly. Only Credential-owned password enrollment can establish it.

Fresh-install replay uses the preallocated Credential ID as the stable opaque verifier reference. If verifier creation committed before a later checkpoint failed, retry verifies the supplied password against the existing verifier and continues. A different password fails closed; plaintext is never placed in the durable installation process.

### Verification before sealing

Before `SEALED`, the installer verifies through semantic read/authentication paths that:

- the first Tenant still matches the planned isolation root;
- normal Identity authentication lookup resolves the planned active Principal to the planned active Identity on the Console target;
- normal Credential password verification succeeds and returns the planned Credential;
- the globally unique Console `client_id` resolves to the planned Tenant and Catalog registration;
- the exact first-party `InitialAdminBootstrap` marker exists and its Administration grant is currently effective.

A prior successful command response is not sufficient evidence for sealing.

## Browser/session rules

The management console uses same-origin first-party sessions when enabled. Long-lived bearer/access tokens or refresh tokens are not stored in browser `localStorage` or `sessionStorage`. The authentication cookie is Secure/HttpOnly/host-only with bounded idle and absolute expiry; authentication rotates the session to prevent fixation; logout invalidates server state. Cookie-authenticated state changes require CSRF protection.

## Sensitive-data rules

Raw bearer tokens, authorization codes, refresh tokens, session tokens, SAML assertions, upstream authorization codes, signing private keys, raw client secrets, passwords, and private authenticator material are never ordinary IAM state, evidence, logs, events, URLs, or error payloads.

External binding may persist exact issuer and external subject as its authoritative mapping key. First-party authentication uses governed Principal/Identity references. Authentication failures return stable semantic errors/correlation IDs rather than framework, SQL, provider, or secret details.

The installation process may persist stable IDs, non-secret display/login/client configuration, correlation metadata and its irreversible seal. It must not persist the installation password, password file contents, derived verifier material, or any reusable bootstrap secret.

## Current implementation boundary

The repository implements both supported control-plane authentication families at their accepted baseline: first-party same-origin browser sessions and external JWT/OIDC bearer authentication. It exposes an explicit typed federation adapter boundary for future upstream OIDC/SAML provider integrations, with no provider implicitly enabled.

The repository also implements ADR-0044 fresh-install orchestration, the first-party Administration bootstrap anchor, Credential-private local password verification, current-state pre-seal verification and the irreversible deployment seal. The fresh-install entrypoint remains a trusted startup adapter only; no remote bootstrap API is defined.

Optional OAuth/OIDC features and concrete upstream first-party-session federation providers remain future product scope until separately accepted and contracted. No future extension may weaken operation-time Administration authorization or bypass server-derived Tenant/governed-actor resolution.
