CREATE INDEX credential_credential_principal_created_idx
    ON credential.credential (
        tenant_id, principal_id, created_at, id);

COMMENT ON INDEX credential.credential_credential_principal_created_idx IS
    'Supports deterministic bounded public Credential listing by Principal using immutable createdAt + id continuation.';
