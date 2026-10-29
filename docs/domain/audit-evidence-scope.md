# Audit Evidence Scope

## Purpose

This is the living implementation-facing contract for ADR-0053. It makes tenant-owned versus deployment-owned Audit evidence explicit without weakening tenant isolation or evidence-lifecycle controls.

## Scope model

Every Audit evidence record/process belongs to one closed ownership realm:

```text
TENANT(ownerTenantId)
DEPLOYMENT
```

The scope is evidence ownership and authorization context, not just a query filter.

Deployment evidence is never represented by a null tenant owner on an ordinary tenant-owned evidence row.

## Provenance

Deployment evidence produced by a governed tenant-owned actor preserves the actor Identity and home-Tenant provenance separately from evidence ownership.

An optional `subjectTenantId` may identify an affected Tenant. It is a typed reference, not the evidence owner and not an authorization shortcut.

## Evidence lifecycle

One export, archive segment, retention-policy application, legal hold or purge process remains within one evidence realm.

Existing invariants continue unchanged:

- retention age is input, never deletion authority;
- active legal hold blocks destructive purge;
- purge requires the accepted dual-control and archive/retention prerequisites;
- immutable evidence/history is never rewritten into current business authority;
- external archive/SIEM calls execute outside authoritative Audit transactions.

## Authorization

Audit read/search/export/hold/purge is authorized for the requested evidence scope.

`PLATFORM_OWNER` does not automatically grant tenant-evidence visibility or destructive evidence authority. Tenant-global Administration scope is global only inside that Tenant. Tenant Audit authority does not automatically grant deployment-evidence access.

Exact deployment-scope public API and permission names remain interface work.

## Producers

A producing capability supplies explicit `AuditEvidenceScope` with its data-minimized semantic Audit request. Audit does not infer evidence ownership from nullable subject/actor/Tenant fields.

Tenant-owned operations produce tenant-scoped evidence. Deployment-owned operations produce deployment-scoped evidence.

Audit failure never rewrites a valid authoritative business/security outcome.

## Persistence and projection

Concrete relational shape, indexes, archive partitioning and read-model implementation are implementation contracts. They must preserve explicit scope and prevent cross-realm ambiguity.

Any future cross-Tenant/deployment reporting or support view is a separately authorized projection/query contract, not an implicit generic Audit search.
