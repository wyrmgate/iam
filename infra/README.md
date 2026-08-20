# Infrastructure as Code

Infrastructure provisioning and host configuration live here.

Planned structure:

- `opentofu/` - cloud/network/compute/storage/DNS provisioning
- `ansible/` - operating-system and IAM-host configuration

Infrastructure code must remain replaceable and must not become part of the IAM domain model. Cloud-provider details stay at the infrastructure edge.

Never commit provider credentials, Terraform/OpenTofu state, private keys, or runtime secret files.
