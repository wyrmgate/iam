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

- identity read/update/suspend/merge/split/correlation;
- application/target/catalog governance;
- Role/Entitlement draft and activation actions;
- policy creation/version activation;
- access administration and request-for-others;
- review campaign administration;
- governance exception request/approval;
- connector/source configuration, testing and reconciliation execution;
- credential rotate/revoke and secret-reference management;
- audit read/export;
- administrative-authorization management.

Raw secret retrieval is not implied by platform administration and is normally unavailable through IAM because secret material is external by default.

## Scope

Initial scope types include:

- GLOBAL;
- ORGANIZATION;
- APPLICATION;
- APPLICATION_TARGET;
- SOURCE_SYSTEM;
- CONNECTOR_INSTANCE;
- IDENTITY_POPULATION;
- SPECIFIC_RESOURCE.

Hierarchy traversal is explicit. An Organization scope does not automatically include descendants unless the grant says so.

## Ownership

Application, Role, Entitlement, Organization, SourceSystem and Identity ownership may be authorization context, but ownership never implies universal administration.

For example, an Application owner may be permitted to manage requestability, application-role packages and application reviews while still being prohibited from modifying connector secret references, global SoD policy, identity-source authority, audit retention or platform-admin grants.

## Delegation

Administrative delegation is separate from approval delegation. Delegation is explicit, scoped and temporal. A delegate cannot receive more authority than the delegator has and may only use delegated authority while the delegator still possesses it.

Audit must identify the actual actor and the acting-for/delegation context; it must never pretend the delegator performed the action.

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

## Temporary elevation and break-glass

Powerful administration should support time-bound/JIT grants. Break-glass elevation is separate from emergency access to a governed business application and requires short validity, explicit reason/incident reference, strong authentication, immutable audit/evidence, notification and post-use review.

## API/service actors

Human, service and workload actors use the same administrative semantics after authentication/actor resolution. OAuth/API scopes may provide coarse API permission but do not replace resource-scoped IAM administrative authorization.

## Tenant/isolation boundary

When tenancy is enabled, tenant isolation is checked before normal administrative authorization. Ordinary roles/scopes cannot cross the tenant boundary. Platform-operator/customer-support access is a separate privileged support mechanism and must be explicit, temporary and auditable.

## Effective authorization and caching

Authorization is evaluated at operation time. Cached effective-admin projections may accelerate evaluation/UI navigation but must be invalidated promptly on grant revocation, owner change, identity suspension, delegation expiry, policy change or temporary-elevation expiry.

## Security invariants

- default deny;
- no ownership-derived superuser;
- no self-escalation by editing/granting admin roles;
- self-approval of administrative elevation denied by default;
- session lifetime does not extend expired authority;
- connector/source/credential/audit powers are more tightly separated than normal application governance;
- sensitive denials and high-impact allowed operations produce security audit evidence.
