# Fresh-install bootstrap runbook

## Purpose

This runbook covers ADR-0044 standalone installation only. It establishes the first Tenant, governed administrator Identity/Principal/PASSWORD Credential, governed Wyrmgate Console SSO client and initial Administration authority, then permanently seals the deployment bootstrap.

It is **not** an administrator recovery procedure. A deployment that has reached `SEALED` never reopens installation bootstrap if all administrators are later lost.

## Preconditions

Use this path only on a fresh database with no Tenant rows and no prior `platform.installation_bootstrap` row.

The accepted first-party IdP/session configuration required for normal Console login must also be enabled. Database migrations must be current through the installation-bootstrap migration set.

Do not enable the legacy external `iam.bootstrap.initial-admin` adapter at the same time as fresh-install bootstrap.

## Prepare the initial password

Create a local file readable only by the Wyrmgate process/operator boundary. The file contains only the initial administrator password. A single trailing newline is ignored.

Example deployment configuration:

```text
IAM_BOOTSTRAP_INSTALLATION_ENABLED=true
IAM_BOOTSTRAP_INSTALLATION_TENANT_DISPLAY_NAME=Wyrmgate
IAM_BOOTSTRAP_INSTALLATION_IDENTITY_DISPLAY_NAME=Initial Administrator
IAM_BOOTSTRAP_INSTALLATION_USERNAME=admin
IAM_BOOTSTRAP_INSTALLATION_PASSWORD_FILE=/run/secrets/wyrmgate_initial_password
IAM_BOOTSTRAP_INSTALLATION_CONSOLE_REDIRECT_URI=https://wyrmgate.example/callback
```

These environment names are the Spring relaxed-binding form of the `iam.bootstrap.installation.*` properties used by the startup adapter.

The password file is external installation input. Its contents are never copied into `platform.installation_bootstrap`, ordinary IAM state, events, audit, logs or command results.

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

Restart with the same non-secret installation inputs and the same password file.

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

1. disable `IAM_BOOTSTRAP_INSTALLATION_ENABLED`;
2. remove/unmount the initial password file;
3. restart under normal configuration if needed;
4. open the Wyrmgate Console and authenticate with the configured username and password;
5. verify a protected Administration operation succeeds through normal session authentication plus Administration authorization;
6. create the normal administrator/delegation/recovery posture required for the deployment rather than retaining installation material.

The Console UI/runtime supplies its configured `client_id` and ApplicationTarget to the local authentication contract. The human login experience is username + password; those hidden routing selectors do not become user-selected Tenant input.

## Security notes

- There is no `/api/bootstrap` endpoint.
- There is no permanent `admin/admin`, root user, wildcard grant or reusable bootstrap password.
- The local verifier provider is `wyrmgate-local-password-v1` and is system-managed by Credential; ordinary Credential creation cannot select it.
- The private local verifier store contains salted one-way PBKDF2-HMAC-SHA-256 material, not plaintext or reversible password material.
- `platform.installation_bootstrap` contains non-secret plan data and the irreversible seal only.
- `administration.initial_admin_bootstrap` remains a separate permanent per-Tenant historical security marker.

## Failure after sealing

Do not attempt to delete or alter the installation seal to recover administration access. Loss of all active administrators, Credentials or Console registrations after sealing is a governed recovery problem and requires the separately accepted recovery mechanism for that deployment.
