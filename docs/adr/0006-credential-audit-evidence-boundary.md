# ADR-0006: Credential secret boundary, audit and evidence

Status: Accepted

## Context

Credential governance requires lifecycle and accountability without turning IAM into an unsafe secret store. Audit and evidence also need trustworthy semantics distinct from operational logging.

## Decision

Credential owns governance/lifecycle metadata and opaque secret/provider references; raw secret/private material is structurally excluded from ordinary IAM persistence, APIs, events, audit, logs, findings and task/error payloads. Secret storage is provided through a dedicated external/provider abstraction.

A Credential belongs to exactly one Principal. Rotation is a durable process with replacement creation, distribution, verification, cutover and old-credential revocation. Compromise makes the credential unsafe immediately even when provider-side revocation is delayed.

Domain facts drive the system. AuditRecord proves security/governance-significant actions. EvidenceSnapshot preserves immutable decision context. Operational logs diagnose runtime behavior. Approval/review decisions and ProvisioningAttempts are immutable evidence.

## Consequences

- Secret leakage prevention is an architectural boundary, not only a logging convention.
- Audit/search/archive infrastructure may evolve without redefining domain facts.
- Evidence can remain understandable after resources are renamed or retired.
