CREATE TABLE identity.principal (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    identity_id uuid NULL,
    application_target_id uuid NOT NULL,
    principal_kind varchar(32) NOT NULL,
    native_principal_key varchar(512) NOT NULL,
    lifecycle_state varchar(24) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT identity_principal_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT identity_principal_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_principal_identity_fk
        FOREIGN KEY (tenant_id, identity_id)
        REFERENCES identity.identity (tenant_id, id),
    CONSTRAINT identity_principal_target_native_uq
        UNIQUE (tenant_id, application_target_id, native_principal_key),
    CONSTRAINT identity_principal_kind_ck CHECK (principal_kind IN ('ACCOUNT')),
    CONSTRAINT identity_principal_state_ck CHECK (lifecycle_state IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT identity_principal_native_key_ck CHECK (btrim(native_principal_key) <> ''),
    CONSTRAINT identity_principal_revision_ck CHECK (revision > 0),
    CONSTRAINT identity_principal_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE INDEX identity_principal_identity_idx
    ON identity.principal (tenant_id, identity_id, application_target_id)
    WHERE identity_id IS NOT NULL;

CREATE INDEX identity_principal_target_idx
    ON identity.principal (tenant_id, application_target_id, lifecycle_state, id);

COMMENT ON TABLE identity.principal IS
    'Identity-owned technical representation/account on one ApplicationTarget. Provider observation does not create this authority automatically.';
