# ADR-0003: Separate governance state, validity, fulfillment and observation

Status: Accepted

## Context

IAM state becomes unsafe and hard to reason about when governance intent, time validity, external execution progress and provider observations are collapsed into one status.

## Decision

The canonical model separates four dimensions:

1. Business/governance state - what IAM decided.
2. Validity - whether that decision is effective at the current time.
3. Fulfillment - whether the desired technical state has been attempted/realized.
4. Observation - what an external provider/source reports.

`AccessAssignment` uses `SCHEDULED`, `ACTIVE`, `SUSPENDED`, `REVOKED`, `EXPIRED`, `CANCELLED`; revocation progress is fulfillment, not assignment state. Expiry/validity is semantic and cannot be extended by scheduler delay.

Observed state never silently authorizes. Downstream failure never rewrites a valid upstream governance decision.

## Consequences

- A revoked assignment may coexist with an observed grant still present; this is drift/failed fulfillment.
- A provider outage does not keep business authorization alive.
- Projections and reconciliation can represent inconsistent desired/observed state explicitly.
