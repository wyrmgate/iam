# First Live DEV Activation Runbook

## Scope and safety boundary

This is the ordered operator runbook for activating the first real single-node Wyrmgate IAM DEV environment. It coordinates the repository OpenTofu, Ansible, Docker Compose, DEV CD, observability, and backup/recovery contracts.

This runbook is not production HA/DR design and does not make the staging/production scaffold live. The repository-hardening change that introduced this runbook performs none of the live steps below.

Never commit or paste OCI private keys, SSH private keys, real OCIDs, database passwords, OTLP authorization values, DNS credentials, OpenTofu state, or generated plans into GitHub, documentation, issues, pull requests, CI logs, or chat.

## 1. Operator prerequisites

Before touching live infrastructure, confirm:

- the reviewed repository revision containing this runbook is on `main`;
- OpenTofu, Ansible tooling, Docker/Compose, Git, SSH tooling, and the OCI CLI/provider authentication mechanism required by your operator workstation are available;
- you have an OCI compartment and permission to create the documented network/compute resources;
- you have selected the intended OCI region, availability domain, supported Ubuntu image, flexible shape, and a narrow administrator CIDR;
- you have a dedicated SSH key pair for DEV; only the public key will be passed to OpenTofu;
- you control the intended DEV DNS name and ACME contact address;
- an OTLP/HTTP backend endpoint and credential are ready;
- you can configure the protected GitHub Environment named `dev`;
- no repository or CI job is expected to run `tofu apply` on your behalf.

Do not reuse production credentials or keys for DEV.

## 2. Establish the OCI authentication boundary

OCI authentication is an operator-side bootstrap concern. Keep the OCI API private key, security token, CLI profile, or equivalent provider credential outside the repository and outside GitHub Environment secrets used by DEV CD.

Authenticate the OCI provider using its standard local configuration/environment, then confirm the identity and target region/tenancy through the OCI CLI/provider tooling before planning. Do not copy the authentication material into `.tfvars`.

The initial bootstrap uses local OpenTofu state. Treat state and plan files as sensitive operator artifacts and keep them on controlled storage; they are ignored by Git and must not be committed.

## 3. Prepare required OpenTofu inputs

Copy the DEV-specific placeholder:

```sh
cp infra/opentofu/dev.tfvars.example infra/opentofu/dev.tfvars
```

Replace every placeholder in the ignored `dev.tfvars` with operator-verified values:

- `region`
- `compartment_ocid`
- `availability_domain`
- `image_ocid`
- `admin_cidr`
- `ssh_public_key`
- `name_prefix` — keep `wyrmgate-iam-dev` unless an explicitly reviewed naming change is required
- `vcn_dns_label` — DEV example uses `iamdev`
- `hostname_label` — DEV example uses `iamdev`
- `instance_shape`, `instance_ocpus`, and `instance_memory_gb` after capacity/cost review

Review `vcn_cidr` and `subnet_cidr` if the defaults conflict with an existing routed network. The current topology must remain one VCN, one public subnet, one Internet gateway/default route, public TCP 80/443, and SSH only from the explicit narrow administrator CIDR. Do not add public PostgreSQL, OTLP, Docker daemon, or application-internal ports.

## 4. Format, initialize, and validate OpenTofu

From the repository root:

```sh
make iac-fmt
make iac-init
make iac-validate
```

`iac-init` initializes providers with `-backend=false`; it does not provision resources.

Review `git diff` afterward. Formatting/validation must not create an unexpected tracked change.

## 5. Create the reviewed OpenTofu plan

Create a saved plan from the exact input file:

```sh
tofu -chdir=infra/opentofu plan -var-file=dev.tfvars -out=dev.tfplan
tofu -chdir=infra/opentofu show dev.tfplan
```

The plan file is ignored and must not be committed or shared through chat. A successful plan is not permission to apply.

## 6. Perform mandatory human plan review

Before apply, a human operator must verify the plan shows only the intended DEV bootstrap:

- one VCN;
- one public subnet;
- one Internet gateway and default route;
- SSH ingress only from the intended narrow `admin_cidr`;
- public TCP 80 and 443;
- no public database, telemetry, Docker daemon, or extra application ports;
- one flexible compute instance using the intended image, shape, availability domain, and SSH **public** key;
- resource display names derived from `wyrmgate-iam-dev`;
- VCN/host DNS labels derived from the DEV inputs;
- no unrelated deletion or replacement.

If the plan differs materially, stop and correct inputs/configuration. Do not approve by habit.

## 7. Apply only the reviewed plan

After the human review is complete, apply the exact saved plan:

```sh
tofu -chdir=infra/opentofu apply dev.tfplan
```

Do not run an unreviewed `tofu apply` that silently creates a new plan. Preserve the resulting local state securely and do not commit it.

## 8. Capture non-secret infrastructure outputs

Record the intended operational identifiers from:

```sh
tofu -chdir=infra/opentofu output
```

At minimum capture the compute `instance_id`, `public_ip`, `subnet_id`, and `vcn_id` in the operator's approved operational record. These identifiers are not credentials, but do not add real environment values to repository examples.

Confirm the public IP belongs to the intended DEV instance before using it anywhere else.

## 9. Independently verify and pin the SSH host key

Do not let the first network SSH connection define trust.

Use an OCI-provided trusted console/out-of-band path to obtain the new host's SSH host-key fingerprint, for example the ED25519 host public key fingerprint. Separately capture the candidate public host key from the network and compare its fingerprint to the trusted value.

An operator-side capture may use:

```sh
ssh-keyscan -t ed25519 <DEV_HOST> > /tmp/dev-known-hosts.candidate
ssh-keygen -lf /tmp/dev-known-hosts.candidate
```

`ssh-keyscan` here is only a capture mechanism **after** an independent trusted fingerprint is available; it is not the trust decision. If the fingerprints differ, stop and investigate.

After they match:

- add the verified entry for the exact host value Ansible will use to the operator's `~/.ssh/known_hosts`;
- retain the verified `known_hosts` line or lines for later GitHub `DEV_SSH_KNOWN_HOSTS`;
- test SSH with strict checking enabled;
- never paste the private SSH key into the repository or chat.

If `DEV_HOST` will be a DNS name rather than the IP, repeat the verified capture for the exact DNS host value once DNS resolves, because DEV CD requires a matching `known_hosts` entry for the exact `DEV_HOST`.

## 10. Create the private Ansible inventory

Copy the ignored inventory:

```sh
cp infra/ansible/inventory/hosts.ini.example infra/ansible/inventory/hosts.ini
```

Replace the documentation-only address with the DEV public IP/host, set the intended administrative user for the selected Ubuntu image, and point `ansible_ssh_private_key_file` to the operator-local DEV private key.

Do not commit `inventory/hosts.ini` or the key. `infra/ansible/ansible.cfg` keeps SSH host-key checking enabled.

## 11. Lint, syntax-check, and provision the host

Install/verify pinned Ansible dependencies, then run repository checks before the live playbook:

```sh
make ansible-install
make ansible-lint
make ansible-syntax
```

Review the inventory and `infra/ansible/group_vars/all.yml`, then provision only the intended DEV host:

```sh
make ansible-provision
```

Provisioning changes packages, Docker, firewall policy, users, and directories. Confirm the target inventory before execution.

## 12. Verify post-provision host state

After Ansible completes, verify at minimum:

- SSH still succeeds with strict host-key checking;
- UFW is enabled with deny-by-default incoming policy and only the intended SSH/HTTP/HTTPS rules;
- Docker Engine and the Compose plugin are installed, enabled, and running;
- `/opt/wyrmgate/iam` exists;
- `/etc/wyrmgate/iam`, `/var/lib/wyrmgate/iam`, and `/var/backups/wyrmgate/iam` exist with the intended owners/modes;
- no service is listening publicly on PostgreSQL, OTLP, Docker daemon, or IAM internal application ports;
- a reboot is not currently required by an incomplete package transaction.

Do not proceed if firewall, SSH, directory permissions, or Docker state differs from the Ansible contract.

## 13. Configure and verify DEV DNS

Create the intended DNS record for `DEV_PUBLIC_HOST` so it resolves to the captured DEV public IP. Do not publish a different environment's hostname or credentials.

Before DEV CD:

- verify public DNS returns the intended address;
- verify inbound TCP 80 and 443 can reach the host;
- ensure any DNS proxy/CDN mode preserves the intended origin TLS/ACME flow;
- keep origin TLS enabled.

Caddy obtains/renews the public certificate during deployment. DNS must be stable enough for ACME validation before the first CD run.

## 14. Prepare OTLP/observability prerequisites

Choose the DEV OTLP/HTTP backend and prepare:

- the base exporter endpoint required by `DEV_OTEL_EXPORTER_ENDPOINT`;
- the complete authorization value required by `DEV_OTEL_EXPORTER_AUTHORIZATION`.

Do not log or commit the authorization value. Confirm the backend account/project is ready to receive DEV traces and metrics.

The runtime Collector is internal-only. A healthy Collector process is not proof that export succeeds; after deployment, verify telemetry arrival and redaction according to [`../engineering/observability.md`](../engineering/observability.md).

## 15. Configure the GitHub `dev` Environment

Create or review the protected GitHub Environment named `dev` and populate exactly the current DEV CD Environment secrets:

- `DEV_HOST`
- `DEV_USER`
- `DEV_SSH_PRIVATE_KEY`
- `DEV_SSH_KNOWN_HOSTS`
- `DEV_PUBLIC_HOST`
- `DEV_ACME_EMAIL`
- `DEV_DB_PASSWORD`
- `DEV_OTEL_EXPORTER_ENDPOINT`
- `DEV_OTEL_EXPORTER_AUTHORIZATION`

`DEV_SSH_KNOWN_HOSTS` must contain the independently verified OpenSSH entry for the exact `DEV_HOST`. Do not generate it dynamically inside CI.

Apply appropriate Environment reviewer/protection rules for DEV. Secret values belong in GitHub Environment storage only, not repository examples.

## 16. Select an immutable green `main` SHA

Choose a full 40-character commit SHA that is reachable from `main` and for which all required checks are green. For the first activation, explicitly review at least:

- Core CI;
- Security CI;
- Container CI, including successful immutable image publication/scanning for that `main` SHA;
- IaC CI;
- Ansible CI;
- DEV deployment contract;
- any other required branch-protection checks on that revision.

Record the SHA in the change/activation evidence. Do not use a branch name, mutable image tag, or unverified commit.

## 17. Run the first DEV CD deployment

For an existing selected green SHA, manually dispatch `DEV CD` with the exact 40-character SHA. Future green `main` revisions may deploy automatically after successful Container CI.

Observe the workflow without printing secret values. Expected behavior is:

1. required Environment inputs are checked;
2. the SHA is validated and confirmed reachable from `main`;
3. the job materializes host-only configuration;
4. pinned SSH host identity is validated;
5. the immutable deployment bundle is copied;
6. the host performs a job-scoped GHCR login;
7. PostgreSQL/Collector start, Flyway migration runs, server/console start, then Caddy starts;
8. public HTTPS health succeeds;
9. `/opt/wyrmgate/iam/current` advances only after health succeeds;
10. GHCR credentials are removed after the attempt.

If SSH identity changes unexpectedly, the deployment must fail. Investigate out-of-band; never replace the pinned key merely to make CI pass.

## 18. Verify public health and telemetry

After a green DEV CD run:

```sh
curl --fail --silent --show-error https://<DEV_PUBLIC_HOST>/actuator/health
```

Also verify:

- the browser/HTTP client receives a trusted certificate for the expected hostname;
- the console loads through HTTPS;
- only the intentionally exposed health/API routes reach the server through Caddy;
- server, console, PostgreSQL, Collector, and Caddy containers are healthy/running as expected;
- traces and metrics arrive in the configured OTLP backend with the expected DEV deployment identity;
- secret/authorization values are absent from deployment logs and exported telemetry.

Do not treat container health alone as public activation success.

## 19. Verify backup, restore, reboot, and rollback behavior

Complete operational recovery checks before declaring first-live DEV activation finished.

**Backup:** run the repository PostgreSQL backup procedure against DEV and confirm a non-empty dump plus SHA-256 sidecar is created under the protected backup directory. Follow [`backup-recovery.md`](backup-recovery.md).

**Restore:** perform a destructive restore only against an isolated/disposable approved target using the real repository restore script and a copy of the DEV backup. Verify application-relevant data after restore. Do not make the first proof by destroying the only live DEV database.

**Reboot:** perform an approved host reboot, reconnect with the same pinned SSH identity, verify Docker/containers recover as expected, and repeat the public HTTPS health and telemetry checks.

**Rollback:** once two backward-compatible green `main` releases are available, exercise an application rollback/redeploy to the previous green SHA using DEV CD and verify public health. Confirm `/opt/wyrmgate/iam/current` reflects the last healthy release. Database schema rollback remains intentionally unsupported; expand/contract migration compatibility is required.

Also verify a failed deployment does not advance `current`. If no previous release exists, the script must stop the partial first deployment rather than leave an unverified public stack running.

Record the backup/restore/reboot/rollback evidence and any corrective action.

## 20. Completion criteria

First live DEV activation is complete only when all of the following are true:

- the applied OCI resources match the human-reviewed OpenTofu plan;
- local OpenTofu state is secured and absent from Git;
- SSH host identity was independently verified and is pinned for both operator access and DEV CD;
- Ansible provisioning and post-provision checks pass;
- DNS points the intended DEV hostname to the intended host and HTTPS uses a trusted certificate;
- all nine required `dev` Environment secrets are configured without repository exposure;
- the deployed SHA is an immutable green `main` revision with corresponding GHCR images;
- DEV CD succeeds using strict pinned SSH trust;
- public health and console access succeed;
- OTLP traces/metrics reach the configured backend without leaking credentials;
- backup creation and isolated restore are proven;
- reboot recovery is proven;
- rollback/redeploy behavior is proven with compatible green revisions;
- PostgreSQL, telemetry, Docker daemon, and internal application ports remain non-public;
- no OCI credential, SSH private key, database password, OTLP credential, DNS credential, `.tfvars`, plan, or state file was committed.

Production/staging activation and production HA/DR remain outside this runbook.
