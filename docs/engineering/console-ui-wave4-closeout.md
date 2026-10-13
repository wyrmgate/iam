# Console UI Wave 4 closeout

## Status

Wave 4 is **DONE / VERIFIED**.

The nine capability refinement tasks UI-031 through UI-039 were integrated through `integration/console-ui/wave-4` and promoted to `main` by PR #378. The exact integrated head `240221159841e3d8cfdf423a4b8b7e90bc68af6b` passed Core CI, Security CI, Container CI and DEV Deployment Contract before promotion. PR #378 was squash-merged to `main` as `f04d48b510c7f3b780b8e915bc611ec59e50138d`.

The Wave-4 integration pool and feature branches are retired and must not receive further work.

## Completed capability tranche

| Work ID | Issue | Capability / UI surface | Task PR | Result |
| --- | --- | --- | --- | --- |
| UI-031 | #360 | Identity | #370 | DONE / VERIFIED |
| UI-032 | #361 | Principal UI surface | #373 | DONE / VERIFIED |
| UI-033 | #362 | Catalog | #371 | DONE / VERIFIED |
| UI-034 | #363 | Access | #369 | DONE / VERIFIED |
| UI-035 | #364 | Governance | #372 | DONE / VERIFIED |
| UI-036 | #365 | Credential | #374 | DONE / VERIFIED |
| UI-037 | #366 | Integration | #375 | DONE / VERIFIED |
| UI-038 | #367 | Administration | #376 | DONE / VERIFIED |
| UI-039 | #368 | Audit | #377 | DONE / VERIFIED |

Principal remains Identity-owned canonical state; its separate workspace is only a Console implementation ownership surface.

## Runtime routing verification

The active Console runtime has one composition path:

```text
apps/console/src/main.tsx
  -> WyrmgateUiProvider
  -> SignInGate
  -> ShellChrome
  -> App
  -> capability workspace
```

`App.tsx` directly imports and renders the current capability workspace modules:

- `IdentityWorkspace`
- `PrincipalsWorkspace`
- `CatalogWorkspace`
- `AccessWorkspace`
- `RequestsWorkspace`, `ReviewsWorkspace`, `PoliciesWorkspace` from `GovernanceWorkspace`
- `CredentialsWorkspace`
- `IntegrationsWorkspace`
- `AdministrationWorkspace`
- `AuditWorkspace`

`consoleNavigation.ts` is the active route registry. The current routes are `/`, `/requests`, `/reviews`, `/identities`, `/identities/:identityId`, `/principals`, `/access`, `/policies`, `/catalog`, `/integrations`, `/credentials`, `/administration`, and `/audit`.

This verifies that normal Console navigation is routed through the decomposed/refined workspace implementation promoted in Wave 4.

## Legacy UI removal verification

The former multi-capability `ControlPlanePages.tsx` implementation is not present in the current Console source tree and `ControlPlanePages` is not referenced on `main`. Wave 4 did not leave an alternate hidden route to that implementation.

The following top-level pages remain intentionally current and are not legacy duplicates:

- `OverviewPage.tsx` — current Overview route surface;
- `IntegrationOperationsPage.tsx` — focused child surface consumed by Integration;
- `CurrentAdministrativeAuthorityPage.tsx` — focused server-derived authority projection consumed by Administration;
- `SemanticWorkspace.tsx` — shared presentation consumer, not a competing IAM/domain UI model.

Authentication and shell composition (`SignInGate`, `ShellChrome`, login styles and `src/ui`) are also current shared Console infrastructure, not the removed legacy capability UI.

## Next verification boundary

Repository CI proves the merged source/build/security/container/deployment contracts. It does not replace managed-environment operator acceptance.

The next Console phase is deployed managed-DEV browser acceptance against reviewed `main` at or after `f04d48b510c7f3b780b8e915bc611ec59e50138d`:

1. verify Railway deploys the exact reviewed SHA and `/actuator/health` is healthy;
2. verify Flyway is at the repository-current migration version (V67 at this checkpoint);
3. verify installation is `SEALED` and temporary bootstrap password material has been removed;
4. verify Cloudflare Pages `/api/*` reaches the intended Railway DEV server;
5. verify normal Console sign-in and a protected Administration operation;
6. verify representative browser workflows across Identity, Principal, Catalog, Access, Governance, Credential, Integration, Administration and Audit;
7. verify tenant isolation and the selected Neon recovery path;
8. record only non-secret environment evidence.

Any defect discovered by managed-DEV acceptance should be filed as a focused follow-up issue. Do not reopen the retired Wave-4 integration pool or restore the removed legacy capability UI as a fallback.
