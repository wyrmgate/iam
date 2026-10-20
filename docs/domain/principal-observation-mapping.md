# Principal Observation Mapping

## Purpose

This document is the living implementation-facing contract for ADR-0050. It defines how Integration correlates a provider-observed account to an existing Identity-owned `Principal` without transferring Principal authority to Integration.

## Ownership

Three concepts remain distinct:

- `Principal` — Identity-owned authoritative technical representation/account of an Identity on one Catalog `ApplicationTarget`.
- `ObservedPrincipal` — Integration-owned provider observation describing an account/object reported by an external provider.
- `PrincipalObservationMapping` — Integration-owned technical interpretation/provenance that associates one provider-native stable account identifier in one `ConnectorBinding` with one existing `Principal` ID.

A mapping is not Principal authority, Access authority, Catalog authority or authentication authority.

## Conceptual key and state

Current resolution is scoped by:

```text
TenantId
+ ConnectorBindingId
+ providerPrincipalStableId
    -> PrincipalObservationMapping
    -> PrincipalId
```

A mapping has a stable mapping ID, positive revision and lifecycle:

```text
ACTIVE -> RETIRED
```

`RETIRED` is terminal. Remapping creates a new mapping identity; retired history is not rewritten.

Within one `ConnectorBinding`, an active `providerPrincipalStableId` maps to at most one `Principal`.

## Stable provider identity rule

The provider side of the mapping must use a provider-native stable object identifier suitable for technical correlation.

Mutable attributes such as username, login name, email address, display name or provider labels are not mapping identity and must not be used as fallback authority when an exact stable identifier is absent or ambiguous.

Provider-native stable identity remains Integration provenance. It never replaces the Wyrmgate `Principal` ID.

## Validation boundary

Creating a mapping requires a current Identity semantic validation that the target `Principal`:

- exists;
- belongs to the same Tenant as the mapping/binding;
- references the same Catalog `ApplicationTarget` represented by the `APPLICATION_TARGET` ConnectorBinding.

Integration validates through a semantic Identity query. It does not read or mutate Identity persistence directly.

If required validation is unavailable, mapping creation fails closed or retries; Integration does not infer the Principal from provider strings.

## Principal authority boundary

`PrincipalObservationMapping` never:

- creates a `Principal`;
- changes Principal ownership/correlation to Identity;
- changes Principal lifecycle;
- merges or splits Principal/Identity authority;
- substitutes one Principal for another during provisioning;
- authorizes business access.

Provider discovery or provider mutation success may produce observations/completion facts, but Identity remains the only capability that may create or change Principal authority.

If a previously valid mapping no longer agrees with current Principal Tenant/ApplicationTarget semantics, consumers fail closed for privilege increase and the mapping requires explicit repair/retirement/remapping. It is never silently rewritten to another Principal.

## Reconciliation use

Principal observation mapping is the Principal-side counterpart to ADR-0015 `EntitlementObservationMapping`.

A normalized present provider grant becomes fully correlatable only when both sides resolve safely:

```text
providerPrincipalStableId
  -> PrincipalObservationMapping
  -> PrincipalId

providerEntitlementStableId
  -> EntitlementObservationMapping
  -> EntitlementId
```

Only then may Integration compare observed access with current desired state using governed stable IDs.

Zero Principal mappings means unresolved provider principal. More than one active mapping for the same binding/provider stable ID is invalid and must fail closed.

Integration must never compare a provider grant to desired state by guessing a Principal from username/email/display strings.

## Governance finding interaction

ADR-0015 `UNRESOLVED_PROVIDER_GRANT_PRINCIPAL` remains the finding condition for a present provider grant whose entitlement is resolved but whose provider principal cannot be safely resolved.

Creating a valid mapping can cause current-state drift reevaluation to resolve that condition, but:

- mapping is not Access adoption;
- resolving a finding is not an `AccessAssignment` mutation;
- unmanaged observed access still requires an explicit governed adoption/remediation path through Governance and Access.

## Observation absence

`ObservedPrincipal`, `PrincipalObservationMapping` and Identity-owned `Principal` are separate state dimensions.

Even trustworthy `COMPLETE` Principal discovery does not by itself:

- delete/disable/retire the governed Principal;
- retire the mapping;
- reassign the mapping;
- remove business access authority.

COMPLETE coverage may support drift/finding evaluation for the exact provider scope. Any Principal lifecycle/correlation change remains an explicit Identity-owned semantic operation.

## Provisioning boundary

A Principal observation mapping can provide current technical correlation/provenance, but it does not automatically choose a provisioning route.

Privilege-increasing provisioning still requires one current unambiguous executable route with compatible ConnectorBinding, Principal, ApplicationTarget, entitlement mapping and current desired state. Multiple eligible bindings/routes remain a separate Integration routing problem and fail closed until resolved.

Privilege reduction may use all safely known current or previously successful realization targets as defined by the accepted Integration desired-state rules.

## Concurrency and idempotency

Mapping create/retire operations are causal-idempotent and revision-aware.

Create rules:

- same causal identity + same material input returns the same logical result;
- same causal identity + different material input is a conflict;
- an active mapping for the same `(tenant, binding, providerPrincipalStableId)` to another Principal is a semantic conflict and is never overwritten implicitly.

Retirement uses optimistic revision semantics and is idempotent. A later remap creates a new mapping record.

Consumers tolerate at-least-once, duplicate, replayed and out-of-order mapping/observation facts by re-reading current mapping and Principal state.

## Transaction boundary

Integration commits only its mapping state in its own transaction. Identity Principal mutation is never part of that transaction.

Mapping validation is a semantic cross-capability query. A race after validation is handled through current-state revalidation when the mapping is consumed. Stale/invalid mapping context fails closed for privilege increase and is surfaced for repair.

Governance finding updates and read-model changes occur in separate owning-capability transactions. Their failure does not roll back a valid Integration mapping change.

## Tenant, audit and secret boundary

Mappings are Tenant-scoped. Cross-Tenant mapping is invalid.

Ordinary mapping state/facts/audit may carry only the minimum required stable IDs, revision, normalized provenance and correlation/causation. Raw provider payloads, passwords, tokens, private keys, credential material, secret references and unrelated provider claims/attributes are excluded.

Audit records semantic map/retire outcomes through the existing Audit boundary; Audit failure never rewrites the Integration result.

## Public API and persistence status

ADR-0050 defines the architecture and domain semantics only. No public mapping API or persistence migration is implied by acceptance of the ADR.

A later implementation may add explicit Integration administration operations such as map/read/unmap with typed default-deny permissions, optimistic revisions and causal idempotency. It must not expose a generic provider-object mapping CRUD surface.

Physical persistence should preserve stable history and enforce active uniqueness for `(tenant, binding, providerPrincipalStableId)` where practical. No cross-capability cascade may delete mapping provenance because a foreign Principal changes lifecycle.

## Non-goals

This model does not replace or define:

- Identity `SourceRecord` / `IdentityLink` correlation;
- OIDC/SAML federated-subject mapping;
- provider credential mapping;
- automatic Principal creation/merge/reassignment;
- generic `ProviderObjectMapping` semantics;
- provisioning route selection;
- unmanaged-access adoption.
