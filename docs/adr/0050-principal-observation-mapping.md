# ADR-0050: Principal observation mapping and provider-account correlation boundary

Status: Accepted

## Context

ADR-0002 makes `Principal` the Identity-owned governed technical representation/account of an Identity on a Catalog-owned `ApplicationTarget`. ADR-0004 makes provider reconciliation Integration-owned observation and keeps desired technical state, execution process state and observed provider state separate. ADR-0015 adds Integration-owned provider-entitlement observation mapping, but deliberately leaves canonical Principal correlation as a later slice.

That gap now matters for principal and grant reconciliation. A provider may report a stable account/object identifier through `ObservedPrincipal`, and `ObservedGrant` may refer to that provider account, while Wyrmgate already has an Identity-owned `Principal` representing the governed account on the same `ApplicationTarget`. Matching these by mutable provider strings such as username, login, email or display name is unsafe, while allowing Integration to create or reassign `Principal` authority would violate the capability boundary.

Wyrmgate therefore needs an explicit durable technical interpretation that says which observed provider account corresponds to which existing governed `Principal`, while preserving Identity as the sole owner of Principal identity, ownership/correlation and lifecycle.

## Decision

### PrincipalObservationMapping ownership and meaning

Integration owns `PrincipalObservationMapping` as authoritative technical interpretation/provenance for provider observation.

A mapping:

- is scoped to exactly one tenant and one active `APPLICATION_TARGET` `ConnectorBinding`;
- identifies one provider account by a provider-native **stable object identifier**, not by mutable username/login/email/display attributes;
- references one existing Identity-owned `Principal` by stable ID;
- is unique by provider-native stable identifier within its `ConnectorBinding` while active;
- carries its own stable mapping ID, positive revision, creation/provenance metadata and lifecycle;
- is technical correlation of provider observation, not Identity authority, Access authority or Catalog authority.

The minimal lifecycle is `ACTIVE` -> `RETIRED`. `RETIRED` is terminal. Changing the provider account or governed Principal target is modeled as retiring the old mapping and creating a new mapping so historical interpretation remains explainable.

A mapping does not make the provider identifier the canonical Principal identity. The Wyrmgate `Principal` ID remains the governed stable reference.

### Identity validation and ownership boundary

Before creating a mapping, Integration must validate through an Identity-owned semantic query that the referenced `Principal`:

- exists in the same tenant;
- references the same Catalog `ApplicationTarget` represented by the `ConnectorBinding`;
- is the exact Principal the caller intends to correlate, without Integration guessing from provider strings or broad attribute similarity.

Integration never inserts, updates, reassigns, merges, splits, activates, disables or otherwise mutates `Principal` authority through this mapping.

Provider discovery alone never creates a `Principal`. If a future automated mapping path is introduced, it may create a mapping only when an already-established exact Principal ID is supplied by an accepted semantic workflow and all validation above succeeds. Provider success or provider observation by itself is insufficient authority to create or reassign Principal state.

Principal reassignment remains an explicit Identity-owned correction path. A mapping that no longer agrees with current Principal tenant/target semantics is not silently rewritten; it becomes unusable for current resolution and requires explicit retirement/remapping or other owning-capability correction.

### Resolution of observed principals and grants

For normalized reconciliation, Integration may resolve:

```text
(tenant, ConnectorBinding, providerPrincipalStableId)
    -> PrincipalObservationMapping
    -> PrincipalId
```

A present `ObservedGrant` may be compared with current desired state only after both sides of the provider relationship are safely resolved:

- provider entitlement -> accepted `EntitlementObservationMapping` -> Catalog Entitlement;
- provider principal -> accepted `PrincipalObservationMapping` -> Identity Principal.

Zero mappings leave the provider principal unresolved. Multiple active mappings for the same provider stable identifier are structurally invalid and must fail closed if encountered. Integration must not fall back to mutable provider usernames, email addresses, display names or other heuristic strings to force a match.

`PrincipalObservationMapping` does not itself select a provisioning route when more than one connector/binding could realize the same governed target. Provisioning route resolution remains a separate current-state Integration decision and must still be unambiguous for privilege increase.

### Mapping cardinality and duplicate provider accounts

Within one `ConnectorBinding`, one active provider stable identifier maps to at most one governed `Principal`.

This ADR does not impose reverse uniqueness from `Principal` to `ConnectorBinding` across the entire tenant. A Principal may be observed through more than one explicitly configured binding when the architecture intentionally permits multiple discovery/execution paths to the same target. Such mappings do not make route selection implicit and do not collapse distinct provider objects into one Principal automatically.

If two distinct provider accounts appear to represent the same Identity or Principal unexpectedly, reconciliation preserves both observations. Wyrmgate does not auto-merge them. Any correction of governed Principal ownership/identity is handled by Identity semantics; any technical mapping change is explicit and history-preserving.

### Observation absence and lifecycle separation

`ObservedPrincipal` remains Integration observation. `PrincipalObservationMapping` remains Integration technical interpretation. `Principal` remains Identity authoritative state.

Absence of an observed provider account, even under trustworthy `COMPLETE` Principal discovery, does not delete, retire, disable or reassign the Identity-owned `Principal` and does not automatically retire its mapping. COMPLETE coverage may support normalized drift/finding evaluation for the exact provider scope, but an owning-capability lifecycle change still requires an explicit accepted semantic path.

Likewise, retiring a mapping does not mutate the provider account and does not mutate the governed Principal. It only removes that mapping from current provider-to-Principal resolution while retaining historical provenance.

### Governance drift boundary

ADR-0015's `UNRESOLVED_PROVIDER_GRANT_PRINCIPAL` condition remains valid. When a present provider grant has a safely mapped entitlement but no usable current Principal observation mapping, the grant remains unresolved and may continue to surface through the existing Governance-finding path.

Creating a Principal observation mapping may remove the unresolved-correlation condition on a later current-state reevaluation, but mapping creation is not access adoption and finding resolution is not an `AccessAssignment` mutation.

Any future adoption/remediation of unmanaged provider access must still cross Governance -> Access through explicit governed semantics. Integration never turns observation or mapping into business authorization.

### Concurrency, idempotency and replay

Mapping mutations are semantic Integration operations with optimistic revision semantics and causal idempotency.

For create:

- replay of the same causal request with the same binding, provider stable identifier and Principal target returns the same logical result;
- reuse of the same causal idempotency identity with different material input is a conflict;
- an active mapping for the same binding + provider stable identifier to a different Principal is a semantic conflict and must not be overwritten silently.

Retirement is revision-guarded and idempotent. A later remap creates a new mapping identity rather than rewriting retired history.

Observation and mapping change triggers are at-least-once. Consumers re-query current Integration/Identity state and tolerate duplicate, replayed and out-of-order facts. Event arrival order never overrides current mapping revision or current Principal authority.

### Transaction and failure boundary

Integration mapping mutation and Identity Principal mutation never share one distributed transaction.

Integration validates the current Principal through a semantic Identity query before committing its own mapping mutation. A race that changes Principal ownership/target semantics after validation is handled through current-state revalidation when the mapping is consumed; stale/invalid mapping context fails closed for privilege increase and is surfaced for repair rather than mutating foreign state.

Failure to update a Governance finding or downstream projection does not roll back a valid mapping mutation. Failure of an Identity query required to validate a new mapping fails closed; Integration does not guess.

### Tenant and secret/data-minimization boundary

A mapping is tenant-scoped. Cross-tenant provider-to-Principal mapping is invalid.

Mapping state and related facts may contain only the stable Integration mapping/binding identifiers, provider-native stable object identifier as required for technical correlation, governed Principal ID, revision, normalized provenance and correlation/causation metadata.

Raw provider payloads, passwords, tokens, credential values, private keys, secret references and unrelated provider claims/attributes are prohibited from ordinary mapping events, audit evidence and errors.

### Interface and persistence boundary

This ADR defines domain semantics, ownership and invariants. It does not require a new public API in the same change.

A later implementation may extend the existing Integration administration surface with explicit map/read/unmap operations and typed default-deny Administration permissions, mirroring the semantic style of EntitlementObservationMapping. It must not expose generic mapping CRUD or arbitrary provider-object mapping.

Physical schema/index details are implementation contracts. Persistence should enforce active uniqueness for `(tenant, binding, providerPrincipalStableId)` where practical and preserve retired mapping history. No cross-capability foreign-key cascade may allow Identity lifecycle/deletion mechanics to remove Integration mapping provenance automatically.

### Explicit non-goals

This ADR does not define:

- Identity `SourceRecord` / `IdentityLink` source correlation; that remains Identity-owned;
- federation login subject mapping for OIDC/SAML; that remains a separate Authentication/Integration boundary;
- provider credential mapping or `ObservedCredential` adoption;
- automatic creation, merge or reassignment of Principal authority from provider discovery;
- a generic `ProviderObjectMapping` abstraction spanning principals, entitlements, credentials and arbitrary provider objects;
- provisioning route selection when multiple ConnectorBindings are available;
- automatic Access adoption from observed grants.

## Consequences

- Provider account correlation becomes explicit, durable and replay-safe without moving Principal authority into Integration.
- Reconciliation can resolve both sides of an observed grant using typed provider-observation mappings rather than mutable provider strings.
- ADR-0015's unresolved-provider-principal finding can converge through a defined technical mapping path.
- Principal creation/reassignment remains exclusively Identity-owned and provider observation never silently becomes governed authority.
- COMPLETE provider discovery can improve drift knowledge without becoming permission to delete/disable Principal authority or mapping provenance.
- Mapping history remains explainable across remap/retirement and does not depend on provider usernames remaining stable.
- Public API, concrete persistence migration and Console UX may be implemented later without changing the ownership decision in this ADR.
