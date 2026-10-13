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

## Enterprise design system

The Console uses one deterministic enterprise visual system across authentication, shell chrome and all currently implemented IAM workspaces.

- Light application surfaces prioritize dense administrative readability over decorative dashboard styling.
- A dark navy navigation dock provides stable product-area orientation without consuming full sidebar width.
- A white contextual top bar keeps tenant, current product location, command navigation and governed actor context visible.
- Tables, forms, resource details, state surfaces, tabs and semantic action controls share one border, spacing, typography and state vocabulary.
- Authentication uses a dedicated split enterprise sign-in surface while preserving server-side tenant routing and the no-browser-token-storage boundary.
- Success, warning, danger and lifecycle colors are semantic and deliberately separate from the blue product accent.
- Narrow-screen layouts preserve the same semantic navigation and operation surfaces instead of substituting simplified browser authority.
- Reduced-motion preferences disable non-essential transitions.

The previous temporary `shell-bridge.css` compatibility layer has been removed. Existing public semantic pages now render directly within the same design system rather than carrying a separate legacy dark page surface.

## Application shell

The console uses a compact Wyrmgate workbench shell rather than a conventional persistent full-width sidebar.

- A fixed navigation dock exposes the major product areas: Overview, Identity, Access, Governance, Catalog, Integration, Security and Audit.
- Product areas with multiple destinations open a transient flyout instead of permanently consuming workspace width.
- `Cmd/Ctrl+K` opens the command palette. The first tranche supports deterministic navigation only; resource search and semantic actions must not be simulated client-side before matching public API contracts exist.
- The top bar shows server-derived tenant context, current product location and current governed actor context.
- The context rail exposes tenant, actor, administrative-eligibility and runtime information without turning browser state into authority.
- Navigation optimization continues to use `/api/v1/current-administrative-authority`; operation-time server authorization remains final.
- The Integration dock group includes public provisioning-job, provisioning-task, provisioning-attempt and reconciliation-run read permissions so operators with only operational visibility can still reach the Integration surface.
- The dock becomes a compact bottom control on narrow screens, while secondary navigation remains an overlay rather than a horizontal menu strip.
- Reduced-motion preferences disable non-essential shell transitions.

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
| `/integrations` | Connector administration plus public read-only provisioning job/task/attempt and reconciliation-run operational status |
| `/administration` | AdministrativeRole, Grant, Delegation, Elevation and BreakGlass workflows plus current backend-derived actor authority |
| `/audit` | AuditRecord/evidence reads and durable export/evidence-lifecycle workspaces |

When the authority projection is available, navigation is optimized from the current effective permission resource types. Scoped authority still leaves its relevant product area visible because resource-level authorization remains a server operation-time decision. If the projection cannot be loaded, the console does not guess: navigation remains available and the server remains authoritative.

The UI preserves the project authority distinctions: Identity versus Principal, AccessAssignment versus EffectiveAccess, governance decision versus remediation, connector lifecycle versus provider observation, and Audit evidence versus generic logging.

## Integration operational visibility

The Integrations **Operations** tab consumes only the accepted public read contract in `integration-operations-v1.json`:

- provisioning jobs;
- provisioning tasks;
- immutable provisioning attempts scoped to a task;
- reconciliation runs.

These views are explicitly labeled operational/non-authoritative. They report Integration-owned fulfillment/reconciliation process state and normalized provider-operation evidence; they do not turn observations into Catalog authority or Access intent.

The browser never calls worker claim, lease renewal, completion, observation-batch or other internal connector-worker execution operations. Public responses do not contain task payloads/idempotency keys, worker lease identifiers/epochs, raw provider metadata/object identifiers, reconciliation checkpoints, completion fingerprints, connector secrets or provider configuration.

Issue #265 and #266 are both implemented without introducing browser-side permission semantics or bypassing owning-capability boundaries.

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
- production console deployments are expected to be triggered by the Cloudflare Pages Git integration when a new commit reaches `main`; Railway deployment status alone does not prove that the matching console build was published.

The current managed DEV topology and activation state are defined by `docs/engineering/dev-cd.md` and `docs/operations/dev-managed-activation.md`; this console README does not independently redefine that deployment topology. End-to-end console verification must use the currently canonical managed DEV environment.
