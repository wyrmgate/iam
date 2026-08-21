# IAM Console

React-based administrative and end-user console for Wyrmgate IAM.

## Toolchain

- Node.js 24 LTS
- React 19.2.x
- TypeScript 7.0.x
- Vite 8.1.x
- npm

The console follows the product navigation and job-oriented UX defined in the IAM v2 documentation rather than mirroring backend modules mechanically.

Frontend rules:

- use a typed API client
- no permanent mock business data
- backend-derived authorization/permissions
- feature-level routes and reusable components
- explicit query-cache invalidation once server-state caching is introduced
- business rules remain server-side

The initial shell connects to `/api/system/info`. Local development preserves Vite's proxy to `http://localhost:8080`. The active managed DEV topology deploys this directory as the Cloudflare Pages project root; `functions/api/[[path]].js` proxies only `/api/*` to the server-side `IAM_BACKEND_ORIGIN`, while `public/_routes.json` keeps ordinary static requests out of Pages Functions.

Cloudflare Pages settings for DEV:

- root directory: `apps/console`
- build command: `npm run build`
- output directory: `dist`

Do not expose the backend origin through a `VITE_*` build variable; the browser remains same-origin.

## Commands

From the repository root:

```bash
make console-install
make console-typecheck
make console-build
make console-dev
```
