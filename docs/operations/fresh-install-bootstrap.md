# Fresh-install bootstrap runbook

## Purpose

This runbook covers ADR-0044 standalone installation only. It establishes the first Tenant, governed administrator Identity/Principal/PASSWORD Credential, governed Wyrmgate Console SSO client and initial Administration authority, then permanently seals the deployment bootstrap.

ADR-0046 makes that first-party Administration authority an explicit standalone tenant-owner permission set so the first administrator can configure the implemented tenant surface after normal sign-in. It is not wildcard/root authority and does not bypass independent governance, assurance, secret or destructive-operation controls.

It is **not** an administrator recovery procedure. A deployment that has reached `SEALED` never reopens installation bootstrap if all administrators are later lost. ADR-0047 provides a separate, explicit compatibility command only for the exact legacy first-party bootstrap state described below.

## Preconditions

Use this path only on a fresh database with no Tenant rows and no prior `platform.installation_bootstrap` row.

The accepted first-party IdP/session configuration required for normal Console login must also be enabled. Database migrations must be current through the installation-bootstrap migration set.

Do not enable the legacy external `iam.bootstrap.initial-admin` adapter at the same time as fresh-install bootstrap.

## Prepare the initial password

The trusted startup adapter accepts exactly one of two password sources before installation is sealed:

1. `IAM_BOOTSTRAP_INSTALLATION_PASSWORD` — a temporary deployment-secret value, preferred for managed DEV/runtime platforms such as Railway where secret variables are the normal operator boundary; or
2. `IAM_BOOTSTRAP_INSTALLATION_PASSWORD_FILE` — a local operator-controlled secret file, preferred where an ephemeral secret mount/file is available.

Do not configure both. The startup adapter fails closed if both are present or if neither is present before `SEALED`.

### Managed deployment secret example

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

For `FIRST_PARTY_PRINCIPAL` bootstrap, Administration creates the marker-linked initial role from the explicit `STANDALONE_TENANT_OWNER` set defined by ADR-0046. The direct grant remains GLOBAL, active, grantable and delegable so the owner can establish narrower long-lived administrative roles through normal ADR-0032 authority-basis checks. The older `EXTERNAL_SUBJECT` bootstrap keeps ADR-0011's narrow four-permission set.

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
5. verify protected Administration and normal tenant-owner operations succeed through normal session authentication plus Administration authorization, including representative Catalog/Integration/Audit reads where enabled;
6. create the normal administrator/delegation/recovery posture required for the deployment rather than retaining installation material.

A sealed deployment checks the durable seal before resolving bootstrap secret input. Therefore normal restarts after `SEALED` do not require either password source and stale/missing bootstrap secret configuration cannot reopen installation.

The Console UI/runtime supplies its configured `client_id` and ApplicationTarget to the local authentication contract. The human login experience is username + password; those hidden routing selectors do not become user-selected Tenant input.

## Existing sealed installations created before ADR-0046

ADR-0047 now defines the supported compatibility path for a pre-ADR-0046 standalone installation that was already sealed with ADR-0011's exact narrow first-party authority. Do not rerun this fresh-install procedure, reopen setup, delete the seal, recreate the first administrator, or edit IAM domain rows directly.

Use [Sealed standalone tenant-owner recovery](sealed-standalone-owner-recovery.md). The recovery command derives the only candidate from the durable sealed installation plan and requires the Administration bootstrap marker, first-party Principal anchor, role/grant shape, four-permission membership, scope, revisions, timestamps and exclusivity checks to match the exact legacy bootstrap state. Exact owner state is an idempotent no-op; customized or ambiguous state fails closed.

A historical pre-release database that already applied the subsequently withdrawn V70 authority-expansion migration may need an explicit Flyway schema-history `repair` before normal application validation can start. That history repair is separate from authority recovery and must never be implemented by restoring the automatic V70 authority mutation or by permanently ignoring missing migrations.

## Security notes

- There is no `/api/bootstrap` endpoint and no ADR-0047 HTTP recovery endpoint.
- There is no permanent `admin/admin`, root user, wildcard grant or reusable bootstrap password.
- `STANDALONE_TENANT_OWNER` is a reviewed explicit permission set. New product permissions are not inherited automatically.
- Possession of tenant-owner permissions does not bypass typed scopes, grantability ceilings, governance approval, strong-assurance requirements, maker-checker controls, secret boundaries, break-glass semantics or destructive-operation controls.
- Managed deployment secret variables may remain recoverable in provider-internal configuration/history according to that provider's controls; remove the temporary bootstrap variable immediately after `SEALED` and use the file/secret-mount option where a tighter secret lifecycle is required.
- The local verifier provider is `wyrmgate-local-password-v1` and is system-managed by Credential; ordinary Credential creation cannot select it.
- The private local verifier store contains salted one-way PBKDF2-HMAC-SHA-256 material, not plaintext or reversible password material.
- `platform.installation_bootstrap` contains non-secret plan data and the irreversible seal only.
- `administration.initial_admin_bootstrap` remains a separate permanent per-Tenant historical security marker.

## Failure after sealing

Do not attempt to delete or alter the installation seal to recover administration access. ADR-0047 is deliberately limited to the exact legacy bootstrap-authority compatibility case; general loss of all active administrators, Credentials or Console registrations remains a separate governed recovery problem.
