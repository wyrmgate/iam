CREATE TABLE authentication.authorization_request (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    client_id uuid NOT NULL,
    request_secret_digest varchar(128) NOT NULL UNIQUE,
    redirect_uri varchar(2048) NOT NULL,
    requested_scopes varchar(1000) NOT NULL,
    client_state varchar(2048) NULL,
    nonce_value varchar(512) NULL,
    pkce_challenge varchar(128) NOT NULL,
    pkce_method varchar(16) NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT authentication_authorization_request_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT authentication_authorization_request_client_fk
        FOREIGN KEY (tenant_id, client_id)
        REFERENCES authentication.client (tenant_id, id),
    CONSTRAINT authentication_authorization_request_pkce_ck
        CHECK (pkce_method = 'S256' AND btrim(pkce_challenge) <> ''),
    CONSTRAINT authentication_authorization_request_time_ck
        CHECK (expires_at > created_at AND (consumed_at IS NULL OR consumed_at >= created_at))
);

CREATE INDEX authentication_authorization_request_expiry_idx
    ON authentication.authorization_request (expires_at, id)
    WHERE consumed_at IS NULL;

CREATE TABLE authentication.authorization_code (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    client_id uuid NOT NULL,
    session_id uuid NOT NULL,
    code_secret_digest varchar(128) NOT NULL UNIQUE,
    redirect_uri varchar(2048) NOT NULL,
    authorized_scopes varchar(1000) NOT NULL,
    nonce_value varchar(512) NULL,
    pkce_challenge varchar(128) NOT NULL,
    pkce_method varchar(16) NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT authentication_authorization_code_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT authentication_authorization_code_client_fk
        FOREIGN KEY (tenant_id, client_id)
        REFERENCES authentication.client (tenant_id, id),
    CONSTRAINT authentication_authorization_code_session_fk
        FOREIGN KEY (tenant_id, session_id)
        REFERENCES authentication.session (tenant_id, id),
    CONSTRAINT authentication_authorization_code_pkce_ck
        CHECK (pkce_method = 'S256' AND btrim(pkce_challenge) <> ''),
    CONSTRAINT authentication_authorization_code_time_ck
        CHECK (expires_at > created_at AND (consumed_at IS NULL OR consumed_at >= created_at))
);

CREATE INDEX authentication_authorization_code_expiry_idx
    ON authentication.authorization_code (expires_at, id)
    WHERE consumed_at IS NULL;

COMMENT ON TABLE authentication.authorization_request IS
    'Short-lived OIDC authorization transaction state. Only a digest of the random browser transaction secret is persisted.';
COMMENT ON TABLE authentication.authorization_code IS
    'Single-use Authorization Code + PKCE state. Only a digest of the authorization code is persisted; access/ID JWT values are not stored.';