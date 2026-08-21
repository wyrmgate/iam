# Observability

## Scope

Wyrmgate IAM uses vendor-neutral OpenTelemetry-compatible application instrumentation. Observability remains an operational layer and must not leak vendor-specific concepts into IAM domain semantics.

## Active managed DEV posture

For the first Cloudflare Pages + Railway + Neon DEV/testing/demo environment, remote telemetry is intentionally disabled with `IAM_OTEL_ENABLED=false` until Grafana Cloud free-tier behavior is confirmed suitable.

Do not add Grafana credentials or backend endpoints to the repository. A later activation must be reviewed as an operational change and preserve the redaction/data-minimization rules below.

## Existing standalone/local telemetry reference

The repository retains the OpenTelemetry Collector configuration used by local development and the standalone Docker Compose reference topology. That reference flow is:

```text
IAM server
  -> OTLP/HTTP traces + metrics
OpenTelemetry Collector
  -> redaction / batching
  -> OTLP-compatible backend
```

Keeping those files does not make the Collector a required process in the initial managed DEV deployment.

## Application variables

The server supports:

- `IAM_OTEL_ENABLED`
- `IAM_DEPLOYMENT_ENVIRONMENT`
- `IAM_OTEL_TRACES_ENDPOINT`
- `IAM_OTEL_METRICS_ENDPOINT`
- `IAM_TRACE_SAMPLING_PROBABILITY`

Export remains disabled unless `IAM_OTEL_ENABLED=true`.

## Redaction contract

Observability is not an audit log and is not a safe destination for authentication material. Application code must never intentionally place passwords, tokens, session identifiers, cookies, MFA/WebAuthn secrets, OAuth client secrets, connector credentials, private keys, or complete credential-bearing HTTP headers into logs, spans, metrics, events, or errors.

Query strings and request/response bodies must not be captured merely to improve observability. Any future body/header capture requires explicit security review and allowlisting.

## Cardinality and privacy

Use bounded operational dimensions for metrics. Do not use user IDs, account IDs, email addresses, request IDs, token IDs, or arbitrary unbounded business values as metric labels. Tenant/domain identifiers require a documented operational and privacy justification.

## Future managed DEV activation

If Grafana Cloud is enabled later, verify the selected free-tier limits, data retention, credential model, and expected cost behavior first. Then confirm a known request produces expected telemetry and that no protected values appear in exported data or deployment logs.
