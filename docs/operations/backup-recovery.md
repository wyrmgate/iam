# Backup and Recovery

## Scope

Sprint 13 establishes a PostgreSQL backup and restore baseline for DEV, DEMO, and constrained standalone deployments. It does not define final production RPO/RTO, cross-region disaster recovery, continuous archiving/PITR, or managed-database snapshot policy.

PostgreSQL is an implementation choice for the initial authoritative relational store. The backup procedure therefore belongs to operations, not to canonical IAM domain semantics.

## Backup contract

`deploy/scripts/backup-postgres.sh` creates a PostgreSQL custom-format dump using `pg_dump`, writes it atomically through a temporary file, restricts file permissions, writes a SHA-256 sidecar, and removes backups older than the configured retention period.

The custom format is compressed by PostgreSQL and is suitable for `pg_restore`. A backup is not considered usable merely because a dump command returned successfully; recovery must be exercised.

Recommended host layout:

```text
/var/backups/wyrmgate/iam/
  wyrmgate-<database>-YYYYMMDDTHHMMSSZ.dump
  wyrmgate-<database>-YYYYMMDDTHHMMSSZ.dump.sha256
```

Backup directories should be mode `0700`; dump and checksum files should normally be mode `0600`. Backup media inherits the sensitivity of the authoritative database and must not be published as CI artifacts or copied into application images.

## Frequency and retention

The low-cost baseline is one logical backup per day with seven days of host-local retention. Operators may choose a longer window. Production policy must be set from explicit business RPO/RTO requirements rather than inheriting this development baseline.

Host-local retention is not disaster recovery. Before production readiness, backups must be replicated to storage that survives loss of the application host and must have an independently tested restore path.

## Restore contract

`deploy/scripts/restore-postgres.sh` is deliberately destructive. It requires `IAM_RESTORE_CONFIRM=RESTORE`, verifies the SHA-256 sidecar, stops application writers, recreates the target database, restores with `pg_restore --exit-on-error`, and performs a post-restore connectivity check.

A restore must be executed against an isolated or explicitly approved target. Do not restore a production dump into a shared development environment without reviewing tenant, credential, audit, and personal-data implications.

## Automated recovery proof

`Backup Recovery Contract` creates a disposable PostgreSQL instance, writes representative state, invokes the real backup script, destroys the original state, invokes the real restore script, and verifies the data returned. This proves the repository scripts work together on every relevant pull request.

The CI test is a mechanical restore proof, not a substitute for periodic operational restore drills using realistic data volume and the actual deployment topology.

## Scheduling

DEV/DEMO hosts may schedule the backup script with systemd timers or cron. Generic scheduler infrastructure inside the IAM application must not own database-backup policy.

Example daily host invocation:

```text
/opt/wyrmgate/iam/current/deploy/scripts/backup-postgres.sh \
  /opt/wyrmgate/iam/current/deploy/config/dev.env \
  /opt/wyrmgate/iam/current/deploy/compose/dev.yml \
  wyrmgate-iam-dev \
  /var/backups/wyrmgate/iam \
  7
```

The same pattern applies to DEMO with its isolated project name and configuration.

## Operational checks

- Alert if the expected daily backup is absent or zero length.
- Verify the `.sha256` sidecar before every restore.
- Periodically restore to an isolated database and run application-level smoke checks.
- Keep backup credentials and storage credentials out of logs and ordinary telemetry.
- Record restore drills and failures as operational evidence.
- Treat backup deletion as retention housekeeping, not secure erasure of all replicas.

## Future production work

Production readiness may add encrypted off-host copies, object-storage lifecycle policy, PostgreSQL physical backup/WAL archiving and point-in-time recovery, explicit RPO/RTO targets, larger-scale restore timing tests, and HA/DR runbooks. Those choices should be made when production topology and availability requirements are concrete.
