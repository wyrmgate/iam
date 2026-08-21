# Software Supply-Chain Baseline

## Scope

This document defines the initial software supply-chain controls for Wyrmgate IAM container artifacts. It is an implementation/security pipeline contract and does not change IAM domain semantics or capability ownership.

## Published artifact identity

Published server and console images use immutable commit-derived tags and must also be consumed by digest for verification-sensitive operations.

Current repositories:

- `ghcr.io/wyrmgate/iam-server`
- `ghcr.io/wyrmgate/iam-console`

A tag such as `sha-<git-sha>` is a discovery label. The OCI digest is the immutable artifact identity used for scanning, signing and verification.

## Build attestations

The `Container CI` publish job produces BuildKit attestations for each multi-platform image:

- software bill of materials (SBOM);
- maximum-detail build provenance.

These attestations are attached to the published OCI image/index in the registry. They are generated from the same build that publishes the image; a separate rebuild is not permitted for attestation generation.

The SBOM is evidence about packaged components, not a statement that every transitive runtime risk has been eliminated. Vulnerability scanning remains a separate control.

## Vulnerability scanning

PR validation builds local server and console images and blocks HIGH/CRITICAL findings according to the existing Trivy policy (`ignore-unfixed: true`).

On `main`, the published image is scanned by immutable digest before signing. A failed required scan prevents the signing step from executing.

## Signing

Published images are signed by immutable digest using Sigstore Cosign keyless signing.

The GitHub Actions job receives a short-lived GitHub OIDC token through `id-token: write`. No long-lived Cosign private signing key is stored in repository or GitHub secrets for this baseline.

Current tool pins:

- `sigstore/cosign-installer@v4.1.2`;
- Cosign `v3.1.2`.

Signing occurs only for `main` image publication after the image has been built, pushed and scanned successfully.

## Signature verification identity

The pipeline immediately verifies the resulting signature using both:

- certificate identity: `https://github.com/wyrmgate/iam/.github/workflows/container.yml@refs/heads/main`;
- OIDC issuer: `https://token.actions.githubusercontent.com`.

Verification must use an immutable image digest. Consumers must not treat possession of a mutable tag as equivalent to signature verification.

If the publishing workflow path, repository ownership or trusted GitHub identity changes, the verification identity is a security-sensitive contract and must be deliberately updated.

## Trust and threat boundary

These controls provide evidence that a published artifact was produced by the trusted repository workflow and expose build/package metadata for inspection. They do not independently protect against every compromise scenario.

Important remaining controls include:

- protected `main` and reviewed pull requests;
- least-privilege workflow permissions;
- pinned/controlled workflow dependencies;
- container vulnerability scanning;
- deployment by immutable digest where practical;
- controlled release promotion;
- future policy enforcement that rejects unsigned/untrusted artifacts before deployment.

A compromised trusted workflow can produce valid signatures. Workflow review and repository governance therefore remain part of the trust root.

## Verification example

Given a known digest:

```bash
cosign verify \
  ghcr.io/wyrmgate/iam-server@sha256:<digest> \
  --certificate-identity 'https://github.com/wyrmgate/iam/.github/workflows/container.yml@refs/heads/main' \
  --certificate-oidc-issuer 'https://token.actions.githubusercontent.com'
```

The same policy applies to the console image.

## Operational checks

After a successful `main` container publication:

1. confirm both image jobs published an immutable `sha-<git-sha>` image;
2. confirm Trivy scanned the exact published digest;
3. confirm BuildKit produced SBOM and provenance attestations;
4. confirm Cosign signed the exact published digest;
5. confirm the in-workflow identity/issuer verification passed;
6. confirm no private signing key or long-lived signing credential was introduced.

## Deferred hardening

Sprint 15 does not yet require:

- admission/deployment policy that rejects unsigned images;
- SLSA level claims beyond the actual BuildKit provenance emitted by the workflow;
- offline release bundles of SBOM/provenance/signatures;
- independent transparency-log mirroring;
- organization-wide artifact policy enforcement;
- digest-only Compose manifests for every environment.

Those controls may be introduced when release and production deployment requirements justify them. Do not claim them before they are implemented.
