# ADR-0026: Explicit Identity merge/split with historical reference preservation

Status: Accepted

## Context

FR-IDM-004 requires explicit Identity merge/split while preserving historical Identity references.
The controlled v0.4 baseline intentionally leaves detailed transfer semantics open.

The current architecture already separates Identity-owned canonical subject state, source correlation
and Principal authority from Access-owned AccessAssignment authority, Governance process/evidence,
Credential authority bound to Principal, and Integration observation/fulfillment.

A merge/split operation therefore cannot be implemented as a generic reassignment of every row that
mentions an Identity ID. That would violate capability ownership, destroy evidence and silently
transfer authorization.

## Decision

### Merge and split are explicit Identity-owned operations

Identity owns immutable IdentityMergeOperation and IdentitySplitOperation evidence.

The first implementation is synchronous and bounded. It moves at most 200 current SourceRecord
links and 200 Principals in one operation. Larger cases require later durable bulk orchestration
rather than one unbounded transaction.

No automatic duplicate detection, inferred merge or inferred split is introduced.

### Merge chooses one stable survivor

A merge names one survivor Identity, one absorbed Identity, optimistic revisions for both and an
operator reason. Both Identities must belong to the same tenant, have the same IdentityType, be
distinct and be non-terminal.

The operation locks both Identity rows in deterministic ID order before validating current state.

The survivor keeps its stable Identity ID. The absorbed Identity row is retained as historical
authoritative identity and is transitioned to terminal DECOMMISSIONED through the ordinary Identity
lifecycle command.

A prior historical reference to the absorbed Identity therefore remains resolvable. It is never
rewritten to pretend that the reference originally named the survivor.

### Only Identity-owned current relationships move

Merge re-homes all current ACCEPTED SourceRecord IdentityLinks and all current Identity-owned
Principals from absorbed to survivor.

SourceRecord correlation is changed only through the existing explicit link-replacement semantic.
The old accepted link becomes historical/superseded and a new accepted link is created. Existing
correlation facts drive normal current canonical candidate/resolution repair.

Principal stable IDs are retained. Merge/split-only Principal reassignment advances Principal
revision and emits a minimized current-owner revalidation fact.

The ordinary public Principal correlation command remains one-way and still cannot reassign an
already correlated Principal.

### Foreign-capability authority never moves implicitly

Merge does not rewrite or copy AccessAssignments, Access/Governance process or evidence rows,
Governance requests/reviews/exceptions/decisions, Integration observation/provisioning evidence, or
any other foreign-capability authoritative state.

The absorbed Identity is decommissioned through the existing lifecycle path. If it had current
AccessAssignments, ADR-0021 makes that access immediately semantically ineffective and Access owns
the durable reduction. Those assignments are not transferred to the survivor.

Credential rows are not rewritten. A Credential remains bound to its stable Principal. If that
Principal is explicitly reassigned by the Identity operation, the Credential still references the
same Principal and no Credential mutation occurs.

### Split creates a fresh PENDING Identity

A split names one source Identity, its optimistic revision, a display name for the new Identity,
explicit selected current SourceRecord links and/or Principals, and an operator reason.

The new Identity has the same IdentityType and compatible typed profile as the source and starts in
PENDING.

The split must select at least one current Identity-owned relationship. Every selected SourceRecord
must currently have an accepted link to the source Identity and every selected Principal must
currently belong to the source Identity. All selections are validated before the new Identity or
relationship mutations are committed.

Only selected relationships move. Unselected relationships remain on the source Identity.

No AccessAssignment or other foreign-capability authority is copied to the new Identity. The new
PENDING Identity is access-ineligible under ADR-0021.

### Canonical attribute history is not rewritten

Canonical candidate, resolved-state and override rows are not physically copied or reassigned
between Identities.

Moving a current SourceRecord link causes the existing correlation processing path to materialize
current mapped candidate authority for the new current Identity and repair the previous side.
Historical candidate/state records retain their original Identity reference.

Manual canonical overrides are never silently copied by merge/split.

### Principal reassignment revalidates both ownership contexts

The Principal reassignment fact carries the previous Identity ID in addition to the Principal's new
current owner.

Access re-evaluates Principal-dependent ANY desired state for both previous and current owners.
This prevents a split from leaving stale desired state on an ACTIVE source Identity after one of its
Principals moves away.

The fact does not authorize AccessAssignment creation or move Access authority.

### Evidence and causality

IdentityMergeOperation records survivor and absorbed Identity IDs, both pre-operation revisions,
moved-link and moved-Principal counts, reason, correlation/causation and completion time.

IdentitySplitOperation records source and new Identity IDs, source pre-operation revision, exact
moved SourceRecord and Principal IDs, reason, correlation/causation and completion time.

Operation evidence is immutable. Relationship history remains separately authoritative in
IdentityLink/Principal history.

### Public operations are semantic and idempotent

Identity v1 exposes explicit POST /identities/{identityId}:merge and
POST /identities/{identityId}:split operations.

Both require strong Identity revision preconditions and causal idempotency.

Merge requires identity:merge permission on both survivor and absorbed Identity. Split requires
identity:split on the source Identity.

These permissions are default-deny and are not automatically added to the initial tenant-admin
permission set.

## Consequences

- Historical Identity IDs remain truthful and queryable.
- Merge cannot accidentally grant the survivor the absorbed Identity's business access.
- Split cannot accidentally clone access into a new subject.
- Identity-owned source/principal relationships can be corrected explicitly while history remains.
- Existing lifecycle, Access reduction, canonical-resolution and desired-state paths are reused.
- Principal reassignment may cause safe downstream technical reduction/re-resolution but never a
  direct provider call inside the merge/split transaction.

## Deferred

- automatic merge candidate detection;
- automatic split inference;
- cross-IdentityType merge;
- automatic copying/merging of canonical overrides;
- foreign-capability authority migration or deduplication;
- merge/split beyond the bounded first-slice relationship count;
- automatic lifecycle activation/reactivation after split;
- generic bulk merge/split workflow.
