CREATE TABLE access.identity_access_reduction (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    source_identity_revision bigint NOT NULL,
    source_lifecycle_state varchar(24) NOT NULL,
    lifecycle_state varchar(16) NOT NULL,
    snapshot_at timestamptz NOT NULL,
    after_created_at timestamptz NULL,
    after_assignment_id uuid NULL,
    processed_assignment_count bigint NOT NULL DEFAULT 0,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz NULL,
    CONSTRAINT identity_access_reduction_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT identity_access_reduction_tenant_id_uq
        UNIQUE (tenant_id, id),
    CONSTRAINT identity_access_reduction_source_uq
        UNIQUE (tenant_id, identity_id, source_identity_revision),
    CONSTRAINT identity_access_reduction_source_revision_ck
        CHECK (source_identity_revision > 0),
    CONSTRAINT identity_access_reduction_source_state_ck
        CHECK (source_lifecycle_state IN (
            'PENDING','SUSPENDED','INACTIVE','DECOMMISSIONED')),
    CONSTRAINT identity_access_reduction_state_ck
        CHECK (lifecycle_state IN ('RUNNING','COMPLETED')),
    CONSTRAINT identity_access_reduction_checkpoint_ck CHECK (
        (after_created_at IS NULL AND after_assignment_id IS NULL)
        OR
        (after_created_at IS NOT NULL AND after_assignment_id IS NOT NULL)
    ),
    CONSTRAINT identity_access_reduction_processed_ck
        CHECK (processed_assignment_count >= 0),
    CONSTRAINT identity_access_reduction_revision_ck
        CHECK (revision > 0),
    CONSTRAINT identity_access_reduction_timestamp_ck
        CHECK (updated_at >= created_at),
    CONSTRAINT identity_access_reduction_completion_ck CHECK (
        (lifecycle_state = 'RUNNING' AND completed_at IS NULL)
        OR
        (lifecycle_state = 'COMPLETED' AND completed_at IS NOT NULL)
    )
);

CREATE INDEX identity_access_reduction_state_idx
    ON access.identity_access_reduction (
        tenant_id, lifecycle_state, created_at, id);

CREATE INDEX access_assignment_identity_reduction_page_idx
    ON access.access_assignment (
        tenant_id, identity_id, created_at, id)
    WHERE lifecycle_state IN ('ACTIVE','SUSPENDED','SCHEDULED');

COMMENT ON TABLE access.identity_access_reduction IS
    'Access-owned durable Leaver reduction process causally keyed by Identity ID and source Identity revision. Identity references are stable cross-capability IDs without database foreign keys.';
