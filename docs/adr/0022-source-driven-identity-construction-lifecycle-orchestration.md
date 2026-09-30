# ADR-0022: Source-driven Identity construction and lifecycle orchestration

Status: Accepted

## Context

Wyrmgate IAM already separates source observation from canonical Identity authority. SourceRecord
is an Identity-owned observation, IdentityLink is the explicit authoritative correlation history,
and canonical attributes are resolved through versioned mappings, authority rules, provenance and
governed overrides under ADR-0007.

The v0.4 formal baseline also requires at-least-once event handling, preserves correlation and
causation, prohibits destructive absence inference from incomplete imports, and defines
ACTIVE-only access eligibility plus durable Access-owned reduction under ADR-0021.

What remains unresolved is the policy that connects positive source observations to correlation,
optional Identity construction and canonical resolution. Without a durable decision, implementation
could accidentally make provider/source observation authoritative, guess across ambiguous matches,
create access-eligible Identities from raw imports, or couple positive Joiner/Mover processing to
destructive absence/Leaver inference.

## Decision

### Identity owns source-driven construction

Source-driven Identity construction and correlation are Identity capability semantics. Integration
may deliver source observations, but only Identity evaluates correlation policy, creates canonical
Identity authority and accepts IdentityLinks.

Source-driven processing does not mutate Access, Governance, Catalog or Integration persistence.
Later cross-capability effects are driven by minimized semantic facts and typed contracts.

### Correlation policy is explicit and versioned

Automatic correlation is enabled only by an activated SourceCorrelationPolicyVersion for the
SourceSystem. Replacing policy creates a new immutable activated version and supersedes the prior
version.

The first bounded policy references exactly one active canonical attribute definition version and
the exact active source mapping version used to obtain its match value. The first implementation
supports only a STRING + SINGLE canonical match attribute with exact normalized equality.
This intentionally avoids a generic expression/rules language.

A policy may separately allow creation on a true no-match. When enabled it fixes:

- the canonical IdentityType to create; and
- the bounded source path used to initialize the Identity display name.

Identity creation is therefore an explicit governed promotion defined by policy, not automatic
promotion of arbitrary source/provider observation.

### Existing accepted links are sticky

If a SourceRecord already has an active accepted IdentityLink, positive automation keeps that link.
It may refresh mapped canonical candidates for that Identity, but it never silently replaces the
link because a later correlation query points elsewhere.

Replacing an accepted IdentityLink remains an explicit Identity-owned action. Replacement preserves
the superseded link as history and causes subsequent source-driven candidate materialization to use
the newly accepted Identity. Automated positive processing does not perform replacement.

### Deterministic match outcomes

For an unlinked SourceRecord, the active policy's mapped source value is compared to current
canonical states for the exact policy definition version.

Only canonical states whose current resolution status is RESOLVED or OVERRIDDEN are eligible
for automatic matching. CONFLICT, UNRESOLVED and NO_VALUE do not provide correlation
authority even when a retained value exists.

The outcome is:

- exactly one matching Identity -> accept correlation to that Identity;
- more than one matching Identity -> ambiguous; do not guess, replace a link or create an Identity;
- no matching Identity -> leave unlinked unless the active policy explicitly permits creation;
- missing/invalid match input -> leave unlinked and do not auto-create.

The matching query is bounded to distinguish zero, one and multiple matches; it does not load an
unbounded candidate set.

### Source-created Identity begins PENDING

When policy permits no-match creation, the created Identity begins in PENDING lifecycle.
ADR-0021 therefore makes it access-ineligible. Source observation alone cannot create an
access-eligible governed subject.

Activation or other lifecycle advancement is a separate explicit lifecycle-policy concern and is
not part of this first positive source slice.

### Positive Joiner/Mover processing is separate from negative absence inference

identity.source-record-observed is the positive trigger for this first slice. The consumer
re-reads current SourceRecord state, so stale positive facts converge on current observation rather
than replaying old payload as authority.

After correlation or policy-authorized construction, the processor:

1. enumerates the SourceSystem's current active canonical mappings;
2. extracts only bounded mapped values from the current SourceRecord;
3. records typed CanonicalAttributeCandidates with mapping/source provenance; and
4. resolves only the affected canonical attributes.

Canonical resolution remains governed by ADR-0007. If the effective outcome and provenance did not
change, resolution remains a no-op and no canonical-state fact is emitted.

Positive processing does not infer absence or termination. COMPLETE/PARTIAL/UNKNOWN import
completeness is irrelevant to accepting a positive observation. Negative absence/Leaver inference
is a separate later durable stage and may act only on trustworthy COMPLETE coverage plus any
source-specific health/authority requirements.

### Delivery, replay and ordering

Processing assumes at-least-once delivery.

- duplicate/replayed source-observation facts are safe;
- an already accepted link is idempotent and sticky;
- canonical resolution suppresses no-op state churn;
- a stale observation fact re-reads the current SourceRecord and must not overwrite newer source state;
- correlation/create/link mutation is serialized on the SourceRecord and re-checks current link state before creation, preventing duplicate source-driven Identity creation.

A source observation fact preserves the originating correlationId. Downstream Identity mutations
and canonical-resolution facts use the source event ID as immediate causationId while retaining
the end-to-end correlation ID.

### Orchestration ownership

The first positive slice does not require a new long-running business process aggregate. Each
positive SourceRecord can be processed as retryable bounded Identity-owned work from the existing
durable outbox.

If future source processing requires fan-out checkpoints beyond a bounded record, Identity will own
typed resumable process state. Generic scheduler/worker infrastructure may provide claiming,
leases and retries but never owns correlation or lifecycle meaning.

No generic BPM engine is introduced.

### Relationship to ADR-0021

Source-created PENDING Identity is access-ineligible. A later explicit transition to ACTIVE uses the
existing Identity lifecycle semantics. A later source-driven transition away from ACTIVE must use
the same Identity lifecycle transition path and ADR-0021 Access reduction chain; source processing
must never directly mutate Access.

## Consequences

- Source observation stays observation until an explicit activated policy promotes a bounded fact.
- Ambiguous correlation fails safe instead of guessing.
- Auto-created subjects cannot immediately acquire access merely because a source record appeared.
- Correlation policy changes are reviewable/versioned and do not silently inherit mapping/schema changes.
- Existing canonical mapping/authority/provenance infrastructure is reused rather than duplicated.
- Positive Joiner/Mover automation can ship independently from destructive Leaver inference.
- Exact-match STRING/SINGLE correlation is intentionally narrow; broader multi-key/fuzzy/typed matching requires an explicit extension rather than a hidden generic rules engine.

## Deferred

- destructive absence inference and mass-Leaver handling;
- source-specific explicit termination mapping;
- automatic lifecycle activation/advancement policy;
- birthright/baseline access and Mover access-delta generation;
- Identity merge/split;
- fuzzy, weighted or arbitrary-expression correlation;
- provider-native Identity or Principal promotion;
- generic workflow/BPM infrastructure.
