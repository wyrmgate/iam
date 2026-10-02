# ADR-0040: Typed MULTI membership for lifecycle-access policy

Status: Accepted

## Context

ADR-0029 introduced exact typed equality for governed policy-addressable SINGLE canonical
attributes. ADR-0039 completed exact equality for DECIMAL, DATE and DATETIME while explicitly
deferring MULTI semantics.

Identity canonical attributes already support governed MULTI cardinality for the same seven scalar
types and persist each current value as a typed normalized row. Access should be able to express
common birthright/Mover membership rules such as "skills contains JAVA" without turning a MULTI
attribute into JSON, a delimited string, or a generic expression tree.

The canonical model does not define set equality, subset semantics, duplicate significance or
collection ordering. This tranche therefore needs one bounded operation whose meaning is already
well-defined by the typed current values.

## Decision

### Add typed CONTAINS membership predicates

Lifecycle-access policy additionally supports:

- CANONICAL_STRING_CONTAINS;
- CANONICAL_BOOLEAN_CONTAINS;
- CANONICAL_INTEGER_CONTAINS;
- CANONICAL_DECIMAL_CONTAINS;
- CANONICAL_DATE_CONTAINS;
- CANONICAL_DATETIME_CONTAINS;
- CANONICAL_ENUM_CONTAINS.

Each predicate references one governed policy-addressable MULTI canonical attribute and one typed
expected scalar value. The predicate matches when any current trusted element equals that expected
value.

SINGLE equality predicates remain unchanged. A CONTAINS predicate cannot activate against a SINGLE
definition, and an equality predicate cannot activate against a MULTI definition.

### Identity exposes MULTI policy input separately from scalar input

IdentityLifecycleAccessQuery exposes trusted MULTI values through a distinct semantic shape rather
than overloading CanonicalScalar.

A MULTI value is available only when:

- the active schema contains the canonical key;
- the definition data type exactly matches the requested type;
- cardinality is MULTI;
- policyAddressable is true;
- current canonical state is RESOLVED or OVERRIDDEN; and
- one or more current typed values are present and all match the governed definition type.

CONFLICT, UNRESOLVED, NO_VALUE, missing/inactive definitions, cardinality mismatch and runtime
value-shape mismatch are unavailable and cannot positively match.

Access never reads canonical value tables directly.

### Membership uses existing exact typed value semantics

Membership ignores collection order, ordinal position and duplicate count. One equal element is
sufficient.

Type equality remains strict with no coercion:

- STRING uses exact string equality;
- BOOLEAN uses exact boolean equality;
- INTEGER uses exact integer equality;
- DECIMAL uses ADR-0039 normalized numeric equality;
- DATE uses exact LocalDate equality;
- DATETIME uses ADR-0039 exact absolute Instant equality at microsecond persistence precision;
- ENUM uses exact governed enum key equality.

The existing typed expected-value columns are reused. Flyway V59 expands only the closed predicate
kind/shape constraints; it does not introduce JSON collection predicates.

### Reconciliation and Governance semantics remain unchanged

Canonical-change facts are triggers only. Access re-reads current Identity lifecycle and current
typed SINGLE/MULTI context, computes desired policy support, applies reductions before increases and
routes every new privilege through the mandatory Governance guard.

Removing the matching element removes only the policy-owned assignment supported by that rule.
Manual and request-item assignments remain independent. Replay/out-of-order delivery converges on
current state.

## Consequences

- common governed group/skill/tag/classification membership can drive birthright/Mover access;
- policy cardinality is explicit and validated at activation;
- collection order and duplicates do not become policy semantics;
- existing typed columns and rule provenance remain reusable;
- no generic collection expression language or EAV policy model is introduced.

## Deferred

- exact set equality;
- subset/superset/contains-all/contains-any over multiple expected values;
- count/ordinal/order predicates;
- compound AND/OR/NOT expressions;
- manager/organization/ownership relationship predicates;
- generic rules/expression engine;
- tenant-wide retroactive policy sweep.
