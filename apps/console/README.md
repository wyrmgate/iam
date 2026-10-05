# IAM Console

React-based administrative and end-user console for Wyrmgate IAM.

## Toolchain

- Node.js 24 LTS
- React 19.2.x
- TypeScript 7.0.x
- Vite 8.1.x
- npm

The console follows the product navigation and job-oriented UX defined in the IAM v2 documentation rather than mirroring backend modules mechanically. Business rules, tenant resolution, governed-actor resolution and administrative authorization remain server-side.

## Browser/API boundary

The browser calls only same-origin `/api/*` routes. In managed DEV, `functions/api/[[path]].js` forwards those routes to the server-side `IAM_BACKEND_ORIGIN`. The backend origin must never be exposed through a `VITE_*` build variable.

The API client in `src/api.ts` is mechanically aligned with the checked-in public OpenAPI contracts for the operations it consumes. It preserves protocol semantics instead of creating frontend-only mutations:

- opaque cursor values are passed back without construction or interpretation;
- authoritative reads retain strong `ETag` values for later `If-Match` mutations;
- retryable mutations receive a fresh causal `Idempotency-Key`;
- 401, 403, 409 and 412 remain distinct conditions in the UI;
- arbitrary backend response bodies are not rendered as error HTML or diagnostics;
- no token, credential secret or provider-native private material is stored in browser storage.

OAuth/OIDC or edge-auth claims are not interpreted as Wyrmgate administrative permissions. Operation-time authorization is authoritative on the server. Permission-aware action optimization requires a public backend-derived effective-authority projection; the console must not invent one.

## Routes and current coverage

Current routes in this bounded issue #263 slice:

| Route | Purpose |
| --- | --- |
| `/` | Runtime/control-plane overview and trust-boundary status |
| `/identities` | Authoritative Identity list with deterministic cursor continuation |
| `/identities/:identityId` | Authoritative Identity detail, revision and lifecycle commands |

The Identity detail keeps Identity authoritative state distinct from Principal/provider-account concepts. Lifecycle commands use the explicit semantic endpoints from `identity-v1.json`; they do not mutate generic status fields.

This slice intentionally does **not** claim completion of issue #263. Principal, Catalog, Access, Governance, Credential, Integration, Administration and Audit routes remain to be implemented against their accepted public contracts. Backend-derived effective-authority visibility must also be added through an accepted public interface before the console can optimize action visibility by administrative permission.

## UX conventions

- loading, empty, connectivity, authentication, authorization, conflict and stale-revision states are explicit;
- destructive/terminal lifecycle changes require confirmation;
- keyboard focus is visible and navigation uses semantic links/buttons;
- layouts remain usable on narrow viewports;
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

From `apps/console` the API-boundary test suite can also be run directly:

```bash
npm test
```

## Managed DEV

Cloudflare Pages settings:

- root directory: `apps/console`
- build command: `npm run build`
- output directory: `dist`
- server-side environment binding: `IAM_BACKEND_ORIGIN`

For deployment verification, exercise the console through the Cloudflare Pages origin and confirm requests remain `/api/*` in the browser while reaching the Railway `iam-server` and Neon-backed DEV environment. A healthy runtime alone does not satisfy issue #263; each supported console workflow must be verified end-to-end before that deployment blocker closes.
