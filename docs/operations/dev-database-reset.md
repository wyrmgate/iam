# Disposable DEV database reset

## Purpose

This runbook covers an intentional destructive reset of a disposable Wyrmgate IAM DEV database after ADR-0048 is merged and the persistence-baseline checks are green.

It is not a production recovery procedure and it is not an automatic application behavior. Never use this procedure against production or against an environment whose IAM state/evidence must be retained.

## Why reset can be appropriate in pre-release DEV

The current project is pre-release and the shared DEV database may contain installation and migration history created while the v2 persistence model was still being established. When that state has no retention requirement, recreating the DEV database from the reviewed current baseline is cleaner than carrying compatibility-only data history forward indefinitely.

A reset does **not** redefine architecture. The recreated database still uses Tenant as the isolation root, Organization as tenant-local business structure, capability-owned authoritative state, the current first-run installation flow, and the finite standalone tenant-owner authority profile.

## Preconditions

Proceed only when all of the following are true:

- the target environment is explicitly identified as disposable DEV/non-production;
- ADR-0048 and its baseline/equivalence checks are merged on the exact `main` SHA that will be deployed;
- no required IAM business state, Audit evidence, diagnostic data or migration evidence exists only in the target database;
- any evidence that must be retained has been exported or otherwise preserved through the appropriate supported mechanism;
- the application is stopped or isolated from the database so no writer can race the reset;
- the operator has positively identified the intended Neon project/branch/database and has separately checked that no production/demo environment shares it;
- deployment secrets remain in the deployment secret store and are not copied into tickets, logs, shell history or this repository.

If any of these conditions is uncertain, do not drop the database.

## Preferred reset shape

For managed DEV, prefer creating a new empty isolated Neon database/branch and switching the DEV deployment to it over destructively reusing an existing database in place. This keeps the old DEV state available briefly for comparison or rollback until the new installation is verified.

If the environment policy instead requires dropping and recreating the same DEV database, perform that operation through the database provider/operator tooling while the application is stopped. Do not implement database drop/recreate SQL as a Wyrmgate startup hook, Flyway migration, or application endpoint.

The repository deliberately does not embed credentials or a universal destructive command because provider/database administrative identities and database naming are deployment-specific.

## Establish the empty database

Deploy the reviewed current `main` build against the new empty database using the normal `IAM_DB_URL`, `IAM_DB_USER` and `IAM_DB_PASSWORD` secret inputs.

Flyway must establish the schema from the current source-controlled baseline migration and then apply any later versioned migrations. V1-V69 remain historical upgrade migrations for databases that already carry that history; V70 remains withdrawn and must not be recreated or reused.

Verify before bootstrap:

1. application/Flyway startup succeeds on the empty database;
2. `flyway info` reports no failed, unresolved or pending migration unexpectedly;
3. the repository's current application schemas exist;
4. no pre-existing Tenant, installation bootstrap, Identity, Principal, Credential, Administration authority or Audit data exists in the new database.

Do not copy old `flyway_schema_history` rows into the recreated database.

## Run the fresh installation flow

A recreated empty database is a **fresh installation**, not ADR-0047 recovery.

For shared managed DEV, use the ADR-0045 first-run web setup adapter documented in [`fresh-install-bootstrap.md`](fresh-install-bootstrap.md) and [`first-run-web-setup.md`](first-run-web-setup.md):

1. enable the first-run web setup boundary using the deployment-managed setup secret;
2. initialize the first Tenant and first-party administrator;
3. allow the durable installation plan to reach irreversible `SEALED`;
4. verify normal first-party Console authentication;
5. verify representative protected tenant-owner operations;
6. remove the temporary setup secret and disable the first-run web adapter after sealing.

Fresh ADR-0046 bootstrap creates the explicit finite `STANDALONE_TENANT_OWNER` authority directly. Do **not** run ADR-0047 recovery on the newly recreated installation.

## Verification after reset

Record non-secret evidence for:

- deployed `main` SHA;
- database/project/branch identifier appropriate for DEV operations;
- Flyway current status and baseline path success;
- first-run installation reaching `SEALED`;
- normal Console authentication;
- representative protected Administration/Identity/Catalog operations;
- tenant-isolation negative checks;
- absence of unexpected V70 history;
- removal of the temporary setup-access secret.

Keep the prior DEV database/branch only as long as the environment retention policy requires. Delete the superseded disposable database/branch only after the recreated environment is verified.

## What reset must never become

- no automatic startup database drop;
- no Flyway `DROP DATABASE` migration;
- no unauthenticated/admin HTTP reset endpoint;
- no production shortcut;
- no reuse of V70;
- no copying historical Flyway rows into a fresh database;
- no manual broadening of Administration authority;
- no conflation of Tenant with Organization;
- no secret material in source control, ordinary logs, Audit payloads or tickets.

## Historical database alternative

If an existing sealed database must be retained rather than reset, preserve its migration history and use the compatibility mechanism appropriate to its actual state. In particular, ADR-0047 remains the supported fail-closed path for an exact legacy sealed standalone first-party bootstrap authority state. A DEV reset is optional deployment cleanup, not a replacement for compatibility semantics in retained databases.
