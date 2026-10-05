# Edge Reverse Proxy and TLS

## Status

Caddy is retained as the standalone-host/reference edge implementation for the Docker Compose topology. It is not the selected shared DEV edge.

The selected DEV edge is Cloudflare Pages: static console assets are served directly by Pages and only `/api/*` invokes a Pages Function. After managed DEV activation completes, that Function proxies to the verified Railway DEV `iam-server` origin through server-side `IAM_BACKEND_ORIGIN`.

Until the activation checks in [`../operations/dev-managed-activation.md`](../operations/dev-managed-activation.md) pass, do not point Pages at an unverified, personal or production backend merely to make the console usable.

## Managed DEV responsibilities

For the selected Cloudflare Pages + Railway DEV topology:

- Cloudflare Pages serves static console assets;
- only `/api/*` executes the Pages Function;
- `IAM_BACKEND_ORIGIN` is server-side only;
- Railway terminates HTTPS for its generated/public DEV service domain;
- Railway `/actuator/health` is the server deployment health gate;
- CDN caching must not be casually enabled for IAM API responses;
- browser-challenge/Under-Attack behavior must not be enabled on the API origin without reviewing automated API, webhook and connector-worker effects.

The generated Railway service domain is an implementation endpoint. It is not a canonical IAM API identifier and may change without changing domain architecture.

## Standalone reference responsibilities

For a future standalone host, Caddy terminates HTTP/HTTPS, manages ACME certificates, applies baseline security headers, and routes application traffic to internal containers. Only ports 80 and 443 are intended to be public; PostgreSQL, telemetry, and application container ports remain internal.

Reference routing remains:

- `/api/*` to the IAM server;
- `/actuator/health` to the IAM server;
- other paths to the console.

This standalone material must not be described as the canonical current DEV activation path. Any future activation of the standalone edge requires an explicit reviewed deployment decision.
