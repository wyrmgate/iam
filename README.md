# Wyrmgate IAM

Private monorepo for the Wyrmgate Identity and Access Management platform.

Wyrmgate IAM is being developed as an enterprise **IAM + Identity Provider (IdP) + Single Sign-On (SSO) + Identity Governance and Administration (IGA)** product with a modular-monolith-compatible backend and React console. It governs canonical identities, principals, credentials, access and administrative authority while also supporting first-party authentication, federation and standards-based OIDC/OAuth SSO.

The current authoritative design is maintained in the IAM v2 documentation set; repository-local implementation contracts and ADRs are added as code is introduced.

## Status

Pre-release / active development.

## Engineering direction

- modular monolith first
- bounded contexts with enforced dependency direction
- Java/Spring backend
- React frontend
- Flyway-managed database migrations
- first-party OIDC/OAuth identity-provider and SSO capability
- external identity-provider federation behind provider-neutral adapters
- REST/OpenAPI administrative APIs
- durable audit/domain-event infrastructure
- provider-specific integrations behind capability-based ports/adapters
- business governance intent separated from authentication, runtime authorization, technical realization and provider-observed state
- no permanent built-in superadmin or wildcard OAuth-to-IAM authority bridge

## Repository map

- `apps/server/` — IAM/IdP/SSO backend
- `apps/console/` — IAM web console
- `packages/` — narrowly scoped shared/generated artifacts
- `migrations/` — migration support assets and tooling
- `deploy/` — deployment profiles and runtime configuration templates
- `infra/` — infrastructure-as-code and host provisioning
- `scripts/` — developer, CI, release, and operations automation
- `tests/` — cross-cutting integration, E2E, protocol, and security tests
- `security/` — cross-cutting security engineering assets
- `docs/` — repository-local implementation documentation and ADRs

See `docs/architecture/repository-boundaries.md` for the initial repository and architecture contract.

## Repository policy

- `main` is the long-lived integration branch.
- Changes arrive through short-lived branches and pull requests.
- Secrets and private key material must never be committed.
- Material deviations from agreed architecture/product baselines must be documented.
- No open-source license has been selected for this product at this stage.

## Developer entry point

Run:

```sh
make help
```

Build, test, local-development, migration, and deployment commands will be added as the corresponding toolchains are bootstrapped.
