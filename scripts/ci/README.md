# CI Scripts

Reusable CI support logic belongs here when it is substantial enough not to live directly in workflow YAML.

## Core CI

The initial workflow lives in `.github/workflows/ci.yml` and deliberately keeps simple commands in the workflow or root Makefile rather than hiding them behind CI-only scripts.

Stable required-check candidates:

- `server`
- `console`
- `repository`

Developers should be able to reproduce the application checks locally with:

```bash
make server-build
make console-build
```

Repository validation checks Compose rendering and rejects committed generated directories or non-example `.env` files.

Security scanning, dependency vulnerability policy, secret scanning, CodeQL, and container scanning belong to the security-CI sprint rather than this baseline.
