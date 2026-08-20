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

The initial shell connects to `/api/system/info` through Vite's development proxy so frontend/backend integration is exercised from the start rather than using mock status data.

## Commands

From the repository root:

```bash
make console-install
make console-typecheck
make console-build
make console-dev
```

Dependency lockfiles and the full lint/test stack are added as part of the reproducible local-development/CI bootstrap.
