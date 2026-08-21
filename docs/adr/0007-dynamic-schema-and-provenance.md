# ADR-0007: Dynamic schema, mapping, authority and provenance

Status: Accepted

## Context

IAM must support customer/provider-specific identity and entitlement data without collapsing into pure EAV or arbitrary JSON semantics.

## Decision

Core IAM semantic fields remain strongly typed. Extensible canonical attributes use stable typed AttributeDefinitions and immutable activated schema versions. Provider-native/source fields remain observations until explicitly mapped.

Mapping and authority are separate concepts: mapping identifies canonical meaning; authority determines which source/candidate is trusted. CanonicalAttributeCandidate preserves normalized source and mapping provenance. CanonicalAttributeState records the resolved value, resolution outcome and authority/version references.

Last-write-wins is not the default authority model. Conflicts may retain the last trusted canonical value while producing degraded/conflict state and governance findings. Governed time-bound overrides may supersede source resolution without modifying observations.

Manager, organization membership, ownership and other domain relationships remain first-class relationships rather than dynamic attributes. Policies may address only governed, compatible canonical attributes, never arbitrary native JSON paths.

## Consequences

- Extensibility does not redefine core domain semantics.
- Attribute provenance is explainable and auditable.
- Schema/mapping/authority activation may require impact simulation and asynchronous recomputation.
- Search/indexing is a projection concern rather than a reason to weaken authoritative modeling.
