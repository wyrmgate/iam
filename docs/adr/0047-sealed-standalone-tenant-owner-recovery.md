# ADR-0047 Sealed standalone tenant-owner recovery

- Status: Accepted
- Date: 2026-10-16
- Amends: [ADR-0046 Standalone tenant-owner bootstrap authority](0046-standalone-tenant-owner-bootstrap-authority.md)
- Constrains: [ADR-0011 Control-plane authentication and initial administrator bootstrap](0011-control-plane-authentication-and-initial-admin-bootstrap.md)
- Preserves: [ADR-0032 Delegated, elevated and emergency administrative authority](0032-delegated-elevated-emergency-administrative-authority.md)

## Context

ADR-0046 corrected fresh ADR-0044 standalone installation so a first-party `FIRST_PARTY_PRINCIPAL` bootstrap creates the finite `AdministrativePermissions.STANDALONE_TENANT_OWNER` authority profile directly. An installation sealed before that correction can still contain ADR-0011's original four-permission bootstrap role and therefore be unable to administer much of the implemented tenant surface.

That compatibility problem cannot safely be solved by a schema migration that silently grants authority. Commit `74abd436e7f0b818437dd89649bfc6bedd962663` briefly introduced Flyway V70 to expand every marker-linked first-party bootstrap role. That migration was subsequently withdrawn from `main` when ADR-0046 was corrected to be prospective. A database may nevertheless already record V70 as applied and may already contain its exact owner expansion. Recovery therefore has to distinguish an unrecovered legacy state, an explainable already-owner state, and customized or ambiguous Administration state.

`SEALED` remains irreversible. Recovery must not reopen setup, recreate the installation, bypass normal Administration authorization, accept arbitrary authority input, or become a general privilege-escalation mechanism.

## Decision

### Recovery is an explicit local maintenance command

Wyrmgate provides one narrowly scoped compatibility command in the existing server executable. The command requires the exact command-line option `--iam.recovery.standalone-tenant-owner.enabled=true`; a lingering environment/configuration property is not sufficient. It additionally requires `--spring.main.web-application-type=none`. The executable exits after the command path completes.

There is no HTTP recovery endpoint. The command accepts no tenant ID, identity ID, principal ID, role ID, grant ID, permission set, scope, or authority flag from the operator. Local process execution and access to the deployment's normal database configuration are the operator control boundary.

Normal application startup, login, setup-status reads, tenant access, the first-run web adapter, and Flyway migrations do not invoke recovery. No generic scheduler, BPM process, recovery framework, or service boundary is introduced.

### Installation state selects the only candidate

The command reads the deployment-local `InstallationBootstrapPlan` and requires:

- a plan exists;
- `state == SEALED`;
- `bootstrapVersion == InstallationBootstrapService.BOOTSTRAP_VERSION`;
- the candidate tenant, Identity, Principal and bootstrap correlation ID come only from that durable plan.

The command then invokes an Administration-owned recovery service. The plan does not authorize mutation by itself; it identifies the only candidate whose Administration state must independently prove eligibility.

### Administration state must prove the exact bootstrap lineage

Recovery requires the tenant's immutable `InitialAdminBootstrap` marker to exist and to match the installation plan. The marker must:

- use `FIRST_PARTY_PRINCIPAL`, never `EXTERNAL_SUBJECT`;
- identify the same bootstrap Identity and Principal as the installation plan;
- carry no external actor binding;
- carry the same bootstrap correlation ID;
- point to the candidate bootstrap role and direct grant;
- still refer to an administratively eligible Identity and an authentication-eligible Principal for that Identity.

The marker-linked role must retain the bootstrap role ID, code `tenant-initial-administrator`, name `Tenant Initial Administrator`, and bootstrap creation timestamp. The marker-linked direct grant must still be the same actor/role relationship, `GLOBAL`, `ACTIVE`, unbounded after its bootstrap `validFrom`, `grantable=true`, `delegable=true`, with no authority-basis grant, revision 1, and unchanged bootstrap timestamps.

The bootstrap Identity must have exactly one direct AdministrativeGrant, the bootstrap role must be referenced by exactly one direct grant, and the bootstrap role must have no delegation, temporary elevation, or break-glass operation. These checks prevent recovery from normalizing a tenant whose bootstrap authority has already been composed into any later Administration authority source.

### Exact legacy state is the only mutable state

The recoverable legacy permission membership is exactly `AdministrativePermissions.INITIAL_TENANT_ADMIN`:

- `administration:manage-authorization`
- `identity:read`
- `identity:create`
- `identity:update`

The role must still be revision 1 and unchanged since bootstrap. An extra permission, a missing permission, a renamed role, a changed scope, changed validity, changed grantability/delegability, extra grant, delegation, elevation, break-glass operation, revised grant, mismatched marker, changed anchor, changed ownership relationship, or any other material mismatch fails closed. Recovery never overwrites or repairs customized state.

For the exact legacy state, the service atomically adds only the permissions necessary to make role membership exactly equal to the current finite `AdministrativePermissions.STANDALONE_TENANT_OWNER` set and advances the role's optimistic revision from 1 to 2. It may materialize missing Administration permission-catalog rows for those explicit semantic permissions, using normal Administration-owned persistence. It does not create a Tenant, Identity, Principal, Credential, role, grant, delegation, elevation, or break-glass operation.

No wildcard, root bit, evaluator bypass, implicit future permission, or arbitrary permission input is introduced.

### Idempotency and historical V70 state

An exact owner role is considered already complete only when all other bootstrap/grant/lineage checks pass and its role revision is explainable as either:

- revision 1 with the bootstrap timestamp: a fresh ADR-0046 installation that created owner authority directly; or
- revision 2 at or after bootstrap: one finite semantic expansion, either historical V70 or ADR-0047 recovery.

That state returns `ALREADY_COMPLETE` without authority mutation. A higher revision, partial owner set, owner-plus-extra permission, or other mixed state is ambiguous and fails closed.

This deliberately permits an environment that already received V70 to converge operationally without applying another authority mutation. It does not declare arbitrary revision-2 state trustworthy; the full marker, role, grant, exclusivity and exact-permission predicates still apply.

### Withdrawn V70 migration history is metadata compatibility, not authority recovery

The withdrawn V70 SQL is not restored to `main`: restoring it would make a new database execute the prohibited automatic authority expansion.

If an existing pre-release environment recorded V70, current Flyway may report that the applied versioned migration is no longer resolved. The operator must first verify that V70 is the expected intentionally withdrawn migration and use Flyway's explicit `repair` operation with the same migration location used by Wyrmgate, causing the missing historical migration to be recorded as deleted in Flyway schema history. This is migration-history reconciliation only; it must not edit IAM domain rows and must not be replaced by a permanent `ignoreMigrationPatterns` exception.

After schema-history repair, the ADR-0047 recovery command evaluates current Administration state. If historical V70 already produced the exact owner state, recovery returns `ALREADY_COMPLETE`; if it left customized or ambiguous state, recovery fails closed for operator investigation.

### Transaction and evidence boundaries

The Administration mutation runs in one authoritative transaction and uses optimistic role revision semantics. No provider or external-system call occurs inside that transaction.

After the Administration result is known, the command appends data-minimized Audit evidence through the existing `SecurityAuditPort`. Evidence identifies the semantic recovery action, installation resource, outcome and bootstrap correlation context. Setup passwords, credential material, private keys and other secrets are never command inputs and never appear in recovery logs, errors or audit payloads.

A failure to prove eligibility is a denied compatibility operation, not permission normalization. The operator investigates the mismatched state rather than asking recovery to fix it.

### Ordinary Administration authorization is unchanged

ADR-0047 is an installation compatibility operation, not a normal Administrative Authorization API. It does not route through or weaken ordinary role/grant mutation rules, does not alter ADR-0032's self-escalation and grantability ceilings, and does not make `administration:manage-authorization` equivalent to root authority.

## Consequences

- A legacy sealed first-party standalone installation has a supported one-shot recovery path without reopening setup or directly editing database rows.
- Eligibility is derived from durable installation/bootstrap state rather than operator-selected authority objects.
- Exact legacy state upgrades once; exact fresh/recovered owner state is a no-op; ambiguous/customized state fails closed.
- Historical V70 authority expansion is tolerated only when the current state independently matches the complete finite owner predicate.
- Historical V70 migration metadata can be reconciled explicitly without restoring an automatic privilege-expansion migration.
- New installations continue to create `STANDALONE_TENANT_OWNER` directly and require no recovery.
- There is no remote recovery endpoint and no automatic sealed-tenant backfill.
- The controlled v0.8 formal specification predates ADR-0047; this decision is folded into the next meaningful controlled revision rather than triggering an ad-hoc formal package rewrite.

## Rejected alternatives

### Restore V70 unchanged

Rejected. A schema migration that grants authority on application migration/startup is exactly the implicit sealed-tenant privilege expansion this ADR prohibits.

### Add a compensating automatic migration

Rejected. A second automatic mutation cannot make the first automatic authority mutation safe and would create more ambiguous states.

### Expose a secured HTTP recovery API

Rejected for this compatibility case. It expands the remote attack surface and introduces tenant/object selection questions that the deployment-local installation plan already answers.

### Accept tenant, role, grant or permission IDs as command arguments

Rejected. Arbitrary object selection turns compatibility recovery into a privilege-escalation primitive.

### Reuse ordinary role-permission mutation and bypass self-escalation checks

Rejected. ADR-0032 remains authoritative for ordinary Administration operations. Recovery has a dedicated narrow semantic port whose only mutation is the fixed legacy-to-owner transition after proving bootstrap lineage.

### Reopen installation bootstrap or recreate the administrator

Rejected. `SEALED` is irreversible and recovery must not create another Tenant, Identity, Principal, Credential, role or grant.

### Normalize near-matches

Rejected. Any near-match may represent deliberate customization, partial failure, historical operator action or corruption. Recovery cannot safely infer intent and therefore fails closed.
