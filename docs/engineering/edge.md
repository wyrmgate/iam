# Edge Reverse Proxy and TLS

Sprint 8 establishes the public edge contract for Wyrmgate IAM.

## Responsibilities

Caddy terminates public HTTP/HTTPS traffic, obtains and renews certificates through ACME, applies baseline security headers, and routes requests to internal IAM application containers.

Only ports 80 and 443 are intended to be public. PostgreSQL, Valkey, OpenTelemetry, the IAM server container, and the IAM console container remain on internal Docker networks.

## Routing

- `/api/*` routes to the IAM server.
- `/actuator/health` and `/actuator/health/*` route to the IAM server for health checks.
- all other paths route to the IAM console.

Other actuator endpoints are intentionally not exposed by the edge configuration.

## TLS

`IAM_PUBLIC_HOST` must be a DNS name that resolves to the host before public deployment. `IAM_ACME_EMAIL` identifies the certificate operator. Caddy redirects HTTP to HTTPS and manages certificate renewal automatically.

Cloudflare may sit in front of the host, but origin TLS remains enabled. DNS/proxy settings and any Cloudflare-specific client-IP trust configuration are deployment concerns and must not weaken the origin's TLS requirement.

## Configuration

Copy `deploy/config/edge.env.example` to the ignored `deploy/config/edge.env` and provide real values. The compose file expects the external Docker network `wyrmgate-edge`; application deployment joins server and console containers to that network in the DEV CD sprint.

## Validation

The `edge` CI check validates both Docker Compose rendering and the Caddy configuration. It does not contact ACME, change DNS, or deploy to a host.
