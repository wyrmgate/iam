CREATE TABLE credential.local_password_verifier (
    reference_key varchar(64) PRIMARY KEY,
    tenant_id uuid NOT NULL,
    credential_id uuid NOT NULL,
    algorithm varchar(64) NOT NULL,
    iterations integer NOT NULL,
    salt bytea NOT NULL,
    verifier bytea NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT credential_local_password_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT credential_local_password_credential_uq UNIQUE (credential_id),
    CONSTRAINT credential_local_password_iterations_ck CHECK (iterations > 0),
    CONSTRAINT credential_local_password_salt_ck CHECK (octet_length(salt) >= 16),
    CONSTRAINT credential_local_password_verifier_ck CHECK (octet_length(verifier) >= 32)
);

COMMENT ON TABLE credential.local_password_verifier IS
    'Credential-private one-way verifier material for Wyrmgate first-party PASSWORD authentication. This table is not ordinary IAM state and is never exposed through ordinary APIs/events/audit.';
COMMENT ON COLUMN credential.local_password_verifier.reference_key IS
    'Opaque system-managed SecretReference key. Ordinary Credential creation cannot select this provider type.';
COMMENT ON COLUMN credential.local_password_verifier.verifier IS
    'Salted one-way verifier material; never plaintext password or reversible secret material.';
