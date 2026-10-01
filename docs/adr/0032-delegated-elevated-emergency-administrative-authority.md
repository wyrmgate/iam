# ADR-0032: Delegated, elevated and emergency administrative authority

Status: Accepted

## Context

ADR-0005 separates IAM administrative authorization from governed business access and requires
default-deny, scoped, assurance-aware control-plane authorization. The v0.5 Security baseline adds
specific requirements that delegated authority never exceed the delegator's current effective
delegable authority (SEC-ADM-003), AdministrativeRole/AdministrativeGrant management prevent
self-escalation beyond grantable authority (SEC-ADM-004), administrative elevation deny
self-approval by default (SEC-ADM-005), and break-glass be temporary, strongly authenticated,
reasoned, audited, notified and post-reviewed (SEC-ADM-006).

The current runtime implements direct AdministrativeGrant evaluation, provider-neutral
control-plane authentication and governed actor binding, burn-once initial-administrator bootstrap,
Governance-owned reusable ApprovalCase, and Audit-owned append-only AuditRecord. It does not yet
define the durable authority graph, provenance, grantability ceiling, elevation application, or
break-glass process needed to implement the remaining Administration security requirements.

Issue #212 records this architecture tranche.

## Decision

### 1. Keep authority kinds distinct

Administration recognizes four materially different authority forms:

1. **Direct AdministrativeGrant** — standing Administration-owned authority assigned directly to a
   governed Identity.
2. **AdministrativeDelegation** — explicit authority from one governed delegator to one delegate,
   dependent on a current direct source AdministrativeGrant.
3. **AdministrativeElevation** — explicit temporary/JIT Administration process and resulting
   temporary authority, optionally gated by Governance ApprovalCase.
4. **AdministrativeBreakGlassOperation** — emergency temporary authority with stronger assurance,
   reason/incident evidence, notification work and mandatory post-use review.

These are not collapsed into a generic "grant type". Their provenance, revocation dependencies and
process invariants differ materially.

Business Role/AccessAssignment, OAuth/OIDC role/scope claims and ApprovalDecision are never
Administrative authority.

### 2. Represent grantability and delegability explicitly on direct grants

A direct AdministrativeGrant carries explicit authority-management rights in addition to its Role,
scope and validity:

- **grantable** — this grant may be used as the authority basis for creating another direct
  AdministrativeGrant or for applying a temporary AdministrativeElevation;
- **delegable** — this grant may be used as the authority basis for AdministrativeDelegation.

Neither right is implied merely by possessing `administration:manage-authorization`. That semantic
permission authorizes the management operation; a current grantability/delegability basis constrains
what authority the operation may create.

A newly created direct grant may carry `grantable=true` only when its chosen authority-basis grant
is itself grantable and contains the complete target authority. Likewise, `delegable=true` may be
propagated only from a delegable authority basis that contains the complete target authority.
Grantability/delegability therefore cannot be invented through role editing or grant creation.

The burn-once initial administrator remains an explicit root authority established by ADR-0011. The
bootstrap permission set is not expanded. When the implementation materializes these new flags, the
bootstrap-created direct grant may be explicitly marked grantable/delegable for only its existing
Role, GLOBAL scope and validity; this is not a wildcard permission and does not authorize authority
outside that grant.

### 3. Use one explicit authority basis for bounded grant creation

The first grant-management slice requires one current effective direct AdministrativeGrant as the
authority basis for each privilege-increasing direct-grant creation. The basis must:

- belong to the acting governed Identity in the same tenant;
- be administratively effective at operation time;
- be `grantable`;
- contain every permission in the target AdministrativeRole;
- contain the requested AdministrativeScope;
- contain the requested validity window; and
- contain any requested grantability/delegability rights.

The created direct AdministrativeGrant retains the stable authority-basis grant ID as creation
provenance, but after valid creation it is independent authority. Later revocation/expiry of the
basis does not automatically revoke an already-created direct grant. This differs intentionally from
delegation.

The first slice does not union multiple grants to synthesize a grantability basis. A request that
cannot be justified by one basis fails closed.

### 4. Define scope and temporal containment fail-closed

Authority containment is typed, not string/JSON comparison.

Initial containment semantics are:

- `GLOBAL` contains supported narrower scopes;
- `SPECIFIC_RESOURCE(resourceType, resourceId)` contains only the exact same resource;
- `CANONICAL_ATTRIBUTE_CLASSIFICATION(key)` contains only the exact same classification key;
- other canonical scope kinds remain unavailable for grant/delegation containment until their owning
  capability supplies the concrete hierarchy/population semantics required to prove containment.

No implicit Organization descendant, Application/Target, population or provider hierarchy is
invented by Administration.

For every dependent authority:

- target `validFrom`, when present, must not precede the basis `validFrom`;
- target `validUntil` must not exceed a finite basis `validUntil`;
- an unbounded target `validUntil` is allowed only from an unbounded basis;
- delegation/elevation/break-glass require a finite `validUntil`.

Time validity is semantic. Authority stops being effective at `validUntil` even if no scheduler has
materialized a lifecycle state change.

### 5. Make delegation single-hop and provenance-preserving in the first slice

AdministrativeDelegation records, at minimum:

- delegate Identity ID;
- delegator Identity ID;
- direct source AdministrativeGrant ID;
- delegated AdministrativeRole ID;
- typed AdministrativeScope;
- finite validity window;
- lifecycle/revision;
- creation and revocation actor/time evidence;
- correlation ID and causation ID where present.

The first slice is **single-hop only**:

- the source must be a direct AdministrativeGrant held by the delegator;
- delegated authority cannot be the source for another delegation;
- a delegation uses the same AdministrativeRole as its source grant and may only narrow scope/time;
- the source grant must be `delegable`.

At operation time a delegation authorizes only when the actual delegate is currently
administratively eligible and the delegation itself is active/time-effective **and** the source
direct grant is still current, effective, delegable, and still contains the delegated authority.
Revocation, expiry or narrowing of the source therefore invalidates dependent delegation
semantically without waiting for cleanup.

Audit identifies the delegate as the actual acting Identity. The delegator is provenance, not a
substitute actor.

### 6. Prevent indirect self-escalation through role mutation

AdministrativeRole permission membership is authority-bearing because existing grants/delegations
reference the current Role. Therefore a role edit is evaluated against its **prospective effective
authority**, not merely against the edited Role row.

Adding a permission to a Role is allowed only when the actor has a current grantable basis that
contains the resulting permission set, scope, validity and propagated grantability/delegability
rights for **every current direct grant and dependent delegation whose authority would increase**.
If any affected authority cannot be justified, the whole privilege-increasing edit fails closed.

Removing a permission is an authority reduction and does not require a grantability-ceiling
evaluation. It still requires normal operation-specific authorization and concurrency checks.
Unrelated Governance/evaluator failure must not block an otherwise authorized authority reduction.

Role metadata edits that do not alter authority remain ordinary revision-guarded mutations.

### 7. Model temporary/JIT elevation as an explicit Administration process

AdministrativeElevation is not an ordinary AdministrativeGrant creation shortcut. It records at
minimum:

- requesting/beneficiary Identity;
- initiator Identity;
- requested AdministrativeRole, scope and finite validity;
- grantable direct AdministrativeGrant authority basis;
- process state/revision;
- optional Governance ApprovalCase reference and immutable approval-context fingerprint;
- application/denial/cancellation timestamps and correlation/causation.

When policy requires approval, Administration requests a typed Governance ApprovalCase.
ApprovalCase/ApprovalDecision are approval evidence only. Governance never writes Administration
tables and approval does not itself create authority.

Self-approval by the elevation beneficiary/initiator is denied by default according to ADR-0016 and
SEC-ADM-005.

Immediately before temporary authority becomes effective, Administration revalidates:

- current tenant and governed Identity eligibility;
- current authority-basis effectiveness and grantability;
- Role permission membership;
- scope/temporal containment;
- approval result and the approval-context fingerprint, when approval was required; and
- any required current assurance/policy context.

Stale approval or stale authority context cannot create current authority. If mandatory
revalidation is unavailable for a privilege increase, application fails closed/remains pending.

Temporary elevation authority is evaluated directly from the AdministrativeElevation record and its
validity; it is not copied into a long-lived direct AdministrativeGrant.

### 8. Model break-glass separately from ordinary elevation

AdministrativeBreakGlassOperation is a separate Administration-owned emergency process/authority
source. A generic GLOBAL grant is never treated as break-glass.

Creation/activation requires:

- exact governed actor/beneficiary;
- requested AdministrativeRole and typed scope;
- a short finite validity within configured security-policy bounds;
- explicit reason;
- explicit incident/reference identifier;
- a provider-neutral authentication-assurance context satisfying a required strong level;
- creation/activation time;
- correlation/causation;
- durable notification work/evidence; and
- a durable post-use review obligation.

The provider-neutral control-plane authentication context is extended with a typed assurance result.
The canonical contract uses semantic levels such as `BASELINE` and `STRONG`, plus relevant
authentication/step-up time where needed. Adapters may derive that result from provider-specific
signals, but OIDC `acr`/`amr` claim names or raw provider claims are not canonical IAM semantics.
Bearer possession alone never satisfies `STRONG`.

Break-glass authority is effective only while its Administration authority state permits use,
current time is inside the validity window, the actor remains administratively eligible, and the
required strong assurance context is present for the operation. Expiry/revocation is semantic and
does not wait for notification/review workers.

Notification delivery and post-use review are durable work/process state, not hidden synchronous side
effects. Delivery failure does not erase a valid recorded emergency activation; it remains visible
for retry/remediation. Ending authority does not erase the mandatory review obligation.

### 9. Preserve capability ownership and evidence boundaries

Administration owns AdministrativeRole, direct AdministrativeGrant, AdministrativeDelegation,
AdministrativeElevation and AdministrativeBreakGlassOperation authoritative/process state.

Governance owns ApprovalCase/ApprovalDecision. Approval is consumed through typed semantic
contracts; Governance does not create or mutate Administration authority.

Audit owns AuditRecord. Every public security-significant Administration mutation records a
data-minimized SUCCESS, DENIED or FAILURE AuditRecord only after the authoritative transaction
commits or rolls back. Audit persistence failure never rewrites the Administration/Governance
business result.

AuditRecord actor ID is always the actual governed actor. Detailed delegation/elevation/break-glass
reason, incident, permission set and authority provenance remain in the owning Administration
evidence/process records and are linked through stable semantic resource references and
correlation/causation rather than copied into AuditRecord as arbitrary payload.

Platform may own timers, leases and retry delivery. It never decides whether authority is valid.

### 10. Public interfaces remain semantic and default-deny

Later public Administration APIs use explicit semantic resources/operations, optimistic revision,
causal idempotency for retryable mutations, signed context-bound cursor pagination, tenant
isolation, and operation-time authorization. They do not expose persistence CRUD or arbitrary
status mutation.

Powerful Administration-management permissions are not silently added to
`INITIAL_TENANT_ADMIN`.

## Consequences

- SEC-ADM-003 and SEC-ADM-004 gain a concrete bounded authority-ceiling model instead of relying on
  `manage-authorization` as a superuser permission.
- Delegation provenance remains bounded and revalidatable; no unbounded transitive delegation graph
  exists in the first implementation.
- Direct grants remain independent after valid creation, while delegated authority remains
  intentionally dependent on its source.
- Role permission edits cannot become an indirect privilege-escalation bypass.
- Governance approval remains reusable without becoming Administration authority.
- JIT and break-glass expiry remains correct even under scheduler delay.
- Strong authentication becomes a provider-neutral typed security input rather than an OIDC-specific
  domain contract.
- Break-glass reason/incident/notification/review evidence remains explicit without turning
  AuditRecord into an arbitrary payload store.

## Deferred

- multi-hop delegation;
- unioning multiple grantability bases to create one target authority;
- hierarchy/population containment for currently fail-closed scope kinds;
- dynamic elevation policy/rule language;
- notification transport choice;
- post-use-review workflow details beyond the durable obligation;
- arbitrary platform-operator/customer-support cross-tenant access;
- provider-specific authentication-assurance mapping policy;
- formal v0.6 specification/RTM checkpoint, which should occur after the Administration security
  tranche stabilizes.
