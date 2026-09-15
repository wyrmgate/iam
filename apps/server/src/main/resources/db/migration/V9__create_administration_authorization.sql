CREATE TABLE administration.administrative_permission (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    resource_type varchar(128) NOT NULL,
    action varchar(128) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT administration_permission_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT administration_permission_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT administration_permission_key_uq UNIQUE (tenant_id, resource_type, action),
    CONSTRAINT administration_permission_resource_ck CHECK (btrim(resource_type) <> ''),
    CONSTRAINT administration_permission_action_ck CHECK (btrim(action) <> '')
);

CREATE TABLE administration.administrative_role (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    code varchar(128) NOT NULL,
    name varchar(512) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT administration_role_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT administration_role_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT administration_role_code_uq UNIQUE (tenant_id, code),
    CONSTRAINT administration_role_code_ck CHECK (btrim(code) <> ''),
    CONSTRAINT administration_role_name_ck CHECK (btrim(name) <> ''),
    CONSTRAINT administration_role_revision_ck CHECK (revision > 0),
    CONSTRAINT administration_role_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE TABLE administration.administrative_role_permission (
    tenant_id uuid NOT NULL,
    role_id uuid NOT NULL,
    permission_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, role_id, permission_id),
    CONSTRAINT administration_role_permission_role_fk
        FOREIGN KEY (tenant_id, role_id)
        REFERENCES administration.administrative_role (tenant_id, id),
    CONSTRAINT administration_role_permission_permission_fk
        FOREIGN KEY (tenant_id, permission_id)
        REFERENCES administration.administrative_permission (tenant_id, id)
);

CREATE TABLE administration.administrative_grant (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    actor_identity_id uuid NOT NULL,
    role_id uuid NOT NULL,
    scope_type varchar(32) NOT NULL,
    scope_resource_type varchar(128) NULL,
    scope_ref_id uuid NULL,
    state varchar(24) NOT NULL,
    valid_from timestamptz NULL,
    valid_until timestamptz NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT administration_grant_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT administration_grant_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT administration_grant_role_fk
        FOREIGN KEY (tenant_id, role_id)
        REFERENCES administration.administrative_role (tenant_id, id),
    CONSTRAINT administration_grant_scope_type_ck CHECK (
        scope_type IN (
            'GLOBAL', 'ORGANIZATION', 'APPLICATION', 'APPLICATION_TARGET',
            'SOURCE_SYSTEM', 'CONNECTOR_INSTANCE', 'IDENTITY_POPULATION',
            'SPECIFIC_RESOURCE'
        )
    ),
    CONSTRAINT administration_grant_scope_shape_ck CHECK (
        (scope_type = 'GLOBAL' AND scope_resource_type IS NULL AND scope_ref_id IS NULL)
        OR
        (scope_type = 'SPECIFIC_RESOURCE' AND btrim(scope_resource_type) <> '' AND scope_ref_id IS NOT NULL)
        OR
        (scope_type NOT IN ('GLOBAL', 'SPECIFIC_RESOURCE')
            AND scope_resource_type IS NULL AND scope_ref_id IS NOT NULL)
    ),
    CONSTRAINT administration_grant_state_ck CHECK (state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT administration_grant_validity_ck CHECK (
        valid_until IS NULL OR valid_from IS NULL OR valid_until > valid_from
    ),
    CONSTRAINT administration_grant_revision_ck CHECK (revision > 0),
    CONSTRAINT administration_grant_timestamp_ck CHECK (updated_at >= created_at)
);

COMMENT ON TABLE administration.administrative_permission IS
    'Administration-owned stable semantic IAM control-plane permissions.';
COMMENT ON TABLE administration.administrative_role IS
    'Administration-owned permission bundles; distinct from Catalog IAM Roles.';
COMMENT ON TABLE administration.administrative_grant IS
    'Tenant-scoped IAM control-plane authority. actor_identity_id is a cross-capability stable Identity ID and intentionally has no database FK.';

CREATE INDEX administration_grant_actor_state_idx
    ON administration.administrative_grant (tenant_id, actor_identity_id, state, role_id);
CREATE INDEX administration_role_permission_permission_idx
    ON administration.administrative_role_permission (tenant_id, permission_id, role_id);
