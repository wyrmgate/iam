# Edge Reverse Proxy and TLS

## Status

Caddy is retained as the standalone-host/reference edge implementation for the Docker Compose topology. It is no longer the active first DEV/testing/demo edge.

The active DEV edge is Cloudflare Pages: static console assets are served directly by Pages and only `/api/*` invokes a Pages Function that proxies to Railway through `IAM_BACKEND_ORIGIN`.

## Standalone reference responsibilities

For a future standalone host, Caddy terminates HTTP/HTTPS, manages ACME certificates, applies baseline security headers, and routes application traffic to internal containers. Only ports 80 and 443 are intended to be public; PostgreSQL, telemetry, and application container ports remain internal.

Reference routing remains:

- `/api/*` to the IAM server;
- `/actuator/health` to the IAM server;
- other paths to the console.

This material must not be described as the canonical current DEV activation path. Any future activation of the standalone edge requires an explicit reviewed deployment decision.
