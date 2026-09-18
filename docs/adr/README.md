# Architecture Decision Records

This directory is the canonical home for Wyrmgate IAM architecture decisions. The previous Google Drive ADR library is superseded by these repository ADRs.

## Status model

Each ADR is `Proposed`, `Accepted`, `Superseded`, or `Rejected`.

## Current canonical ADRs

- [ADR-0001 Framework-neutral capability architecture](0001-framework-neutral-capability-architecture.md)
- [ADR-0002 Canonical identity, principal, role and access model](0002-canonical-identity-principal-access-model.md)
- [ADR-0003 Governance state, validity, fulfillment and observation separation](0003-state-validity-fulfillment-observation.md)
- [ADR-0004 Desired-state integration, connectors and reconciliation](0004-desired-state-integration-and-reconciliation.md)
- [ADR-0005 Administrative authorization and tenant isolation](0005-administrative-authorization-and-tenant-isolation.md)
- [ADR-0006 Credential secret boundary, audit and evidence](0006-credential-audit-evidence-boundary.md)
- [ADR-0007 Dynamic schema, mapping, authority and provenance](0007-dynamic-schema-and-provenance.md)
- [ADR-0008 API and event contract model](0008-api-and-event-contract-model.md)
- [ADR-0009 Domain-owned workflow and durable orchestration](0009-domain-owned-workflow-and-durable-orchestration.md)
- [ADR-0010 Physical data model and persistence boundaries](0010-physical-data-model-and-persistence-boundaries.md)
- [ADR-0011 Control-plane authentication and initial administrator bootstrap](0011-control-plane-authentication-and-initial-admin-bootstrap.md)

## Proposed ADRs

- [ADR-0012 Integrity-protected API continuation cursors](0012-integrity-protected-api-continuation-cursors.md)

Formal specification documents are versioned separately in the IAM `Formal Specifications` Drive folder. An accepted ADR may amend a formal specification between document revisions; the next formal revision must fold the ADR into the specification.
