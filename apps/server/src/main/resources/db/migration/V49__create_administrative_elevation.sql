CREATE TABLE administration.administrative_elevation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    beneficiary_identity_id uuid NOT NULL,
    initiator_identity_id uuid NOT NULL,
    authority_basis_grant_id uuid NOT NULL,
    role_id uuid NOT NULL,
    scope_type varchar(48) NOT NULL,
    scope_resource_type varchar(128) NULL,
    scope_ref_id uuid NULL,
    scope_key varchar(64) NULL,
    valid_from timestamptz NULL,
    valid_until timestamptz NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    state varchar(32) NOT NULL,
    approval_case_id uuid NULL,
    approval_plan_fingerprint varchar(64) NULL,
    activated_at timestamptz NULL,
    denied_at timestamptz NULL,
    cancelled_at timestamptz NULL,
    revoked_at timestamptz NULL,
    correlation_id uuid NULL,
    causation_id uuid NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT administrative_elevation_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT administrative_elevation_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT administrative_elevation_basis_fk
        FOREIGN KEY (tenant_id, authority_basis_grant_id)
        REFERENCES administration.administrative_grant (tenant_id, id),
    CONSTRAINT administrative_elevation_role_fk
        FOREIGN KEY (tenant_id, role_id)
        REFERENCES administration.administrative_role (tenant_id, id),
    CONSTRAINT administrative_elevation_state_ck CHECK (
        state IN ('REQUESTED','PENDING_APPROVAL','ACTIVE','DENIED','CANCELLED','REVOKED')
    ),
    CONSTRAINT administrative_elevation_validity_ck CHECK (
        valid_from IS NULL OR valid_until > valid_from
    ),
    CONSTRAINT administrative_elevation_approval_shape_ck CHECK (
        (approval_case_id IS NULL AND approval_plan_fingerprint IS NULL)
        OR
        (approval_case_id IS NOT NULL AND approval_plan_fingerprint IS NOT NULL)
    ),
    CONSTRAINT administrative_elevation_revision_ck CHECK (revision > 0),
    CONSTRAINT administrative_elevation_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE INDEX administrative_elevation_beneficiary_state_idx
    ON administration.administrative_elevation
       (tenant_id, beneficiary_identity_id, state, valid_until);
CREATE INDEX administrative_elevation_role_state_idx
    ON administration.administrative_elevation
       (tenant_id, role_id, state, valid_until);

COMMENT ON TABLE administration.administrative_elevation IS
    'Administration-owned temporary/JIT authority process; Governance ApprovalCase is referenced evidence only.';
