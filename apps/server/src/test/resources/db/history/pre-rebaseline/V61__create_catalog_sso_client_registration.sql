CREATE TABLE catalog.sso_client_registration (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    application_id uuid NOT NULL,
    client_id varchar(200) NOT NULL,
    requires_governed_access boolean NOT NULL DEFAULT true,
    lifecycle_state varchar(24) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT catalog_sso_client_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT catalog_sso_client_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT catalog_sso_client_application_fk
        FOREIGN KEY (tenant_id, application_id)
        REFERENCES catalog.application (tenant_id, id),
    CONSTRAINT catalog_sso_client_client_id_uq UNIQUE (tenant_id, client_id),
    CONSTRAINT catalog_sso_client_client_id_ck CHECK (btrim(client_id) <> ''),
    CONSTRAINT catalog_sso_client_lifecycle_ck CHECK (lifecycle_state IN ('ACTIVE','RETIRED')),
    CONSTRAINT catalog_sso_client_revision_ck CHECK (revision > 0),
    CONSTRAINT catalog_sso_client_timestamp_order_ck CHECK (updated_at >= created_at)
);

CREATE TABLE catalog.sso_client_redirect_uri (
    tenant_id uuid NOT NULL,
    sso_client_registration_id uuid NOT NULL,
    redirect_uri varchar(2048) NOT NULL,
    PRIMARY KEY (tenant_id, sso_client_registration_id, redirect_uri),
    CONSTRAINT catalog_sso_client_redirect_parent_fk
        FOREIGN KEY (tenant_id, sso_client_registration_id)
        REFERENCES catalog.sso_client_registration (tenant_id, id)
        ON DELETE CASCADE,
    CONSTRAINT catalog_sso_client_redirect_uri_ck CHECK (btrim(redirect_uri) <> '')
);

CREATE TABLE catalog.sso_client_scope (
    tenant_id uuid NOT NULL,
    sso_client_registration_id uuid NOT NULL,
    scope varchar(32) NOT NULL,
    PRIMARY KEY (tenant_id, sso_client_registration_id, scope),
    CONSTRAINT catalog_sso_client_scope_parent_fk
        FOREIGN KEY (tenant_id, sso_client_registration_id)
        REFERENCES catalog.sso_client_registration (tenant_id, id)
        ON DELETE CASCADE,
    CONSTRAINT catalog_sso_client_scope_value_ck CHECK (scope IN ('openid','profile','email'))
);

CREATE INDEX catalog_sso_client_application_page_idx
    ON catalog.sso_client_registration (tenant_id, application_id, created_at, id);

CREATE INDEX catalog_sso_client_active_client_id_idx
    ON catalog.sso_client_registration (tenant_id, client_id)
    WHERE lifecycle_state = 'ACTIVE';

COMMENT ON TABLE catalog.sso_client_registration IS
    'Catalog-owned governed public OIDC relying-party registration; framework client records are projections only.';
COMMENT ON TABLE catalog.sso_client_redirect_uri IS
    'Exact registered redirect URIs for one governed SSO client; wildcard redirect matching is prohibited.';
COMMENT ON TABLE catalog.sso_client_scope IS
    'Curated protocol scopes for one governed SSO client; these values are not Wyrmgate AdministrativePermission.';
