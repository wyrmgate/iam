CREATE TABLE platform.idp_browser_session (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    credential_id uuid NOT NULL,
    credential_revision bigint NOT NULL,
    token_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    idle_expires_at timestamptz NOT NULL,
    absolute_expires_at timestamptz NOT NULL,
    revoked_at timestamptz NULL,
    CONSTRAINT idp_browser_session_tenant_fk
        FOREIGN KEY (tenant_id)
        REFERENCES platform.tenant (id),
    CONSTRAINT idp_browser_session_tenant_id_uq
        UNIQUE (tenant_id, id),
    CONSTRAINT idp_browser_session_token_hash_uq
        UNIQUE (tenant_id, token_hash),
    CONSTRAINT idp_browser_session_credential_revision_ck
        CHECK (credential_revision > 0),
    CONSTRAINT idp_browser_session_token_hash_ck
        CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT idp_browser_session_time_ck CHECK (
        last_seen_at >= created_at
        AND idle_expires_at > created_at
        AND absolute_expires_at > created_at
        AND idle_expires_at <= absolute_expires_at
        AND (revoked_at IS NULL OR revoked_at >= created_at)
    )
);

CREATE INDEX idp_browser_session_expiry_idx
    ON platform.idp_browser_session (
        tenant_id, idle_expires_at, absolute_expires_at)
    WHERE revoked_at IS NULL;

COMMENT ON TABLE platform.idp_browser_session IS
    'Platform-owned first-party IdP browser authentication sessions. This is authentication context, not IAM governance or access authority.';
COMMENT ON COLUMN platform.idp_browser_session.token_hash IS
    'SHA-256 digest of a high-entropy opaque browser token. Raw session tokens are never persisted.';
COMMENT ON COLUMN platform.idp_browser_session.credential_id IS
    'Stable cross-capability reference to the Credential that established the session; intentionally no database foreign key.';
