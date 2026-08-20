# Engineering Scripts

Repository automation that does not belong inside an application runtime.

Planned areas:

- `dev/` - local developer helpers
- `ci/` - CI support scripts
- `release/` - release and artifact tooling
- `operations/` - operator-facing maintenance helpers

Scripts must be deterministic where practical, fail loudly on errors, and avoid embedding credentials or environment-specific secrets.
