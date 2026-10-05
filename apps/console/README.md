# IAM Console

React-based administrative and end-user console for Wyrmgate IAM.

## Toolchain

- Node.js 24 LTS
- React 19.2.x
- TypeScript 7.0.x
- Vite 8.1.x
- npm

The console follows the job-oriented IAM v2 product model rather than mirroring Java packages, Maven modules or database tables. Business rules, tenant resolution, governed-actor resolution and Administrative Authorization remain server-side.

## Browser/API boundary

The browser calls only same-origin `/api/*` routes. The backend origin must never be exposed through a `VITE_*` build variable.

`src/api.ts` is mechanically aligned with checked-in public OpenAPI semantics for the operations the console consumes:

- opaque cursor values are passed back without construction or interpretation;
- authoritative reads retain strong `ETag` values for later `If-Match` mutations;
- retryable mutations receive causal `Idempotency-Key` values;
- 401, 403, 404, 409, 412 and mandatory-evaluator 503 remain distinct conditions;
- arbitrary backend HTML, stack traces and proxy bodies are not rendered as diagnostics;
- no bearer token, raw credential secret/private material, connector secret or provider-native private payload is written to browser storage, URLs or logs.

OAuth/OIDC scopes, roles, groups and token claims are not interpreted as Wyrmgate administrative permissions. Operation-time server authorization is final. Backend-derived current effective-authority visibility is tracked as #265; until that public projection exists, navigation visibility is not proof of permission and 403 remains authoritative.

## Navigation and coverage

| Route | Public contract coverage |
| --- | --- |
| `/` | Runtime/control-plane overview and trust-boundary status |
| `/identities` | Identity list/create/read/metadata/lifecycle, canonical effective-value disclosure, merge/split correction |
| `/principals` | Principal list/register/read/correlate |
| `/catalog` | Applications, ApplicationTargets, Entitlements, Roles and typed RoleVersion composition/lifecycle |
| `/access` | AccessAssignment intent, explicit lifecycle commands, read-only EffectiveAccess, typed lifecycle-access policy |
| `/requests` | AccessRequest create/read/submit and approval inbox/decision evidence |
| `/reviews` | Review campaign list/create/start and reviewer KEEP/REVOKE inbox |
| `/policies` | Identity source policy, Access lifecycle policy, Governance ACCESS_REQUEST policy lifecycle and exception lookup/revocation |
| `/credentials` | Principal-scoped Credential metadata, revoke/compromise and durable rotation planning |
| `/integrations` | ConnectorInstance, ConnectorBinding, ConnectorWorker and entitlement-observation mapping public administration surfaces |
| `/administration` | AdministrativeRole, Grant, Delegation, Elevation and BreakGlass authority workflows |
| `/audit` | AuditRecord/evidence reads and durable export/evidence-lifecycle workspaces |

The UI preserves the project authority distinctions: Identity versus Principal, AccessAssignment versus EffectiveAccess, governance decision versus remediation, connector lifecycle versus provider observation, and Audit evidence versus generic logging.

## Explicit public-API gaps

The console does not bypass missing contracts:

- **#265** — current governed actor/effective Administrative Authorization projection. Until implemented, the browser does not synthesize permissions from token claims.
- **#266** — public operator-facing provisioning/reconciliation status. The internal connector-worker protocol is not called by the browser and Integration tables are not queried directly.

These gaps remain visible in the relevant pages. They prevent issue #263 from being closed as fully operator-ready until their accepted public interfaces exist.

## UX and safety conventions

- loading, empty, authentication, authorization, not-found, conflict, stale-revision and retry states are explicit;
- destructive/reduction operations require confirmation where accidental invocation is materially harmful;
- break-glass is visually separated from normal grants/elevation and retains server-side assurance/evaluator enforcement;
- structured IAM relationships use typed fields/builders rather than generic status mutation;
- provider configuration is Integration-owned and secret-shaped fields are redacted from generic detail rendering;
- Credential secret references are metadata only; raw secret/private material must never be pasted into ordinary console flows;
- keyboard focus is visible and layouts remain usable on narrow viewports;
- no permanent mock business data is shipped.

## Local development

Local development preserves Vite's same-origin `/api` proxy to `http://localhost:8080`.

From the repository root:

```bash
make console-install
make console-typecheck
make console-build
make console-dev
```

From `apps/console`:

```bash
npm test
```

## Managed console deployment

The repository still supports the Cloudflare Pages console contract:

- root directory: `apps/console`
- build command: `npm run build`
- output directory: `dist`
- server-side edge binding: `IAM_BACKEND_ORIGIN`

The previously documented Cloudflare Pages -> Railway `iam-server` -> Neon end-to-end DEV topology has been retired; Railway is no longer the canonical server target. Do not treat the Pages contract as evidence of a current managed server deployment. When a replacement managed server target is accepted, end-to-end console verification must be performed against that canonical topology before #263 closes.
