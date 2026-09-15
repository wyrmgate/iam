# Release Pipeline

## Scope

Sprint 16 establishes the controlled product release pipeline for Wyrmgate IAM. This is an implementation and release-governance contract. It does not change IAM domain semantics, capability ownership, API/event semantics, persistence semantics, or the formal v0.2 architecture baseline.

A release is a versioned promotion of artifacts already built from `main`. The release workflow must never rebuild application images.

## Artifact model

Container CI remains the only workflow that builds and publishes the application container images:

- `ghcr.io/wyrmgate/iam-server:sha-<git-sha>`
- `ghcr.io/wyrmgate/iam-console:sha-<git-sha>`

Sprint 15 attaches SBOM/provenance and signs those published artifacts by OCI digest. Sprint 16 consumes those existing artifacts and records their immutable digests in a versioned release manifest.

The release version is therefore a mapping:

`semantic version -> source main SHA -> exact server digest + exact console digest`

No version release may silently substitute a rebuilt or differently signed image.

## Trigger and approval boundary

The `Release` GitHub Actions workflow is manual (`workflow_dispatch`). It requires:

- a semantic version without a leading `v`, for example `0.3.0`;
- a full 40-character lowercase Git commit SHA;
- an explicit prerelease choice.

The release job runs in the protected GitHub Environment named `release`. Environment protection/reviewer policy should be configured before the first controlled release.

The job has only the permissions needed for the release operation:

- `contents: write` to create the Git tag/release and upload release assets;
- `packages: read` to inspect the already-published GHCR artifacts;
- `id-token: write` for short-lived Sigstore keyless signing of the release manifest.

No long-lived release-signing private key is introduced.

## Source revision validation

Before release creation, the workflow checks that the requested revision:

1. is syntactically a full Git SHA;
2. exists in repository history;
3. is reachable from current `main`.

This prevents the release mechanism from publishing an arbitrary feature-branch commit as an official product release.

A release may intentionally target an older commit still reachable from `main` when an operator needs to release a previously validated revision. The workflow does not require the target to equal the current tip of `main`.

## Artifact resolution and verification

For each application image, the workflow resolves the immutable digest behind `sha-<target-sha>` and rejects the release if a digest cannot be resolved.

Before any GitHub Release is created, both exact digests must pass Cosign verification against the Sprint 15 publishing identity:

- certificate identity: `https://github.com/wyrmgate/iam/.github/workflows/container.yml@refs/heads/main`;
- OIDC issuer: `https://token.actions.githubusercontent.com`.

This proves that the release is promoting artifacts produced and signed by the trusted `main` container workflow rather than merely trusting the existence of a registry tag.

## Release manifest

The workflow creates `release-manifest.json` containing:

- manifest schema version;
- Wyrmgate IAM product name;
- semantic product version;
- source revision;
- creation timestamp;
- server repository, digest and source tag;
- console repository, digest and source tag.

It also creates a SHA-256 checksum file for the manifest.

The manifest is evidence that ties the human-facing product version to the exact immutable OCI artifacts. It is not a replacement for the images' existing SBOM, provenance, or Cosign signatures.

## Manifest signing

`release-manifest.json` is signed using Cosign keyless blob signing. The signing identity is the release workflow itself:

- certificate identity: `https://github.com/wyrmgate/iam/.github/workflows/release.yml@refs/heads/main`;
- OIDC issuer: `https://token.actions.githubusercontent.com`.

The resulting Sigstore bundle is immediately verified by the workflow before the release is created.

Release assets are:

- `release-manifest.json`;
- `release-manifest.json.sha256`;
- `release-manifest.sigstore.json`.

## GitHub Release and tag

After all artifact and manifest verification succeeds, the workflow creates Git tag `v<version>` and the matching GitHub Release against the requested source SHA.

The workflow refuses to reuse an existing tag or release version. Release versions are immutable identifiers; a correction requires a new version rather than silently replacing artifacts behind an existing version.

GitHub-generated release notes are used as the initial change summary. A future release-management phase may add curated changelog generation if product release requirements need richer customer-facing notes.

## Prereleases

The workflow supports marking the GitHub Release as a prerelease. During the current pre-release development stage, prerelease should normally remain enabled unless the project has deliberately reached an approved stable release checkpoint.

A GitHub prerelease flag is release metadata only. It does not weaken artifact verification, signature requirements, or environment approval.

## Deployment relationship

Creating a product release does not deploy it anywhere.

The active managed DEV/testing/demo environment does not consume the GHCR release images as its deployment mechanism. Cloudflare Pages and Railway Git integrations build and deploy reviewed `main` revisions under the provider controls documented in [`dev-cd.md`](dev-cd.md); GitHub Actions validates that contract but is not the DEV deployment authority.

The signed GHCR images and release manifest remain the controlled immutable artifact path for staging/production promotion and for any optional standalone-host/reference environment that deliberately consumes released images. The retained standalone DEMO/host material is reference-only and must not be confused with the active managed DEV topology.

A future staging or production deployment implementation must preserve the release manifest's exact artifact identity rather than rebuilding application images during promotion.

## Verification contract

The `Release Contract` workflow runs on pull requests that change the release pipeline. It verifies that the release workflow retains:

- protected `release` environment usage;
- least-required release permissions;
- `main` ancestry validation;
- registry digest resolution;
- Cosign image verification;
- release-manifest signing and verification;
- GitHub Release creation;
- the prohibition on rebuilding application images.

The contract also installs the pinned Cosign version so a broken tool pin fails before release time.

## Tool pins

Sprint 16 intentionally uses the same Sigstore pins as Sprint 15:

- `sigstore/cosign-installer@v4.1.2`;
- Cosign `v3.1.2`.

Keeping the pins aligned avoids two independent signing tool baselines inside the same repository.

## Operational release checklist

Before triggering a release:

1. confirm the target SHA is a reviewed `main` revision;
2. confirm Container CI published and signed both server and console images for that SHA;
3. choose a new semantic version that has never been used;
4. confirm the `release` GitHub Environment protections are appropriate;
5. trigger the workflow with the exact SHA and prerelease setting;
6. verify the workflow confirms both container signatures;
7. verify the signed release manifest names the expected SHA and digests;
8. verify the GitHub Release/tag points to the requested source revision.

## Deferred release hardening

Sprint 16 does not yet implement:

- production/staging deployment from release manifests;
- automatic changelog classification or customer-facing release-note curation;
- release approval rules encoded outside GitHub Environment protection;
- offline export of container SBOM/provenance attestations;
- package formats other than the current container artifacts;
- long-term release archival outside GitHub/GHCR;
- enforced deployment admission that rejects an artifact not present in a trusted release manifest.

Those are later implementation/operations concerns and must not be claimed as present until implemented.
