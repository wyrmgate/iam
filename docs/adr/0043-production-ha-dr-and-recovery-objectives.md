# ADR-0043: Production HA/DR and recovery objectives

- Status: Accepted
- Date: 2026-10-10
- Supersedes: none

## Context

OD-005 requires Wyrmgate to define production service objectives, recovery objectives, failure-domain expectations and operator recovery policy before a production topology can be treated as complete. The v0.8 formal baseline deliberately does not assign production SLO/RPO/RTO numbers. Repository operations documentation likewise kept those targets as explicit decision debt rather than inheriting values from the former managed DEV topology.

Wyrmgate now includes first-party IdP/SSO as well as IGA. Production availability therefore protects both control-plane administration and authentication/SSO protocol surfaces. The design must preserve capability ownership, tenant isolation, durable/replay-safe asynchronous work, data-minimized evidence, and the rule that provider failure never rewrites valid governance decisions.

The production decision must remain provider-neutral. A concrete cloud/database vendor may satisfy the topology, but no vendor becomes part of canonical IAM architecture.

## Decision

### Service availability objectives

The production monthly availability objective for eligible Wyrmgate public protocol and control-plane API requests is **99.95% per calendar month**.

Eligible requests exclude client-caused 4xx validation/authorization failures and explicitly external-provider-originated failures after Wyrmgate has correctly entered a visible degraded state. Wyrmgate-generated 5xx responses, timeouts, failed health-gated deployments, database unavailability and internal dependency failures count against availability. Planned maintenance is not automatically excluded from the error budget.

Availability is measured separately for at least:

- first-party OIDC/SSO protocol endpoints;
- authenticated control-plane APIs;
- critical health/readiness dependencies required to serve those surfaces.

### Synchronous latency objectives

For bounded requests under normal production load and healthy dependencies:

- read/discovery/JWKS/token-exchange operations: **p95 <= 500 ms, p99 <= 1.5 s**;
- authoritative control-plane mutations: **p95 <= 1.0 s, p99 <= 2.5 s**;
- first-party local credential verification: **p95 <= 2.0 s** excluding human interaction, browser round trips and upstream federation latency.

Operations intentionally modeled as durable asynchronous work are not forced into these synchronous latency budgets.

### Durable work timeliness objectives

When required internal dependencies are healthy:

- security-sensitive privilege-reduction/revocation work must reach execution or an explicit retry/manual-remediation disposition within **5 minutes**;
- normal eligible durable work must begin processing within **5 minutes**, and **95%** should complete or reach an explicit retry/manual-remediation disposition within **15 minutes**;
- any eligible non-terminal work item older than **30 minutes** without an explicit dependency-degraded explanation is an alert condition.

External provider outage does not convert a valid governance decision into failure or rollback. Provider-blocked work must remain visible as retry/degraded/manual-remediation state and is measured separately from internal queue health.

### Authoritative/evidence recovery objectives

For a declared production disaster affecting authoritative/evidence PostgreSQL state:

- **RPO <= 5 minutes**;
- **RTO <= 60 minutes** from declared recovery start until all service re-entry criteria pass.

Loss of one application instance or one availability zone should be absorbed by the HA topology without invoking the regional-disaster RTO; the operational objective for those failures is restoration of healthy service within **10 minutes**.

Rebuildable projections do not receive an independent RPO equivalent to authoritative/evidence state. They must be reproducible from recovered authoritative inputs and replay/rebuild mechanisms.

### Production topology shape

Production uses a **multi-zone active runtime with one authoritative PostgreSQL primary HA domain and cross-region warm disaster recovery**.

The topology must provide:

- at least two application failure domains in the primary region;
- database high availability across independent failure domains in the primary region;
- continuous or sufficiently frequent protected database recovery state in a second region so the 5-minute RPO can be met;
- a warm cross-region recovery target that can be promoted or restored within the 60-minute RTO;
- controlled/manual regional failover rather than multi-writer active-active database authority;
- health-gated deployment and rollback to a previously verified application revision compatible with the current database schema;
- durable work revalidation after failover/restore before external provider effects execute.

Multi-region active-active authoritative database writes are not required and are rejected for the current baseline because they add conflict/consistency complexity without a demonstrated requirement.

### Backup and retention policy

Provider-native HA/PITR is necessary but not sufficient for production disaster recovery.

Production must maintain:

- continuous/PITR recovery history for at least **35 days**;
- independently protected encrypted backups outside the primary database provider/account failure boundary;
- daily independent recovery copies retained for at least **90 days**;
- monthly independent recovery copies retained for **12 months**;
- integrity metadata/checksums and lifecycle controls for backup objects;
- access controls that keep backup credentials and contents outside application logs, telemetry and ordinary IAM interfaces.

Customer/legal deletion obligations and applicable retention law remain able to impose a stricter deletion policy; operational backup retention is not a license to retain data contrary to governing policy.

### Recovery verification cadence

A full isolation-safe production recovery drill is required **quarterly**.

An out-of-cycle drill is additionally required after material changes to the production database/topology, PITR/backup mechanics, cross-region recovery design, migration/recovery procedures, secret/configuration recovery ownership, or durability/idempotency mechanisms that can affect replay after restore.

### Alerting and escalation ownership

Production requires 24x7 actionable alerting.

- **Platform Operations** owns primary service availability, database, capacity, deployment, durable-work and recovery alerts.
- **Security Operations** is co-paged for tenant-isolation risk, authentication/credential security incidents, signing/secret compromise, Audit integrity failures and other security-severity conditions.
- **Severity 1** pages require acknowledgement within **15 minutes**.
- **Severity 2** pages require acknowledgement within **30 minutes**.
- Unacknowledged alerts escalate to the designated secondary/on-call owner at the acknowledgement deadline.

Named people, paging products and vendor-specific routes are deployment configuration/runbook concerns rather than architecture.

## Consequences

- OD-005 now has explicit measurable service, recovery, backup and drill objectives.
- Production provider selection must prove it can satisfy these objectives; DEV provider defaults do not define production.
- A multi-zone primary plus warm cross-region DR model is required, but vendor choice remains replaceable.
- Production cost will be higher than single-region/serverless DEV because the topology intentionally buys failure-domain redundancy, protected recovery state and independent backups.
- Regional failover is controlled and evidence-producing rather than automatic multi-writer promotion.
- Recovery drills and alert response become ongoing production obligations, not one-time launch tasks.
- The next controlled formal specification revision must fold these reviewed OD-005 decisions into the BRD/SRS/SAD/Security/Integration/RTM as applicable.

## Rejected alternatives

- **Inherit availability/RPO/RTO from a managed DEV provider plan:** provider defaults are not reviewed business requirements.
- **Single-zone production with backups only:** cannot satisfy the intended availability boundary or isolate common infrastructure failure.
- **Provider-native PITR without an independent backup boundary:** does not protect against provider/account/control-plane loss or certain corruption/operational failure classes.
- **Active-active multi-writer authoritative database across regions:** adds distributed consistency/conflict complexity not justified by the current requirements.
- **Treat projection loss as authoritative-data loss:** projections are explicitly rebuildable and should be restored from authoritative inputs instead.
- **Allow provider outage to roll back governance decisions:** violates the established governance/fulfillment separation.
