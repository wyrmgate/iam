# ADR-0036: Audit EvidenceSnapshot, material display snapshot and integrity metadata

Status: Accepted

## Context

The formal v0.6 specifications distinguish AuditRecord, EvidenceSnapshot, capability-owned decision evidence and operational logging. They also require evidence to remain understandable after rename/retirement.

The existing AuditRecord table reserved `material_snapshot` and `integrity_metadata` JSON columns but intentionally left them unused. Arbitrary JSON cannot become a generic evidence model.

## Decision

### 1. EvidenceSnapshot is a separate immutable Audit concept

`EvidenceSnapshot` captures bounded decision-time contextual references without becoming current-state authority.

The first closed schema records:

- stable snapshot ID;
- occurred/recorded time;
- optional governed actor;
- semantic snapshot type;
- one subject resource type/ID/revision;
- optional policy resource type/ID/revision;
- optional related resource type/ID/revision;
- normalized decision/outcome label;
- optional data-minimized subject/policy/related display labels;
- correlation/causation.

It does not accept arbitrary provider payloads, free-form JSON, secrets, credential material or mutable state.

### 2. AuditRecord material snapshot is a closed typed display snapshot

An AuditRecord may optionally carry `AuditMaterialSnapshot` containing only:

- actor display label;
- resource display label;
- resource revision;
- resource lifecycle/state label.

These values are explanatory evidence only. Stable IDs in AuditRecord remain authoritative references.

The persisted JSON representation is versioned and closed by application validation. Producers that do not have safe material context continue to write no material snapshot.

### 3. AuditRecord integrity metadata is typed and deterministic

Every newly appended AuditRecord stores `AuditIntegrityMetadata v1` with:

- algorithm = SHA-256;
- schema version;
- canonical-content SHA-256;
- optional material-snapshot SHA-256.

The digest is calculated from a canonical closed representation of the immutable AuditRecord semantic fields plus `recordedAt` and the optional closed material snapshot.

This is tamper-detection metadata, not a blockchain, signature, global ordering guarantee or authorization signal.

### 4. Replay comparison includes material evidence when supplied

Stable AuditRecord ID replay remains idempotent only when immutable semantic content matches. If a producer supplies material snapshot content, conflicting material content for the same record ID is rejected.

Integrity metadata is derived and is never client-supplied.

### 5. EvidenceSnapshot has bounded read/search

EvidenceSnapshot is immutable, tenant-scoped, exact-filter queryable and deterministically paged. Public read requires a separate default-deny `evidence-snapshot:read` permission.

Creation is an internal typed Audit command boundary for capability producers; a generic public JSON snapshot-create endpoint is not introduced.

## Consequences

- evidence remains understandable after selected display metadata changes without copying entire foreign aggregates;
- the reserved JSON columns acquire governed typed meaning rather than becoming extension bags;
- integrity verification is deterministic and testable;
- EvidenceSnapshot remains distinct from AuditRecord and from capability-owned approval/review/provisioning evidence.
