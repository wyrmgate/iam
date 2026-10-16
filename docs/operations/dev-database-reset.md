# Disposable DEV database reset

## Purpose

This runbook covers an intentional destructive reset of a disposable Wyrmgate IAM DEV database after ADR-0048 and ADR-0049 are merged and the persistence checks are green.

It is not a production recovery procedure and it is not automatic application behavior. Never use this procedure against production or an environment whose IAM state or evidence must be retained.

## Why reset is appropriate at this pre-release checkpoint

ADR-0048 replaces the former development-era Flyway V1-V69 chronology with a clean current-state baseline for new empty databases. ADR-0049 adds the first semantic delta at V11 for explicit deployment/tenant owner authority and platform-owned application offerings. The old SQL migrations were removed from the repository after exact schema equivalence was proven.

The reset does not redefine architecture. The recreated database still uses Tenant as the isolation root, Organization as tenant-local business structure, capability-owned authoritative state, the current first-run installation flow, explicit first-Tenant `TENANT_OWNER`, and explicit deployment-scoped `PLATFORM_OWNER` authority.

## Preconditions

Proceed only when all of the following are true:

- the target is explicitly disposable DEV/non-production;
- ADR-0048 and ADR-0049 and their persistence/tenant checks are merged on the exact `main` SHA that will be deployed;
- no required business state, Audit evidence, diagnostics or migration evidence exists only in the target database;
- any evidence that must be retained has been exported through a supported mechanism;
- the application is stopped or isolated so no writer can race the reset;
- the operator has positively identified the intended database/project/branch and verified that no production/demo environment shares it;
- deployment secrets remain in the deployment secret store and are not copied into tickets, logs, shell history or the repository.

If any condition is uncertain, do not drop the database.

## Preferred reset shape

For managed DEV, prefer creating a new empty isolated database/branch and switching DEV to it after verification instead of destructively reusing the existing database in place. This keeps the previous DEV state temporarily available for comparison/rollback.

If policy requires dropping and recreating the same DEV database, do that through database provider/operator tooling while the application is stopped. Do not implement `DROP DATABASE` as a Wyrmgate startup hook, Flyway migration or application endpoint.

## Establish the empty database

Deploy the reviewed current `main` build against an empty database using the normal secret inputs.

The active Flyway sequence at the ADR-0049 checkpoint is:

1. `V1__create_capability_schemas.sql`
2. `V2__platform_baseline.sql`
3. `V3__identity_baseline.sql`
4. `V4__catalog_baseline.sql`
5. `V5__access_baseline.sql`
6. `V6__governance_baseline.sql`
7. `V7__credential_baseline.sql`
8. `V8__integration_baseline.sql`
9. `V9__administration_baseline.sql`
10. `V10__audit_baseline.sql`
11. `V11__platform_authority_and_shared_applications.sql`
12. any legitimate later semantic migrations.

V1-V10 are the ADR-0048 capability baseline. V11 is schema-only: it adds explicit ADR-0049 platform/tenant-owner and platform-application structures but grants no authority, infers no owner and rewrites no tenant-owned Application row.

The former V1-V69 development migrations are no longer present in the repository or runtime migration location. Do not restore/copy them into a fresh deployment. Historical withdrawn V70 remains absent and reserved.

A database carrying the old V1-V69 Flyway history must not be switched in place to the new namespace. Recreate disposable DEV. A retained historical environment requires a separately reviewed compatibility/cutover plan rather than edited or forged Flyway rows.

Verify before bootstrap:

1. Flyway/application startup succeeds on the empty database;
2. `flyway_schema_history` contains the capability baseline V1-V10 followed by V11 and only legitimate later migrations;
3. no failed or unresolved migration exists;
4. schemas `platform`, `identity`, `catalog`, `access`, `governance`, `credential`, `integration`, `administration` and `audit` exist;
5. ADR-0049 platform-owned tables exist while ordinary tenant-owned capability tables still satisfy the tenant-isolation verifier; and
6. the database contains no pre-existing Tenant, installation bootstrap, Identity, Principal, Credential, Administration authority or Audit business data.

Never copy historical `flyway_schema_history` rows into the recreated database.

## Run the fresh installation flow

A recreated empty database is a fresh ADR-0049 installation, not ADR-0047 recovery and not legacy Platform Owner adoption.

For shared managed DEV, use the ADR-0045 first-run web setup adapter documented in [`fresh-install-bootstrap.md`](fresh-install-bootstrap.md) and [`first-run-web-setup.md`](first-run-web-setup.md):

1. enable the first-run web setup boundary using the deployment-managed setup secret;
2. initialize the first Tenant and first-party administrator;
3. allow the Administration step to establish both the first Tenant's `TENANT_OWNER` and the deployment `PLATFORM_OWNER` for the bootstrap Identity/Principal;
4. require semantic verification of Identity, Principal, Credential, SSO registration, Tenant Owner and Platform Owner state before `SEALED`;
5. verify normal first-party Console authentication;
6. verify representative protected tenant-owner and Platform Owner operations through their distinct authority paths; and
7. remove the temporary setup secret and disable the first-run web adapter after sealing.

Do not run ADR-0047 recovery or ADR-0049 legacy owner adoption against the recreated installation. Those commands are compatibility mechanisms for retained sealed historical installations, not fresh setup steps.

## Verification after reset

Record non-secret evidence for:

- deployed `main` SHA;
- DEV database/project/branch identifier;
- Flyway current status showing V1-V10 plus V11 and legitimate later deltas;
- first-run installation reaching `SEALED`;
- current effective deployment `PLATFORM_OWNER` for the bootstrap Identity/Principal;
- current effective first-Tenant `TENANT_OWNER` authority;
- normal Console authentication;
- representative protected Administration/Identity/Catalog operations;
- representative Platform Owner-authorized platform Catalog operation where enabled;
- tenant-isolation negative checks, including preservation of tenant-local SSO routing;
- absence of historical V70;
- removal of the temporary setup-access secret.

Retire the superseded disposable database only after the recreated environment is verified and any required temporary rollback window has passed.

## What reset must never become

- no automatic startup database drop;
- no Flyway `DROP DATABASE` migration;
- no unauthenticated/admin HTTP reset endpoint;
- no production shortcut;
- no restoration or reuse of historical V70;
- no resurrection of the old V1-V69 migration chain;
- no copying or forging historical Flyway rows into a fresh database;
- no manual broadening or inference of Administration authority;
- no interpretation of a tenant GLOBAL grant as deployment authority;
- no conflation of Tenant with Organization;
- no secret material in source control, ordinary logs, Audit payloads or tickets.

## Historical database alternative

If an existing sealed database must be retained rather than reset, preserve its actual migration history and use an explicit compatibility mechanism appropriate to its state.

ADR-0047 remains the supported fail-closed path only for the exact legacy sealed standalone first-party bootstrap permission state. After that tenant-owner compatibility state is established where needed, a sealed pre-ADR-0049 first-party installation that lacks explicit owner rows uses the separately fenced [`../runbooks/platform-owner-adoption.md`](../runbooks/platform-owner-adoption.md) command. Neither operation is a substitute for recreating disposable DEV from the current baseline, and neither permits arbitrary privilege elevation.