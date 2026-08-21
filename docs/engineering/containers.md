# Container and Registry Baseline

## Scope

Sprint 5 establishes production-oriented container images and GitHub Container Registry publishing for the Wyrmgate IAM server and console.

## Images

- `ghcr.io/wyrmgate/iam-server`
- `ghcr.io/wyrmgate/iam-console`

Every image produced from `main` is tagged with the immutable source revision:

`sha-<full-git-sha>`

Deployments must prefer immutable SHA tags or resolved digests. Environment promotion must reuse an existing image rather than rebuild application code.

## Platforms

Published images target:

- `linux/amd64`
- `linux/arm64`

Pull-request validation builds `linux/amd64` only to control CI cost and turnaround time. Multi-architecture publication occurs once after merge to `main`.

## Server image

The server image is a multi-stage Java 25 build. Maven compiles the Spring Boot application in the build stage and only the packaged application plus Java runtime are present in the runtime stage. The application runs as a non-root UID.

## Console image

The console image is a multi-stage Node/Vite build. The final image contains only the generated static assets and Caddy static-file runtime; Node.js and source files are not part of the runtime image.

## Security

Pull requests build and scan both runtime images with Trivy. HIGH and CRITICAL vulnerabilities with an available fix block the image check.

After a merge to `main`, the workflow publishes the same source revision as a multi-architecture GHCR image and scans the published reference.

Image signing, SBOM publication, provenance attestations, and release SemVer tags are intentionally deferred to the supply-chain and release sprints.

## GHCR permissions

The publish job uses the repository-scoped `GITHUB_TOKEN` with `packages: write`. No long-lived registry password or PAT is required for normal CI publication.
