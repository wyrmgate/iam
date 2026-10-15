CREATE TABLE administration.administrative_delegation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    delegate_identity_id uuid NOT NULL,
    delegator_identity_id uuid NOT NULL,
    source_grant_id uuid NOT NULL,
    role_id uuid NOT NULL,
    scope_type varchar(48) NOT NULL,
    scope_resource_type varchar(128) NULL,
    scope_ref_id uuid NULL,
    scope_key varchar(64) NULL,
    state varchar(24) NOT NULL,
    valid_from timestamptz NULL,
    valid_until timestamptz NOT NULL,
    created_by_identity_id uuid NOT NULL,
    revoked_by_identity_id uuid NULL,
    revoked_at timestamptz NULL,
    correlation_id uuid NULL,
    causation_id uuid NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT administration_delegation_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT administration_delegation_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT administration_delegation_source_grant_fk
        FOREIGN KEY (tenant_id, source_grant_id)
        REFERENCES administration.administrative_grant (tenant_id, id),
    CONSTRAINT administration_delegation_role_fk
        FOREIGN KEY (tenant_id, role_id)
        REFERENCES administration.administrative_role (tenant_id, id),
    CONSTRAINT administration_delegation_scope_type_ck CHECK (
        scope_type IN (
            'GLOBAL', 'ORGANIZATION', 'APPLICATION', 'APPLICATION_TARGET',
            'SOURCE_SYSTEM', 'CONNECTOR_INSTANCE', 'IDENTITY_POPULATION',
            'CANONICAL_ATTRIBUTE_CLASSIFICATION', 'SPECIFIC_RESOURCE'
        )
    ),
    CONSTRAINT administration_delegation_scope_shape_ck CHECK (
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
    CONSTRAINT administration_delegation_state_ck CHECK (state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT administration_delegation_validity_ck CHECK (
        valid_from IS NULL OR valid_until > valid_from
    ),
    CONSTRAINT administration_delegation_revocation_shape_ck CHECK (
        (state = 'ACTIVE' AND revoked_by_identity_id IS NULL AND revoked_at IS NULL)
        OR
        (state = 'REVOKED' AND revoked_by_identity_id IS NOT NULL AND revoked_at IS NOT NULL)
    ),
    CONSTRAINT administration_delegation_revision_ck CHECK (revision > 0),
    CONSTRAINT administration_delegation_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE INDEX administration_delegation_delegate_state_idx
    ON administration.administrative_delegation
        (tenant_id, delegate_identity_id, state, role_id, valid_until);

CREATE INDEX administration_delegation_source_state_idx
    ON administration.administrative_delegation
        (tenant_id, source_grant_id, state, valid_until);

CREATE INDEX administration_delegation_role_state_idx
    ON administration.administrative_delegation
        (tenant_id, role_id, state, valid_until);

COMMENT ON TABLE administration.administrative_delegation IS
    'Administration-owned explicit single-hop delegated authority. Authority remains dependent on the current direct source grant.';
COMMENT ON COLUMN administration.administrative_delegation.delegate_identity_id IS
    'Actual governed acting Identity when delegated authority is used; cross-capability stable ID with no database FK.';
COMMENT ON COLUMN administration.administrative_delegation.delegator_identity_id IS
    'Governed Identity that owns the direct source grant; provenance only, never substituted as the acting Identity.';
