# ADR-0039: Exact DECIMAL, DATE and DATETIME lifecycle-access predicates

Status: Accepted

## Context

ADR-0029 deliberately bounded lifecycle-access predicates to exact equality over policy-addressable
SINGLE STRING, BOOLEAN, INTEGER and ENUM canonical attributes. The canonical Identity type system
already also governs DECIMAL, DATE and DATETIME values, and those values are persisted in typed
columns rather than provider JSON.

The next OD-003 policy slice should extend exact typed equality without introducing range ordering,
calendar-relative interpretation, multi-valued logic or a generic expression engine.

## Decision

### Add three exact predicate kinds

Lifecycle-access policy additionally supports:

- CANONICAL_DECIMAL_EQUALS;
- CANONICAL_DATE_EQUALS;
- CANONICAL_DATETIME_EQUALS.

The predicates remain one-key, one-value exact equality. ALWAYS and the ADR-0029 predicate kinds are
unchanged.

### DECIMAL equality is normalized numeric equality

Canonical DECIMAL values use BigDecimal normalized by stripping insignificant trailing zeroes.
Lifecycle policy applies the same normalization to an expected decimal and compares values by
numeric equality rather than scale-sensitive object equality. Therefore 12.0 and 12.00 are the same
policy value.

The persisted policy column is numeric(38,12), matching the existing canonical numeric storage
shape. Activation/model construction rejects an expected value whose integer digits exceed 26 or
whose normalized fractional scale exceeds 12. Wyrmgate never rounds a lifecycle predicate into
persistence.

No decimal ordering, threshold or range operator is introduced by this ADR.

### DATE equality is an exact civil date

DATE predicates compare one canonical LocalDate to one expected LocalDate. There is no timezone,
current-time or relative-calendar interpretation.

No before/after/range operator is introduced.

### DATETIME equality is one exact absolute instant at persisted precision

Canonical DATETIME values are absolute instants and are persisted as PostgreSQL timestamptz.
Lifecycle policy DATETIME expectations must already be aligned to microsecond precision, which is
the persistence precision used by the current PostgreSQL contract. Values with finer precision are
rejected rather than silently truncated or rounded.

Equality compares the exact Instant. No tenant/user timezone conversion, local-time interpretation,
window, tolerance or ordering operator is introduced.

### Identity remains the semantic policy-input owner

IdentityLifecycleAccessQuery exposes DECIMAL, DATE and DATETIME scalar values only when:

- the active schema contains the canonical key;
- the definition type exactly matches the requested scalar type;
- cardinality is SINGLE;
- policyAddressable is true;
- current canonical state is RESOLVED or OVERRIDDEN; and
- exactly one typed value is present.

CONFLICT, UNRESOLVED, NO_VALUE, missing/inactive definitions, cardinality mismatch and runtime
value-shape mismatch remain unavailable and cannot positively match.

Access never reads Identity canonical persistence directly.

### Activation and reconciliation remain fail-closed

Policy activation validates each typed predicate against the current governed canonical schema.
Incompatible rules are rejected before activation.

Canonical-change events remain triggers only. Access re-reads current lifecycle and canonical
context, applies reductions before privilege increases and routes every new privilege through the
existing mandatory Governance guard. Replay and out-of-order delivery converge on current state.

## Consequences

- governed monetary/classification decimals, exact dates and absolute instants can drive Joiner/Mover
  birthright access without string coercion;
- equality remains deterministic and typed across the Access/Identity boundary;
- persistence cannot silently round DECIMAL or DATETIME policy values;
- no generic rules engine, arbitrary JSON expression or cross-capability persistence shortcut is
  introduced.

## Deferred

- DECIMAL ordering/ranges;
- DATE/DATETIME ordering/ranges, relative-time and calendar-window semantics;
- MULTI membership/set semantics;
- compound AND/OR/NOT expressions;
- manager/organization/ownership relationship predicates;
- generic rules/expression engine;
- tenant-wide retroactive policy sweep.
