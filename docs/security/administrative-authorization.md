# Administrative Authorization and Scoped Administration

## Purpose

This document defines authorization to operate IAM itself. It is intentionally separate from governed business/application access and from runtime application authorization.

## Separation of planes

```text
GOVERNED ACCESS PLANE
Identity → AccessAssignment → EffectiveAccess → Principal → Target

IAM CONTROL PLANE
Actor → Administrative Authorization → IAM Command → IAM State

RUNTIME AUTHORIZATION PLANE
Principal / Client → Runtime Authn/Authz → Runtime Resource
```

An IAM business/application Role never grants permission to administer the IAM platform. An `AdministrativeRole` is not an IAM `Role`, and an `AdministrativeGrant` is not an `AccessAssignment`.

## Authorization model

Authorization evaluates:

- authenticated actor / governed Identity;
- administrative permission (`resourceType + action`);
- requested IAM resource;
- strongly typed resource scope;
- ownership/relationship context;
- contextual security policy;
- authentication assurance when required;
- tenant/isolation boundary when enabled.

Default decision is DENY.

## Administrative roles and permissions

AdministrativeRole is a manageable bundle of stable control-plane permissions. Example permission families include:

- identity read/create/update/activate/suspend/deactivate/decommission/merge/split/correlation;
- Principal read/register/correlate;
- application/target/catalog governance;
- Role/Entitlement draft and activation actions;
- policy creation/version activation;
- access administration and request-for-others;
- review campaign administration;
- governance exception request/approval;
- connector/source configuration, testing and reconciliation execution;
- credential read/create/revoke/compromise/rotate and credential-rotation read; secret-reference management remains metadata-only and never implies raw secret retrieval;
- audit read/export;
- administrative-authorization management.

The implemented Identity lifecycle surface separates `identity:activate`, `identity:suspend`, `identity:deactivate`, and `identity:decommission` from generic `identity:update`. Possession of metadata-update authority therefore does not authorize lifecycle changes. These lifecycle permissions are default-deny and are not silently added to the initial tenant administrator permission set.

The implemented Principal administration surface likewise separates `principal:read`, `principal:register`, and `principal:correlate` from `identity:update` and from connector/provider administration. Registration authority cannot assign an Identity or mutate provider lifecycle; correlation authority performs only the explicit one-way Identity-owned relationship change. These Principal permissions are default-deny and are not silently added to the initial tenant administrator permission set.

Raw secret retrieval is not implied by platform administration and is unavailable through the Credential public API because secret material is external by default. The implemented Credential surface uses separate semantic permissions `credential:read`, `credential:create`, `credential:revoke`, `credential:compromise`, `credential:rotate`, and `credential-rotation:read`; none is silently added to the initial tenant administrator permission set.

The implemented Audit read/search surface uses the separate default-deny `audit:read` permission. A GLOBAL grant may authorize bounded collection search; an exact SPECIFIC_RESOURCE grant for resource type `audit` authorizes only that AuditRecord and never a collection search. Audit read authority is not silently added to the initial tenant administrator permission set, and this slice does not expose `audit:export`.

## Scope

Initial scope types include:

- GLOBAL;
- ORGANIZATION;
- APPLICATION;
- APPLICATION_TARGET;
- SOURCE_SYSTEM;
- CONNECTOR_INSTANCE;
- IDENTITY_POPULATION;
- SPECIFIC_RESOURCE;
- CANONICAL_ATTRIBUTE_CLASSIFICATION — exact governed canonical classification key for the dedicated `canonical-attribute-value:read` permission.

Hierarchy traversal is explicit. An Organization scope does not automatically include descendants unless the grant says so.

## Ownership

Application, Role, Entitlement, Organization, SourceSystem and Identity ownership may be authorization context, but ownership never implies universal administration.

For example, an Application owner may be permitted to manage requestability, application-role packages and application reviews while still being prohibited from modifying connector secret references, global SoD policy, identity-source authority, audit retention or platform-admin grants.

## Delegation

ADR-0032 makes administrative delegation a distinct Administration-owned authority object rather than a generic AdministrativeGrant type or approval delegation. The first implementation is deliberately single-hop: one delegate, one delegator, and one current direct source AdministrativeGrant. Re-delegation is not allowed.

The source direct grant must explicitly be delegable. A delegation keeps the same AdministrativeRole as its source and may only narrow typed scope and time. It records the actual delegate, delegator, source grant, role, scope, finite validity, revision, creation/revocation evidence, and correlation/causation. At operation time Administration revalidates both the delegation and its source; source revocation, expiry, loss of delegability, role narrowing, or other loss of the delegated authority immediately makes the delegation ineffective without waiting for cleanup.

Scope containment is fail-closed. GLOBAL can contain a supported narrower scope; SPECIFIC_RESOURCE and CANONICAL_ATTRIBUTE_CLASSIFICATION use exact containment. Other modeled scope kinds cannot be granted/delegated until concrete owning-capability hierarchy/population semantics can prove containment.

Audit identifies the delegate as the actual actor. Delegator/source details remain Administration provenance linked through semantic resource references and correlation/causation; Audit must never pretend the delegator performed the action.

## High-impact actions

Sensitive actions may require stronger authentication and/or maker-checker approval even when the initiator has the base permission. Examples include:

- granting platform/security administration;
- changing connector secret references or critical connector configuration;
- identity merge/split or forced correlation;
- activation of high-risk Role/Policy versions;
- authoritative-source/correlation-rule changes;
- audit/security control changes;
- sensitive audit exports;
- break-glass use.

Authorization and approval are separate questions: authorization determines whether the actor may initiate/perform an operation; governance policy determines whether the operation requires additional approval before execution.

## Grantability and self-escalation prevention

The semantic permission `administration:manage-authorization` authorizes a management operation but is not a superuser/mint-authority permission. ADR-0032 adds explicit grantable/delegable rights to direct AdministrativeGrant authority.

A privilege-increasing direct grant must name one current effective grantable direct grant held by the acting Identity as its authority basis. That one basis must contain the target role's complete permission set, requested scope, validity, and any propagated grantable/delegable rights. The first slice does not union multiple bases to synthesize authority.

AdministrativeRole permission edits are evaluated prospectively. Adding a permission must be justified for every current direct grant/delegation whose effective authority would increase; otherwise the whole edit fails closed. Permission removal is authority reduction and does not require the privilege-increase ceiling check, although normal operation authorization and optimistic revision still apply.

The burn-once bootstrap permission set is not expanded. Its root grant may be explicitly materialized as grantable/delegable only for its existing role/scope/validity; no wildcard authority is introduced.

## Implemented direct role/grant management foundation

The first ADR-0032 implementation slice persists direct-grant `grantable` / `delegable` rights and creation-time `authorityBasisGrantId` provenance. Administration-owned role/grant commands now require current `administration:manage-authorization` plus one current effective grantable basis for privilege-increasing direct-grant creation. Target permissions, supported typed scope, validity and propagated grant/delegation rights must all fit inside that basis.

AdministrativeRole permission additions perform a bounded prospective check across current non-expired direct grants referencing the role. The actor's current grantable authority must justify every affected grant under the resulting permission set; the edit otherwise fails closed. Removing a permission and revoking a grant remain authority reductions and intentionally skip that privilege-increase ceiling while retaining normal authorization and optimistic revision checks.

The synchronous prospective scan is deliberately bounded in this first slice. If the configured implementation bound is exceeded, the privilege-increasing edit fails closed rather than silently skipping affected authority. Public Administration HTTP/OpenAPI/idempotency/signed-cursor contracts remain deferred to the later public-API phase.

## Implemented single-hop delegation foundation

The ADR-0032 single-hop delegation runtime now persists `AdministrativeDelegation` separately from direct grants. Creation requires one current effective `delegable` direct grant held by the delegator, an ACTIVE same-tenant delegate Identity, the exact source Role, a supported typed scope no wider than the source, and a finite validity window contained by the source. Re-delegation is structurally absent from the command model.

Operation-time authorization evaluates the delegate as the actual actor and revalidates both the delegation and its direct source grant. Revoked/expired/non-delegable source authority, source-role mismatch, scope/time escape, delegation expiry/revocation, or delegate ineligibility fails closed immediately; no scheduler transition is needed for authority to end. Privilege-increasing Role edits include current source-backed delegations in the bounded prospective grantability check.

Delegation creation and revocation retain Administration-owned provenance, including delegator/delegate/source Role and grant, creation/revocation actor/time, and optional correlation/causation identifiers. Public Administration transport, idempotency and AuditRecord producer wiring remain intentionally deferred to the public Administration API phase.

## Temporary elevation and break-glass

ADR-0032 models temporary/JIT elevation as a distinct Administration-owned process/authority source, not as an ordinary long-lived AdministrativeGrant shortcut. An elevation records beneficiary/initiator, requested role/scope/finite validity, one current grantable authority basis, revision, correlation/causation and, where required, a Governance ApprovalCase reference plus immutable approval-context fingerprint. Governance approval is evidence only; it never mutates Administration persistence.

The temporary-elevation foundation is now implemented through Flyway V49. Administration persists the requested context separately from direct grants, binds optional Governance ApprovalCase evidence through a typed semantic boundary, and activates only after a final current-state revalidation. The immutable request fingerprint binds beneficiary and initiator, the direct basis grant revision, the basis Role revision and permission set, the target Role revision and permission set, typed scope, and validity window. Any material basis/Role change between request and activation therefore makes the approved context stale and fails closed.

Self-approval by the elevation beneficiary **or** initiator is denied by the typed elevation approval adapter before an ApprovalCase can be used. The approver resolver is intentionally constrained and provider-neutral; no default privileged group is invented. If no explicit `AdministrativeElevationApproverResolver` is configured, the approval path fails closed rather than minting authority. Immediately before activation, Administration revalidates current beneficiary/initiator eligibility, basis ownership/effectiveness/grantability, basis permission containment, role permissions, typed scope/time containment, bound approval case/fingerprint, and remaining validity. Governance approval never creates authority by itself.

Only an `ACTIVE` AdministrativeElevation contributes temporary authority, and operation-time authorization still evaluates the governed beneficiary and semantic validity window. `REQUESTED`, `PENDING_APPROVAL`, `DENIED`, `CANCELLED`, `REVOKED`, not-yet-valid and expired elevations never authorize. Expiry is semantic from time; no scheduler delay can extend authority. Public Administration transport/idempotency/AuditRecord producer wiring remains deferred to the public Administration API slice.

Break-glass is a separate typed Administration emergency process and a generic GLOBAL role/grant is never treated as break-glass. Activation requires exact governed actor, requested role/scope, short finite validity, explicit reason, incident/reference, strong provider-neutral authentication assurance, immutable Administration evidence, AuditRecord evidence, durable notification work/evidence and a durable post-use review obligation.

The control-plane security context uses typed provider-neutral assurance such as BASELINE/STRONG plus relevant authentication/step-up time. Provider adapters may map their own signals into this contract, but bearer possession and raw OIDC claim names are not canonical assurance semantics. Notification/review delivery is durable process work and does not define authority validity; break-glass expiry/revocation takes effect immediately from Administration state/time.

## Implemented assurance-aware break-glass foundation

Flyway V50 implements `AdministrativeBreakGlassOperation` as a distinct Administration-owned emergency authority source. The first bounded slice is self-use only: the exact governed actor is the beneficiary, initiation still requires existing `administration:manage-authorization`, and emergency role/scope authority is permitted only by an explicitly configured fail-closed `AdministrativeBreakGlassPolicy`. No direct AdministrativeGrant or ordinary elevation is synthesized.

Activation requires a short finite validity window, non-blank reason and incident/reference, current governed actor eligibility, and provider-neutral `STRONG` assurance with a policy-bounded step-up age. The default control-plane assurance resolver yields `BASELINE`; bearer possession alone therefore cannot activate or use break-glass. A deployment adapter may map already validated provider context into the canonical assurance contract, but raw provider claims and OIDC claim names remain outside Administration semantics.

Activation atomically creates durable `SECURITY_NOTIFICATION` and `POST_USE_REVIEW` obligations. Their delivery/completion state never defines or extends authority. Operation-time authorization rechecks semantic validity, current actor eligibility and current strong-assurance recency; downgrade, expiry or explicit revocation ends emergency authority immediately without waiting for workers.

Break-glass activation/revocation attempts append data-minimized SUCCESS/DENIED/FAILURE AuditRecord evidence outside the authoritative transaction. Audit evidence contains the actual governed actor, semantic action, stable emergency-operation reference when known, outcome and correlation/causation only; reason, incident text, permission sets and raw authentication context remain in Administration-owned evidence. Audit outage cannot rewrite a committed Administration result.

## API/service actors

Human, service and workload actors use the same administrative semantics after authentication/actor resolution. OAuth/API scopes may provide coarse API permission but do not replace resource-scoped IAM administrative authorization.

## Tenant/isolation boundary

When tenancy is enabled, tenant isolation is checked before normal administrative authorization. Ordinary roles/scopes cannot cross the tenant boundary. Platform-operator/customer-support access is a separate privileged support mechanism and must be explicit, temporary and auditable.

## Effective authorization and caching

Authorization is evaluated at operation time. Cached effective-admin projections may accelerate evaluation/UI navigation but must be invalidated promptly on grant revocation, owner change, identity suspension, delegation expiry, policy change or temporary-elevation expiry.

## First implementation slice

The first persisted Administration authorization slice implements the direct-grant evaluation foundation needed before public IAM control-plane resources can be exposed. It deliberately does not create a bootstrap superuser or turn transport authentication claims into authoritative IAM permissions.

The current implementation persists tenant-scoped semantic `AdministrativePermission`, `AdministrativeRole`, role-permission membership and `AdministrativeGrant`. Grant actor references are stable governed Identity IDs; Administration does not mutate or directly own Identity state. Identity implements the narrow `GovernedActorStatusQuery` consumed by Administration.

Evaluation is operation-time and default-deny. An actor must resolve to an `ACTIVE` Identity in the same tenant; missing, foreign-tenant, `PENDING`, `SUSPENDED`, `INACTIVE` or `DECOMMISSIONED` actors are ineligible. Grant state and `validFrom`/`validUntil` are evaluated directly from time, so a delayed scheduler cannot extend authority.

All canonical scope types are structurally modeled. The first evaluator intentionally grants authority only for:

- `GLOBAL`, which applies to a matching semantic permission in the tenant; and
- `SPECIFIC_RESOURCE`, which requires an exact semantic resource type and stable resource ID match.

`CANONICAL_ATTRIBUTE_CLASSIFICATION` is now implemented only for exact classification-key matching with `canonical-attribute-value:read`; it has no hierarchy, wildcard or sensitivity ordering semantics. `ORGANIZATION`, `APPLICATION`, `APPLICATION_TARGET`, `SOURCE_SYSTEM`, `CONNECTOR_INSTANCE` and `IDENTITY_POPULATION` remain fail-closed until their concrete hierarchy/population semantics and owning-capability queries are implemented. A resource-specific grant never authorizes a collection query.

A fresh installation contains no administrative roles or grants and therefore denies protected control-plane operations. Initial-administrator bootstrap/provisioning, transport authentication/actor resolution, governed direct role/grant management, single-hop administrative delegation, and the temporary/JIT elevation foundation are implemented. Assurance-aware break-glass and the public Administration control-plane surface remain subsequent implementation slices governed by ADR-0032. Public control-plane mutations continue to use operation-time authorization and ADR-0031 AuditRecord evidence.

## Security invariants

- default deny;
- no ownership-derived superuser;
- no self-escalation by editing/granting admin roles;
- self-approval of administrative elevation denied by default;
- session lifetime does not extend expired authority;
- connector/source/credential/audit powers are more tightly separated than normal application governance;
- sensitive denials and high-impact allowed operations produce security audit evidence.
