CREATE TABLE access.desired_principal_state (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    application_target_id uuid NOT NULL,
    desired_state varchar(24) NOT NULL,
    desired_revision bigint NOT NULL,
    source_generation bigint NOT NULL,
    computed_at timestamptz NOT NULL,
    CONSTRAINT access_desired_principal_state_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT access_desired_principal_state_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT access_desired_principal_state_revision_ck CHECK (desired_revision > 0),
    CONSTRAINT access_desired_principal_state_generation_ck CHECK (source_generation > 0),
    CONSTRAINT access_desired_principal_state_state_ck
        CHECK (desired_state IN ('PRESENT', 'ABSENT'))
);

CREATE TABLE access.desired_grant_state (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    application_target_id uuid NOT NULL,
    entitlement_id uuid NOT NULL,
    principal_id uuid NULL,
    desired_state varchar(24) NOT NULL,
    desired_revision bigint NOT NULL,
    source_generation bigint NOT NULL,
    computed_at timestamptz NOT NULL,
    CONSTRAINT access_desired_grant_state_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT access_desired_grant_state_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT access_desired_grant_state_revision_ck CHECK (desired_revision > 0),
    CONSTRAINT access_desired_grant_state_generation_ck CHECK (source_generation > 0),
    CONSTRAINT access_desired_grant_state_state_ck
        CHECK (desired_state IN ('PRESENT', 'ABSENT'))
);

CREATE INDEX access_desired_principal_state_target_idx
    ON access.desired_principal_state (tenant_id, application_target_id, identity_id);

CREATE INDEX access_desired_grant_state_target_idx
    ON access.desired_grant_state (tenant_id, application_target_id, identity_id, entitlement_id);
