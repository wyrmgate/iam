-- ADR-0079: explicit route and disposable, browser-bound upstream interaction.
ALTER TABLE integration.federation_trust ADD COLUMN client_registration_id uuid;
CREATE UNIQUE INDEX federation_trust_active_client_uq ON integration.federation_trust(tenant_id,client_registration_id)
 WHERE lifecycle_state='ACTIVE' AND client_registration_id IS NOT NULL;
CREATE TABLE platform.idp_federation_interaction (
 id uuid PRIMARY KEY, state_hash varchar(64) NOT NULL UNIQUE, browser_hash varchar(64) NOT NULL,
 tenant_id uuid NOT NULL, trust_id uuid NOT NULL, trust_revision bigint NOT NULL CHECK(trust_revision>0),
 registration_id uuid NOT NULL, registration_revision bigint NOT NULL CHECK(registration_revision>0),
 target_id uuid NOT NULL, client_id varchar(200) NOT NULL, redirect_uri text NOT NULL,
 scope_value varchar(256) NOT NULL, downstream_state varchar(512) NOT NULL,
 code_challenge varchar(43) NOT NULL, nonce_value varchar(512),
 expires_at timestamptz NOT NULL,
 CHECK(state_hash ~ '^[0-9a-f]{64}$' AND browser_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idp_federation_interaction_expiry_idx ON platform.idp_federation_interaction(expires_at,id);
