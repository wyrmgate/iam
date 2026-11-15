-- ADR-0078: mutually exclusive local Credential and federated trust/link anchors.
ALTER TABLE platform.idp_browser_session
 ALTER COLUMN credential_id DROP NOT NULL,
 ALTER COLUMN credential_revision DROP NOT NULL,
 ADD COLUMN federation_trust_id uuid,
 ADD COLUMN federation_trust_revision bigint CHECK(federation_trust_revision>0),
 ADD COLUMN federation_link_id uuid,
 ADD COLUMN federation_link_revision bigint CHECK(federation_link_revision>0),
 ADD COLUMN federation_authenticated_at timestamptz,
 ADD COLUMN federation_proof_expires_at timestamptz,
 ADD CONSTRAINT idp_session_anchor_ck CHECK (
 (credential_id IS NOT NULL AND credential_revision IS NOT NULL AND federation_trust_id IS NULL
   AND federation_trust_revision IS NULL AND federation_link_id IS NULL AND federation_link_revision IS NULL
   AND federation_authenticated_at IS NULL AND federation_proof_expires_at IS NULL)
 OR (credential_id IS NULL AND credential_revision IS NULL AND federation_trust_id IS NOT NULL
   AND federation_trust_revision IS NOT NULL AND federation_link_id IS NOT NULL AND federation_link_revision IS NOT NULL
   AND federation_authenticated_at IS NOT NULL AND federation_proof_expires_at IS NOT NULL
   AND tenant_revision IS NOT NULL AND principal_revision IS NOT NULL AND identity_revision IS NOT NULL
   AND federation_authenticated_at<=created_at AND federation_proof_expires_at>=absolute_expires_at));
COMMENT ON COLUMN platform.idp_browser_session.credential_id IS 'Local PASSWORD anchor only; federated sessions carry exact trust/link references instead.';
