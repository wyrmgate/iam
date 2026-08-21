# Secure configuration

This directory is for encrypted, version-controlled configuration only.

## SOPS + age

1. Generate an age key pair outside the repository.
2. Store the age private key in an operator secret store or CI environment secret; never commit it.
3. Copy `.sops.yaml.example` to `.sops.yaml` and replace the placeholder with the age public recipient.
4. Encrypt sensitive files with SOPS and commit only files named `*.sops.yaml`, `*.sops.yml`, `*.sops.json`, or `*.sops.env`.
5. Decrypt only at deployment/runtime boundaries into restricted host paths such as `/etc/wyrmgate/iam`.

The repository must never contain decrypted credentials, private signing keys, age identities, database passwords, OAuth client secrets, SMTP/API credentials, or cloud-provider credentials.

## Runtime secret files

DEV/DEMO deployment workflows materialize secrets from GitHub Environment secrets. Host-side secret/config files must be owned by root or the Wyrmgate service account and mode `0600` unless a narrower read-only group is explicitly required.

Signing private keys for the file-backed provider belong under a restricted host-only path such as `/etc/wyrmgate/iam/keys/`. Public keys may be less restricted but should be managed with the same release/config discipline.
