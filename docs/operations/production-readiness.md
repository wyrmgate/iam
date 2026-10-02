# Production Readiness and Recovery Objectives

## Purpose

This document defines the implementation-facing OD-005 production readiness contract. It does not select a production vendor topology, assign unreviewed numeric SLO/RPO/RTO targets, or change IAM domain semantics.

The production design must preserve the existing architecture: capability ownership remains framework/provider neutral; only the owning capability mutates authoritative state; external provider calls remain outside authoritative transactions; asynchronous work is at-least-once and retry/replay safe; authoritative privilege reduction must not be blocked by unrelated evaluator failure; secrets/private credential material remain excluded from ordinary APIs, events, audit, logs, task payloads and errors.

## Status

OD-005 remains open until the objectives, selected topology, recovery design, verification drill, observability coverage and operator runbooks are all reviewed and evidenced.

The former Cloudflare Pages / Railway Serverless / Neon PostgreSQL combination is retired as an end-to-end DEV topology because the Railway server is no longer in use. Historical deployment evidence remains useful, but no replacement managed server target is currently canonical and none of this settles the production architecture.

## Production objective register

Numeric targets must be approved from explicit business/operational requirements before they are treated as production commitments.

| Objective | Indicator / definition | Target | Approval/evidence |
| --- | --- | --- | --- |
| Public/control-plane API availability | successful eligible requests / total eligible requests over the review window | **TBD** | business/operations approval required |
| Public/control-plane API latency | request latency distribution by semantic operation class, excluding deliberate async completion time | **TBD** | business/operations approval required |
| Durable work timeliness | age of oldest eligible non-terminal work item and completion latency by work class | **TBD** | business/operations approval required |
| Authoritative database RPO | maximum accepted loss of committed authoritative/evidence state after declared disaster | **TBD** | business/operations approval required |
| Authoritative database RTO | elapsed time from declared recovery start until the recovered service passes re-entry criteria | **TBD** | business/operations approval required |
| Recovery verification cadence | maximum interval between successful production recovery drills | **TBD** | operations/security approval required |

Rebuildable projections do not receive an independent data-loss guarantee equivalent to authoritative/evidence state. They must be recoverable from authoritative sources and replay/rebuild mechanisms.

## Service level indicators

At minimum production telemetry must support:

- request success/error rate for public/control-plane endpoints;
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

The production design must explicitly handle the following failure classes.

| Failure | Required behavior |
| --- | --- |
| Application instance/process failure | another healthy instance or controlled restart restores service without corrupting authoritative state; claimed work must become safely reclaimable |
| Deployment regression | application revision can be rolled back to a previously verified compatible revision; database down-migration is not the default rollback mechanism |
| Database instance/service failure | failover or restore follows the selected database HA/recovery design and stays within reviewed RPO/RTO targets |
| Data corruption / destructive migration / operator error | recover through PITR/backup/forward fix according to documented decision criteria; never repair by ad-hoc cross-capability mutation |
| Zone/infrastructure failure | selected topology states automatic versus manual failover and the expected service degradation |
| Provider regional/control-plane outage | selected topology states the isolation/failover boundary; provider outage must not rewrite valid governance decisions |
| Deployment configuration/account compromise | configuration and secret ownership/recovery are documented separately from application data recovery |
| External connector/provider outage | business/governance authority remains valid; technical work retries or becomes remediation/finding state without distributed rollback |

## Production topology decision gate

A production topology must not be declared complete until the reviewed SLO/RPO/RTO targets and failure model exist.

The selected topology must document:

- runtime redundancy and failure-domain placement;
- database HA, PITR/continuous recovery and backup retention;
- whether cross-region recovery is required and, if so, its consistency/failover model;
- secret/configuration storage and recovery ownership;
- deployment health gates and rollback mechanics;
- expected behavior during partial provider outages;
- how durable background work resumes after failover/restore;
- how tenant isolation is preserved during recovery and verification.

If the selected topology or DR model becomes a durable architectural choice, capture it in an ADR. Provider-specific operator steps remain in runbooks, not canonical domain architecture.

## Recovery classes

### Authoritative and evidence state

Includes capability-owned authoritative state, immutable evidence, idempotency/deduplication state needed for safe replay, and durable process/work state whose loss could duplicate or skip business effects.

Recovery must preserve referential/tenant invariants and enough causal state to resume safely.

### Rebuildable projections

EffectiveAccess and other explicitly rebuildable projections may be recreated from authoritative inputs. Recovery verification must prove rebuild, not manually edit projection rows.

### External/provider state

Provider state is observation/fulfillment, not the database recovery authority. After database/service recovery, reconciliation establishes current external truth and drives convergence.

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
9. recovery evidence and any exceptions/follow-up actions are recorded.

## Outstanding decisions before OD-005 closure

- approve numeric production SLO targets;
- approve authoritative database RPO and RTO;
- select the production runtime/database topology;
- approve backup retention/protection and independent-backup requirements;
- decide whether cross-region recovery is required;
- approve recovery-drill cadence;
- implement production alert routing/escalation appropriate to the selected operations environment;
- execute a measured isolation-safe recovery drill and retain evidence.
