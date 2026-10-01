# Production Recovery Verification Drill

## Purpose

This runbook defines the repeatable evidence-producing recovery drill required for OD-005. It is intentionally provider-neutral until a production topology is selected.

A successful backup job is not proof of recoverability. Recovery must be exercised against an isolation-safe target.

## Preconditions

Before a drill:

- use a reviewed production recovery design and the currently approved RPO/RTO targets;
- identify the exact source environment/revision and recovery mechanism being tested;
- choose an isolation-safe target that cannot serve production traffic accidentally;
- prevent recovered connectors/webhooks/outbound integrations from mutating real external systems unless the drill plan explicitly authorizes a safe test path;
- ensure no production secret is copied into an uncontrolled lower/shared environment;
- assign a drill owner and evidence location.

## Evidence header

Record:

- drill identifier;
- start/end timestamps;
- operator(s);
- source application commit/release;
- source database/schema/Flyway version;
- selected recovery point or backup identifier;
- target environment identifier;
- recovery mechanism;
- approved RPO/RTO values;
- reason/cadence trigger for the drill.

Do not record secret values.

## Procedure

### 1. Establish recovery point

Record the newest intended recoverable transaction/time/revision allowed by the recovery mechanism.

For PITR/continuous recovery, select a precise recovery timestamp/point. For logical backup, identify the dump and verify its integrity/checksum where applicable.

### 2. Recover into isolation-safe target

Create or restore into a target that is isolated from production traffic and destructive external integrations.

Do not overwrite the active production database as part of a routine verification drill.

### 3. Verify schema and migration state

Verify:

- expected capability schemas exist;
- Flyway history is internally consistent;
- latest expected successful migration is present;
- the application can start against the recovered target without applying an unintended destructive repair.

### 4. Verify authoritative and evidence state

Use representative records across multiple capabilities to confirm that:

- stable IDs and revisions are preserved;
- tenant ownership/isolation remains correct;
- authoritative state is present;
- immutable evidence/AuditRecord samples expected inside the recovery point are present;
- state known to be after the chosen recovery point is absent when loss within RPO is expected.

Do not treat projection rows alone as proof of authoritative recovery.

### 5. Verify rebuildable projections

For at least one representative projection path:

- clear/rebuild only through the supported rebuild mechanism in the isolated target;
- prove the projection converges from authoritative inputs;
- verify no manual cross-capability table editing is required.

### 6. Verify durable asynchronous work safety

Inspect and exercise representative durable work/outbox/inbox/scheduled items.

Verify:

- claimed work with expired leases can be reclaimed;
- duplicate/replayed delivery does not duplicate authoritative effects;
- idempotency/deduplication state behaves as expected;
- stale desired/provider work is revalidated before external execution;
- retry/final-failure/manual-remediation states survive recovery appropriately.

External calls should use safe test doubles or isolated provider targets unless explicitly approved for the drill.

### 7. Verify application health

Start the application against the recovered target and verify:

- startup completes;
- health checks pass;
- database connectivity is healthy;
- critical read paths work;
- any intentionally disabled external integration is visibly degraded rather than silently treated as healthy.

### 8. Measure RPO

Determine the newest safely recovered committed data point and compare it with the source timeline.

Record the observed data-loss interval and whether it satisfies the approved RPO.

### 9. Measure RTO

Measure from the declared recovery start until all documented service re-entry criteria are satisfied.

Record the elapsed duration and whether it satisfies the approved RTO.

### 10. Record exceptions and cleanup

Record:

- failed checks;
- manual interventions;
- unexpected duplicate/retry behavior;
- recovery gaps;
- follow-up issues and owners.

Remove disposable targets/credentials after evidence has been retained according to policy.

## Pass criteria

A drill passes only when:

- recovered schema and application revision are compatible;
- representative authoritative/evidence state is correct;
- tenant isolation is preserved;
- projection rebuild is proven;
- durable work resumes safely under replay/retry assumptions;
- application health/re-entry checks pass;
- measured RPO and RTO satisfy the approved targets;
- evidence and follow-up actions are recorded.

Any material failure keeps OD-005 open.

## Drill cadence

The normal cadence remains **TBD** until approved by operations/security/business stakeholders.

Run an out-of-cycle drill after a material change to:

- production topology or database provider;
- backup/PITR policy or retention;
- migration/recovery mechanics;
- cross-region/failover design;
- secret/configuration recovery ownership;
- durability/idempotency mechanisms that could affect replay after restore.
