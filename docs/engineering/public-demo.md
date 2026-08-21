# Public DEMO Usage

## Active direction

The first real Wyrmgate IAM DEV environment is also the initial testing/demo environment. It uses Cloudflare Pages + Pages Functions for the console/edge, Railway Serverless for `iam-server`, and Neon PostgreSQL.

There is no separate active host-based DEMO deployment in the current first-live direction. The managed DEV environment may be used for demonstrations only with synthetic/non-sensitive data and appropriate product restrictions for whatever features are exposed at that time.

## Standalone DEMO reference

The existing `deploy/compose/demo.yml`, Caddy configuration, deployment/reset scripts, and `.github/workflows/demo.yml` are retained only as a validated standalone-host reference. The workflow validates those artifacts; it does not deploy or reset a live host.

This preserves prior operational work without creating a second canonical DEV/DEMO topology.

## Public-exposure gate

Before an Internet-accessible demo is treated as safe, verify controls appropriate to implemented functionality, including:

- synthetic/fake data only;
- dangerous administration operations disabled or tightly constrained;
- abuse/rate limiting at an appropriate edge/application layer;
- restricted outbound messaging;
- no production credentials, connectors, tenants, or customer data;
- predictable recovery/reset behavior when destructive demo operations exist;
- logging/telemetry that excludes secrets and authentication material.

`WYRMGATE_DEMO_MODE=true`, where used by a standalone reference deployment, is only a profile marker and is not itself a security control.

## Production boundary

This managed DEV/demo topology is not the production topology and does not resolve production HA/DR, isolation, scaling, backup, or disaster-recovery requirements.
