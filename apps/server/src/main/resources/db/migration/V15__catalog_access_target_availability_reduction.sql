-- ADR-0052 Catalog target availability propagation and Access-owned target reduction.
-- Catalog owns retirement meaning/propagation. Access alone mutates AccessAssignment authority.

CREATE TABLE catalog.access_target_availability_propagation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    cause_kind character varying(32) NOT NULL,
    cause_id uuid NOT NULL,
    cause_revision bigint NOT NULL,
    phase character varying(32) NOT NULL,
    after_created_at timestamp with time zone,
    after_resource_id uuid,
    processed_resource_count bigint DEFAULT 0 NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    lifecycle_state character varying(16) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT catalog_availability_propagation_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT catalog_availability_propagation_source_uq
        UNIQUE (tenant_id, cause_kind, cause_id, cause_revision),
    CONSTRAINT catalog_availability_propagation_cause_ck
        CHECK (cause_kind IN ('APPLICATION','APPLICATION_TARGET','ENTITLEMENT','ROLE','ROLE_VERSION')),
    CONSTRAINT catalog_availability_propagation_cause_revision_ck
        CHECK (cause_revision > 0),
    CONSTRAINT catalog_availability_propagation_phase_ck
        CHECK (phase IN ('ENTITLEMENTS','ROLES','ROLE_EXPANSIONS')),
    CONSTRAINT catalog_availability_propagation_checkpoint_ck CHECK (
        (after_created_at IS NULL AND after_resource_id IS NULL)
        OR (after_created_at IS NOT NULL AND after_resource_id IS NOT NULL)),
    CONSTRAINT catalog_availability_propagation_processed_ck
        CHECK (processed_resource_count >= 0),
    CONSTRAINT catalog_availability_propagation_revision_ck CHECK (revision > 0),
    CONSTRAINT catalog_availability_propagation_state_ck
        CHECK (lifecycle_state IN ('RUNNING','COMPLETED')),
    CONSTRAINT catalog_availability_propagation_completion_ck CHECK (
        (lifecycle_state = 'RUNNING' AND completed_at IS NULL)
        OR (lifecycle_state = 'COMPLETED' AND completed_at IS NOT NULL)),
    CONSTRAINT catalog_availability_propagation_time_ck CHECK (updated_at >= created_at),
    CONSTRAINT catalog_availability_propagation_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES tenancy.tenant(id)
);

CREATE INDEX catalog_availability_propagation_running_idx
    ON catalog.access_target_availability_propagation
       (tenant_id, lifecycle_state, updated_at, id);

COMMENT ON TABLE catalog.access_target_availability_propagation IS
    'Catalog-owned bounded propagation of one material retirement cause to target-availability and role-expansion triggers.';

CREATE TABLE access.access_target_reduction (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    target_kind character varying(16) NOT NULL,
    target_id uuid NOT NULL,
    source_cause_kind character varying(32) NOT NULL,
    source_cause_id uuid NOT NULL,
    source_cause_revision bigint NOT NULL,
    lifecycle_state character varying(16) NOT NULL,
    after_created_at timestamp with time zone,
    after_assignment_id uuid,
    processed_assignment_count bigint DEFAULT 0 NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT access_target_reduction_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT access_target_reduction_source_uq UNIQUE (
        tenant_id, target_kind, target_id,
        source_cause_kind, source_cause_id, source_cause_revision),
    CONSTRAINT access_target_reduction_target_kind_ck
        CHECK (target_kind IN ('ROLE','ENTITLEMENT')),
    CONSTRAINT access_target_reduction_cause_kind_ck
        CHECK (source_cause_kind IN ('APPLICATION','APPLICATION_TARGET','ENTITLEMENT','ROLE')),
    CONSTRAINT access_target_reduction_source_revision_ck
        CHECK (source_cause_revision > 0),
    CONSTRAINT access_target_reduction_state_ck
        CHECK (lifecycle_state IN ('RUNNING','COMPLETED')),
    CONSTRAINT access_target_reduction_checkpoint_ck CHECK (
        (after_created_at IS NULL AND after_assignment_id IS NULL)
        OR (after_created_at IS NOT NULL AND after_assignment_id IS NOT NULL)),
    CONSTRAINT access_target_reduction_processed_ck CHECK (processed_assignment_count >= 0),
    CONSTRAINT access_target_reduction_revision_ck CHECK (revision > 0),
    CONSTRAINT access_target_reduction_completion_ck CHECK (
        (lifecycle_state = 'RUNNING' AND completed_at IS NULL)
        OR (lifecycle_state = 'COMPLETED' AND completed_at IS NOT NULL)),
    CONSTRAINT access_target_reduction_time_ck CHECK (updated_at >= created_at),
    CONSTRAINT access_target_reduction_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES tenancy.tenant(id)
);

CREATE INDEX access_target_reduction_running_idx
    ON access.access_target_reduction
       (tenant_id, lifecycle_state, updated_at, id);

COMMENT ON TABLE access.access_target_reduction IS
    'Access-owned durable bounded reduction of non-terminal AccessAssignments whose Catalog Role/Entitlement target remains terminally unavailable.';
