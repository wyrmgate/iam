# Cross-Cutting Tests

This area contains tests that span application or bounded-context boundaries.

Planned areas:

- `integration/` - cross-component integration behavior
- `e2e/` - end-to-end IAM journeys
- `security/` - security regression tests and privilege-boundary checks

Unit, application-service, repository, and architecture tests should remain close to the code they verify. This top-level test area is reserved for scenarios that are genuinely cross-cutting.

Critical end-to-end journeys include identity lifecycle, request/approval/assignment/provisioning, reconciliation, review/remediation, and negative authorization paths.
