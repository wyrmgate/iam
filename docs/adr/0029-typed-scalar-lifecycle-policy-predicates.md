# ADR-0029: Typed scalar equality for lifecycle-access policy predicates

Status: Accepted

## Context

ADR-0025 introduced Access-owned lifecycle/birthright policy with a deliberately bounded predicate
model: ALWAYS or exact equality on one policy-addressable canonical STRING/SINGLE attribute. The
formal v0.4 Mover flow requires authoritative canonical changes to cause lifecycle/policy
reevaluation and access delta, while leaving detailed predicate language to architecture.

Identity canonical schema already governs STRING, BOOLEAN, INTEGER, DECIMAL, DATE, DATETIME and
ENUM with SINGLE/MULTI cardinality and explicit policy-addressability. Expanding lifecycle policy
must reuse that governed type system rather than introduce arbitrary JSON, string coercion or a
generic expression engine.

## Decision

### Add bounded typed scalar equality

Lifecycle-access policy supports exact equality predicates for policy-addressable SINGLE canonical
attributes of:

- STRING;
- BOOLEAN;
- INTEGER;
- ENUM.

ALWAYS remains unchanged.

Each rule carries an explicit predicate kind whose expected value has the corresponding typed
shape. Values are persisted in typed columns/constraints. No string coercion is used between
different canonical types.

### Identity remains the semantic source of policy inputs

Access does not read canonical persistence.

IdentityLifecycleAccessQuery is extended to expose trusted typed scalar values for requested
canonical keys. A value is available only when the active canonical schema defines the key with the
expected supported type, SINGLE cardinality and policyAddressable=true, and current canonical state
is RESOLVED or OVERRIDDEN with exactly one value of the declared type.

CONFLICT, UNRESOLVED, NO_VALUE, missing definitions, inactive schema, cardinality mismatch and
runtime value-shape mismatch are unavailable and cannot positively match.

### Activation validates predicate/schema compatibility

Before activating a lifecycle policy version, Access asks Identity whether each typed predicate is
supported by the current governed canonical schema. Type, SINGLE cardinality and
policy-addressability must match the rule predicate.

An incompatible rule is rejected at activation rather than relying on runtime coercion.

### Reconciliation semantics are unchanged

Identity canonical-change facts remain triggers only. Access re-reads current Identity lifecycle
and typed canonical context, applies reductions before increases, and sends every new privilege
through the mandatory Governance guard.

Exact equality uses canonical typed value equality. Replay/out-of-order events converge on current
state. Stable rule provenance continues to own only its own AccessAssignment.

## Consequences

- common Mover/birthright policies can match governed booleans, integer classifications and enums
  without encoding them as strings;
- policy semantics remain deterministic, typed and framework-neutral;
- Identity retains canonical schema/value authority;
- Access retains lifecycle policy and AccessAssignment authority;
- no generic expression language or EAV policy model is introduced.

## Deferred

- DECIMAL equality/ordering and precision semantics;
- DATE/DATETIME equality/range semantics;
- MULTI membership/set semantics;
- compound AND/OR/NOT expressions;
- manager/organization/ownership relationship predicates;
- generic rules/expression engine;
- tenant-wide retroactive policy sweep.
