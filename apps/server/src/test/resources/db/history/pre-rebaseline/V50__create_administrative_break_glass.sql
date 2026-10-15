CREATE TABLE administration.administrative_break_glass_operation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    actor_identity_id uuid NOT NULL,
    role_id uuid NOT NULL,
    scope_type varchar(48) NOT NULL,
    scope_resource_type varchar(128) NULL,
    scope_ref_id uuid NULL,
    scope_key varchar(64) NULL,
    reason varchar(2048) NOT NULL,
    incident_reference varchar(512) NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_until timestamptz NOT NULL,
    activation_assurance_level varchar(16) NOT NULL,
    activation_authenticated_at timestamptz NULL,
    activation_step_up_at timestamptz NOT NULL,
    max_assurance_age_seconds bigint NOT NULL,
    state varchar(24) NOT NULL,
    activated_at timestamptz NOT NULL,
    revoked_by_identity_id uuid NULL,
    revoked_at timestamptz NULL,
    correlation_id uuid NULL,
    causation_id uuid NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT administrative_break_glass_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT administrative_break_glass_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT administrative_break_glass_role_fk
        FOREIGN KEY (tenant_id, role_id)
        REFERENCES administration.administrative_role (tenant_id, id),
    CONSTRAINT administrative_break_glass_scope_type_ck CHECK (
        scope_type IN (
            'GLOBAL', 'ORGANIZATION', 'APPLICATION', 'APPLICATION_TARGET',
            'SOURCE_SYSTEM', 'CONNECTOR_INSTANCE', 'IDENTITY_POPULATION',
            'CANONICAL_ATTRIBUTE_CLASSIFICATION', 'SPECIFIC_RESOURCE'
        )
    ),
    CONSTRAINT administrative_break_glass_scope_shape_ck CHECK (
        (scope_type = 'GLOBAL'
            AND scope_resource_type IS NULL AND scope_ref_id IS NULL AND scope_key IS NULL)
        OR
        (scope_type = 'SPECIFIC_RESOURCE'
            AND scope_resource_type IS NOT NULL
            AND btrim(scope_resource_type) <> ''
            AND scope_ref_id IS NOT NULL
            AND scope_key IS NULL)
        OR
        (scope_type = 'CANONICAL_ATTRIBUTE_CLASSIFICATION'
            AND scope_resource_type IS NULL
            AND scope_ref_id IS NULL
            AND scope_key IS NOT NULL
            AND btrim(scope_key) <> '')
        OR
        (scope_type NOT IN ('GLOBAL', 'SPECIFIC_RESOURCE', 'CANONICAL_ATTRIBUTE_CLASSIFICATION')
            AND scope_resource_type IS NULL
            AND scope_ref_id IS NOT NULL
            AND scope_key IS NULL)
    ),
    CONSTRAINT administrative_break_glass_reason_ck CHECK (btrim(reason) <> ''),
    CONSTRAINT administrative_break_glass_incident_ck CHECK (btrim(incident_reference) <> ''),
    CONSTRAINT administrative_break_glass_validity_ck CHECK (valid_until > valid_from),
    CONSTRAINT administrative_break_glass_assurance_ck CHECK (
        activation_assurance_level = 'STRONG'
        AND max_assurance_age_seconds > 0
    ),
    CONSTRAINT administrative_break_glass_state_ck CHECK (state IN ('ACTIVE','REVOKED')),
    CONSTRAINT administrative_break_glass_revocation_shape_ck CHECK (
        (state = 'ACTIVE' AND revoked_by_identity_id IS NULL AND revoked_at IS NULL)
        OR
        (state = 'REVOKED' AND revoked_by_identity_id IS NOT NULL AND revoked_at IS NOT NULL)
    ),
    CONSTRAINT administrative_break_glass_revision_ck CHECK (revision > 0),
    CONSTRAINT administrative_break_glass_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE INDEX administrative_break_glass_actor_state_idx
    ON administration.administrative_break_glass_operation
       (tenant_id, actor_identity_id, state, valid_until);
CREATE INDEX administrative_break_glass_role_state_idx
    ON administration.administrative_break_glass_operation
       (tenant_id, role_id, state, valid_until);

CREATE TABLE administration.administrative_break_glass_obligation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    break_glass_operation_id uuid NOT NULL,
    obligation_type varchar(32) NOT NULL,
    state varchar(24) NOT NULL,
    completed_at timestamptz NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT administrative_break_glass_obligation_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT administrative_break_glass_obligation_operation_fk
        FOREIGN KEY (tenant_id, break_glass_operation_id)
        REFERENCES administration.administrative_break_glass_operation (tenant_id, id),
    CONSTRAINT administrative_break_glass_obligation_uq
        UNIQUE (tenant_id, break_glass_operation_id, obligation_type),
    CONSTRAINT administrative_break_glass_obligation_type_ck CHECK (
        obligation_type IN ('SECURITY_NOTIFICATION','POST_USE_REVIEW')
    ),
    CONSTRAINT administrative_break_glass_obligation_state_ck CHECK (
        state IN ('PENDING','COMPLETED')
    ),
    CONSTRAINT administrative_break_glass_obligation_shape_ck CHECK (
        (state = 'PENDING' AND completed_at IS NULL)
        OR
        (state = 'COMPLETED' AND completed_at IS NOT NULL)
    ),
    CONSTRAINT administrative_break_glass_obligation_revision_ck CHECK (revision > 0),
    CONSTRAINT administrative_break_glass_obligation_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE INDEX administrative_break_glass_obligation_pending_idx
    ON administration.administrative_break_glass_obligation
       (tenant_id, state, obligation_type, created_at, id);

COMMENT ON TABLE administration.administrative_break_glass_operation IS
    'Administration-owned emergency authority. Strong assurance, short semantic validity, reason/incident evidence and durable obligations are explicit.';
COMMENT ON TABLE administration.administrative_break_glass_obligation IS
    'Durable SECURITY_NOTIFICATION and POST_USE_REVIEW work/evidence obligations. Obligation state never extends emergency authority.';
