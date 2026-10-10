# Production Readiness and Recovery Objectives

## Purpose

This document defines the implementation-facing OD-005 production readiness contract. It uses the approved objectives and topology direction from [ADR-0043](../adr/0043-production-ha-dr-and-recovery-objectives.md) while keeping provider-specific deployment mechanics outside canonical architecture.

The production design preserves the existing architecture: capability ownership remains framework/provider neutral; only the owning capability mutates authoritative state; external provider calls remain outside authoritative transactions; asynchronous work is at-least-once and retry/replay safe; authoritative privilege reduction must not be blocked by unrelated evaluator failure; secrets/private credential material remain excluded from ordinary APIs, events, audit, logs, task payloads and errors.

## Status

The OD-005 objective/topology decision gate is resolved by ADR-0043. OD-005 itself remains open until the selected production environment implements these controls, production alert routing exists, and a measured isolation-safe recovery drill proves the RPO/RTO and service re-entry criteria.

The former Cloudflare Pages / Railway Serverless / Neon PostgreSQL combination was a DEV topology and does not define production. Production provider selection must satisfy this contract rather than redefine it.

## Production objective register

| Objective | Indicator / definition | Approved target | Verification |
| --- | --- | --- | --- |
| Public/protocol and control-plane API availability | successful eligible requests / total eligible requests over a calendar month | **99.95% monthly** | production SLI/error-budget dashboard and incident evidence |
| Read/discovery/JWKS/token latency | bounded request latency under normal load and healthy dependencies | **p95 <= 500 ms; p99 <= 1.5 s** | production histograms and load verification |
| Authoritative mutation latency | bounded synchronous semantic mutation latency | **p95 <= 1.0 s; p99 <= 2.5 s** | production histograms and load verification |
| First-party local credential verification | server verification latency excluding human/browser/upstream-federation delay | **p95 <= 2.0 s** | authentication latency histogram without secret capture |
| Security-sensitive durable work disposition | time until execution or explicit retry/manual-remediation state | **<= 5 min** with healthy internal dependencies | durable-work age/completion metrics |
| Normal durable work | processing start and completion/disposition | **start <= 5 min; 95% complete/disposition <= 15 min; alert if unexplained age > 30 min** | backlog/oldest-age/completion metrics |
| Authoritative/evidence database RPO | maximum accepted committed-state loss after declared disaster | **<= 5 min** | measured recovery drill |
| Authoritative/evidence database RTO | declared recovery start until service re-entry criteria pass | **<= 60 min** | measured recovery drill |
| Single-instance/zone recovery | time to restore healthy serving capacity without invoking regional DR | **<= 10 min** | HA/failover exercise |
| Recovery verification cadence | maximum interval between successful full drills | **quarterly** | retained drill evidence |
| Sev-1 acknowledgement | page to acknowledged owner | **<= 15 min, 24x7** | paging evidence |
| Sev-2 acknowledgement | page to acknowledged owner | **<= 30 min, 24x7** | paging evidence |

Eligible availability requests exclude client-caused 4xx validation/authorization failures and explicitly external-provider-originated failures after Wyrmgate has correctly entered a visible degraded state. Wyrmgate-generated 5xx responses, timeouts, database unavailability, failed health-gated deployments and internal dependency failures count against availability. Planned maintenance is not automatically excluded from the error budget.

Rebuildable projections do not receive an independent data-loss guarantee equivalent to authoritative/evidence state. They must be recoverable from authoritative sources and replay/rebuild mechanisms.

## Approved production topology direction

Production uses a provider-neutral **multi-zone active runtime with one authoritative PostgreSQL primary HA domain and cross-region warm disaster recovery**.

The concrete production environment must provide:

- at least two application failure domains in the primary region;
- database HA across independent primary-region failure domains;
- protected continuous or sufficiently frequent cross-region recovery state capable of meeting the 5-minute RPO;
- a warm second-region recovery target capable of meeting the 60-minute RTO;
- controlled/manual regional promotion rather than active-active multi-writer database authority;
- health-gated deployment and rollback to a previously verified schema-compatible application revision;
- safe replay/revalidation of durable work after failover/restore before provider effects execute.

Provider-specific implementation details belong in deployment runbooks. A provider that cannot prove these properties is not an acceptable production target.

## Backup and retention policy

Production database recovery requires all of the following:

- provider-native or equivalent continuous/PITR history retained for at least **35 days**;
- independently protected encrypted backups outside the primary database provider/account failure boundary;
- daily independent recovery copies retained for at least **90 days**;
- monthly independent recovery copies retained for **12 months**;
- integrity metadata/checksums and lifecycle controls;
- access control and secret handling that keep backup content/credentials out of ordinary IAM APIs, telemetry and logs.

A governing customer/legal deletion rule may require stricter deletion than the operational maximum. Backup retention is not permission to retain data contrary to applicable policy.

## Service level indicators

At minimum production telemetry must support:

- request success/error rate for first-party OIDC/SSO and control-plane endpoints;
- latency distributions by bounded semantic operation class;
- server process availability and startup/migration failures;
- database connection/availability health;
- oldest durable-work age, backlog size, retry exhaustion and stuck/expired leases;
- outbox/public-event delivery health where enabled;
- connector provisioning/reconciliation retry/final-failure health;
- backup/recovery job failures and stale recovery-verification evidence;
- saturation signals appropriate to the selected runtime/database topology.

Observability is operational evidence only. It does not replace AuditRecord or domain evidence.

## Failure model

| Failure | Required behavior |
| --- | --- |
| Application instance/process failure | another healthy instance or controlled restart restores service without corrupting authoritative state; claimed work becomes safely reclaimable |
| Deployment regression | roll back to a previously verified compatible application revision; database down-migration is not the default rollback mechanism |
| Database instance/service failure | primary-region HA/failover restores healthy serving capacity within the 10-minute objective without violating transaction integrity |
| Data corruption / destructive migration / operator error | recover through PITR/independent backup/forward fix according to documented decision criteria; never repair by ad-hoc cross-capability mutation |
| Zone/infrastructure failure | surviving primary-region failure domain maintains or restores service within the 10-minute objective |
| Regional/provider control-plane outage | controlled cross-region recovery meets <=5-minute RPO and <=60-minute RTO; valid governance state is not rewritten |
| Deployment configuration/account compromise | recover configuration and secrets through separately protected ownership/recovery paths |
| External connector/provider outage | business/governance authority remains valid; technical work retries or becomes degraded/manual-remediation state without distributed rollback |

## Recovery classes

### Authoritative and evidence state

Includes capability-owned authoritative state, immutable evidence, idempotency/deduplication state needed for safe replay, and durable process/work state whose loss could duplicate or skip business effects.

Recovery must preserve referential/tenant invariants and enough causal state to resume safely.

### Rebuildable projections

EffectiveAccess and other explicitly rebuildable projections may be recreated from authoritative inputs. Recovery verification must prove rebuild, not manually edit projection rows.

### External/provider state

Provider state is observation/fulfillment, not database recovery authority. After service recovery, reconciliation establishes current external truth and drives convergence.

## Service re-entry criteria

A recovered production environment must not be returned to normal service until:

1. the expected schema/Flyway version is verified;
2. representative authoritative and evidence records are present;
3. tenant isolation checks pass;
4. application health and required dependencies are healthy;
5. durable outbox/inbox/scheduled/retry work can resume without harmful duplicate effects;
6. rebuildable projections have been rebuilt or are demonstrably rebuildable;
7. provider/connector work is either healthy, retrying, or explicitly degraded with operator visibility;
8. measured recovery point/time are recorded against the approved RPO/RTO;
9. production alerting and paging are active for the recovered topology;
10. recovery evidence and any exceptions/follow-up actions are recorded.

## Alert ownership

Production alerting is 24x7.

- Platform Operations owns primary availability, database, capacity, deployment, durable-work, backup and recovery alerts.
- Security Operations is co-paged for tenant-isolation risk, authentication/credential security incidents, signing/secret compromise, Audit integrity failures and equivalent security-severity conditions.
- Sev-1 acknowledgement target is 15 minutes.
- Sev-2 acknowledgement target is 30 minutes.
- Unacknowledged pages escalate at the acknowledgement deadline.

Named staff and paging products remain deployment configuration, not architecture.

## Remaining OD-005 closure work

- select/configure the concrete production provider environment that satisfies ADR-0043;
- implement the provider-specific HA/failover, cross-region recovery and independent-backup paths;
- implement production dashboards, SLO/error-budget calculations and alert routing;
- execute the quarterly isolation-safe recovery drill and retain measured <=5-minute RPO / <=60-minute RTO evidence;
- verify application/zone failover against the <=10-minute objective;
- update the next controlled formal specification/RTM checkpoint only when the implementation and evidence support the claimed status.
