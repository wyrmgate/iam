# ADR-0082 Exact connector execution configuration and local secret boundary

Status: Accepted

Local SCIM execution is default-deny unless the deployment authorizes the exact tenant, connector instance, HTTPS base URI and opaque env:IAM_CONNECTOR_* reference. Administration cannot select arbitrary server environment secrets or send approved credentials to a different endpoint. Provider config is a typed bounded SCIM profile, not arbitrary executable JSON. DNS and network egress remain deployment security policy. Existing routes must be explicitly authorized before enabling local execution. No secret values enter task payloads or worker envelopes.

The worker lease envelope adds a non-secret executionContext pin (runtime ID/version, configuration version, binding revision, connector revision). Remote executors must load the exact authorized configuration and renew immediately before provider mutation. Missing legacy pin is not executable. Local reconciliation renews and compares the exact snapshot before external I/O; stale claims cannot materialize current state. No distributed rollback or provider call inside authoritative transactions.

Deployment property iam.integration.scim-local.authorized-routes: semicolon-separated tenantUUID|connectorUUID|https://host/base|env:IAM_CONNECTOR_NAME. Empty denies every local route. The initial profile excludes automatic redirects, arbitrary custom authorization headers and arbitrary server environment access.
