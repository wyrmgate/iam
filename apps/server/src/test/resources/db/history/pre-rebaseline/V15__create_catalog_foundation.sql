CREATE TABLE catalog.application (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    code varchar(128) NOT NULL,
    name varchar(512) NOT NULL,
    lifecycle_state varchar(24) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT catalog_application_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT catalog_application_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT catalog_application_code_uq UNIQUE (tenant_id, code),
    CONSTRAINT catalog_application_code_ck CHECK (btrim(code) <> ''),
    CONSTRAINT catalog_application_name_ck CHECK (btrim(name) <> ''),
    CONSTRAINT catalog_application_lifecycle_ck CHECK (lifecycle_state IN ('ACTIVE','RETIRED')),
    CONSTRAINT catalog_application_revision_ck CHECK (revision > 0),
    CONSTRAINT catalog_application_timestamp_order_ck CHECK (updated_at >= created_at)
);

CREATE TABLE catalog.application_target (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    application_id uuid NOT NULL,
    code varchar(128) NOT NULL,
    lifecycle_state varchar(24) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT catalog_application_target_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT catalog_application_target_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT catalog_application_target_app_id_uq UNIQUE (tenant_id, id, application_id),
    CONSTRAINT catalog_application_target_code_uq UNIQUE (tenant_id, application_id, code),
    CONSTRAINT catalog_application_target_application_fk
        FOREIGN KEY (tenant_id, application_id)
        REFERENCES catalog.application (tenant_id, id),
    CONSTRAINT catalog_application_target_code_ck CHECK (btrim(code) <> ''),
    CONSTRAINT catalog_application_target_lifecycle_ck CHECK (lifecycle_state IN ('ACTIVE','RETIRED')),
    CONSTRAINT catalog_application_target_revision_ck CHECK (revision > 0),
    CONSTRAINT catalog_application_target_timestamp_order_ck CHECK (updated_at >= created_at)
);

CREATE TABLE catalog.entitlement (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    application_id uuid NOT NULL,
    application_target_id uuid NULL,
    code varchar(256) NOT NULL,
    native_key varchar(1024) NULL,
    entitlement_type varchar(64) NOT NULL,
    lifecycle_state varchar(24) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT catalog_entitlement_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT catalog_entitlement_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT catalog_entitlement_code_uq UNIQUE (tenant_id, application_id, code),
    CONSTRAINT catalog_entitlement_application_fk
        FOREIGN KEY (tenant_id, application_id)
        REFERENCES catalog.application (tenant_id, id),
    CONSTRAINT catalog_entitlement_target_fk
        FOREIGN KEY (tenant_id, application_target_id, application_id)
        REFERENCES catalog.application_target (tenant_id, id, application_id),
    CONSTRAINT catalog_entitlement_code_ck CHECK (btrim(code) <> ''),
    CONSTRAINT catalog_entitlement_native_key_ck CHECK (native_key IS NULL OR btrim(native_key) <> ''),
    CONSTRAINT catalog_entitlement_type_ck CHECK (btrim(entitlement_type) <> ''),
    CONSTRAINT catalog_entitlement_lifecycle_ck CHECK (lifecycle_state IN ('ACTIVE','RETIRED')),
    CONSTRAINT catalog_entitlement_revision_ck CHECK (revision > 0),
    CONSTRAINT catalog_entitlement_timestamp_order_ck CHECK (updated_at >= created_at)
);

CREATE INDEX catalog_application_tenant_lifecycle_idx
    ON catalog.application (tenant_id, lifecycle_state, created_at, id);

CREATE INDEX catalog_application_target_tenant_app_idx
    ON catalog.application_target (tenant_id, application_id, lifecycle_state, created_at, id);

CREATE INDEX catalog_entitlement_tenant_app_idx
    ON catalog.entitlement (tenant_id, application_id, lifecycle_state, created_at, id);

CREATE INDEX catalog_entitlement_target_idx
    ON catalog.entitlement (tenant_id, application_target_id, lifecycle_state, created_at, id)
    WHERE application_target_id IS NOT NULL;

COMMENT ON TABLE catalog.application IS
    'Catalog-owned authoritative governable business application/capability.';
COMMENT ON TABLE catalog.application_target IS
    'Catalog-owned authoritative technical target/environment belonging to one Application.';
COMMENT ON TABLE catalog.entitlement IS
    'Catalog-owned authoritative smallest governable technical access unit; provider observations never mutate this table directly.';
