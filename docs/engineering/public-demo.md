# Public DEMO Usage

## Active direction

The selected shared DEV/testing/demo topology is Cloudflare Pages + Pages Functions for the console/edge, Railway Serverless for `iam-server`, and Neon PostgreSQL.

The current Railway service is a new DEV activation after the previous Railway service was retired. It must not be treated as an active demonstration environment until the checks in [`../operations/dev-managed-activation.md`](../operations/dev-managed-activation.md) are complete.

There is no separate active host-based DEMO deployment. Once managed DEV activation is complete, that environment may also be used for demonstrations only with synthetic/non-sensitive data and appropriate product restrictions for whatever features are exposed at that time.

## Standalone DEMO reference

The existing `deploy/compose/demo.yml`, Caddy configuration, deployment/reset scripts, and `.github/workflows/demo.yml` are retained only as a validated standalone-host reference. The workflow validates those artifacts; it does not deploy or reset a live host.

This preserves prior operational work without creating a second canonical DEV/DEMO topology.

## Public-exposure gate

Before an Internet-accessible demo is treated as safe, verify controls appropriate to implemented functionality, including:

- managed DEV activation is complete;
- synthetic/fake data only;
- dangerous administration operations disabled or tightly constrained;
- abuse/rate limiting at an appropriate edge/application layer;
- restricted outbound messaging;
- no production credentials, connectors, tenants, or customer data;
- predictable recovery/reset behavior when destructive demo operations exist;
- logging/telemetry that excludes secrets and authentication material.

`WYRMGATE_DEMO_MODE=true`, where used by a standalone reference deployment, is only a profile marker and is not itself a security control.

## Production boundary

This managed DEV/demo topology is not the production topology and does not resolve production HA/DR, isolation, scaling, backup or disaster-recovery requirements under OD-005.
