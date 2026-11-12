-- ADR-0054: target-scoped Catalog Entitlements and RoleVersion lifecycle tightening.
-- This is a pre-release semantic contraction. Do not silently reinterpret retained incompatible data.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM catalog.entitlement
        WHERE application_target_id IS NULL
    ) THEN
        RAISE EXCEPTION
            'V16 requires every Catalog Entitlement to have application_target_id; resolve targetless retained data explicitly before migration';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM catalog.role_version
        WHERE state IN ('VALIDATING', 'REJECTED', 'CANCELLED')
    ) THEN
        RAISE EXCEPTION
            'V16 cannot remove legacy RoleVersion states while retained rows still use them; resolve those rows explicitly before migration';
    END IF;
END
$$;

ALTER TABLE catalog.entitlement
    ALTER COLUMN application_target_id SET NOT NULL;

ALTER TABLE catalog.entitlement
    DROP COLUMN native_key;

ALTER TABLE catalog.entitlement
    DROP COLUMN entitlement_type;

ALTER TABLE catalog.role_version
    DROP CONSTRAINT catalog_role_version_state_ck;

ALTER TABLE catalog.role_version
    ADD CONSTRAINT catalog_role_version_state_ck
    CHECK (state IN ('DRAFT', 'READY', 'ACTIVE', 'SUPERSEDED'));
