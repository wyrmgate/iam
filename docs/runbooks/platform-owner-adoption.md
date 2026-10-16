# Legacy Platform Owner Adoption

Use this operation only for an installation that was already successfully sealed by the first-party installation bootstrap before ADR-0049/V11 and therefore has the historical finite standalone tenant-owner grant but no explicit `PLATFORM_OWNER` / `TENANT_OWNER` designation rows.

Fresh ADR-0049 installations do not use this operation. V11 never invokes it automatically.

## Safety properties

The operation is disabled by default and fails closed unless all of the following are true:

- Installation proves the exact supplied Tenant, Identity, Principal and original bootstrap correlation ID belong to the durable `SEALED` installation plan.
- Administration has the matching first-party initial-admin bootstrap marker.
- The governed Identity is administratively eligible.
- The supplied Principal is still an authentication-eligible first-party Principal for that Identity.
- The bootstrap role is exactly the reviewed standalone tenant-owner permission profile and has an allowed historical revision shape.
- The bootstrap grant is exactly the original active, non-expiring, grantable/delegable Tenant-global bootstrap grant.
- That bootstrap Identity/role has no additional grants, delegation, elevation or break-glass history that would make the historical state ambiguous.
- There is no existing Platform Owner or Tenant Owner state, unless it is already the exact adopted lineage; an exact retry is idempotent.

A successful operation creates the explicit `TENANT_OWNER` designation over the historical grant and the deployment-scoped `PLATFORM_OWNER` authority in one Administration transaction and emits `administration.platform-owner-adopted` through the transactional outbox.

## Invocation

Provide the exact values from the sealed installation/bootstrap evidence and enable the one-shot startup adapter:

```properties
iam.bootstrap.platform-owner-adoption.enabled=true
iam.bootstrap.platform-owner-adoption.tenant-id=<first-tenant-uuid>
iam.bootstrap.platform-owner-adoption.identity-id=<bootstrap-identity-uuid>
iam.bootstrap.platform-owner-adoption.principal-id=<bootstrap-principal-uuid>
iam.bootstrap.platform-owner-adoption.bootstrap-correlation-id=<original-bootstrap-correlation-uuid>
```

Start the server once with those settings. A mismatch fails startup rather than guessing or broadening authority.

After a successful run, remove/disable the adoption settings and restart normally. Re-running with the exact same evidence is safe and returns the already-complete state; different evidence is rejected.

## Do not use

Do not use this operation to:

- add a second Platform Owner;
- recover a disabled/decommissioned owner;
- bypass normal future Platform Owner delegation/transfer commands;
- convert arbitrary tenant administrators into Platform Owners;
- replace ADR-0047 historical tenant-owner permission recovery.

Those are separate governed operations and must not be conflated with legacy installation adoption.
