# ADR-0054: Target-scoped Catalog Entitlements and RoleVersion lifecycle tightening

Status: Accepted

Date: 2026-11-12

## Context

Catalog currently models `Entitlement` as the smallest governable technical access unit but allows `applicationTargetId` to be absent. That optionality conflicts with the implemented security semantics:

- Access cannot safely realize a targetless Entitlement because Principal selection and provider routing require an exact `ApplicationTarget`;
- RoleVersion validation already rejects targetless Entitlements;
- ADR-0015 Entitlement observation mapping requires the mapped Catalog Entitlement to belong to the exact ApplicationTarget represented by the ConnectorBinding; and
- ADR-0052 availability treats a targetless Entitlement as unavailable rather than assignable.

The result is a Catalog object that may be created successfully but can never become valid new access authority.

The current Entitlement implementation also carries `nativeKey` and a free-form `entitlementType` string. Provider-native stable identifiers already belong to Integration-owned `EntitlementObservationMapping` and provider observation. A free-form provider taxonomy inside Catalog has no accepted provider-neutral business semantics and risks becoming accidental authority.

RoleVersion persistence also permits `VALIDATING`, `REJECTED` and `CANCELLED` even though the accepted implemented workflow has no semantic operations that enter those states. Validation is synchronous and revision-guarded: a failed validation leaves the version in DRAFT.

## Decision

### Every governable Entitlement is target-scoped

A tenant-owned Catalog `Entitlement` belongs to exactly one `Application` and exactly one `ApplicationTarget` from that same Application.

`applicationTargetId` is required and immutable after creation.

Catalog creation fails closed when the referenced Application or ApplicationTarget is missing, retired, belongs to another Tenant, or belongs to another Application.

There is no null-target Entitlement meaning in the current canonical model. If a future requirement needs provider-independent or logical access units, it requires an explicit typed concept rather than overloading a null technical target.

### Provider-native identity remains Integration-owned

Catalog Entitlement authority contains no provider-native correlation identifier.

The canonical Entitlement identity is its stable Wyrmgate ID plus tenant-scoped governed Catalog code. Provider-native group/permission/object identifiers remain Integration observation/mapping provenance, including `EntitlementObservationMapping.providerStableId`.

Catalog does not retain a duplicate `nativeKey` field.

### Do not preserve an ungoverned entitlement taxonomy

The current free-form `entitlementType` string is removed from authoritative Catalog state and the public Catalog contract because no accepted provider-neutral semantics depend on it.

A future entitlement classification may be introduced only as a typed governed vocabulary with explicit behavior/requirements. Provider-native object classes remain Integration observation until explicitly mapped.

### RoleVersion lifecycle matches implemented semantics

The canonical RoleVersion lifecycle for the current runtime is:

```text
DRAFT -> READY -> ACTIVE -> SUPERSEDED
```

- validation is the explicit DRAFT -> READY operation;
- failed validation leaves the RoleVersion in DRAFT;
- activation is READY -> ACTIVE and atomically supersedes the prior ACTIVE version;
- ACTIVE and SUPERSEDED content is immutable;
- SUPERSEDED is terminal and is not reactivated.

`VALIDATING`, `REJECTED` and `CANCELLED` are removed from the current authoritative vocabulary. A future asynchronous validation or explicit draft-abandonment workflow may add typed process/state semantics through a later accepted contract.

### Availability and retirement semantics are preserved

ADR-0052 remains unchanged in ownership and fail-safe behavior.

Because every Entitlement is target-scoped, an active Entitlement is `ASSIGNABLE` only while the Entitlement, owning Application and exact ApplicationTarget are all active. Retirement of any of those material Catalog resources makes the Entitlement terminally unavailable.

Catalog never mutates `AccessAssignment`. Access immediately suppresses terminally unavailable targets semantically and owns durable `AccessTargetReduction`.

## Persistence and migration

V16 is a focused semantic delta after the ADR-0048 baseline and V15 Catalog availability work.

Before tightening constraints, V16 refuses to proceed if retained data contains:

- any Entitlement with null `application_target_id`; or
- any RoleVersion in `VALIDATING`, `REJECTED` or `CANCELLED`.

Those cases require an explicit operator/data decision rather than implicit reinterpretation.

V16 then:

- makes `catalog.entitlement.application_target_id` NOT NULL;
- removes `catalog.entitlement.native_key`;
- removes `catalog.entitlement.entitlement_type`; and
- narrows the RoleVersion state constraint to DRAFT, READY, ACTIVE and SUPERSEDED.

This is a pre-release contract tightening. The removed Entitlement fields are not governance authority and are not migrated into another capability automatically.

## Public/API consequences

The Catalog Entitlement create contract now requires exactly:

- `applicationTargetId`;
- `code`.

Entitlement responses expose the stable Entitlement ID, Application ID, ApplicationTarget ID, code, lifecycle, revision and timestamps. Provider-native identifiers and untyped provider taxonomies are not returned as Catalog authority.

Bounded Entitlement search uses governed Catalog code and target presentation rather than provider-native keys.

## Consequences

- Every newly created Entitlement is usable within the same target semantics required by Access, Role composition and Integration mapping.
- The Catalog model no longer contains a permanently unassignable null-target Entitlement shape.
- Provider-native correlation has one clear owner in Integration instead of duplicate Catalog/Integration identifiers.
- Future entitlement classification cannot silently become an uncontrolled string taxonomy.
- RoleVersion state vocabulary matches the actual command/state machine.
- Existing tenant isolation, Role composition, SSO registration, platform offering/binding and ADR-0052 retirement boundaries remain unchanged.
- Formal v0.8 remains the controlled checkpoint; this accepted amendment is folded into the next meaningful formal revision.

## Rejected alternatives

### Keep applicationTargetId optional and treat null as application-wide access

Rejected. No accepted technical realization, Principal selection or provider-routing semantics exist for such authority, and current runtime already treats it as unavailable.

### Keep nativeKey as convenient Catalog metadata

Rejected. Provider-native stable identity is already explicitly owned by Integration observation/mapping. Keeping a second provider identifier invites competing correlation authority.

### Replace entitlementType with a guessed enum

Rejected. GROUP/PERMISSION/LICENSE or provider object-class vocabularies have not been accepted as universal Wyrmgate business semantics. A typed classification should be added only when requirements define its behavior.

### Keep unused RoleVersion states for possible future workflows

Rejected for the current canonical model. State names without semantic operations create false process meaning. Future workflows may add explicit states/process objects when requirements justify them.
