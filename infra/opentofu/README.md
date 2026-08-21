# OpenTofu

OpenTofu is retained as optional/reference infrastructure for a future standalone Wyrmgate environment. It is **not** the active first DEV/testing/demo target.

The current active DEV topology is Cloudflare Pages + Pages Functions, Railway Serverless, and Neon PostgreSQL as documented in `docs/engineering/dev-cd.md`.

## Reference OCI baseline

This directory still defines the earlier OCI single-host foundation:

- one VCN and public subnet
- internet gateway and default route
- inbound HTTP/HTTPS
- SSH restricted to an explicit administrator CIDR
- one configurable OCI flexible compute instance
- public IP and infrastructure identifiers as outputs

No cloud resources are applied by CI. Keeping this configuration preserves a reviewed standalone-host option without making it a competing canonical DEV definition.

## Toolchain

- OpenTofu 1.12.5
- Oracle OCI provider 8.25.0

Both are pinned for reproducibility.

## Authentication and state

Do not put OCI credentials in `.tf` or `.tfvars` files. Only an SSH public key belongs in configuration input. Private SSH keys, OCI API private keys, state files, plans containing sensitive values, and real environment files must never be committed.

Local validation remains:

```sh
make iac-fmt
make iac-init
make iac-validate
```

Any future decision to activate this reference topology as a real environment requires a new reviewed operational decision and plan; it must not silently replace the managed DEV topology.
