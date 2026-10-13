# Console test harness

Console tests use Node's built-in test runner and keep reusable setup under `tests/helpers/`. Test helpers are test-only infrastructure; they must not carry business rules, IAM authorization semantics, permanent mock business data, or provider secrets.

## Helpers

- `helpers/http.mjs` installs/restores `globalThis.fetch`, builds JSON responses, and captures outbound request details for semantic API assertions.
- `helpers/source-contract.mjs` loads Console source files and provides grouped positive/negative source assertions for implementation-contract tests.
- `helpers/browser.mjs` installs a deterministic lightweight `window`/`location`/`history` boundary for navigation-state tests that do not require a full browser DOM.

Prefer these helpers over duplicating global setup in individual tests. Keep test doubles at browser/network/runtime boundaries and assert stable semantic behavior (route, operation, revision/idempotency headers, state visibility, authority boundaries) rather than incidental component structure where possible.

The authentication layout test remains separately owned while its protected login-composition work is active and must not be migrated opportunistically.
