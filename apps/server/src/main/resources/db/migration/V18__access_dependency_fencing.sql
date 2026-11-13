-- Access v3 dependency-fencing foundation.
ALTER TABLE identity.identity
    ADD COLUMN access_eligibility_epoch bigint NOT NULL DEFAULT 1;

ALTER TABLE identity.identity
    ADD CONSTRAINT identity_access_eligibility_epoch_ck
    CHECK (access_eligibility_epoch > 0);

ALTER TABLE access.access_assignment
    ADD COLUMN identity_access_epoch bigint;

UPDATE access.access_assignment a
SET identity_access_epoch = i.access_eligibility_epoch
FROM identity.identity i
WHERE i.tenant_id = a.tenant_id
  AND i.id = a.identity_id;

ALTER TABLE access.access_assignment
    ALTER COLUMN identity_access_epoch SET NOT NULL;

ALTER TABLE access.access_assignment
    ADD CONSTRAINT access_assignment_identity_access_epoch_ck
    CHECK (identity_access_epoch > 0);

CREATE INDEX access_assignment_identity_epoch_idx
    ON access.access_assignment (tenant_id, identity_id, identity_access_epoch, created_at, id)
    WHERE lifecycle_state IN ('ACTIVE','SUSPENDED','SCHEDULED');
