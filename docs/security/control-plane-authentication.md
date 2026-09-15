# Control-plane Authentication and Initial Administrator Bootstrap

## Purpose

This document defines the implementation-facing authentication boundary that precedes Wyrmgate Administrative Authorization. It implements ADR-0011 and remains subordinate to the formal Security/SRS requirements and accepted ADRs.

Authentication answers **who presented a valid external credential**. Administration answers **what that governed actor may do to IAM state**. They are deliberately separate decisions.

## Bearer authentication boundary

The first runtime target is an OAuth/OIDC-compatible JWT resource server configured with:

- one trusted issuer URI;
- one required Wyrmgate API audience;
- issuer-discovered signing keys and normal JWT validity checks.

A valid token contributes only the exact external authentication subject `(issuer, subject)`. Provider roles, groups, scopes, tenant claims, or similar custom claims are not converted into Wyrmgate `AdministrativePermission`.

When `iam.auth.enabled=false` (the default), protected `/api/v1/**` routes stay closed. Health, system information, and API-documentation endpoints may remain readable as explicitly configured.

## Governed actor resolution

Administration owns `ControlPlaneActorBinding`:

```text
validated issuer + subject
        |
        v
ControlPlaneActorBinding
        |
        +--> TenantContext
        +--> governed Identity ID
```

The binding is server-side authoritative security configuration. The caller does not select its tenant or governed Identity through headers or writable token claims.

An actor binding grants no IAM permission by itself. The resolved actor still passes the normal Administration operation-time checks, including current governed Identity eligibility, semantic permission, resource, scope, temporal grant validity, and tenant isolation.

The initial implementation maps an external issuer+subject to one ordinary tenant-scoped governed actor. Future customer-support/platform-operator access is a separate privileged mechanism and must not weaken this uniqueness rule.

## Initial administrator bootstrap

A fresh tenant intentionally has no administrative grant and therefore cannot authorize a normal administrative API call. Its first grant is established through an explicit operator-only one-shot server command, not an HTTP endpoint and not a permanent bootstrap account.

The selected administrator must already exist as an `ACTIVE` governed Identity in the target tenant.

The bootstrap transaction creates:

- an immutable tenant-unique `InitialAdminBootstrap` marker;
- the external authentication subject binding;
- explicit semantic permissions needed by the first implemented surfaces;
- one `tenant-initial-administrator` AdministrativeRole;
- one `GLOBAL` AdministrativeGrant for that role and Identity;
- one minimized internal `administration.initial-admin-bootstrapped` outbox fact.

The initial role contains only:

- `administration:manage-authorization`;
- `identity:read`;
- `identity:create`;
- `identity:update`.

`GLOBAL` broadens the resource scope of those permissions only. It is not a wildcard permission or hidden superuser semantic.

## Burn-once invariant

Bootstrap is permanently one-time per tenant.

The tenant-unique bootstrap marker is durable even if the original grant is later revoked. The system must never infer bootstrap eligibility from the current absence of an active administrator. Losing all administrative authority after bootstrap therefore requires a future governed recovery mechanism.

The marker is inserted inside the same database transaction as the binding, role, grant and internal fact. Deferred same-capability constraints permit the marker to act as the concurrency guard while referenced rows are created later in that same transaction. If any part fails, the transaction rolls back and the marker is not burned.

Bootstrap also refuses to start when any AdministrativeGrant already exists for the tenant or when the requested external subject is already bound elsewhere.

## Operator command

Bootstrap is disabled by default. Run the server once with the explicit command-line flag and required values, for example:

```bash
java -jar wyrmgate-iam-server.jar \
  --iam.bootstrap.initial-admin.enabled=true \
  --iam.bootstrap.initial-admin.tenant-id=<tenant-uuid> \
  --iam.bootstrap.initial-admin.identity-id=<identity-uuid> \
  --iam.bootstrap.initial-admin.issuer=https://issuer.example \
  --iam.bootstrap.initial-admin.subject=<external-subject>
```

Equivalent environment variables exist for deployment tooling, but the enable flag must not be left enabled for normal runtime. The application exits after the one-shot bootstrap invocation completes.

A successful second invocation for the same tenant fails closed. An operator must remove the enable flag after successful provisioning; it is not a recurring startup task.

## Runtime configuration

The first bearer-authentication configuration is:

```text
IAM_AUTH_ENABLED=true
IAM_AUTH_ISSUER_URI=https://issuer.example
IAM_AUTH_AUDIENCE=wyrmgate-api
```

Issuer discovery is lazy so migration/bootstrap startup does not require contacting the identity provider unless a bearer token is actually decoded.

## Sensitive-data rules

Wyrmgate does not persist bearer tokens, refresh tokens, signing private keys, provider client secrets, or token role/scope claims as part of actor binding or bootstrap.

The binding persists the exact issuer and external subject because they are the authoritative mapping key. Those values are excluded from the bootstrap outbox fact and ordinary error responses.

Authentication failures return stable semantic errors and correlation IDs rather than JWT/library/SQL exception details.

## Current completion boundary

This slice establishes trusted bearer validation, server-side tenant/governed-actor resolution, and burn-once initial-administrator provisioning. It does **not** yet expose the Identity OpenAPI operations as runtime controllers.

The next Identity HTTP slice must combine `ControlPlaneActorRequestContext` with `AdministrativeAuthorizationService` for every semantic operation. Bearer possession alone is never sufficient.

Authentication assurance (`acr`/`amr`), step-up, administrative delegation, maker-checker elevation, break-glass, and post-bootstrap recovery remain future governed security slices rather than implicit token behavior.
