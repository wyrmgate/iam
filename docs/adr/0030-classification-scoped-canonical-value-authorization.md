# ADR-0030: Classification-scoped canonical attribute value authorization

Status: Accepted

## Context

The v0.5 Security baseline requires sensitive canonical attributes to carry governed classification
that controls visibility and outbound propagation. The Identity v1 API already exposes canonical
attribute metadata and models typed values, but the runtime deliberately redacts every value because
`identity:read` alone must not imply authority to inspect all classified canonical data.

AttributeDefinitionVersion already carries a non-blank tenant-governed classification key. The
Administration capability currently supports GLOBAL and exact UUID resource scope evaluation, but
has no typed scope for that classification key.

A visibility decision must not invent a fixed PUBLIC/CONFIDENTIAL hierarchy, reinterpret
classification as an Identity-owned permission, or expose source/provider observations as canonical
truth.

## Decision

### Value disclosure uses a separate semantic permission

Administration defines the semantic permission:

- `canonical-attribute-value:read`.

`identity:read` remains necessary for the Identity canonical-attribute collection and authorizes
the metadata row only. A canonical value is disclosed only when the actor also has an effective
`canonical-attribute-value:read` grant that is either:

- GLOBAL; or
- scoped to the exact classification key of that active AttributeDefinitionVersion.

The new permission is default-deny and is not part of INITIAL_TENANT_ADMIN.

### Classification scope is exact and opaque

Administration adds the strongly typed scope kind
`CANONICAL_ATTRIBUTE_CLASSIFICATION`.

That scope carries exactly one non-blank classification key. Classification keys are opaque,
tenant-local governed strings. No ordering, inheritance, wildcard, sensitivity ranking or implicit
PUBLIC/CONFIDENTIAL taxonomy is introduced.

A classification-scoped grant matches by exact string equality only. It does not authorize another
classification and it does not authorize an Identity resource by itself.

Other existing unsupported scope kinds remain fail-closed.

### Metadata and value authorization stay separate

The Identity canonical-attribute endpoint first requires `identity:read` for the requested Identity.
Failure still rejects the operation.

For every returned canonical attribute row:

- metadata remains visible after the Identity read check;
- if value-read authorization matches its classification, the response may expose the current
  effective typed canonical values;
- otherwise `visibility=REDACTED` and the values field is omitted.

A denied value-read grant never removes the metadata row and never converts a read into a mutating
resolution operation.

### Only current effective canonical values are disclosable

Identity remains the owner of canonical resolution. The API may disclose only the effective values
returned by the existing current-state resolution evaluator for the active definition version.

RESOLVED and OVERRIDDEN values are therefore readable when authorized. CONFLICT or UNRESOLVED may
still expose a compatible retained trusted value only when the existing evaluator says that value
is effective; the degraded resolution status remains explicit. NO_VALUE exposes no value.

All values retain their canonical typed shape: STRING, BOOLEAN, INTEGER, DECIMAL, DATE, DATETIME or
ENUM, including bounded MULTI collections. There is no string coercion or raw JSON substitute.

Raw candidate rows, source payloads, provider observations, mappings and authority internals are
never exposed by this permission.

### Provenance remains redacted

This slice does not authorize detailed canonical provenance. The existing provenance field remains
omitted/redacted even when value-read authority is present. A future provenance-read contract may
introduce a separate permission and data-minimization review.

### Tenant and temporal semantics are unchanged

Administrative grants remain tenant-scoped, actor eligibility is re-evaluated at operation time,
and ACTIVE/revoked plus validFrom/validUntil semantics apply directly. Expired, revoked, foreign-
tenant or mismatched-classification grants do not disclose values.

## Consequences

- canonical value visibility can now be granted without broadening Identity administration;
- customers retain their own classification vocabulary without Wyrmgate inventing a universal
  sensitivity hierarchy;
- metadata remains useful under least privilege while values fail closed;
- Identity canonical authority and Administration authorization ownership remain separate;
- the existing closed Identity v1 typed-value schema can be used without exposing observation or
  persistence internals.

## Deferred

- classification hierarchy, wildcard or ordered sensitivity semantics;
- provenance-read authorization;
- relationship/ownership-derived value visibility;
- outbound event/connector propagation policy by classification;
- attribute-level access grants finer than exact classification;
- general-purpose arbitrary string scopes.
