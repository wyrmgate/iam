# OpenTofu

OpenTofu owns cloud machine and network infrastructure. Ansible owns host configuration, and Docker Compose owns the IAM runtime. Provider-specific details must not leak into the IAM domain.

## Sprint 6 baseline

This directory defines the initial OCI demo-host foundation:

- one VCN and public subnet
- internet gateway and default route
- inbound HTTP/HTTPS
- SSH restricted to an explicit administrator CIDR
- one configurable OCI flexible compute instance
- public IP and infrastructure identifiers as outputs

No cloud resources are applied by CI. Sprint 6 only establishes versioned configuration plus formatting/init/validation checks.

## Toolchain

- OpenTofu 1.12.5
- Oracle OCI provider 8.25.0

Both are pinned deliberately for reproducibility. Version updates should come through reviewed pull requests.

## Authentication

Do not put OCI credentials in `.tf` or `.tfvars` files. The OCI provider should authenticate from the standard OCI CLI configuration/environment available to the operator or deployment runner.

Only an SSH **public** key belongs in the configuration input. Private SSH keys, OCI API private keys, state files, and real environment variable files must never be committed.

## Local validation

From the repository root:

```sh
make iac-fmt
make iac-init
make iac-validate
```

For an eventual local plan, copy the example and replace all placeholders:

```sh
cp infra/opentofu/terraform.tfvars.example infra/opentofu/terraform.tfvars
tofu -chdir=infra/opentofu plan
```

`terraform.tfvars` and OpenTofu state are ignored by Git.

## OCI inputs

The image OCID and availability domain are explicit inputs because they are region-specific. This avoids CI needing live OCI credentials merely to validate the configuration. Before a real apply, select a supported current image for the target region and confirm capacity for the requested shape.

The default compute sizing is `VM.Standard.A1.Flex`, 1 OCPU, and 6 GB RAM. It is intended as a cheap demo baseline, not a production sizing guarantee. OCI capacity and free-tier eligibility must be checked at provisioning time.

## State

Sprint 6 does not define a remote state backend. During bootstrap, state is local and must never be committed. A durable remote-state design should be introduced when a real shared environment is provisioned, rather than creating state infrastructure speculatively.

## Security boundary

Ports 80 and 443 are public by design for the eventual edge proxy. Port 22 requires a narrow `admin_cidr`; the configuration rejects world-open SSH. Database, Valkey, and telemetry ports are not exposed by this network baseline.
