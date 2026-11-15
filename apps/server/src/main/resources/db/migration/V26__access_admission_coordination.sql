-- Access owns this technical row; Identity is a stable cross-capability reference.
CREATE TABLE access.admission_lock (
    tenant_id uuid NOT NULL REFERENCES tenancy.tenant(id),
    identity_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, identity_id)
);
CREATE INDEX access_assignment_admission_page_idx
    ON access.access_assignment (tenant_id, identity_id, identity_access_epoch, created_at, id)
    WHERE lifecycle_state IN ('ACTIVE','SCHEDULED');
