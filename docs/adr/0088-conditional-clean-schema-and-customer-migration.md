# ADR-0088: Conditional clean persistence baseline and beta-company migration

- Status: Accepted (G2 conditional policy; draft PR #544, not yet effective on main)
- Date: 2026-11-16
- Product direction: conditional new baseline approved; no schema reset or migration authorized ([#539](https://github.com/wyrmgate/iam/issues/539#issuecomment-6905749487)).
- Proposed amendment to ADR-0048 only if target chosen; preserves ADR-0010, 0005, 0047, 0051, 0053, 0043. Related #437/#438/#543; OD-006.

## Context
Current V42 follows previous development rebaseline; V70 reserved. ADR-0048 permits disposable DEV reset after exact schema equivalence but not historical customer Flyway forgery. One beta company is an owner-stated constraint, actual deployed schema/history/data/keys/provider bindings unknown. IAM Tenant ownership moved to Tenancy in V12; historical ADR-0048 wording is superseded by ADR-0051.

## Decision
Compare A incremental V42 deltas vs B clean target schema + explicit versioned migration/export-import. Retain V42 and incremental legitimate deltas as current baseline. Choose B only if material simplification/security/reuse benefit plus proven preservation/mapping, and a rehearsed operational cutover. Otherwise A. No automatic schema drop/reset or customer Flyway history rewrite. Preserve stable IDs/revisions, tenant and deployment ownership, typed first-class relationships, access intent vs projection/observation/evidence, immutable evidence and historical references. Rebuild projections explicitly, not authoritative data. Do not migrate secrets in ordinary export: protected secret/verifier compatibility or controlled rotation/re-enrollment, session invalidation, issuer/subject continuity and signing-key policy. Actual customer source version must be inventoried before migration design is final.

Rebaseline experiment E4 uses disposable synthetic two-tenant dataset and isolated DBs: compare V42 schema vs candidate, normalized dump exact equivalence for unchanged semantics and explicit migration mapping for intentional changes; semantic counts/references/revisions, audit integrity/retention, effective access and provider binding checks, tenant constraints and rollback/restore. Preserve V70 reservation and source history; candidate fresh target migration history is its own. No real customer data until approved protected access. Repeatable rerun, checkpoint/resume, failed row handling; no partial destructive absence. No external effect inside data import; pause/fence workers, verify current admission and reconcile after restore.

Cutover plan: approved inventory, backup/restore proof, pause/drain/fence writers and providers, source read-only, final delta/checkpoint, import and reconcile, staged login/provider verification, single writer switch, monitored acceptance. Rollback before target writes can return source with explicit decision; after target writes require approved reverse migration/reconciliation or forward recovery, never silently lose writes/duplicate authority. Release owner and customer approve downtime/RPO/rollback window; ADR-0043 production objectives and recovery drill remain, not waived. Preserve old installation securely until retention/rollback period expires.

## Alternatives / consequences
A incremental deltas minimizes data movement, remains fallback. B clean target can remove baggage but adds migration/security/compatibility work; conditional. C destructive reset customer rejected; disposable DEV reset separately explicit per ADR-0048/#437. No baseline regenerated for ordinary changes.

## G2 evidence and scope
[Approved 2026-11-16](https://github.com/wyrmgate/iam/issues/538#issuecomment-6906844883). E4 equivalent V42 schema replay and six-table synthetic fixture passed; no changed target schema or customer migration proved. No rebaseline namespace selected; ADR-0048 is not amended by this conditional policy alone. V70 reserved. No customer access, migration, reset, or change to Flyway history authorized. 

## Evidence / approval
E4 report and Data/Platform review; actual beta inventory/retention/keys and compatibility in #543; #542 integrated staging first; customer rehearsal/recovery and human cutover approval before activation. If B chosen, amend ADR-0048 with exact version namespace and migration mapping, then canonical data/ops docs and #438 controlled formal checkpoint. No migration SQL or data change here.
