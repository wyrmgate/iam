-- Access v3 dependency-fencing foundation.
ALTER TABLE identity.identity
    ADD COLUMN access_eligibility_epoch bigint NOT NULL DEFAULT 1;

ALTER TABLE identity.identity
    ADD CONSTRAINT identity_access_eligibility_epoch_ck
    CHECK (access_eligibility_epoch > 0);

-- The Identity epoch is introduced as 1 for every pre-existing Identity above.
-- PostgreSQL can therefore seed the Access fence with the same constant without an
-- unbounded table rewrite/backfill transaction. Drop the default afterwards so all
-- future application writes must supply the captured Identity epoch explicitly.
ALTER TABLE access.access_assignment
    ADD COLUMN identity_access_epoch bigint NOT NULL DEFAULT 1;

ALTER TABLE access.access_assignment
    ALTER COLUMN identity_access_epoch DROP DEFAULT;

ALTER TABLE access.access_assignment
    ADD CONSTRAINT access_assignment_identity_access_epoch_ck
    CHECK (identity_access_epoch > 0);

CREATE INDEX access_assignment_identity_epoch_idx
    ON access.access_assignment (tenant_id, identity_id, identity_access_epoch, created_at, id)
    WHERE lifecycle_state IN ('ACTIVE','SUSPENDED','SCHEDULED');


-- Principal-change desired-state repair is bounded and restartable. Keep each branch
-- indexable independently so large tenants do not require an unbounded sort/scan.
CREATE INDEX access_desired_grant_specific_refresh_idx
    ON access.desired_grant_state (
        tenant_id,
        application_target_id,
        principal_constraint_key,
        id);

CREATE INDEX access_desired_grant_any_refresh_idx
    ON access.desired_grant_state (
        tenant_id,
        application_target_id,
        identity_id,
        id)
    WHERE principal_constraint_key = 'ANY'
      AND desired_state = 'PRESENT';
