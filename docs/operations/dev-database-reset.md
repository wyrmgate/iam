# Disposable DEV database reset

## Purpose

This runbook covers an intentional destructive reset of a disposable Wyrmgate IAM DEV database after ADR-0048 is merged and the persistence checks are green.

It is not a production recovery procedure and it is not automatic application behavior. Never use this procedure against production or an environment whose IAM state or evidence must be retained.

## Why reset is appropriate at this pre-release checkpoint

ADR-0048 replaces the former development-era Flyway V1-V69 chronology with a clean current-state baseline for new empty databases. The old SQL migrations were removed from the repository after exact schema equivalence was proven.

The reset does not redefine architecture. The recreated database still uses Tenant as the isolation root, Organization as tenant-local business structure, capability-owned authoritative state, the current first-run installation flow and the finite standalone tenant-owner authority profile.

## Preconditions

Proceed only when all of the following are true:

- the target is explicitly disposable DEV/non-production;
- ADR-0048 and its persistence/tenant checks are merged on the exact `main` SHA that will be deployed;
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

The active Flyway baseline is:

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
11. any later semantic migrations beginning at V11.

The former V1-V69 development migrations are no longer present in the repository or runtime migration location. Do not restore/copy them into a fresh deployment. Historical withdrawn V70 remains absent and reserved.

A database carrying the old V1-V69 Flyway history must not be switched in place to the new namespace. Recreate disposable DEV. A retained historical environment requires a separately reviewed compatibility/cutover plan rather than edited or forged Flyway rows.

Verify before bootstrap:

1. Flyway/application startup succeeds on the empty database;
2. `flyway_schema_history` contains the capability baseline beginning at V1 and continuing through V10, plus only legitimate later migrations;
3. no failed or unresolved migration exists;
4. schemas `platform`, `identity`, `catalog`, `access`, `governance`, `credential`, `integration`, `administration` and `audit` exist;
5. the database contains no pre-existing Tenant, installation bootstrap, Identity, Principal, Credential, Administration authority or Audit business data.

Never copy historical `flyway_schema_history` rows into the recreated database.

## Run the fresh installation flow

A recreated empty database is a fresh installation, not ADR-0047 recovery.

For shared managed DEV, use the ADR-0045 first-run web setup adapter documented in [`fresh-install-bootstrap.md`](fresh-install-bootstrap.md) and [`first-run-web-setup.md`](first-run-web-setup.md):

1. enable the first-run web setup boundary using the deployment-managed setup secret;
2. initialize the first Tenant and first-party administrator;
3. allow the durable installation plan to reach irreversible `SEALED`;
4. verify normal first-party Console authentication;
5. verify representative protected tenant-owner operations;
6. remove the temporary setup secret and disable the first-run web adapter after sealing.

Fresh ADR-0046 bootstrap creates the finite `STANDALONE_TENANT_OWNER` authority directly. Do not run ADR-0047 recovery against the recreated installation.

## Verification after reset

Record non-secret evidence for:

- deployed `main` SHA;
- DEV database/project/branch identifier;
- Flyway current status showing the V1-V10 capability baseline and legitimate later deltas;
- first-run installation reaching `SEALED`;
- normal Console authentication;
- representative protected Administration/Identity/Catalog operations;
- tenant-isolation negative checks;
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
- no manual broadening of Administration authority;
- no conflation of Tenant with Organization;
- no secret material in source control, ordinary logs, Audit payloads or tickets.

## Historical database alternative

If an existing sealed database must be retained rather than reset, preserve its actual migration history and use an explicit compatibility mechanism appropriate to its state. ADR-0047 remains the supported fail-closed path for the exact legacy sealed standalone first-party bootstrap authority state. ADR-0048 does not authorize pretending that an old database was created by the new baseline.
