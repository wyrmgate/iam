# Observability

## Scope

Sprint 12 establishes vendor-neutral application telemetry using OpenTelemetry Protocol (OTLP), Micrometer, Spring Boot observability support, and an OpenTelemetry Collector between the IAM server and any external backend.

The application does not contain Grafana-specific APIs or credentials. Grafana Cloud is one compatible OTLP backend; another OTLP-capable service can replace it without changing application code.

## Data flow

```text
IAM server
  -> OTLP/HTTP traces + metrics
OpenTelemetry Collector
  -> memory limiter
  -> attribute redaction
  -> batch
  -> OTLP/HTTP backend
```

The Collector remains on the internal application network in DEV and DEMO and does not publish OTLP ports to the Internet.

Local development uses the existing loopback-only Collector and its debug exporter.

## Application instrumentation

The server uses Spring Boot's OpenTelemetry starter for tracing and Micrometer's OTLP registry for metrics. Export is disabled unless `IAM_OTEL_ENABLED=true`.

Runtime variables:

- `IAM_OTEL_ENABLED`
- `IAM_DEPLOYMENT_ENVIRONMENT`
- `IAM_OTEL_TRACES_ENDPOINT`
- `IAM_OTEL_METRICS_ENDPOINT`
- `IAM_TRACE_SAMPLING_PROBABILITY`

DEV and DEMO point the server at the internal Collector. Their default trace sampling probability is `0.10`; local development uses `1.0` for easier troubleshooting.

Trace and span IDs are added to the logging correlation prefix when a trace context exists. Application logs remain ordinary process logs in this sprint; direct OTLP log export from the server is not required for correlation.

## Collector backend configuration

The runtime Collector receives these host-only values:

- `IAM_OTEL_EXPORTER_ENDPOINT` — OTLP/HTTP base endpoint of the selected backend.
- `IAM_OTEL_EXPORTER_AUTHORIZATION` — complete Authorization header value required by the backend.

The DEV GitHub Environment supplies these as `DEV_OTEL_EXPORTER_ENDPOINT` and `DEV_OTEL_EXPORTER_AUTHORIZATION`. DEMO uses the corresponding `DEMO_...` secrets.

These values are credentials/configuration and must not be committed or printed in CI logs.

## Redaction contract

Observability is not an audit log and is not a safe destination for authentication material. Application code must never intentionally place any of these values in log messages, span attributes, metric labels, events, or exception messages:

- passwords or password hashes;
- access, refresh, ID, activation, reset, or verification tokens;
- authorization codes;
- session identifiers or cookies;
- MFA/OTP/WebAuthn secrets or assertions;
- OAuth client secrets;
- connector/API credentials;
- private keys or key-encryption material;
- complete `Authorization`, `Proxy-Authorization`, `Cookie`, or `Set-Cookie` headers.

The runtime Collector deletes common sensitive attribute names and common HTTP credential-header attributes before forwarding telemetry. This is defense-in-depth only: it cannot reliably sanitize arbitrary secrets embedded in free-form log bodies, exception text, SQL, URLs, or custom attribute values.

Query strings and request/response bodies must not be captured merely to improve observability. Any future body/header capture requires explicit security review and field-level allowlisting.

## Cardinality and privacy

Use bounded, operational dimensions for metrics. Do not use user IDs, account IDs, email addresses, request IDs, token IDs, entitlement names with unbounded growth, or arbitrary error text as metric labels.

Tenant/domain identifiers may only be added to telemetry when there is a documented operational requirement and an explicit privacy/cardinality decision. They are not part of the baseline.

## Backend independence

The Collector is the backend boundary. Changing from Grafana Cloud to another OTLP-compatible backend should normally require Collector/environment configuration only.

Do not introduce vendor SDKs into domain or application code for routine traces, metrics, or logs.

## Operational checks

Before treating an environment as observable:

1. verify the Collector is healthy;
2. generate a known HTTP request and confirm a trace reaches the backend;
3. confirm server metrics arrive with `service.name=wyrmgate-iam` and the expected deployment environment;
4. confirm logs show trace/span correlation for traced requests;
5. run a synthetic credential-bearing request and verify forbidden headers/attributes are not present in exported telemetry;
6. confirm Collector/backend credentials do not appear in container inspect output accessible to unprivileged users, deployment logs, or application logs.

## Known limits

Sprint 12 does not yet provide production SLOs, dashboards, alert policies, centralized application-log shipping, tail sampling, or audit-event visualization. Those are operational layers on top of this telemetry foundation.
