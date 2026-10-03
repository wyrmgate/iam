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

## OD-006 legacy-to-v2 migration

Legacy-to-v2 migration is governed by [the OD-006 discovery and cutover contract](../docs/migration/legacy-to-v2-discovery-and-cutover.md).

Before adding production migration code or fixtures, establish an observed legacy inventory and reviewed typed mapping register. Migration tooling must preserve v2 capability ownership and Authoritative State / Observation / Evidence / Projection boundaries, remain restartable/idempotent, preserve required historical evidence, and prevent partial migration from being interpreted as complete current truth or destructive absence.

This directory may host cross-version tooling, fixtures, crosswalk support and migration reports once their semantics are grounded in observed legacy data. It must not become a generic bypass around capability-owned invariants or a location for secret/private credential material.
