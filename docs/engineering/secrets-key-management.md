# Secrets and Signing-Key Management

## Scope

Sprint 11 establishes the secret-handling boundary and signing-key provider abstraction. It does not implement OAuth/OIDC token issuance, automatic key rotation, Vault, cloud KMS, or HSM integrations yet.

## Secret classes

Secrets include database passwords, connector credentials, SMTP/API credentials, OAuth client secrets, cloud credentials, encryption identities, and private signing keys. They are never committed in plaintext, logged, placed in ordinary API responses/events, or embedded in container images.

Version-controlled sensitive configuration must be encrypted with SOPS using age recipients. The age public recipient may be committed; the age private identity must not be committed. `deploy/secure-config/.sops.yaml.example` is a template only and is intentionally not a usable production recipient.

## Runtime delivery

Environment deployment workflows receive live values from GitHub Environment secrets and materialize host-only configuration during deployment. Runtime secret files should live under `/etc/wyrmgate/iam` (or the isolated DEMO equivalent where applicable), be owned by root or the Wyrmgate service account, and normally use mode `0600`.

Release bundles must not become a long-term secret archive. When deployment scripts currently materialize environment files inside release directories, access remains host-restricted; later hardening may move all mutable secret material into `/etc/wyrmgate/iam` and reference it from immutable release manifests.

## SigningKeyProvider

Cryptographic consumers depend on `SigningKeyProvider` rather than a concrete secret system.

Initial provider families are:

- **File** — local/DEV/DEMO and constrained standalone deployments.
- **Vault** — future adapter for Vault-managed keys/secrets.
- **CloudKMS** — future adapter for managed cloud key services.
- **HSM** — future adapter for hardware-backed key operations.

The port exposes public metadata for the active signing key and a signing operation. It deliberately does **not** expose a Java `PrivateKey`, because production providers may use non-exportable keys held by Vault transit, cloud KMS, or an HSM.

The current file adapter accepts a PKCS#8 PEM private key and X.509 PEM public key. It parses them once at construction, keeps the private key inside the adapter, exposes only public metadata, and performs signatures through the provider operation.

The adapter does not generate keys, rotate keys, choose JWT/JWS algorithms, publish JWKS, or persist private material. Those responsibilities belong to later authorization-platform/key-lifecycle work.

## File-provider host layout

A recommended host layout is:

```text
/etc/wyrmgate/iam/
  keys/
    signing-private.pem   # 0600
    signing-public.pem    # 0644 or stricter
```

Private key files must not be mounted into unrelated containers. Only the IAM server process that performs signing should receive access.

## Rotation direction

Future rotation requires overlap: a new signing key becomes active for signing while previously published public keys remain available long enough to validate tokens issued before the switch. The provider abstraction therefore must not be interpreted as a permanent single-key model; Sprint 11 only defines the first active-key boundary.

## CI enforcement

`Secrets Contract` rejects obvious tracked private-key files, restricts `deploy/secure-config` to documentation/templates or SOPS-encrypted naming, and runs the file-provider signing test. Existing secret scanning remains the broader detection control.

Filename policy is only a repository guardrail; a `*.sops.*` name does not itself prove encryption. Before real encrypted configuration is committed, CI should additionally validate the file structure with SOPS or an equivalent parser rather than relying on naming alone.

## Operational rules

- Never print secret values during CI/CD troubleshooting.
- Prefer short-lived/job-scoped credentials over long-lived PATs.
- Rotate a secret after suspected exposure rather than merely deleting it from the latest commit.
- Keep production signing keys outside application source/config repositories even when other configuration is SOPS-encrypted.
- Treat public DEMO as hostile Internet exposure; it must never reuse DEV/production keys or credentials.
