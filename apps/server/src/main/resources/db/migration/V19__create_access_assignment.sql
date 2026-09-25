CREATE TABLE access.access_assignment (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    target_kind varchar(16) NOT NULL,
    role_id uuid NULL,
    entitlement_id uuid NULL,
    principal_constraint_kind varchar(16) NOT NULL,
    specific_principal_id uuid NULL,
    provenance_kind varchar(32) NOT NULL,
    provenance_ref_id uuid NULL,
    lifecycle_state varchar(24) NOT NULL,
    valid_from timestamptz NULL,
    valid_until timestamptz NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT access_assignment_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT access_assignment_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT access_assignment_target_kind_ck
        CHECK (target_kind IN ('ROLE', 'ENTITLEMENT')),
    CONSTRAINT access_assignment_target_shape_ck CHECK (
        (target_kind = 'ROLE'
            AND role_id IS NOT NULL
            AND entitlement_id IS NULL)
        OR
        (target_kind = 'ENTITLEMENT'
            AND entitlement_id IS NOT NULL
            AND role_id IS NULL)
    ),
    CONSTRAINT access_assignment_principal_constraint_kind_ck
        CHECK (principal_constraint_kind IN ('ANY', 'SPECIFIC')),
    CONSTRAINT access_assignment_principal_constraint_shape_ck CHECK (
        (principal_constraint_kind = 'SPECIFIC'
            AND specific_principal_id IS NOT NULL)
        OR
        (principal_constraint_kind = 'ANY'
            AND specific_principal_id IS NULL)
    ),
    CONSTRAINT access_assignment_provenance_kind_ck
        CHECK (provenance_kind IN ('MANUAL')),
    CONSTRAINT access_assignment_manual_provenance_ck
        CHECK (provenance_kind <> 'MANUAL' OR provenance_ref_id IS NULL),
    CONSTRAINT access_assignment_lifecycle_state_ck
        CHECK (lifecycle_state IN (
            'SCHEDULED', 'ACTIVE', 'SUSPENDED',
            'REVOKED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT access_assignment_scheduled_valid_from_ck
        CHECK (lifecycle_state <> 'SCHEDULED' OR valid_from IS NOT NULL),
    CONSTRAINT access_assignment_validity_ck
        CHECK (valid_until IS NULL OR valid_from IS NULL OR valid_until > valid_from),
    CONSTRAINT access_assignment_revision_ck CHECK (revision > 0),
    CONSTRAINT access_assignment_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE INDEX access_assignment_identity_state_idx
    ON access.access_assignment (
        tenant_id, identity_id, lifecycle_state, valid_until)
    WHERE lifecycle_state IN ('SCHEDULED', 'ACTIVE', 'SUSPENDED');

CREATE INDEX access_assignment_entitlement_idx
    ON access.access_assignment (
        tenant_id, entitlement_id, identity_id)
    WHERE target_kind = 'ENTITLEMENT';

CREATE INDEX access_assignment_specific_principal_idx
    ON access.access_assignment (
        tenant_id, specific_principal_id, lifecycle_state)
    WHERE specific_principal_id IS NOT NULL;

COMMENT ON TABLE access.access_assignment IS
    'Access-owned authoritative business access intent. Technical fulfillment and provider observation are separate state dimensions.';
