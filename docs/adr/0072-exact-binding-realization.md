# ADR-0072: Exact binding-scoped provider realization

Status: Accepted

## Decision

Integration v3 preserves ConnectorInstance/Binding, durable ProvisioningJob/Task, immutable ProvisioningAttempt, ReconciliationRun, observations and explicit technical mappings. Identity, Catalog, Access, Governance, Credential, Tenancy, Administration and Audit remain their own Layer-4 authorities; provider adapters are Layer 5 and runtime lease mechanics Layer 6. Source ingestion is an Identity-owned acceptance/correlation operation, distinct from target observation. Federation trust/link follows ADR-0071; verified proof is not an observation mapping.

A privilege-increasing grant route requires a present observed entitlement mapped to the exact Catalog Entitlement AND a present observed account mapped to the exact Identity Principal in the same Tenant and ConnectorBinding. Exactly one eligible route is necessary; zero or multiple routes fail closed. Principal.nativePrincipalKey, email, username and confidence scores are not provider correlation authority. The initial grant slice requires explicit mappings; no automatic cross-binding correlation or inferred backfill. Existing account reactivation/disable may use explicit binding mapping or exact successful account-provisioning evidence for the same desired Principal cause and provider object; an active conflicting mapping rejects historical evidence. Use the resolved provider ID, not a generic Principal key, in the operation payload. Historical grant cleanup and coalesced demand remain ADR-0056. Provider configuration and route revision fencing at claim/execution, safe repair and late-effect convergence are subsequent implementation slices.

## Consequences

Previously implicitly routed grants may require explicit binding-scoped mapping before new provisioning. Existing authority and successful execution history are preserved. No migration invents mappings. The current SCIM baseline and worker protocol remain; no new provider or public API activated.
