# Docker Compose

Local developer infrastructure lives in `local.yml`.

The local stack intentionally runs infrastructure services only; the Spring server and React console run on the host during normal development for fast reload and debugging.

Current services:

- PostgreSQL
- Valkey
- Mailpit
- OpenTelemetry Collector

All published ports bind to `127.0.0.1`. The stack is not suitable for shared/public environments.

Use repository commands from the root:

```bash
make dev-init
make dev-up
make dev-status
make dev-logs
make dev-down
make dev-reset
```

Development and demo deployment profiles remain separate concerns. Later immutable application images should still be promotable through dev/demo/staging/production without rebuilding per environment.
