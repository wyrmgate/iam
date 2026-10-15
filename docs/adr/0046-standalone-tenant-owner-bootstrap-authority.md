# ADR-0046 Standalone tenant-owner bootstrap authority

- Status: Accepted
- Date: 2026-10-15
- Amends: [ADR-0011 Control-plane authentication and initial administrator bootstrap](0011-control-plane-authentication-and-initial-admin-bootstrap.md)
- Extends: [ADR-0044 Fresh-install bootstrap and first-party initial administrator establishment](0044-fresh-install-bootstrap-and-first-party-initial-admin.md)

## Context

ADR-0011 deliberately gave the first administrator a narrow four-permission role: `administration:manage-authorization`, `identity:read`, `identity:create`, and `identity:update`. That was sufficient for the first externally authenticated control-plane slice, and ADR-0032 correctly made `administration:manage-authorization` a management permission rather than wildcard authority.

ADR-0044 later introduced a completely standalone first-party installation. In that path the installation wizard creates the first Tenant, governed Identity, Console registration, Principal, PASSWORD Credential and initial Administration grant, then permanently seals bootstrap. Reusing ADR-0011's narrow permission set creates an authority dead-end: the only administrator can authenticate and manage a limited Identity surface but cannot authorize Applications, Principals, Access, Credentials, Governance, Integration, Audit or other implemented tenant capabilities. ADR-0032's grantability ceiling correctly prevents that administrator from minting permissions they do not already hold.

A standalone product must therefore establish enough governed authority to administer the tenant without introducing a permanent hidden root account, wildcard permission, table mutation bypass, or reopening the irreversible installation bootstrap.

## Decision

### Separate external bootstrap from standalone first-party bootstrap

The ADR-0011 external-subject bootstrap remains narrow and unchanged. `EXTERNAL_SUBJECT` initial administration continues to receive only the original explicit four-permission `INITIAL_TENANT_ADMIN` set.

The ADR-0044 `FIRST_PARTY_PRINCIPAL` standalone bootstrap receives a separate explicit `STANDALONE_TENANT_OWNER` permission set. The role/grant remains Administration-owned, GLOBAL scoped, direct, grantable and delegable under the existing ADR-0032 rules.

The existing stable role code `tenant-initial-administrator` is retained for compatibility. The semantic distinction is carried by the immutable initial-bootstrap authentication anchor and the explicit permission membership rather than by introducing a second bootstrap marker or parallel authority model.

### Explicit enumerated authority, never wildcard root

`STANDALONE_TENANT_OWNER` contains the currently implemented tenant control-plane permissions that an installation owner needs to configure and operate Wyrmgate. It is an explicit reviewed set of `AdministrativePermission` values.

It is not `*:*`, is not a bypass in the authorization evaluator, and is not implicitly expanded when a new permission is added to the product. Future permissions must be deliberately reviewed before being added to the tenant-owner set.

Possessing a permission does not bypass independent controls. Existing requirements for typed scope, current governed-actor eligibility, optimistic concurrency, grantability/delegability ceilings, Governance approval, maker-checker, authentication assurance, break-glass semantics, secret boundaries and destructive-operation controls continue to apply.

### No self-escalation exception

ADR-0032 self-escalation prevention is unchanged. `administration:manage-authorization` still does not mint authority. The first-party owner can create roles/grants only within the permissions, scope, validity and propagation rights of a current grantable authority basis.

The bootstrap establishes the reviewed root of tenant authority once; normal Administration semantics govern all subsequent delegation and narrowing.

### Prospective bootstrap correction

ADR-0046 changes the semantics of new `FIRST_PARTY_PRINCIPAL` bootstrap only. A fresh standalone installation constructs its initial AdministrativeRole from `STANDALONE_TENANT_OWNER` before the deployment is sealed.

This ADR does not authorize a generic historical schema backfill that rewrites Administration authority for already-sealed tenants. Existing sealed installations created before ADR-0046 remain governed by their committed Administration state. Disposable pre-release environments may be recreated from a clean database; durable recovery or upgrade of existing authority requires an explicit supported Administration/recovery mechanism rather than direct table mutation or reopening installation setup.

### Bootstrap verification

ADR-0044's pre-seal verification continues to require a current effective first-party initial grant. New installations additionally construct that grant from `STANDALONE_TENANT_OWNER`, so a newly sealed installation is immediately administrable through ordinary authenticated Console/control-plane operations.

## Consequences

- A fresh standalone installation can configure the full implemented tenant surface after normal sign-in.
- Existing sealed first-party installations are not silently rewritten by a schema migration.
- External-subject bootstrap behavior does not silently broaden.
- There is still no permanent built-in superuser, wildcard permission or authorization bypass.
- Future control-plane permissions do not silently become tenant-owner authority and require explicit review.
- The controlled v0.8 formal specification predates ADR-0042 through ADR-0046; this decision must be folded into the next meaningful formal revision.

## Rejected alternatives

### Let `manage-authorization` mint arbitrary permissions

Rejected. That would defeat ADR-0032 grantability ceilings and turn a management operation into hidden superuser authority.

### Add a wildcard/root permission

Rejected. Wildcard authority weakens semantic authorization, obscures review of new capabilities and creates an enduring bypass.

### Reopen the first-run wizard after sealing

Rejected. `SEALED` is intentionally irreversible; loss or correction of Administration authority is not a reason to reactivate installation bootstrap.

### Repair existing sealed tenants through direct table edits

Rejected. Direct table edits bypass Administration ownership and reviewable security semantics.

### Carry a permanent historical bootstrap backfill migration

Rejected. The accepted semantic correction is prospective. Historical authority repair belongs in an explicit supported recovery/upgrade mechanism or disposable-environment rebuild, not an opaque schema migration that mutates already-sealed tenant authority.

### Give the expanded set to every external bootstrap

Rejected. ADR-0011's externally authenticated bootstrap may be intentionally constrained by deployment operators. The standalone owner expansion is specific to the ADR-0044 first-party fresh-install path.
