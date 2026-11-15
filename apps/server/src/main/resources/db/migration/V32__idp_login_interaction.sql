-- ADR-0069: explicit Catalog local authentication account realm.
ALTER TABLE catalog.sso_client_registration ADD COLUMN authentication_target_id uuid;
ALTER TABLE catalog.sso_client_registration ADD CONSTRAINT sso_client_auth_target_fk
    FOREIGN KEY (tenant_id, authentication_target_id) REFERENCES catalog.application_target(tenant_id, id);
UPDATE catalog.sso_client_registration c SET authentication_target_id = p.console_target_id,
    revision = c.revision + 1, updated_at = GREATEST(c.updated_at, CURRENT_TIMESTAMP)
FROM platform.installation_bootstrap p
WHERE c.tenant_id = p.tenant_id AND c.id = p.console_sso_registration_id
  AND c.application_id = p.console_application_id AND c.client_id = p.console_client_id
  AND EXISTS (SELECT 1 FROM catalog.application_target t WHERE t.tenant_id = p.tenant_id
      AND t.id = p.console_target_id AND t.application_id = p.console_application_id);
CREATE TABLE platform.idp_login_interaction (
    interaction_hash varchar(64) PRIMARY KEY CHECK (interaction_hash ~ '^[0-9a-f]{64}$'),
    browser_hash varchar(64) NOT NULL CHECK (browser_hash ~ '^[0-9a-f]{64}$'),
    tenant_id uuid NOT NULL,
    client_registration_id uuid NOT NULL,
    client_revision bigint NOT NULL CHECK (client_revision > 0),
    authentication_target_id uuid NOT NULL,
    client_id varchar(200) NOT NULL,
    redirect_uri varchar(2048) NOT NULL,
    scopes varchar(256) NOT NULL,
    state_value varchar(512) NOT NULL,
    pkce_challenge varchar(43) NOT NULL,
    nonce_value varchar(512),
    expires_at timestamptz NOT NULL
);
CREATE INDEX idp_login_interaction_expiry_idx ON platform.idp_login_interaction(expires_at);
