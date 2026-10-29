# ADR-0053: Explicit tenant and deployment Audit evidence scope

Status: Accepted

## Context

The existing Audit model is tenant-scoped, while ADR-0049 introduces deployment-scoped platform authority and deployment-owned platform application state. Security-significant deployment operations need durable Audit evidence, but modeling them as ordinary tenant evidence with a nullable `tenantId` would make ownership, authorization, retention and destructive-evidence controls ambiguous.

Deployment-scoped evidence must also avoid a second mistake: a platform operator or `PLATFORM_OWNER` must not automatically gain unrestricted visibility into tenant-owned Audit evidence merely because deployment evidence exists.

The agreed Audit architecture therefore introduces an explicit closed evidence-ownership scope.

## Decision

### AuditEvidenceScope

Every Audit evidence record/process belongs to exactly one explicit evidence realm:

```text
AuditEvidenceScope = TENANT(ownerTenantId) | DEPLOYMENT
```

This scope is evidence ownership and authorization context, not merely a filter.

Tenant-owned Audit evidence always has one owning Tenant. Deployment evidence has no tenant owner and is not represented by setting a tenant-ownership column to null on an ordinary tenant-owned row.

### Actor and affected-Tenant provenance

A deployment-scoped Audit event produced by a governed actor preserves the actor's governed identity and, where that actor is tenant-owned, the actor's home-Tenant provenance separately from evidence ownership.

A deployment event may also carry an optional typed `subjectTenantId` / affected-Tenant reference where a deployment operation concerns a Tenant. That reference does not transfer evidence ownership to the Tenant and does not by itself authorize tenant-evidence access.

### Lifecycle realm consistency

Audit lifecycle operations remain scope-consistent. One export, archive segment, retention-policy application, legal hold, purge operation or equivalent evidence-lifecycle process operates within one evidence realm.

Cross-realm evidence aggregation is not implied by ordinary Audit APIs or storage. Any future cross-Tenant/deployment reporting or support view requires an explicit separately authorized query/projection contract.

Retention age never authorizes destructive deletion by itself. Existing legal-hold, archive-coverage, dual-control and destructive-purge invariants continue to apply within the selected evidence scope.

### Authorization boundary

Audit read/search/export/hold/purge authorization is evaluated for the requested evidence scope.

`PLATFORM_OWNER` or other deployment authority does not automatically grant unrestricted read/export/hold/purge authority over tenant-owned Audit evidence. Tenant-global administrative scope remains global only inside that Tenant.

Conversely, tenant-scoped Audit authority does not automatically authorize deployment evidence.

Exact public permission names and deployment-scope API shapes are interface/implementation decisions and are not fixed by this ADR.

### Cross-capability production

The originating capability commits its authoritative result first under its own rules. Audit records a data-minimized semantic occurrence through the Audit boundary. Audit failure never rewrites an otherwise valid business/security result.

Tenant-owned operations append tenant-scoped evidence. Deployment-owned operations append deployment-scoped evidence. The producing capability supplies explicit `AuditEvidenceScope`; Audit does not guess ownership from nullable foreign identifiers.

### External evidence and SIEM

Archive-artifact and SIEM calls remain technical adapter work outside authoritative Audit transactions. Retry/lease/delivery mechanics remain Platform infrastructure and never own evidence semantics.

Deployment evidence may be exported/archived/SIEM-delivered only under the same data-minimization, integrity, retry and authorization guarantees as tenant evidence.

### Persistence boundary

This ADR defines logical ownership and invariants, not a mandatory physical schema. Implementations must make scope explicit and structurally non-ambiguous; they must not rely on a generic nullable tenant owner to distinguish deployment evidence.

No cross-capability cascade may delete Audit evidence because a Tenant or referenced resource changes lifecycle.

## Consequences

- Audit can record deployment-level security/governance operations without weakening Tenant isolation.
- Actor home-Tenant and affected-Tenant provenance remain explainable without being confused with evidence ownership.
- Export/archive/retention/hold/purge semantics remain deterministic within one evidence realm.
- Platform authority and tenant Audit visibility stay separate and default-deny.
- Public deployment-scope Audit APIs, permission vocabulary and concrete persistence are deferred implementation/interface work after this architectural decision.
