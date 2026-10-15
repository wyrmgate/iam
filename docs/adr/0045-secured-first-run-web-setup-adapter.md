# ADR-0045 Secured first-run web setup adapter

- Status: Accepted
- Date: 2026-10-15
- Amends: [ADR-0044 Fresh-install bootstrap and first-party initial administrator establishment](0044-fresh-install-bootstrap-and-first-party-initial-admin.md)

## Context

ADR-0044 established the deployment-scoped, burn-once, resumable fresh-install bootstrap and deliberately limited its initial adapter to trusted local/startup invocation. It explicitly rejected an unauthenticated remote bootstrap API until a separate security design existed.

That durable engine is correct, but requiring operators to provide all human installation inputs through deployment configuration produces a poor first-run experience. Wyrmgate Console needs a first-run wizard without turning the browser into an IAM authority, without allowing browser-selected routing or provider configuration, and without creating a second bootstrap state machine.

The security boundary is unusual because no Tenant, governed administrator session or ordinary Administration authority exists yet. Any browser adapter must therefore authenticate an installation-scoped proof that is distinct from IAM authentication and must cease to have effect after the durable installation seal exists.

## Decision

Wyrmgate adds an optional same-origin first-run web setup adapter over the existing `InstallationBootstrapService`.

The adapter does **not** own a new workflow. `platform.installation_bootstrap` remains the single durable installation plan and state machine from ADR-0044:

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

### Explicit deployment enablement

Web setup is disabled unless `iam.bootstrap.web.enabled=true` is configured by the deployment operator.

The web adapter and the ADR-0044 startup mutation adapter are mutually exclusive. A deployment must not enable both `iam.bootstrap.web.enabled` and `iam.bootstrap.installation.enabled` at the same time. Both adapters reuse the same durable installation service and repository, but only one may supply human bootstrap input for an unsealed deployment.

The existing startup/headless adapter remains supported for automated installations.

### Installation-scoped setup proof

When web setup is enabled, the deployment must provide a setup secret through an operator-controlled file using `iam.bootstrap.web.setup-secret-file`. The setup secret:

- is not an Identity, Principal, Credential or Administration authority;
- is not the initial administrator password;
- is never persisted in IAM authoritative state, the installation plan, audit, events or logs;
- is not returned to the browser after proof submission;
- is not retained in plaintext by the running server after startup configuration is resolved;
- must contain at least 32 characters.

A successful proof exchange issues a short-lived, cryptographically signed, install-scoped setup-session cookie. The default lifetime is 15 minutes and configured lifetime may not exceed one hour. The cookie is `Secure`, `HttpOnly` and `SameSite=Strict`.

The setup session is stateless application proof only. It MUST NOT be accepted by ordinary control-plane authorization or converted into a governed actor.

### CSRF and same-origin boundary

Setup mutation uses a setup-specific double-submit CSRF token, separate from the normal authenticated Console CSRF token. The CSRF cookie is `Secure` and `SameSite=Strict`; mutation requires the matching `X-Wyrmgate-Setup-CSRF` header.

The adapter does not broaden CORS. Console uses same-origin `/api/setup/*` requests.

### Public status projection

`GET /api/setup/status` may be read before ordinary authentication so Console can choose between setup and normal sign-in. It is `no-store` and data-minimized.

It may expose only the information required to render/resume setup:

- lifecycle classification: `UNINITIALIZED`, `IN_PROGRESS` or `SEALED`;
- current ADR-0044 phase when a durable plan exists;
- frozen non-secret human inputs already present in the plan;
- the trusted Console redirect URI;
- whether the presented setup-session cookie is currently valid.

It MUST NOT expose installation object IDs, Credential/private verifier material, correlation identifiers, setup secret material or generated SSO client secrets.

### Server-owned routing configuration

The browser never chooses the Wyrmgate public/base URL, Console redirect URI, SSO client identifier, ApplicationTarget identifier or provider-native configuration.

The Console redirect URI comes from trusted deployment configuration and is displayed read-only by the wizard. The installation service continues generating stable internal IDs and the public Console `client_id` server-side.

### Human inputs and password handling

Before the durable plan exists, the wizard may collect:

- first Tenant display name;
- first administrator Identity display name;
- first administrator username / Principal key;
- initial administrator password and confirmation.

The password is ephemeral request input. It is passed only into the existing installation service, which passes it through the Credential-owned enrollment and verification boundary. The web adapter and installation process do not persist it.

After a durable plan exists, all non-secret installation inputs are frozen. The wizard renders those values read-only. Resume requires the original password again; if the Credential verifier already exists, a different password fails closed under the ADR-0044 verification rules and is not treated as password rotation.

### Seal behavior

`SEALED` remains irreversible.

After the installation reaches `SEALED`:

- setup initialization can no longer mutate state;
- setup-session proof cannot reopen installation;
- setup cookies are cleared when sealing completes;
- status reports `SEALED` and Console proceeds to normal first-party sign-in;
- later loss of administrators or Credentials is a recovery concern and never re-enables setup.

### Failure and recovery

The browser UI projects the current durable ADR-0044 phase. It does not maintain independent business/process state.

If initialization fails after an owner capability committed, the existing stable IDs, owner-local idempotency and durable checkpoints govern retry. The operator refreshes status and resumes with the frozen inputs and original password. There is no distributed rollback and no destructive restart action in the wizard.

The initial implementation invokes the existing durable coordinator synchronously. The UI may show the last durable phase before and after an attempt, but does not fabricate intermediate completion. A future asynchronous adapter may be added only if it preserves the same authoritative state machine and security boundary.

## HTTP adapter contract

The initial browser adapter uses:

```text
GET  /api/setup/status
POST /api/setup/session
POST /api/setup/initialize
```

`POST /api/setup/session` accepts only the installation setup proof and establishes the short-lived setup session.

`POST /api/setup/initialize` requires the setup session plus setup-specific CSRF and accepts only the human inputs above. Trusted routing values are injected server-side.

Errors are stable, data-minimized semantic errors. Secret values, verifier material and internal exception diagnostics are never reflected.

## Consequences

Positive consequences:

- fresh installs gain a guided Console experience without weakening the ADR-0044 durable model;
- browser authority is narrowly installation-scoped and expires quickly;
- deployment routing remains operator-owned and server-trusted;
- crash/restart behavior remains forward-recovery rather than rollback;
- automated/headless deployments keep the existing startup adapter.

Costs and constraints:

- operators enabling web setup must provision and later remove a separate setup-secret file;
- first-run deployments must serve the Console/API through HTTPS for `__Host-` secure cookies to work;
- web setup and startup bootstrap cannot race because they are intentionally mutually exclusive;
- the controlled v0.8 formal specification package does not yet contain this amendment and must fold ADR-0045 into the next meaningful formal revision together with the outstanding ADR-0042 through ADR-0044 amendments.

## Rejected alternatives

### Public unauthenticated bootstrap mutation endpoint

Rejected. Installation mutation before ordinary IAM authentication still requires a strong deployment-scoped proof.

### Browser-selected public/base URL or redirect URI

Rejected. Browser input is not a trusted deployment boundary and must not determine SSO routing.

### Reuse the initial administrator password as setup proof

Rejected. It collapses installation authorization and Credential secret semantics and would require exposing the administrator password before the Credential-owned path needs it.

### Persist the setup secret or setup session in IAM domain tables

Rejected. The setup proof is deployment bootstrap transport security, not governed IAM state.

### Create a second wizard-owned workflow/state machine

Rejected. ADR-0044 already defines the authoritative durable progression; duplicating it would create competing state and recovery semantics.
