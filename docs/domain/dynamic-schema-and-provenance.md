# Dynamic Schema and Provenance Model

## Status

Current domain/data baseline for extensible identity, principal, source, target, and entitlement attributes.

## Core principle

Core IAM semantics remain strongly typed. Extensible attributes are governed typed data. Provider-native data remains observation until explicitly mapped into canonical semantics.

The model separates:

1. **Canonical IAM schema** — what IAM understands and governs.
2. **Logical source/target schema** — what a configured SourceSystem or ApplicationTarget exposes to IAM.
3. **Native provider schema** — what Workday, LDAP, Entra, GitHub, SAP, or another provider actually exposes.

## Attributes do not replace relationships

Manager, organization membership, ownership, role composition, and principal ownership are first-class relationships, not extension attributes.

A source field such as `managerEmployeeNumber` may be used by a relationship mapping to resolve a `ManagerRelationship`; it must not become an opaque `extensionAttributes["manager"]` substitute for the domain relationship.

## Attribute definitions

Canonical extension attributes are governed by stable `AttributeDefinition` semantics:

- stable attribute identifier and immutable canonical key
- display name and description
- subject/applicability
- data type and cardinality
- data classification
- query/search/policy addressability
- validation and normalization rules
- override policy
- status/version

The initial type system should stay intentionally small: string, boolean, integer, decimal, date, datetime, enum, formatted string, and single/multi cardinality. Arbitrary nested JSON is native/provider metadata rather than normal policy-addressable canonical identity state.

## Schema versioning

Schema, mapping, and authority configuration are independently versioned. Activated versions are immutable. Breaking type/semantic changes require a new version, validation, and impact analysis.

Do not create one universal `IamObject` schema for every capability. Identity schema, target-principal schema, entitlement governance metadata, and provider-native schema may share descriptor conventions while retaining distinct ownership and invariants.

## Mapping versus authority

Mapping answers: **what canonical fact does this source field represent?**

Authority answers: **which source/candidate is trusted when several sources provide that fact?**

These are separate governance concepts and must not be collapsed into arrival order or a generic mapping priority.

Last-write-wins is not a normal IAM authority strategy.

## Candidate and resolution model

The conceptual identity path is:

`SourceRecord -> MappingVersion -> CanonicalAttributeCandidate -> AuthorityRuleVersion -> CanonicalAttributeState -> IdentityDelta`

A candidate preserves normalized value and provenance such as source system, source record, source path, mapping version, source update time, and observation time.

Canonical resolution records the selected result/provenance and may distinguish `RESOLVED`, `OVERRIDDEN`, `CONFLICT`, `UNRESOLVED`, and `NO_VALUE` semantics.

Equal-authority conflicts are not silently overwritten. Governance-critical conflicts can retain the last trusted canonical value while marking resolution degraded and creating a finding.

## Overrides

`CanonicalOverride` is an explicit, governed, time-bounded override above source resolution. It never mutates SourceRecord. Once the override is no longer semantically valid, normal authority resolution applies immediately even if a scheduler has not materialized a state change yet.

## Explainability

The system must be able to answer why a canonical value exists, for example:

`Identity -> canonical costCenter -> AuthorityRule v8 -> Workday candidate -> Mapping v12 -> SourceRecord -> ImportRun`

The same provenance principle applies to first-class relationships such as manager, organization, and ownership.

## Policy and search

Policies address governed canonical attributes by stable attribute ID/key. Policy activation validates that referenced attributes exist, are active, type-compatible, and policy-addressable.

Search/filter/sort behavior is a projection/index concern. Only attributes explicitly marked queryable/searchable should receive corresponding indexes/projections; arbitrary provider/native JSON paths are not public policy/query contracts.

## Outbound provisioning

Provisioning mappings are separate from source-ingestion mappings. They transform selected canonical Identity/Principal/Target context into logical target-principal attributes before the provider adapter translates them into native API calls.

Attribute classification participates in outbound propagation so a target mapping cannot silently exfiltrate sensitive/highly restricted identity data.

## Impact and recomputation

Changing schema, mapping, or authority rules may affect large identity populations and downstream lifecycle/access policy. High-impact changes should support simulation and approval where appropriate.

Activation does not synchronously rewrite millions of identities. Configuration revision changes mark affected canonical resolution as stale; asynchronous recomputation emits Identity changes only where canonical meaning actually changed.

Raw source changes that do not change canonical semantics stop before lifecycle/access processing.