# Managed DEV Inline Signing Keys

## Purpose

This note documents the temporary managed-DEV option for supplying Wyrmgate signing keys as deployment variables while first-party IdP/SSO activation is being verified. It is an implementation/deployment convenience only and does not change the Platform signing boundary, IdP/SSO semantics, or the rule that private signing material must never appear in ordinary APIs, logs, audit records, events, tasks, or source control.

File-backed signing remains supported and is the existing local/standalone option. The inline mode is intended for DEV/DEMO managed runtimes where mounting small secret files is operationally awkward.

## Railway DEV configuration

When signing is intentionally activated, configure:

```text
IAM_SIGNING_ENABLED=true
IAM_SIGNING_KEY_ID=<non-secret key id>
IAM_SIGNING_KEY_ALGORITHM=RSA
IAM_SIGNING_ALGORITHM=SHA256withRSA
IAM_SIGNING_PRIVATE_KEY_PEM=<sealed multiline PKCS#8 PEM>
IAM_SIGNING_PUBLIC_KEY_PEM=<multiline X.509 public-key PEM>
```

The private value must be stored as a sealed/secret deployment variable. Never commit it, paste it into issues or pull requests, emit it in logs, or expose it through an API.

`IAM_SIGNING_PRIVATE_KEY_PEM` must contain the complete standard PKCS#8 private-key PEM envelope. `IAM_SIGNING_PUBLIC_KEY_PEM` must contain the complete standard X.509 public-key PEM envelope. Preserve the original PEM header/footer and line breaks when entering each deployment variable; do not document or commit example private-key envelope text in the repository.

Configure either the paired inline `IAM_SIGNING_PRIVATE_KEY_PEM` / `IAM_SIGNING_PUBLIC_KEY_PEM` inputs or the paired file-path `IAM_SIGNING_PRIVATE_KEY_PATH` / `IAM_SIGNING_PUBLIC_KEY_PATH` inputs. Mixed inline/file configuration fails startup deliberately.

`IAM_SIGNING_VERIFICATION_KEYS` remains a file-backed retained-public-key rotation mechanism and cannot be combined with inline PEM mode. If retained-key overlap is required, use the file-backed signing configuration until a separately reviewed managed-key rotation adapter exists.

## Removal

This inline mode is intentionally easy to remove after managed DEV activation is stable. Removing it later should only require deleting the inline provider/configuration binding and returning DEV to the selected long-term signing-key adapter; no IAM domain or public API migration is implied.
