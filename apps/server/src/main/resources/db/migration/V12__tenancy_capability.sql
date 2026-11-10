-- ADR-0051 first-class Tenancy capability.
-- Move the Tenant isolation root out of Platform technical ownership without changing stable IDs
-- or the existing tenant foreign-key graph. PostgreSQL preserves foreign-key references when the
-- referenced table moves schemas because constraints bind to the table object, not its text name.

CREATE SCHEMA tenancy;

COMMENT ON SCHEMA tenancy IS
    'Tenancy capability authoritative Tenant identity and lifecycle state.';

ALTER TABLE platform.tenant SET SCHEMA tenancy;

ALTER TABLE tenancy.tenant
    ADD COLUMN lifecycle_state character varying(24);

-- Existing pre-ADR-0051 Tenant rows represented operational realms with no lifecycle dimension.
-- Preserve that operational meaning at the migration boundary. Fresh installs created after V12
-- start PROVISIONING through the Tenancy command service and activate only after owner viability.
UPDATE tenancy.tenant
SET lifecycle_state = 'ACTIVE';

ALTER TABLE tenancy.tenant
    ALTER COLUMN lifecycle_state SET NOT NULL;

ALTER TABLE tenancy.tenant
    ADD CONSTRAINT tenant_lifecycle_state_ck CHECK (
        lifecycle_state IN (
            'PROVISIONING',
            'ACTIVE',
            'SUSPENDED',
            'DECOMMISSIONING',
            'DECOMMISSIONED'));

COMMENT ON TABLE tenancy.tenant IS
    'Tenancy-owned deployment-scoped Tenant isolation roots. DECOMMISSIONED is terminal business lifecycle, not physical deletion.';

COMMENT ON COLUMN tenancy.tenant.lifecycle_state IS
    'Authoritative Tenant lifecycle under ADR-0051.';
