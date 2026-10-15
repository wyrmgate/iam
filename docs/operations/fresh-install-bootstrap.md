# Fresh-install bootstrap runbook

## Purpose

This runbook covers the ADR-0044 **startup/headless** installation adapter. It establishes the first Tenant, governed administrator Identity/Principal/PASSWORD Credential, governed Wyrmgate Console SSO client and initial Administration authority, then permanently seals the deployment bootstrap.

Shared managed DEV does **not** use this adapter. Shared DEV uses the ADR-0045 first-run web setup wizard documented in [`first-run-web-setup.md`](first-run-web-setup.md) and [`dev-managed-activation.md`](dev-managed-activation.md). The managed DEV container rejects `IAM_BOOTSTRAP_INSTALLATION_ENABLED=true` when `IAM_DEPLOYMENT_ENVIRONMENT=dev`.

This startup/headless path remains supported for explicit standalone and automated installation scenarios.

It is **not** an administrator recovery procedure. A deployment that has reached `SEALED` never reopens installation bootstrap if all administrators are later lost.

## Preconditions

Use this path only on a fresh database with no Tenant rows and no prior `platform.installation_bootstrap` row, and only where the deployment intentionally selected the startup/headless adapter rather than ADR-0045 web setup.

The accepted first-party IdP/session configuration required for normal Console login must also be enabled. Database migrations must be current through the installation-bootstrap migration set.

Do not enable the legacy external `iam.bootstrap.initial-admin` adapter at the same time as fresh-install bootstrap. Do not enable `IAM_BOOTSTRAP_WEB_ENABLED=true` at the same time as this adapter.

## Prepare the initial password

The trusted startup adapter accepts exactly one of two password sources before installation is sealed:

1. `IAM_BOOTSTRAP_INSTALLATION_PASSWORD` — a temporary deployment-secret value for explicit automation/runtime environments that intentionally use this headless adapter; or
2. `IAM_BOOTSTRAP_INSTALLATION_PASSWORD_FILE` — a local operator-controlled secret file.

Do not configure both. The startup adapter fails closed if both are present or if neither is present before `SEALED`.

### Deployment-secret example

```text
IAM_BOOTSTRAP_INSTALLATION_ENABLED=true
IAM_BOOTSTRAP_INSTALLATION_TENANT_DISPLAY_NAME=Wyrmgate
IAM_BOOTSTRAP_INSTALLATION_IDENTITY_DISPLAY_NAME=Initial Administrator
IAM_BOOTSTRAP_INSTALLATION_USERNAME=admin
IAM_BOOTSTRAP_INSTALLATION_PASSWORD=<temporary deployment secret>
IAM_BOOTSTRAP_INSTALLATION_CONSOLE_REDIRECT_URI=https://wyrmgate.example/callback
```

Mark `IAM_BOOTSTRAP_INSTALLATION_PASSWORD` as a secret in the deployment platform. Do not place its value in source control, command output, tickets, screenshots or ordinary logs.

### Secret-file example

Create a local file readable only by the Wyrmgate process/operator boundary. The file contains only the initial administrator password. A single trailing newline is ignored.

```text
IAM_BOOTSTRAP_INSTALLATION_ENABLED=true
IAM_BOOTSTRAP_INSTALLATION_TENANT_DISPLAY_NAME=Wyrmgate
IAM_BOOTSTRAP_INSTALLATION_IDENTITY_DISPLAY_NAME=Initial Administrator
IAM_BOOTSTRAP_INSTALLATION_USERNAME=admin
IAM_BOOTSTRAP_INSTALLATION_PASSWORD_FILE=/run/secrets/wyrmgate_initial_password
IAM_BOOTSTRAP_INSTALLATION_CONSOLE_REDIRECT_URI=https://wyrmgate.example/callback
```

These environment names are the Spring relaxed-binding form of the `iam.bootstrap.installation.*` properties used by the startup adapter.

The password is ephemeral external installation input regardless of source. It is passed only to Credential enrollment/verification and is never copied into `platform.installation_bootstrap`, ordinary IAM state, events, audit, logs or command results.

## What startup does

On an enabled fresh run Wyrmgate commits a durable non-secret plan first, including all stable IDs needed for recovery. It then advances:

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

The Console Application/ApplicationTarget precedes Principal creation because the Principal must target an existing ACTIVE ApplicationTarget.

Each capability owns its authoritative mutation. The installation service does not directly insert into Identity, Catalog, Credential or Administration tables.

## Interruption and resume

Restart with the same non-secret installation inputs and the same password through the same selected secret source.

All capability object IDs are preallocated in the durable plan. If a capability transaction committed but its checkpoint did not, replay verifies/reuses the exact object instead of creating a duplicate.

For the password step, Credential uses the preallocated Credential ID as the stable private verifier reference. If verifier material already exists, the same supplied password is verified and processing resumes. A different password fails closed.

Do not edit the durable plan to skip a failed step. Resolve the underlying configuration/storage problem and restart.

## Verification and seal

The installer does not seal from command-return history alone. Before `SEALED` it verifies current semantic state:

- the planned first Tenant exists;
- the planned ACTIVE Principal resolves to the planned ACTIVE Identity on the Console target;
- normal Credential password verification succeeds using the planned PASSWORD Credential;
- the globally unique Console `client_id` resolves to the planned Tenant and Catalog registration;
- the Administration `InitialAdminBootstrap` marker names the same Identity/Principal and its grant is currently effective.

Only then does the deployment process advance `VERIFIED -> SEALED`.

## After successful bootstrap

After `SEALED`:

1. disable or remove `IAM_BOOTSTRAP_INSTALLATION_ENABLED`;
2. remove `IAM_BOOTSTRAP_INSTALLATION_PASSWORD` from deployment configuration, or remove/unmount the password file, depending on the selected source;
3. restart under normal configuration if needed;
4. open the Wyrmgate Console and authenticate with the configured username and password;
5. verify a protected Administration operation succeeds through normal session authentication plus Administration authorization;
6. create the normal administrator/delegation/recovery posture required for the deployment rather than retaining installation material.

A sealed deployment checks the durable seal before resolving bootstrap secret input. Therefore normal restarts after `SEALED` do not require either password source and stale/missing bootstrap secret configuration cannot reopen installation.

The Console UI/runtime supplies its configured `client_id` and ApplicationTarget to the local authentication contract. The human login experience is username + password; those hidden routing selectors do not become user-selected Tenant input.

## Security notes

- Shared managed DEV must use ADR-0045 web setup instead of this startup adapter.
- There is no `/api/bootstrap` endpoint.
- There is no permanent `admin/admin`, root user, wildcard grant or reusable bootstrap password.
- Deployment secret variables may remain recoverable in provider-internal configuration/history according to that provider's controls; remove temporary bootstrap material immediately after `SEALED` and prefer the file/secret-mount option where a tighter secret lifecycle is required.
- The local verifier provider is `wyrmgate-local-password-v1` and is system-managed by Credential; ordinary Credential creation cannot select it.
- The private local verifier store contains salted one-way PBKDF2-HMAC-SHA-256 material, not plaintext or reversible password material.
- `platform.installation_bootstrap` contains non-secret plan data and the irreversible seal only.
- `administration.initial_admin_bootstrap` remains a separate permanent per-Tenant historical security marker.

## Failure after sealing

Do not attempt to delete or alter the installation seal to recover administration access. Loss of all active administrators, Credentials or Console registrations after sealing is a governed recovery problem and requires the separately accepted recovery mechanism for that deployment.
