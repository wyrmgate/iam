# Managed DEV Activation Runbook

## Status

Railway Serverless has been selected again as the shared DEV compute target for `iam-server`, with Cloudflare Pages + Pages Functions for the console/edge and Neon PostgreSQL as the managed DEV database.

This is a new activation of the current Railway service, not restoration of the retired service instance. The activation is not complete until the verification steps in this runbook pass against the current reviewed `main` revision.

## Current selected topology

```text
Browser
  -> Cloudflare Pages static console
  -> /api/* Pages Function
  -> Railway Serverless iam-server
  -> Neon PostgreSQL
```

The topology is DEV only. It does not define or close OD-005 production HA/DR, SLO, RPO or RTO decisions.

## Railway service settings

The selected Railway DEV service should use:

- repository: `wyrmgate/iam`;
- branch: `main`;
- repository root `/` as build context;
- Dockerfile builder with `/apps/server/Dockerfile`;
- no custom start command;
- generated/public Railway HTTPS domain;
- application port 8080 through Railway's assigned `PORT` contract;
- `/actuator/health` as Healthcheck Path;
- Railway `Wait for CI` enabled;
- one replica in the selected DEV region;
- Serverless enabled for the low-cost DEV posture;
- restart policy `On Failure`;
- no cron schedule for the application service;
- CDN caching and browser-challenge/Under-Attack behavior disabled for the API origin unless separately reviewed.

The old `apps/server/railway.json` file must not be recreated merely to mirror the retired deployment. The current Railway legacy Config-as-Code path is not the active repository contract.

## Required runtime configuration

Railway must provide these values through deployment configuration/secret storage, never source control:

- `IAM_DB_URL` — JDBC PostgreSQL URL for the selected Neon DEV database, including required TLS options;
- `IAM_DB_USER`;
- `IAM_DB_PASSWORD`;
- `IAM_DEPLOYMENT_ENVIRONMENT=dev`;
- `IAM_OTEL_ENABLED=false` for the baseline low-cost DEV posture.

Optional integration-event, SIEM, connector-worker, break-glass notification, signing and authentication settings remain disabled until the corresponding DEV test is intentionally activated.

Do not commit or paste real secrets into issues, PRs, documentation or ordinary logs.

## Fresh DEV installation uses the first-run wizard

A new empty shared DEV database must use the ADR-0045 first-run web setup wizard. The ADR-0044 startup/headless bootstrap remains available for other non-interactive deployment modes, but **startup/headless bootstrap is not the shared DEV activation path**.

Configure Railway with:

```text
IAM_IDP_ENABLED=true
IAM_BOOTSTRAP_WEB_ENABLED=true
IAM_BOOTSTRAP_WEB_SETUP_SECRET=<temporary random Railway secret, at least 32 characters>
IAM_BOOTSTRAP_WEB_SESSION_TTL=PT15M

IAM_BOOTSTRAP_INSTALLATION_ENABLED=false
IAM_BOOTSTRAP_INSTALLATION_CONSOLE_REDIRECT_URI=https://wyrmgate-iam-dev.pages.dev/callback
```

`IAM_BOOTSTRAP_WEB_SETUP_SECRET` is a deployment transport only. The checked-in server container entrypoint creates a private runtime file, exports its path as `IAM_BOOTSTRAP_WEB_SETUP_SECRET_FILE`, unsets the plaintext variable, and only then starts Java. The application continues to consume ADR-0045's file-only setup-proof boundary.

Do not configure both `IAM_BOOTSTRAP_WEB_SETUP_SECRET` and `IAM_BOOTSTRAP_WEB_SETUP_SECRET_FILE`.

Do not configure any of these old DEV startup-bootstrap inputs:

```text
IAM_BOOTSTRAP_INSTALLATION_TENANT_DISPLAY_NAME
IAM_BOOTSTRAP_INSTALLATION_IDENTITY_DISPLAY_NAME
IAM_BOOTSTRAP_INSTALLATION_USERNAME
IAM_BOOTSTRAP_INSTALLATION_PASSWORD
IAM_BOOTSTRAP_INSTALLATION_PASSWORD_FILE
```

When `IAM_DEPLOYMENT_ENVIRONMENT=dev`, the container fails startup if `IAM_BOOTSTRAP_INSTALLATION_ENABLED=true`. This makes accidental bypass of the first-run wizard an explicit deployment error.

### Run the wizard

After Railway reports healthy and Cloudflare `/api/*` points at that Railway deployment:

1. open `https://wyrmgate-iam-dev.pages.dev`;
2. Console resolves `/api/setup/status` before normal sign-in and presents first-run setup for an unsealed installation;
3. enter the same setup-access secret stored in Railway as `IAM_BOOTSTRAP_WEB_SETUP_SECRET`;
4. enter the first Tenant display name;
5. enter the initial administrator display name, username and password;
6. review the server-owned Console redirect URI;
7. initialize and allow the durable bootstrap to advance through `PLANNED -> ... -> VERIFIED -> SEALED`;
8. sign in normally with the administrator username/password created in the wizard.

If initialization is interrupted after the durable plan exists, reopen the wizard, unlock it again with the setup-access secret, and re-enter the **original** administrator password. Frozen non-secret plan inputs are not editable during resume.

### Post-seal cleanup

After normal sign-in and a protected Administration operation succeed:

1. remove `IAM_BOOTSTRAP_WEB_SETUP_SECRET` from Railway;
2. set `IAM_BOOTSTRAP_WEB_ENABLED=false` (or remove it so the default remains false);
3. keep `IAM_BOOTSTRAP_INSTALLATION_ENABLED=false`;
4. keep the trusted Console redirect configuration needed by normal SSO routing;
5. redeploy/restart if Railway does not already restart because of the variable change.

The durable `SEALED` state is irreversible; removing or recreating deployment secrets cannot reopen installation bootstrap.

See [`first-run-web-setup.md`](first-run-web-setup.md) for the full security and resume contract.

## Existing SEALED DEV installation with legacy bootstrap authority

Do not reset Neon, delete the installation seal, rerun the first-run wizard, or edit Administration rows merely because a pre-ADR-0046 DEV installation sealed its first administrator with ADR-0011's narrow four-permission role.

Use ADR-0047's operator-only recovery procedure in [`sealed-standalone-owner-recovery.md`](sealed-standalone-owner-recovery.md). It derives the only candidate tenant/Identity/Principal/role/grant from durable installation/bootstrap state, accepts no object IDs or permission inputs, upgrades only the exact untouched legacy first-party state, and fails closed on customization or ambiguity.

A DEV database that already ran the subsequently withdrawn pre-release V70 may first fail normal Flyway validation because that migration is no longer present in current migration resources. Follow the runbook's explicit `flyway info` / operator-approved `flyway repair` metadata procedure only after confirming V70 is the expected missing applied migration and no unrelated migration discrepancy exists. Do not restore V70's automatic authority SQL and do not install a permanent missing-migration ignore rule.

After schema-history reconciliation when needed, run the ADR-0047 command outside the normal Railway web process with `spring.main.web-application-type=none`. An exact V70-expanded owner state returns `ALREADY_COMPLETE`; an exact unrecovered legacy state returns `RECOVERED`; any denial requires investigation rather than database normalization.

## Activation procedure

1. Select a reviewed `main` SHA with required GitHub checks green.
2. Confirm Railway `Wait for CI` is enabled and the deployment is associated with that exact reviewed SHA.
3. Confirm Railway is building the checked-in `apps/server/Dockerfile` from repository root and is not overriding its `ENTRYPOINT`.
4. Confirm required Neon database variables are present in Railway secret/configuration storage.
5. For a fresh empty database, configure the first-run wizard inputs above; verify `IAM_BOOTSTRAP_INSTALLATION_ENABLED=false`.
6. Deploy and wait for Railway's `/actuator/health` gate to return 2xx.
7. Verify the deployed server reports healthy over the public Railway HTTPS origin.
8. Verify `public.flyway_schema_history` records the repository current migration version successfully; current repository migration resources extend through Flyway V69. If this is a historical DEV database that recorded the withdrawn V70, use the ADR-0047 runbook's explicit schema-history reconciliation before treating migration validation as healthy.
9. Verify the server is using non-production DEV data and that tenant isolation checks pass.
10. Configure Cloudflare Pages `IAM_BACKEND_ORIGIN` to the verified Railway DEV origin.
11. Exercise the console `/api/*` path and confirm it reaches the intended DEV server rather than a stale/personal/production origin.
12. If fresh, complete the first-run wizard and confirm the durable installation state reaches `SEALED`.
13. Verify normal Console username/password login and a protected Administration operation after bootstrap is sealed. For an eligible legacy sealed DEV installation, complete ADR-0047 recovery first instead of rerunning setup.
14. Remove the temporary setup secret and disable web setup as described above.
15. Verify the selected Neon recovery window and perform or record an isolation-safe recovery check before destructive migration/data tests.
16. Record non-secret activation evidence: deployed SHA, Railway environment/service identifier, health result, Flyway version/history status, database branch/project identifier, wizard/seal or ADR-0047 recovery result as applicable, console routing/login result and recovery-check result.

## Cloudflare Pages contract

The repository supports the existing Pages build/function contract:

- production branch may be `main` for the shared DEV console;
- root directory is `apps/console`;
- build command is `npm run build`;
- output directory is `dist`;
- only `/api/*` executes the Pages Function;
- `IAM_BACKEND_ORIGIN` is server-side only.

Do not expose the Railway origin as a browser-side `VITE_*` variable merely to bypass the Pages Function.

## Flyway verification

For a new or reset DEV database, verify both:

1. the deployed Railway server reports healthy;
2. `public.flyway_schema_history` records the repository latest migration successfully.

The application-startup regression test remains repository-level proof that Spring Boot and Flyway wiring works against an empty PostgreSQL database. It does not replace checking the actual managed DEV database.

For a historical database that recorded withdrawn V70, migration-history reconciliation is an explicit exceptional operator action documented by ADR-0047; it is not evidence that current migration resources include V70 and it must not mutate IAM authority state.

## Serverless and background work

Railway Serverless is intentionally enabled for the low-cost DEV posture. Background outbound activity can keep the service awake, so remote telemetry and optional delivery workers stay disabled unless they are the subject of the test.

When testing long-running asynchronous delivery, connector workers, SIEM, webhooks, break-glass notifications or other background work, temporarily keeping the DEV service awake may be expected. Do not change domain semantics simply to optimize the provider's sleep behavior.

## Recovery

Neon provider-native recovery/history is the first DEV recovery path. Before destructive tests or risky migrations:

1. verify the intended recovery point is inside the current provider recovery window;
2. prefer an isolated/disposable recovery target when available;
3. verify schema and representative data after recovery;
4. remove disposable recovery objects after verification;
5. record the outcome as operational evidence.

Provider-native recovery is not an independently retained backup. Production recovery requirements remain governed by [`production-readiness.md`](production-readiness.md) and [`production-recovery-drill.md`](production-recovery-drill.md).

ADR-0047 authority compatibility recovery is separate from database backup/restore: it is permitted only for the exact sealed first-party legacy bootstrap state and must not be used to repair arbitrary damaged/customized Administration state.

## Completion criteria

Managed DEV activation is complete only when all of the following are true:

- the current Railway service deploys a reviewed green `main` SHA;
- Railway `/actuator/health` is healthy;
- Flyway is confirmed at the repository current migration version/history state;
- a fresh installation, when applicable, used the first-run web setup wizard and reached `SEALED`;
- an affected legacy sealed installation, when applicable, completed ADR-0047 with `RECOVERED` or exact `ALREADY_COMPLETE` rather than a database reset/manual authority edit;
- the temporary setup-access secret has been removed and web setup has been disabled after sealing;
- the startup/headless installation bootstrap was not enabled for shared DEV;
- the service uses the intended Neon DEV database and non-production data;
- tenant isolation verification passes;
- Cloudflare Pages `/api/*` routes to the intended Railway DEV origin;
- normal Console authentication and a protected Administration operation succeed;
- the Neon recovery path has been verified for the current environment;
- no real secret exists in Git or ordinary logs;
- repository DEV topology documentation matches the deployed environment.

Until all of these are recorded, Railway is the **selected managed DEV target**, but the shared managed DEV environment remains **activation pending**.
