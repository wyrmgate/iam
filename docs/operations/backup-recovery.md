# Backup and Recovery

## Scope

This document defines PostgreSQL backup/recovery operational baselines for Wyrmgate IAM development, demo, and constrained standalone deployments. It does not define final production RPO/RTO, cross-region disaster recovery, continuous archiving/PITR policy, or the production managed-database strategy.

PostgreSQL is an implementation choice for the initial authoritative relational store. Backup and recovery procedures therefore belong to operations, not to canonical IAM domain semantics.

## Active managed DEV recovery contract

The active DEV/testing/demo environment uses **Neon PostgreSQL**. Its primary recovery mechanism is Neon provider-native restore history / point-in-time recovery for the configured project and plan, not the standalone-host `pg_dump` timer described later in this document.

Neon restore capability is plan-dependent and provider-controlled. As of the current Neon plan documentation:

- Free supports Instant Restore up to 6 hours or 1 GB of changes, whichever is smaller;
- Launch can configure a restore window up to 7 days;
- Scale can configure a restore window up to 30 days.

These limits are external provider facts and may change. The Neon console for the actual `wyrmgate-iam-dev` project is the operational source of truth for the active plan, configured restore window, and available Backup & Restore controls. Do not encode a billing-plan assumption in application code or repository secrets.

Before destructive DEV tests, risky migrations, or data resets, operators should confirm that the intended recovery point is inside the current restore window. A recovery drill should restore to a disposable branch or otherwise isolated target first when possible, verify expected data/schema state, and remove the disposable recovery object afterward.

Provider-native PITR improves recovery speed but is not the same thing as an independently retained logical backup. If DEV requires retention beyond the selected Neon plan, cross-provider portability, or protection against provider-account loss, add an external `pg_dump`/`pg_restore` process and protect that backup independently.

The managed DEV activation checklist in [`dev-managed-activation.md`](dev-managed-activation.md) includes the required plan/window verification and recovery drill.

## Standalone-host backup contract

For optional/reference standalone PostgreSQL deployments, `deploy/scripts/backup-postgres.sh` creates a PostgreSQL custom-format dump using `pg_dump`, writes it atomically through a temporary file, restricts file permissions, writes a SHA-256 sidecar, and removes backups older than the configured retention period.

The custom format is compressed by PostgreSQL and is suitable for `pg_restore`. A backup is not considered usable merely because a dump command returned successfully; recovery must be exercised.

Recommended host layout:

```text
/var/backups/wyrmgate/iam/
  wyrmgate-<database>-YYYYMMDDTHHMMSSZ.dump
  wyrmgate-<database>-YYYYMMDDTHHMMSSZ.dump.sha256
```

Backup directories should be mode `0700`; dump and checksum files should normally be mode `0600`. Backup media inherits the sensitivity of the authoritative database and must not be published as CI artifacts or copied into application images.

## Standalone frequency and retention

The low-cost standalone baseline is one logical backup per day with seven days of host-local retention. Operators may choose a longer window. Production policy must be set from explicit business RPO/RTO requirements rather than inheriting this development baseline.

Host-local retention is not disaster recovery. Before production readiness, backups must be replicated to storage that survives loss of the application host and must have an independently tested restore path.

## Standalone restore contract

`deploy/scripts/restore-postgres.sh` is deliberately destructive. It requires `IAM_RESTORE_CONFIRM=RESTORE`, verifies the SHA-256 sidecar, stops application writers, recreates the target database, restores with `pg_restore --exit-on-error`, and performs a post-restore connectivity check.

A restore must be executed against an isolated or explicitly approved target. Do not restore a production dump into a shared development environment without reviewing tenant, credential, audit, and personal-data implications.

## Automated recovery proof

`Backup Recovery Contract` creates a disposable PostgreSQL instance, writes representative state, invokes the real backup script, destroys the original state, invokes the real restore script, and verifies the data returned. This proves the repository standalone backup/restore scripts work together on every relevant pull request.

The CI test is a mechanical restore proof for that standalone path. It is not a substitute for periodic operational restore drills against the active managed DEV provider or for realistic production recovery exercises.

## Standalone scheduling

Standalone DEV/DEMO hosts may schedule the backup script with systemd timers or cron. Generic scheduler infrastructure inside the IAM application must not own database-backup policy.

Example daily host invocation:

```text
/opt/wyrmgate/iam/current/deploy/scripts/backup-postgres.sh \
  /opt/wyrmgate/iam/current/deploy/config/dev.env \
  /opt/wyrmgate/iam/current/deploy/compose/dev.yml \
  wyrmgate-iam-dev \
  /var/backups/wyrmgate/iam \
  7
```

The same pattern applies to a standalone DEMO deployment with its isolated project name and configuration. This scheduling example is not the active managed Neon DEV path.

## Operational checks

- For managed Neon DEV, periodically verify the active plan, configured restore window, and provider restore workflow.
- Exercise a point-in-time recovery to a disposable/isolation-safe target after material provider-plan changes and before risky destructive testing.
- If independent logical backups are enabled, verify their integrity and perform isolated `pg_restore` drills.
- For standalone hosts, alert if the expected daily backup is absent or zero length.
- Verify checksum sidecars before standalone restores.
- Keep backup credentials and storage credentials out of logs and ordinary telemetry.
- Record restore drills and failures as operational evidence.
- Treat backup deletion as retention housekeeping, not secure erasure of all replicas.

## Future production work

Production readiness may add independently encrypted off-provider copies, object-storage lifecycle policy, PostgreSQL physical backup/WAL archiving, managed-provider PITR policy, explicit RPO/RTO targets, larger-scale restore timing tests, and HA/DR runbooks. Those choices must be made against the separate production topology and availability requirements; the managed DEV posture does not settle OD-005.
