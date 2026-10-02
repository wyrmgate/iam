# ADR-0035: Audit legal hold, destructive purge and archived query continuity

Status: Accepted

## Context

ADR-0031 makes AuditRecord append-only evidence. ADR-0034 adds verified immutable archive segments and versioned retention policy but deliberately does not authorize deletion.

The remaining retention problem is not “delete old rows.” It is a governed evidence-lifecycle decision: preserve required evidence, make legal holds explicit, prove an immutable archive exists, prevent self-approved destruction, keep public Audit read semantics stable, and record what was removed.

## Decision

### 1. Legal hold is explicit Audit-owned state

Audit owns a tenant-scoped `AuditLegalHold` with:

- stable ID and immutable creation evidence;
- exact occurrence window `[occurredFrom, occurredUntil)`;
- optional exact AuditRecord actor/action/resource/outcome/correlation filters;
- non-secret reason code and external case/reference identifier;
- `ACTIVE -> RELEASED` lifecycle with optimistic revision;
- creator/releaser governed Identity plus correlation/causation.

An ACTIVE hold protects every matching AuditRecord in its window. Release is explicit evidence; expiry is not inferred.

### 2. Purge is a durable dual-control Audit process

`AuditPurgeOperation` records immutable request context:

- tenant;
- exact bounded occurrence window and optional exact filters;
- requesting governed Identity;
- request-time `snapshotRecordedAt` cutoff;
- pinned retention-policy version;
- one verified covering archive segment;
- correlation/causation.

State is:

`REQUESTED -> APPROVED -> RUNNING -> SUCCEEDED | FAILED | BLOCKED`

Approval requires a different governed Identity from the requester. Approval is evidence only; execution revalidates every destructive prerequisite.

### 3. Retention policy alone never authorizes deletion

At request, approval and execution Audit revalidates:

- the pinned policy exists;
- every candidate record is older than the policy's minimum-online retention at execution time;
- the selected archive segment is `SUCCEEDED`, verified, tenant-matching, covers the entire requested occurrence range and has `snapshotRecordedAt >= purge.snapshotRecordedAt`;
- no ACTIVE legal hold matches any candidate record;
- requester and approver remain distinct;
- operation revision/state are current.

Any uncertainty blocks deletion.

### 4. Destructive deletion is database-fenced

The ordinary AuditRecord append-only trigger continues to reject UPDATE and arbitrary DELETE.

A DELETE is allowed only while the same database transaction has set a local purge-operation fence for one `RUNNING` AuditPurgeOperation. The trigger validates that the row belongs to that operation's tenant, immutable range/filter and snapshot cutoff.

Application code cannot issue an unrestricted AuditRecord delete.

### 5. Archive query continuity is a derived Audit projection

Successful archive generation creates an immutable `AuditArchivedRecordIndex` projection keyed by tenant + AuditRecord ID. It contains only the closed public AuditRecord fields plus the archive-segment ID.

The index is derived evidence-query state, not business authority and not a substitute for the immutable external archive artifact.

Public Audit detail/search read the union of:

- online `audit_record`; and
- archived-record index rows whose source online row is absent.

Online wins during overlap. Results retain the existing deterministic `occurredAt DESC, id DESC` ordering and exact filters.

This gives bounded transparent reads after allowed online purge without scanning external artifacts on every query.

### 6. Purge preserves deletion evidence

On success Audit records:

- exact operation ID/context;
- approved-by;
- covering archive segment;
- deleted row count;
- execution/completion time;
- normalized outcome.

Purge does not delete archive metadata, archived-record index, legal-hold history, purge history or other evidence.

### 7. Public administration uses semantic operations

The public control plane may expose:

- create/read/release legal hold;
- create/read/approve purge operation.

Execution remains asynchronous and not an arbitrary DELETE endpoint.

Separate default-deny permissions are used for hold management and purge. They are not added to INITIAL_TENANT_ADMIN.

## Consequences

- append-only remains the default database invariant;
- destructive deletion is possible only through a separately approved, proven, bounded process;
- legal hold dominates retention;
- public Audit read/search remains coherent after permitted online purge;
- archive artifacts remain immutable evidence while the relational archive index is a rebuildable/query-oriented projection;
- no cross-tenant or wildcard purge exists in this slice.
