ALTER TABLE access.desired_grant_state
    ADD COLUMN principal_constraint_key varchar(512);

UPDATE access.desired_grant_state
SET principal_constraint_key = CASE
    WHEN principal_id IS NULL THEN 'ANY'
    ELSE 'SPECIFIC:' || principal_id::text
END
WHERE principal_constraint_key IS NULL;

ALTER TABLE access.desired_grant_state
    ALTER COLUMN principal_constraint_key SET NOT NULL;

ALTER TABLE access.desired_grant_state
    ADD CONSTRAINT access_desired_grant_constraint_key_ck
        CHECK (btrim(principal_constraint_key) <> '');

ALTER TABLE access.desired_principal_state
    ADD CONSTRAINT access_desired_principal_tuple_uq
        UNIQUE (tenant_id, identity_id, application_target_id);

ALTER TABLE access.desired_grant_state
    ADD CONSTRAINT access_desired_grant_tuple_uq
        UNIQUE (
            tenant_id,
            identity_id,
            application_target_id,
            entitlement_id,
            principal_constraint_key);

CREATE INDEX access_desired_grant_present_target_idx
    ON access.desired_grant_state (
        tenant_id, identity_id, application_target_id, desired_state);

COMMENT ON COLUMN access.desired_grant_state.principal_constraint_key IS
    'Stable Access projection tuple key aligned with EffectiveAccess: ANY or SPECIFIC:<principal UUID>.';
