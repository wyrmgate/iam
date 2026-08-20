# Deployment Assets

Deployment assets for local, development, demo, staging, and production profiles.

Planned structure:

- `compose/` - Docker Compose profiles
- `caddy/` - reverse-proxy/TLS configuration
- `config/` - non-secret environment configuration templates

Deployment topology must not leak into IAM domain concepts. The same product artifact should be usable from a single-node development/demo deployment through larger production topologies.

Secrets and private keys must never be committed here.
