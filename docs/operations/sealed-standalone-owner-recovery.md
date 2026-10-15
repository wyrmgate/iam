# Sealed standalone tenant-owner recovery

## Purpose

This runbook implements ADR-0047 for the narrow compatibility case where an ADR-0044 standalone first-party installation was already `SEALED` while its marker-linked initial administrator still had ADR-0011's original four-permission authority.

This is not general administrator recovery. It does not recover lost passwords, lost Credentials, deleted Principals, arbitrary roles, arbitrary tenants or intentionally customized Administration state. It never reopens the first-run wizard.

## Safety boundary

The recovery process:

- is operator-initiated only;
- runs the existing server executable with web serving disabled;
- accepts no tenant, Identity, Principal, role, grant, scope or permission identifiers;
- derives the sole candidate from `platform.installation_bootstrap`;
- requires that plan to be `SEALED` and at the supported bootstrap version;
- cross-checks the Administration bootstrap marker and first-party Principal anchor;
- mutates only an exact untouched ADR-0011 legacy bootstrap role;
- targets exactly the finite current `AdministrativePermissions.STANDALONE_TENANT_OWNER` set;
- treats exact already-owner state as `ALREADY_COMPLETE`;
- fails closed on any customized, partial or ambiguous state;
- never creates another Tenant, Identity, Principal, Credential, role, grant or delegation.

Do not manually update IAM domain tables as part of this procedure.

## Historical withdrawn V70 check

A short-lived pre-release commit (`74abd436e7f0b818437dd89649bfc6bedd962663`) shipped a V70 migration that automatically expanded marker-linked first-party bootstrap roles. V70 was later withdrawn because sealed authority must not be changed implicitly.

If the target database never applied V70, skip this section.

If Flyway reports `APPLIED_VERSIONED_MIGRATION_NOT_RESOLVED` for V70, first confirm that the only intentionally missing applied migration is the withdrawn V70. Do not configure a permanent `ignoreMigrationPatterns` workaround and do not restore the old V70 SQL.

Use an operator-approved Flyway CLI against the same database and the same repository migration location. Supply database credentials through the operator's secret/environment mechanism rather than embedding them in shell history:

```sh
export FLYWAY_URL="$IAM_DB_URL"
export FLYWAY_USER="$IAM_DB_USER"
export FLYWAY_PASSWORD="$IAM_DB_PASSWORD"
export FLYWAY_LOCATIONS="filesystem:apps/server/src/main/resources/db/migration"

flyway info
```

Inspect the output. Proceed only when V70 is the expected intentionally withdrawn missing migration and there is no unrelated failed, checksum-mismatched or missing migration requiring separate investigation.

Then reconcile Flyway schema-history metadata explicitly:

```sh
flyway repair
flyway info
```

`repair` marks the intentionally missing migration as deleted in Flyway schema history. It is metadata reconciliation only; it must not be accompanied by IAM domain-row edits. Use the same migration location for `info`, `repair` and subsequent Wyrmgate migration execution.

If `flyway info` shows any other unexplained migration discrepancy, stop and investigate instead of running recovery.

## Run recovery

Use the normal Wyrmgate database configuration for the target deployment. For a repository checkout using the project Maven wrapper:

```sh
sh ./apps/server/mvnw \
  -f apps/server/pom.xml \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=none --iam.recovery.standalone-tenant-owner.enabled=true" \
  spring-boot:run
```

The process must be run in an operator-controlled environment with the target deployment's `IAM_DB_URL`, `IAM_DB_USER` and `IAM_DB_PASSWORD` already supplied through its normal secret mechanism. No installation password, administrator password, Credential secret, signing key, tenant ID, role ID or grant ID is an input to this command.

For a packaged server artifact, pass the same two application arguments to the normal Java process:

```sh
java -jar wyrmgate-iam-server.jar \
  --spring.main.web-application-type=none \
  --iam.recovery.standalone-tenant-owner.enabled=true
```

The executable exits after the explicit recovery runner completes.

## Expected results

### `RECOVERED`

The deployment proved the exact legacy state. Administration atomically expanded only the marker-linked bootstrap role from exact `INITIAL_TENANT_ADMIN` membership to exact `STANDALONE_TENANT_OWNER` membership and advanced the role revision once.

Run the command a second time only if operational verification requires an idempotency check; it must return `ALREADY_COMPLETE` and perform no authority mutation.

### `ALREADY_COMPLETE`

The deployment already has the exact finite owner state and every lineage/grant/exclusivity check passed. This includes a fresh ADR-0046 bootstrap at role revision 1 and the explainable single-expansion revision-2 state produced by historical V70 or ADR-0047.

No authority mutation occurred.

### Not eligible / failure

Stop. Do not alter the role, grant, marker, installation plan or Flyway history to make the predicate pass.

A denial means recovery could not prove the exact supported compatibility state. Examples include an external-subject bootstrap, non-SEALED installation, marker mismatch, renamed role, extra/missing permission, changed scope/validity, changed grantability/delegability, revised grant, additional direct grant, delegation, unsupported revision or partial owner profile.

Investigate the current authoritative state and decide on a separate governed repair design if needed.

## Post-recovery verification

After a `RECOVERED` or `ALREADY_COMPLETE` result:

1. start Wyrmgate normally, without either recovery argument;
2. authenticate through the normal first-party Console path;
3. verify representative protected tenant-owner operations through normal authorization;
4. verify the recovery AuditRecord exists with the bootstrap correlation context and no secret material;
5. remove any temporary Flyway CLI secret environment used for history repair;
6. do not leave `iam.recovery.standalone-tenant-owner.enabled=true` in normal deployment configuration.

Normal Administration role/grant changes after recovery remain subject to ADR-0032 grantability and self-escalation controls.

## What this procedure never does

- no automatic startup backfill;
- no Flyway authority mutation;
- no first-run wizard reopen;
- no arbitrary tenant selection;
- no arbitrary permission input;
- no wildcard/root authority;
- no evaluator bypass;
- no provider call in the Administration transaction;
- no Tenant/Identity/Principal/Credential/role/grant/delegation creation;
- no secret or private credential data in command arguments, audit payloads or recovery errors.
