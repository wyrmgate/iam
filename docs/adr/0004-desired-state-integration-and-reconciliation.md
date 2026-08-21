# ADR-0004: Desired-state integration, connectors and reconciliation

Status: Accepted

## Context

Provisioning and reconciliation must remain provider-neutral, retry-safe and unable to redefine governance authority.

## Decision

Access computes desired principal/grant state. Integration owns ConnectorInstance/Binding, ProvisioningJob/Task, ReconciliationRun and provider observations. Provisioning consumes desired state; it does not create governance intent.

Connector capabilities are semantic and transport-neutral. The same connector contract may execute in-process or through a remote worker later.

Provisioning work is durable, idempotent and revision-aware. Stale tasks are revalidated and superseded/no-op. External calls never execute inside the authoritative transaction.

Reconciliation records observed state and run completeness separately. Destructive absence inference is allowed only with trustworthy COMPLETE coverage and healthy supported discovery semantics. Observed excess access becomes a finding; adoption requires explicit governance.

## Consequences

- Partial discovery/import cannot trigger mass revocation/leaver behavior.
- Desired and observed state may diverge visibly without corrupting authority.
- Connector/runtime transport can evolve independently from domain semantics.
