# Database Migrations

All schema changes are version-controlled and applied through Flyway.

Rules:

- prefer additive migrations first
- migrations must be testable from supported starting versions
- destructive changes are separated and reviewed explicitly
- do not combine first-use replacement behavior with irreversible legacy deletion
- preserve audit/governance history
- migration anomalies must be observable and reportable

The backend build will define the canonical Flyway migration location when application bootstrapping begins. This top-level area is reserved for migration support assets, fixtures, reports, and cross-version migration tooling where appropriate.
