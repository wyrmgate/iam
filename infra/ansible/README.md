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

Caddy/TLS configuration is intentionally deferred to Sprint 8. Application image deployment is intentionally deferred to the deployment/CD sprints.

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

Provisioning is idempotent, but it changes operating-system packages, Docker installation, firewall state, users, and directories. Run it only against an intended IAM host.
