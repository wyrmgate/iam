ALTER TABLE catalog.sso_client_registration
    ADD CONSTRAINT catalog_sso_client_public_client_id_uq UNIQUE (client_id);

CREATE TABLE platform.idp_authorization_code (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    client_registration_id uuid NOT NULL,
    client_registration_revision bigint NOT NULL,
    application_id uuid NOT NULL,
    client_id varchar(200) NOT NULL,
    browser_session_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    code_hash varchar(64) NOT NULL,
    redirect_uri varchar(2048) NOT NULL,
    scopes varchar(128) NOT NULL,
    pkce_challenge varchar(64) NOT NULL,
    nonce_value varchar(512) NULL,
    auth_time timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz NULL,
    CONSTRAINT idp_authorization_code_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT idp_authorization_code_session_fk
        FOREIGN KEY (tenant_id, browser_session_id)
        REFERENCES platform.idp_browser_session (tenant_id, id),
    CONSTRAINT idp_authorization_code_hash_uq
        UNIQUE (tenant_id, code_hash),
    CONSTRAINT idp_authorization_code_client_revision_ck
        CHECK (client_registration_revision > 0),
    CONSTRAINT idp_authorization_code_hash_ck
        CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT idp_authorization_pkce_ck
        CHECK (pkce_challenge ~ '^[A-Za-z0-9_-]{43}$'),
    CONSTRAINT idp_authorization_time_ck
        CHECK (
            auth_time <= created_at
            AND expires_at > created_at
            AND (consumed_at IS NULL OR consumed_at >= created_at)
        )
);

CREATE INDEX idp_authorization_code_expiry_idx
    ON platform.idp_authorization_code (tenant_id, expires_at)
    WHERE consumed_at IS NULL;

COMMENT ON TABLE platform.idp_authorization_code IS
    'Platform-owned short-lived OAuth authorization-code state; not IAM governance or access authority.';
COMMENT ON COLUMN platform.idp_authorization_code.code_hash IS
    'SHA-256 digest of a high-entropy single-use authorization code. Raw codes are never persisted.';
COMMENT ON COLUMN platform.idp_authorization_code.client_registration_id IS
    'Stable cross-capability reference to Catalog SSO registration; intentionally no database foreign key.';
