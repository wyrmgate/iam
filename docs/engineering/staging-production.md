# Staging and Production Scaffold

## Scope

Sprint 17 establishes non-live staging and production promotion boundaries. It does not declare Wyrmgate IAM production-ready, define final HA/DR architecture, or activate any host.

## Artifact promotion

Staging and production consume an existing GitHub Release created by the Sprint 16 release pipeline. Promotion must not rebuild application artifacts. The release manifest is the source for exact server and console OCI digests.

Before an environment may consume a release, the promotion workflow verifies:

1. the requested immutable GitHub Release exists;
2. the release-manifest checksum is valid;
3. the release manifest's Sigstore bundle verifies against the trusted `release.yml` workflow identity.

The standard topology examples use digest references, not mutable release tags.

## Protected environments

Create GitHub Environments named `staging` and `production` before activation. Production should require explicit reviewer approval and should use stronger environment protection than staging.

Sprint 17 does not place deployment credentials in these environments because no host deployment is enabled yet.

## Standard single-node topology

`deploy/compose/standard.yml` is a reusable single-node topology compatible with the existing DEV/DEMO model:

- PostgreSQL authoritative relational store;
- server and console using exact released image digests;
- internal OpenTelemetry collector;
- isolated internal/edge network names and PostgreSQL volumes per environment;
- external edge network, leaving TLS/routing to the existing edge boundary.

This topology is a compatibility scaffold, not the final production availability architecture.

## Production activation gate

Production deployment must remain disabled until the project explicitly resolves and documents at least:

- target infrastructure/provider and network topology;
- database service/topology and operational privilege model;
- backup retention, off-host storage, tested recovery objectives, RPO and RTO;
- HA/failover requirements and failure domains;
- ingress/TLS/DNS and rate/abuse controls;
- observability backend and alerting ownership;
- secrets delivery and host trust/SSH policy;
- deployment rollback and database migration compatibility policy;
- capacity and scaling assumptions;
- incident response and change approval expectations.

If those decisions materially alter the architecture baseline, record them in an ADR and fold them into the appropriate formal specification checkpoint.

## Staging activation

Staging may be activated earlier as a production-like validation environment, but it still requires explicit infrastructure, DNS, secrets and operational ownership. It should exercise the same release artifacts and migration model intended for production.

## What the workflow does not do

`Environment Promotion Scaffold` deliberately performs no SSH, registry login on a host, Compose start, database migration or public DNS change. A successful workflow run means only that release evidence passed the promotion boundary for the protected environment.

This prevents a scaffold from being mistaken for a safe production deployment implementation.
