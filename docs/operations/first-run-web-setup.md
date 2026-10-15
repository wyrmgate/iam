# First-run web setup runbook

## Purpose

This runbook covers the ADR-0045 browser adapter over the ADR-0044 fresh-install bootstrap engine. It provides a guided Console setup flow while preserving the same deployment-scoped durable plan, owner-local mutations, verification and irreversible `SEALED` state.

Use either this web adapter or the ADR-0044 startup/headless adapter for a fresh deployment. Do not enable both mutation adapters at the same time. Shared managed DEV specifically uses this web adapter; the startup/headless adapter remains for explicit standalone or automated installation scenarios.

## Preconditions

- database migrations are current through the installation-bootstrap migration set;
- first-party IdP/session support needed for normal post-install Console login is enabled;
- HTTPS terminates at or in front of the Console/API origin so `Secure` `__Host-` setup cookies can be used;
- the trusted Console redirect URI is configured by the deployment operator;
- no prior installation has reached `SEALED`.

The browser does not configure the public/base URL or Console redirect. Those remain trusted deployment configuration.

## Create the setup access secret

Generate an installation-only random secret containing at least 32 characters. Do not use the initial administrator password as this secret. Do not place the setup secret in source control, tickets, screenshots, ordinary logs or browser bookmarks.

The application boundary remains file-only: `iam.bootstrap.web.setup-secret-file` points to an operator-controlled or deployment-materialized file source. A single trailing newline is ignored.

### Direct secret-file deployment

Where the runtime supports secret files/mounts directly, configure:

```text
IAM_BOOTSTRAP_WEB_ENABLED=true
IAM_BOOTSTRAP_WEB_SETUP_SECRET_FILE=/run/secrets/wyrmgate_setup_access
IAM_BOOTSTRAP_WEB_SESSION_TTL=PT15M

IAM_BOOTSTRAP_INSTALLATION_ENABLED=false
IAM_BOOTSTRAP_INSTALLATION_CONSOLE_REDIRECT_URI=https://wyrmgate.example/callback
```

### Managed provider secret-variable deployment

The checked-in Wyrmgate server container supports provider-managed environments such as shared Railway DEV where the platform's normal secret boundary is an environment variable rather than a secret-file mount.

Configure:

```text
IAM_BOOTSTRAP_WEB_ENABLED=true
IAM_BOOTSTRAP_WEB_SETUP_SECRET=<temporary provider-managed secret, at least 32 characters>
IAM_BOOTSTRAP_WEB_SESSION_TTL=PT15M

IAM_BOOTSTRAP_INSTALLATION_ENABLED=false
IAM_BOOTSTRAP_INSTALLATION_CONSOLE_REDIRECT_URI=https://wyrmgate.example/callback
```

`IAM_BOOTSTRAP_WEB_SETUP_SECRET` is a **container/deployment adapter input**, not a Spring application property. The container entrypoint materializes it into a private one-use file/FIFO before Java starts, exports only `IAM_BOOTSTRAP_WEB_SETUP_SECRET_FILE`, and removes the raw provider secret variable from the JVM environment. The Java adapter continues to consume only the ADR-0045 file source and retains only derived setup-proof/session material after initialization.

Do not configure both `IAM_BOOTSTRAP_WEB_SETUP_SECRET` and `IAM_BOOTSTRAP_WEB_SETUP_SECRET_FILE`. The deployment adapter fails closed when both or neither are present while web setup is enabled.

`IAM_BOOTSTRAP_WEB_SESSION_TTL` is optional. The default is 15 minutes and the maximum accepted value is one hour.

When web setup is enabled, the startup bootstrap password/password-file settings are not used. Tenant name, administrator display name, username and administrator password are collected by the secured wizard instead.

## Run the wizard

Open the Wyrmgate Console on the configured HTTPS origin. Before normal sign-in, Console queries the data-minimized installation status endpoint.

For an unsealed installation the flow is:

```text
Secure setup access
  -> Installation
  -> Initial administrator
  -> Review
  -> Initialize / resume
  -> Verified + SEALED
  -> Normal sign-in
```

### Secure setup access

Enter the setup access secret from the operator/deployment secret boundary. A successful proof establishes a short-lived setup session using a `Secure`, `HttpOnly`, `SameSite=Strict` cookie. Setup mutations also require a separate same-origin CSRF cookie/header pair.

The setup session is not an IAM Principal, Credential or Administration authority and cannot authorize ordinary `/api/v1/**` operations.

### Installation

Enter the first Tenant display name.

The Console redirect URI is displayed read-only. If it is wrong, correct the deployment configuration before initializing; do not attempt to override it in the browser.

### Initial administrator

Enter:

- administrator display name;
- username / Principal key;
- initial password and confirmation.

The password is ephemeral request input. The web adapter passes it into the existing bootstrap service only for Credential-owned enrollment and verification. It is not stored in the installation plan, audit, events or logs.

### Review and initialize

Review the non-secret values and initialize. The server creates or resumes the same ADR-0044 durable plan and progresses through:

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

Only the owning capability mutates its authoritative state. The browser does not directly create Tenant, Identity, Catalog, Principal, Credential or Administration records.

## Interruption and resume

If initialization fails or the browser/server is restarted, reopen the Console and unlock setup again with the same setup access secret.

Once a durable plan exists, the wizard displays the frozen Tenant name, administrator display name, username and Console redirect read-only. Re-enter the **original** administrator password to resume.

Stable IDs and owner-local idempotency prevent duplicate capability objects. If the password verifier was already enrolled, the same password verifies and resumes; a different password fails closed. Web setup is not a password-rotation path.

Do not delete/edit the installation row or attempt a destructive restart. Resolve the underlying failure and resume forward.

## After successful sealing

When semantic verification succeeds, the server advances `VERIFIED -> SEALED`, clears the setup cookies and Console transitions to normal first-party sign-in.

Then:

1. disable `IAM_BOOTSTRAP_WEB_ENABLED` in deployment configuration;
2. remove the temporary setup-access secret source (`IAM_BOOTSTRAP_WEB_SETUP_SECRET`, the mounted secret file, or equivalent provider materialization source);
3. keep `IAM_BOOTSTRAP_INSTALLATION_ENABLED=false` for managed wizard-first environments;
4. keep the trusted Console redirect configuration used by normal Console/SSO operation;
5. sign in using the administrator username and password created in the wizard;
6. establish the deployment's normal delegated/recovery administrator posture.

A sealed deployment never reopens setup, even if setup-secret material remains present or all administrators are later lost.

## HTTP surface

The adapter exposes only:

```text
GET  /api/setup/status
POST /api/setup/session
POST /api/setup/initialize
```

`GET /api/setup/status` is unauthenticated, `no-store` and data-minimized so the Console can decide whether to show setup or normal sign-in.

`POST /api/setup/session` requires the setup access proof.

`POST /api/setup/initialize` requires the short-lived setup-session cookie plus `X-Wyrmgate-Setup-CSRF` matching the setup CSRF cookie.

The adapter does not enable cross-origin setup access.

## Security notes

- setup proof and administrator password are separate secrets with separate semantics;
- provider-variable materialization is an outer deployment adapter only; Java still consumes the ADR-0045 file boundary;
- the raw managed-provider setup variable is removed before the JVM process is exec'd;
- setup proof plaintext is discarded after startup configuration is derived and after each browser proof request is handled;
- no installation object IDs, correlation IDs, verifier material or secret values are returned by setup status;
- trusted Console routing is injected server-side, never accepted from setup request bodies;
- setup cookies use `__Host-` names, `Secure` and `SameSite=Strict`; the session cookie is `HttpOnly`;
- web setup and startup bootstrap are mutually exclusive to prevent competing human-input adapters;
- after `SEALED`, setup authority is permanently ineffective and administrator loss is a separate recovery concern.
