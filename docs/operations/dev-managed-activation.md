# Managed DEV Activation Runbook

## Status

The former Cloudflare Pages + Railway Serverless + Neon end-to-end DEV topology is no longer active because the Railway server is retired.

There is currently no canonical replacement managed server target. This runbook therefore defines the gate for the next managed DEV activation instead of instructing operators to recreate Railway.

## Preconditions for a new managed DEV target

Before declaring a replacement managed DEV environment active:

1. select a reviewed `main` commit with required CI green;
2. name the server/runtime provider and database target explicitly;
3. document provider-specific secret/configuration ownership without committing credentials;
4. prove the checked-in `apps/server/Dockerfile` or equivalent reviewed artifact is what is deployed;
5. verify `/actuator/health`;
6. verify Flyway completed to the repository current migration version;
7. verify tenant isolation with non-production data;
8. document rollback and database recovery controls;
9. verify the console/API routing target is the intended isolated environment;
10. update this runbook and `docs/engineering/dev-cd.md` in the same reviewed change.

## Cloudflare Pages

The repository still supports the existing Pages build/function contract. If Pages is used for the console:

- production branch may be `main`;
- root directory is `apps/console`;
- build command is `npm run build`;
- output directory is `dist`;
- only `/api/*` executes the Pages Function;
- `IAM_BACKEND_ORIGIN` is server-side only.

Do not point `IAM_BACKEND_ORIGIN` at an unreviewed, personal, or production backend merely to make the console usable.

## Server/runtime requirements

The replacement server environment must provide:

- `IAM_DB_URL`;
- `IAM_DB_USER`;
- `IAM_DB_PASSWORD`;
- `IAM_DEPLOYMENT_ENVIRONMENT=dev`;
- any optional integration-event/SIEM/notification secrets through provider secret storage only.

Do not assume Railway-specific port, health, build-context, sleep, pool-size, or deployment-history semantics. Capture provider-specific behavior only after the replacement is selected.

## Flyway verification

For a new or reset DEV database, verify both:

1. the deployed server reports healthy;
2. `public.flyway_schema_history` records the repository latest migration successfully.

The application-startup regression test remains the repository-level proof that Spring Boot and Flyway wiring works against an empty PostgreSQL database.

## Recovery

Before destructive tests or risky migrations, document and exercise the recovery mechanism of the selected DEV database/provider. Provider-native recovery is not automatically an independently retained backup.

For production recovery requirements, use [`production-readiness.md`](production-readiness.md); DEV mechanics do not close OD-005.

## Completion criteria

Managed DEV activation is complete only after a replacement server target is intentionally selected, documented, deployed from a green reviewed revision, migration/health/tenant checks pass, console routing points to that environment, recovery controls are verified, and no real secret exists in Git.

Until then, the managed DEV topology is **not active**.
