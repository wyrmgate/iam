# ADR-0015: Provider observation mapping and governance drift boundary

Status: Accepted

## Context

ADR-0002 defines Catalog Entitlement as canonical technical access authority and AccessAssignment as authoritative access intent. ADR-0004 defines desired/observed convergence, requires observed excess access to become a finding, and requires explicit governance for adoption. ADR-0010 keeps Authoritative State, Observation, Evidence and Projection physically and semantically distinct.

The first SCIM Group slice now produces Integration-owned `ObservedEntitlement` and `ObservedGrant` state. The next slice needs a durable way to resolve a provider-native entitlement identifier to an existing canonical Catalog Entitlement and to surface unresolved observed access to Governance without allowing provider observation to redefine Catalog or Access authority.

The architecture did not previously state which capability owns the provider-native-to-canonical resolution record or where drift evaluation crosses from Integration observation into Governance finding state.

## Decision

### Provider observation mapping ownership

Integration owns provider-observation mapping records.

A provider entitlement mapping:

- is scoped to one tenant and one ConnectorBinding/ApplicationTarget;
- identifies one provider-native observed entitlement by stable provider identifier;
- references one canonical Catalog Entitlement by stable ID;
- is unique by provider-native stable identifier within its ConnectorBinding; the canonical Entitlement reference is not a reverse-unique identity key, so separately observed provider identifiers may explicitly resolve to the same canonical Entitlement when semantically appropriate;
- carries its own revision and provenance;
- is technical interpretation of provider observation, not Catalog authority.

Catalog does not store provider observation rows and Integration does not mutate Catalog tables.

Before a mapping is created or changed, Integration must validate through a semantic Catalog query that the target Entitlement:

- exists in the same tenant;
- is ACTIVE;
- is scoped to the same ApplicationTarget represented by the ConnectorBinding.

A mapping cannot make an unscoped or differently scoped Entitlement equivalent to a provider access unit.

### Catalog authority

Catalog alone creates, changes, retires and defines Entitlement meaning.

Provider discovery never creates a Catalog Entitlement automatically. An explicit future governed catalog-adoption flow may request Catalog to create an Entitlement, but that is a separate business operation and is not implied by observation or mapping.

Provider IDs remain mapping/provenance evidence, not canonical Entitlement identity.

### Governance findings

Governance owns `GovernanceFinding` authoritative/process state.

Integration exposes normalized observed-access facts and mapping resolution through a semantic query/contract. Drift evaluation reports conditions to Governance through a semantic command such as `ReportGovernanceObservation`; it never writes Governance persistence directly.

The first supported observation-driven finding types are:

- `UNMAPPED_PROVIDER_ENTITLEMENT` — a present provider entitlement has no canonical mapping;
- `UNMAPPED_PROVIDER_GRANT_ENTITLEMENT` — a present provider grant references an unmapped provider entitlement;
- `UNRESOLVED_PROVIDER_GRANT_PRINCIPAL` — a present provider grant has a mapped entitlement but its provider principal cannot yet be resolved to canonical Principal/Identity state.

Finding identity is deterministic for the tenant, finding type and normalized observation subject so repeated reconciliation/reporting is idempotent.

When the underlying condition disappears, Governance resolves the corresponding finding. If the same condition later recurs, the finding may reopen rather than creating uncontrolled duplicates.

Findings contain stable IDs and minimized normalized context only. Raw provider payloads, credentials, secret references and private credential material are prohibited.

### Access adoption and remediation

Mapping an observed entitlement is not access adoption.

Resolving or accepting a GovernanceFinding is not an AccessAssignment mutation by itself.

Only Access may create or revoke AccessAssignment authority. A future explicit governed adoption/remediation flow may cause Governance to issue an `AccessIntentCommand` to Access after required policy, approval, SoD/risk and current-state validation. This ADR does not define that later workflow.

Until canonical Principal runtime/correlation exists, observed grants must not be guessed against `DesiredGrantState` by provider strings. Unresolved principal correlation remains visible as a finding.

### Processing and transaction boundary

Provider observation materialization and Governance finding mutation occur in separate capability-owned transactions.

The first implementation commits a data-minimized internal `integration.observed-access-input-changed` outbox fact in the same Integration transaction that completes ENTITLEMENT/GRANT observation materialization or changes an entitlement-observation mapping. The fact carries the ConnectorBinding scope and source aggregate reference only; it carries no provider payload, credentials, secret reference, or Access authority. Governance leases that internal fact later, re-queries current normalized Integration state through the semantic observed-access query, and mutates findings in a Governance-owned transaction. The internal fact is not a public integration event.

At-least-once reporting is expected. Reporting and finding updates must be idempotent and revision-aware. Delayed or out-of-order trigger facts cause reevaluation of current Integration state rather than replay of historical provider state. A failure to create/update a finding does not rewrite valid Integration observation state.

Partial reconciliation may produce positive observations and corresponding findings, but partial coverage never implies destructive absence or resolution of unseen observation-derived findings. Resolution based on provider absence requires trustworthy COMPLETE coverage for the relevant object class.

## Consequences

- Provider-native identity can be explicitly mapped without making Integration a Catalog owner.
- Catalog remains the sole authority for governable Entitlement meaning.
- Observed access becomes actionable Governance state without silently authorizing it.
- AccessAssignment authority remains isolated in Access.
- Drift processing tolerates retry and temporary cross-capability failure.
- Principal correlation and fully resolved desired-vs-observed grant comparison remain separate future slices.
- A later governed adoption workflow can build on stable findings and mappings without changing these ownership boundaries.
