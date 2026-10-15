CREATE TABLE identity.identity (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    identity_type varchar(16) NOT NULL,
    lifecycle_state varchar(24) NOT NULL,
    display_name varchar(512) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT identity_identity_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT identity_identity_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_identity_profile_ref_uq UNIQUE (tenant_id, id, identity_type),
    CONSTRAINT identity_identity_type_ck
        CHECK (identity_type IN ('PERSON', 'SERVICE', 'WORKLOAD')),
    CONSTRAINT identity_identity_lifecycle_ck
        CHECK (lifecycle_state IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'INACTIVE', 'DECOMMISSIONED')),
    CONSTRAINT identity_identity_display_name_ck CHECK (btrim(display_name) <> ''),
    CONSTRAINT identity_identity_revision_ck CHECK (revision > 0),
    CONSTRAINT identity_identity_timestamp_order_ck CHECK (updated_at >= created_at)
);

COMMENT ON TABLE identity.identity IS
    'Identity-owned authoritative governed subject. Identity type is stable for normal operations and determines exactly one compatible typed profile.';

CREATE TABLE identity.person_profile (
    identity_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    identity_type varchar(16) NOT NULL DEFAULT 'PERSON',
    created_at timestamptz NOT NULL,
    CONSTRAINT identity_person_profile_tenant_id_uq UNIQUE (tenant_id, identity_id),
    CONSTRAINT identity_person_profile_type_ck CHECK (identity_type = 'PERSON'),
    CONSTRAINT identity_person_profile_identity_fk
        FOREIGN KEY (tenant_id, identity_id, identity_type)
        REFERENCES identity.identity (tenant_id, id, identity_type)
);

CREATE TABLE identity.service_profile (
    identity_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    identity_type varchar(16) NOT NULL DEFAULT 'SERVICE',
    created_at timestamptz NOT NULL,
    CONSTRAINT identity_service_profile_tenant_id_uq UNIQUE (tenant_id, identity_id),
    CONSTRAINT identity_service_profile_type_ck CHECK (identity_type = 'SERVICE'),
    CONSTRAINT identity_service_profile_identity_fk
        FOREIGN KEY (tenant_id, identity_id, identity_type)
        REFERENCES identity.identity (tenant_id, id, identity_type)
);

CREATE TABLE identity.workload_profile (
    identity_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    identity_type varchar(16) NOT NULL DEFAULT 'WORKLOAD',
    created_at timestamptz NOT NULL,
    CONSTRAINT identity_workload_profile_tenant_id_uq UNIQUE (tenant_id, identity_id),
    CONSTRAINT identity_workload_profile_type_ck CHECK (identity_type = 'WORKLOAD'),
    CONSTRAINT identity_workload_profile_identity_fk
        FOREIGN KEY (tenant_id, identity_id, identity_type)
        REFERENCES identity.identity (tenant_id, id, identity_type)
);

COMMENT ON TABLE identity.person_profile IS
    'Typed PERSON profile boundary. Profile-specific fields are added only when governed requirements define them.';
COMMENT ON TABLE identity.service_profile IS
    'Typed SERVICE profile boundary. Profile-specific fields are added only when governed requirements define them.';
COMMENT ON TABLE identity.workload_profile IS
    'Typed WORKLOAD profile boundary. Profile-specific fields are added only when governed requirements define them.';

CREATE INDEX identity_identity_tenant_lifecycle_idx
    ON identity.identity (tenant_id, lifecycle_state, id);
