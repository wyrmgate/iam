CREATE TABLE catalog.role (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    role_type varchar(24) NOT NULL,
    application_id uuid NULL,
    code varchar(128) NOT NULL,
    name varchar(512) NOT NULL,
    lifecycle_state varchar(24) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT catalog_role_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT catalog_role_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT catalog_role_application_fk
        FOREIGN KEY (tenant_id, application_id)
        REFERENCES catalog.application (tenant_id, id),
    CONSTRAINT catalog_role_type_ck CHECK (role_type IN ('BUSINESS','APPLICATION')),
    CONSTRAINT catalog_role_shape_ck CHECK (
        (role_type = 'BUSINESS' AND application_id IS NULL)
        OR (role_type = 'APPLICATION' AND application_id IS NOT NULL)
    ),
    CONSTRAINT catalog_role_code_ck CHECK (btrim(code) <> ''),
    CONSTRAINT catalog_role_name_ck CHECK (btrim(name) <> ''),
    CONSTRAINT catalog_role_lifecycle_ck CHECK (lifecycle_state IN ('ACTIVE','RETIRED')),
    CONSTRAINT catalog_role_revision_ck CHECK (revision > 0),
    CONSTRAINT catalog_role_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX catalog_business_role_code_uq
    ON catalog.role (tenant_id, code)
    WHERE role_type = 'BUSINESS';

CREATE UNIQUE INDEX catalog_application_role_code_uq
    ON catalog.role (tenant_id, application_id, code)
    WHERE role_type = 'APPLICATION';

CREATE TABLE catalog.role_version (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    role_id uuid NOT NULL,
    version_number bigint NOT NULL,
    state varchar(24) NOT NULL,
    content_hash varchar(128) NOT NULL,
    activated_at timestamptz NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT catalog_role_version_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT catalog_role_version_number_uq
        UNIQUE (tenant_id, role_id, version_number),
    CONSTRAINT catalog_role_version_role_fk
        FOREIGN KEY (tenant_id, role_id)
        REFERENCES catalog.role (tenant_id, id),
    CONSTRAINT catalog_role_version_number_ck CHECK (version_number > 0),
    CONSTRAINT catalog_role_version_state_ck CHECK (state IN (
        'DRAFT','VALIDATING','READY','ACTIVE',
        'SUPERSEDED','REJECTED','CANCELLED')),
    CONSTRAINT catalog_role_version_hash_ck CHECK (btrim(content_hash) <> ''),
    CONSTRAINT catalog_role_version_activation_ck CHECK (
        (state IN ('ACTIVE','SUPERSEDED') AND activated_at IS NOT NULL)
        OR (state NOT IN ('ACTIVE','SUPERSEDED') AND activated_at IS NULL)
    ),
    CONSTRAINT catalog_role_version_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX catalog_role_one_active_version_uq
    ON catalog.role_version (tenant_id, role_id)
    WHERE state = 'ACTIVE';

CREATE TABLE catalog.role_version_member (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    role_version_id uuid NOT NULL,
    member_kind varchar(32) NOT NULL,
    member_role_id uuid NULL,
    member_entitlement_id uuid NULL,
    ordinal integer NOT NULL,
    CONSTRAINT catalog_role_version_member_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT catalog_role_version_member_ordinal_uq
        UNIQUE (tenant_id, role_version_id, ordinal),
    CONSTRAINT catalog_role_version_member_role_version_fk
        FOREIGN KEY (tenant_id, role_version_id)
        REFERENCES catalog.role_version (tenant_id, id),
    CONSTRAINT catalog_role_version_member_role_fk
        FOREIGN KEY (tenant_id, member_role_id)
        REFERENCES catalog.role (tenant_id, id),
    CONSTRAINT catalog_role_version_member_entitlement_fk
        FOREIGN KEY (tenant_id, member_entitlement_id)
        REFERENCES catalog.entitlement (tenant_id, id),
    CONSTRAINT catalog_role_version_member_kind_ck
        CHECK (member_kind IN ('APPLICATION_ROLE','ENTITLEMENT')),
    CONSTRAINT catalog_role_version_member_shape_ck CHECK (
        (member_kind = 'APPLICATION_ROLE'
            AND member_role_id IS NOT NULL
            AND member_entitlement_id IS NULL)
        OR
        (member_kind = 'ENTITLEMENT'
            AND member_entitlement_id IS NOT NULL
            AND member_role_id IS NULL)
    ),
    CONSTRAINT catalog_role_version_member_ordinal_ck CHECK (ordinal >= 0)
);

CREATE INDEX catalog_role_version_role_idx
    ON catalog.role_version (tenant_id, role_id, version_number DESC);

CREATE INDEX catalog_role_member_child_role_idx
    ON catalog.role_version_member (tenant_id, member_role_id)
    WHERE member_role_id IS NOT NULL;

CREATE INDEX catalog_role_member_entitlement_idx
    ON catalog.role_version_member (tenant_id, member_entitlement_id)
    WHERE member_entitlement_id IS NOT NULL;

COMMENT ON TABLE catalog.role IS
    'Catalog-owned governed BUSINESS or APPLICATION access package.';
COMMENT ON TABLE catalog.role_version IS
    'Versioned Role composition. Activated and superseded content is immutable.';
COMMENT ON TABLE catalog.role_version_member IS
    'Normalized typed RoleVersion composition; graph/type validity is enforced by Catalog domain logic.';
