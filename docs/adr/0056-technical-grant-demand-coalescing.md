# ADR-0056: Coalesced technical grant demand across Access desired causes

Status: Accepted

## Context

Access keeps business meaning explainable at the semantic-cause level. Distinct AccessAssignment
support and principal-constraint tuples can therefore produce distinct DesiredGrantState rows even
when they currently resolve to the same technical provider grant.

For example, an ANY assignment and a SPECIFIC assignment may both require the same Entitlement on
the same governed Principal. Removing one semantic cause must not remove the provider grant while
the other cause remains valid. Conversely, when the final cause disappears, cleanup must still be
possible even if a different desired-cause row originally created the provider grant.

Collapsing DesiredGrantState into one provider-level row would lose provenance and would conflate
business access semantics with technical execution. Making Integration provisioning task history
authoritative for access would violate the Access/Integration boundary.

## Decision

### DesiredGrantState remains cause-preserving

Access continues to materialize DesiredGrantState per semantic tuple:

```text
Identity + ApplicationTarget + Entitlement + principalConstraintKey
```

The row is a rebuildable Access projection, not provider execution state. An ABSENT row retains the
last resolved governed Principal ID when known so downstream cleanup can remain exact and
explainable.

### Technical demand is coalesced by governed technical tuple

Access exposes a narrow current desired-state query answering whether any PRESENT desired cause
requires:

```text
ApplicationTarget + Entitlement + Principal
```

The answer is `REQUIRED`, `NOT_REQUIRED`, or `UNAVAILABLE`.

Integration must consult this query before planning a provider REMOVE whose reason is disappearance
or re-resolution of a desired cause. `REQUIRED` suppresses that REMOVE. `UNAVAILABLE` fails
closed for removal planning and is retried; Integration does not guess.

The query is not a new authoritative grant aggregate. It is a current derived question over
Access-owned desired state.

### Successful ADD evidence is technical, not cause-exclusive

Integration may reuse successful ADD execution evidence from any DesiredGrantState cause for the
same governed technical tuple when planning final cleanup. Data-minimized task evidence carries the
governed Principal and Entitlement IDs alongside the provider-native identifiers required by the
adapter.

This reuse does not transfer access authority to Integration. A successful historical ADD proves
where a technical grant was realized; only current Access technical demand decides whether removal
is now allowed.

### Provider observation cleanup requires explicit mappings

Observed provider grants may supplement execution evidence only after both sides are safely
interpreted:

- provider Entitlement through current EntitlementObservationMapping;
- provider Principal through current PrincipalObservationMapping from ADR-0050.

Mutable usernames, email addresses, display names or other provider strings are never used to infer
which governed Principal an observed grant belongs to.

### Principal movement and route movement differ

When the same DesiredGrantState moves from one governed Principal to another, Integration checks
coalesced demand before removing the old Principal's technical grant because another semantic cause
may still require it.

When the governed Principal remains the same but the current connector/provider mapping changes,
the old technical route may be removed after the replacement ADD is planned according to the
ordinary forward-recovery dependency rules. Coalesced demand does not preserve obsolete technical
routes merely because semantic access remains required.

### Failure and replay

Desired-state and provisioning triggers remain at-least-once. Duplicate ADD or REMOVE planning is
made harmless by existing task idempotency and current desired-revision validation.

If Access desired-state lookup or governed Principal resolution is unavailable, provider removal is
not planned. Provider failure never rewrites AccessAssignment or DesiredGrantState authority.

## Consequences

- Overlapping ANY/SPECIFIC or other semantic causes cannot prematurely remove a shared provider
  grant.
- The final disappearing cause can clean a grant originally created by another desired cause.
- Access keeps cause-level explainability while Integration keeps execution evidence.
- No universal `AccessGrant`, provider-membership authority, or cross-capability database mutation
  is introduced.
- Observation-based cleanup now follows the accepted mapping boundary instead of provider-string
  matching.
