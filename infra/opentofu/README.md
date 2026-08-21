# OpenTofu

OpenTofu owns cloud machine and network infrastructure. Ansible owns host configuration, and Docker Compose owns the IAM runtime. Provider-specific details must not leak into the IAM domain.

## Foundation scope

This directory defines the initial single-host OCI foundation used by constrained environments such as DEV:

- one VCN and public subnet;
- Internet gateway and default route;
- public HTTP/HTTPS ingress;
- SSH restricted to an explicit narrow administrator CIDR;
- one configurable OCI flexible compute instance;
- public IP and infrastructure identifiers as outputs.

It does not define Kubernetes, a managed database, public telemetry, production HA/DR, or a shared remote-state backend. CI validates the configuration but never applies cloud resources.

The Terraform/OpenTofu resource block names still contain the historical `demo` label. Those labels are internal state addresses only and are intentionally retained to avoid unnecessary state-address churn. OCI display names, VCN DNS label, and host label are controlled by explicit inputs.

## Toolchain

- OpenTofu 1.12.5
- Oracle OCI provider 8.25.0

Both are pinned deliberately for reproducibility. Version updates should come through reviewed pull requests.

## Authentication boundary

Do not put OCI credentials in `.tf` or `.tfvars` files. The OCI provider authenticates from the standard OCI CLI configuration/environment available to the operator running `tofu plan` or `tofu apply`.

Only an SSH **public** key belongs in the configuration input. Private SSH keys, OCI API private keys, state files, and real environment variable files must never be committed.

## Validation

From the repository root:

```sh
make iac-fmt
make iac-init
make iac-validate
```

For generic local planning, copy the generic example and replace placeholders:

```sh
cp infra/opentofu/terraform.tfvars.example infra/opentofu/terraform.tfvars
tofu -chdir=infra/opentofu plan
```

For first DEV activation, use the DEV-specific example so environment naming is explicit:

```sh
cp infra/opentofu/dev.tfvars.example infra/opentofu/dev.tfvars
tofu -chdir=infra/opentofu plan -var-file=dev.tfvars
```

Both generated input files and OpenTofu state are ignored by Git. Never commit a generated plan or state file containing environment data.

## Inputs and naming

The image OCID and availability domain are explicit because they are region-specific. `name_prefix`, `vcn_dns_label`, and `hostname_label` make resource/display/DNS identity environment-specific. The DEV example uses `name_prefix = "wyrmgate-iam-dev"` and `iamdev` DNS/host labels; it contains documentation-only placeholders for all live identifiers.

The default compute sizing is `VM.Standard.A1.Flex`, 1 OCPU, and 6 GB RAM. It is a low-cost bootstrap default, not a production sizing or free-tier guarantee. Capacity and eligibility must be checked at provisioning time.

## State

The bootstrap foundation does not define a remote state backend. During initial single-operator activation, state is local and must never be committed or shared through chat. A durable remote-state design should be introduced only when shared environment operations require it.

## Security boundary

Ports 80 and 443 are public by design for the edge proxy. Port 22 requires a narrow `admin_cidr`; validation rejects world-open SSH. PostgreSQL, OpenTelemetry, Docker daemon, and application-internal ports are not exposed by this network baseline.

The ordered first-live procedure is documented in [`../../docs/operations/dev-live-activation.md`](../../docs/operations/dev-live-activation.md). It requires a reviewed plan before apply and independent SSH host-key verification before DEV CD is enabled.
