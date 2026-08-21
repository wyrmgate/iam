# Ansible

Host bootstrap and operating-system configuration live here. Application deployment remains separate from host provisioning.

## Sprint 7 baseline

The `site.yml` playbook prepares Ubuntu 24.04+ hosts for Wyrmgate IAM by:

- applying safe package upgrades;
- installing Docker Engine, Buildx, and the Docker Compose plugin from Docker's official apt repository;
- creating the dedicated `wyrmgate` system account and IAM runtime/config/data/backup directories;
- enabling unattended package upgrades;
- configuring UFW with deny-by-default inbound policy and explicit SSH, HTTP, and HTTPS ingress;
- enabling and starting Docker.

Caddy/TLS configuration is intentionally owned by the deployment/edge baseline rather than host provisioning. Application images are deployed by the CD workflows.

## Sprint 13 backup timer

The host role now installs a reusable systemd service/timer for PostgreSQL logical backups. It is disabled by default because the host role is environment-neutral and must not guess whether a machine is DEV, DEMO, or a future production profile.

Enable it in trusted inventory/group variables for a persistent environment:

```yaml
wyrmgate_backup_enabled: true
wyrmgate_backup_profile: dev
wyrmgate_backup_retention_days: 14
wyrmgate_backup_calendar: '*-*-* 02:15:00'
wyrmgate_backup_randomized_delay_seconds: 900
```

The service calls the backup script from the environment's `current` release and writes outside release directories under `wyrmgate_backup_dir`. `ConditionPathExists` makes provisioning safe before the first deployment.

Public DEMO remains reset-oriented and should normally leave scheduled backups disabled unless an operator has a specific need for demo snapshots.

See `docs/operations/backup-restore.md` for the backup, restore, checksum, safety-backup, and disaster-recovery boundaries.

## Tooling

Pinned controller tooling lives in `requirements.txt` and collection dependencies in `requirements.yml`.

```bash
python -m pip install -r infra/ansible/requirements.txt
ansible-galaxy collection install -r infra/ansible/requirements.yml
```

## Inventory

Copy the placeholder inventory and replace the documentation-only address and SSH key path:

```bash
cp infra/ansible/inventory/hosts.ini.example infra/ansible/inventory/hosts.ini
```

`inventory/hosts.ini` is ignored and must not be committed. Host private keys and secrets must stay outside the repository.

## Validation

```bash
make ansible-lint
make ansible-syntax
```

## Provisioning

Review the inventory and variables before executing against a real host:

```bash
make ansible-provision
```

Provisioning is idempotent, but it changes operating-system packages, Docker installation, firewall state, users, directories, and systemd unit state. Run it only against an intended IAM host.
