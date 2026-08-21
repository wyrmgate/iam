# ADR-0005: Administrative authorization and tenant isolation

Status: Accepted

## Context

Business access, IAM control-plane authority and runtime/API authorization are distinct security concerns. Treating ownership, OAuth scope or tenant membership as superuser authority would create escalation paths.

## Decision

The architecture recognizes three planes: governed business access, IAM administrative authorization and runtime application authorization.

Administrative authorization uses actor + semantic permission + resource + strongly typed scope + context/assurance. It defaults deny. AdministrativeRole, AdministrativeGrant and AdministrativeDelegation are distinct from business Roles/AccessAssignments.

Ownership does not imply unrestricted administration. Delegation cannot exceed the delegator's current effective delegable authority. Self-escalation and self-approval of administrative elevation are denied by default. JIT/break-glass authority is short-lived, strongly authenticated, reasoned, audited, notified and post-reviewed.

Tenant is an optional hard isolation/customer boundary; Organization is business hierarchy. Ordinary tenant-owned relationships and administrative scopes cannot cross tenant boundaries.

## Consequences

- OAuth scopes can be coarse API gates but are not the authoritative IAM administration model.
- Multi-tenant and single-tenant deployments use the same core semantics.
- Scoped query construction must enforce authorization before pagination/result exposure.
