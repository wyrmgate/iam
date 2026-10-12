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

OAuth/OIDC scopes, roles, groups and token claims are not interpreted as Wyrmgate administrative permissions. The console reads `/api/v1/current-administrative-authority` as a no-store, backend-derived point-in-time projection to hide obviously unrelated navigation and to render **My Administrative Authority**. The projection preserves semantic permission, typed scope, source and validity. It is not durable session authority and never replaces operation-time server authorization; a server `403` remains final.

## Navigation and coverage

| Route | Public contract coverage |
| --- | --- |
| `/` | Runtime/control-plane overview, trust-boundary status and current authority summary |
| `/identities` | Identity list/create/read/metadata/lifecycle, canonical effective-value disclosure, merge/split correction |
| `/principals` | Principal list/register/read/correlate |
| `/catalog` | Applications, ApplicationTargets, Entitlements, Roles and typed RoleVersion composition/lifecycle |
| `/access` | AccessAssignment intent, explicit lifecycle commands, read-only EffectiveAccess, typed lifecycle-access policy |
| `/requests` | AccessRequest create/read/submit and approval inbox/decision evidence |
| `/reviews` | Review campaign list/create/start and reviewer KEEP/REVOKE inbox |
| `/policies` | Identity source policy, Access lifecycle policy, Governance ACCESS_REQUEST policy lifecycle and exception lookup/revocation |
| `/credentials` | Principal-scoped Credential metadata, revoke/compromise and durable rotation planning |
| `/integrations` | ConnectorInstance, ConnectorBinding, ConnectorWorker and entitlement-observation mapping public administration surfaces |
| `/administration` | AdministrativeRole, Grant, Delegation, Elevation and BreakGlass workflows plus current backend-derived actor authority |
| `/audit` | AuditRecord/evidence reads and durable export/evidence-lifecycle workspaces |

When the authority projection is available, top-level navigation is optimized from the current effective permission resource types. Scoped authority still leaves its relevant product area visible because resource-level authorization remains a server operation-time decision. If the projection cannot be loaded, the console does not guess: navigation remains available and the server remains authoritative.

The UI preserves the project authority distinctions: Identity versus Principal, AccessAssignment versus EffectiveAccess, governance decision versus remediation, connector lifecycle versus provider observation, and Audit evidence versus generic logging.

## Explicit public-API gaps

The console does not bypass missing contracts:

- **#266** — public operator-facing provisioning/reconciliation status. The internal connector-worker protocol is not called by the browser and Integration tables are not queried directly.

Issue #265 is implemented by the current Administration-owned self-projection. No browser permission model or token-claim mapping was introduced.

## UX and safety conventions

- loading, empty, authentication, authorization, not-found, conflict, stale-revision and retry states are explicit;
- destructive/reduction operations require confirmation where accidental invocation is materially harmful;
- break-glass is visually separated from normal grants/elevation and retains server-side assurance/evaluator enforcement;
- structured IAM relationships use typed fields/builders rather than generic status mutation;
- provider configuration is Integration-owned and secret-shaped fields are redacted from generic detail rendering;
- Credential secret references are metadata only; raw secret/private material must never be pasted into ordinary console flows;
- current authority is never written to `localStorage`/`sessionStorage` and is refreshed from the no-store backend projection;
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

The repository supports the Cloudflare Pages console contract:

- root directory: `apps/console`
- build command: `npm run build`
- output directory: `dist`
- server-side edge binding: `IAM_BACKEND_ORIGIN`

The current managed DEV topology and activation state are defined by `docs/engineering/dev-cd.md` and `docs/operations/dev-managed-activation.md`; this console README does not independently redefine that deployment topology. End-to-end console verification must use the currently canonical managed DEV environment.
