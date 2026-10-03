# ADR-0041: Typed MULTI expected-set lifecycle-access predicates

Status: Accepted

## Context

ADR-0040 introduced one-value typed CONTAINS membership over governed policy-addressable MULTI
canonical attributes. It intentionally deferred multi-value expected-set semantics because the
canonical model does not make collection ordering, duplicate count or arbitrary set algebra part of
Identity authority.

Common birthright/Mover policy still needs bounded rules such as "region contains any of APAC/EMEA"
or "skills contain all of JAVA/SQL". This can be added without a generic expression engine if the
expected collection is itself closed, typed, bounded and relational.

## Decision

### Add CONTAINS_ANY and CONTAINS_ALL

Lifecycle-access policy additionally supports typed CONTAINS_ANY and CONTAINS_ALL variants for
STRING, BOOLEAN, INTEGER, DECIMAL, DATE, DATETIME and ENUM MULTI canonical attributes.

Each such rule carries 2 through 20 distinct expected values of exactly one type.

- CONTAINS_ANY matches when at least one expected value is present in the current trusted MULTI
  canonical value.
- CONTAINS_ALL matches when every expected value is present in the current trusted MULTI canonical
  value.

A one-value membership condition continues to use ADR-0040 CONTAINS. Set predicates reject fewer
than two values so the policy model has one canonical expression for one-value membership.

### Expected-set semantics are mathematical, not ordinal

Expected-set order is non-semantic. Current MULTI value order and value_ordinal are also
non-semantic for policy evaluation. Duplicate expected values are rejected; duplicate current
values, if present in canonical state, do not increase membership strength.

Exact value comparison reuses ADR-0039/0040:

- STRING exact equality;
- BOOLEAN exact equality;
- INTEGER exact equality;
- DECIMAL normalized numeric equality;
- DATE exact civil-date equality;
- DATETIME exact absolute microsecond-precision Instant equality;
- ENUM exact governed key equality.

No cross-type coercion is permitted.

### Persist expected members relationally

Access persists set members as typed child rows of one immutable lifecycle policy rule. Each child
row carries one typed value and a bounded ordinal used only for deterministic persistence/readback.

No JSON array, delimited string, provider-native payload or arbitrary expression AST stores core
policy semantics.

The application enforces:

- 2-20 expected members;
- homogeneous type matching the predicate kind;
- normalized DECIMAL/DATETIME precision rules;
- no duplicate expected values.

Database constraints enforce typed row shape, bounded ordinal and parent rule ownership.

### Identity and Governance boundaries are unchanged

Set predicates activate only against current active policy-addressable MULTI definitions of the
matching type through IdentityLifecycleAccessQuery.

CONFLICT, UNRESOLVED, NO_VALUE, schema/type/cardinality mismatch and runtime shape mismatch remain
unavailable and cannot positively match.

Canonical-change facts remain triggers only. Access re-reads current state, applies policy-owned
reductions before increases and routes every new privilege increase through the mandatory Governance
guard. Approval evidence never creates AccessAssignment authority.

## Consequences

- common multi-value birthright policy can express bounded OR-like membership and full required
  membership without introducing compound rule expressions;
- expected values remain auditable relational typed data;
- existing stable rule provenance and reconciliation semantics are unchanged;
- set-member ordering and duplicate counts do not become business semantics.

## Deferred

- exact set equality;
- subset/superset predicates beyond CONTAINS_ALL;
- count/cardinality/ordinal/order predicates;
- compound AND/OR/NOT across independent predicates;
- manager/organization/ownership relationship predicates;
- generic rules/expression engine;
- tenant-wide retroactive policy sweep.
