-- ADR-0067: bind exact establishing subject/realm revisions without inventing old proof.
ALTER TABLE platform.idp_browser_session
    ADD COLUMN tenant_revision bigint,
    ADD COLUMN principal_revision bigint,
    ADD COLUMN identity_revision bigint;

UPDATE platform.idp_browser_session
SET revoked_at = GREATEST(created_at, CURRENT_TIMESTAMP)
WHERE revoked_at IS NULL;

ALTER TABLE platform.idp_browser_session
    ADD CONSTRAINT idp_browser_session_subject_revisions_ck CHECK (
        (tenant_revision IS NULL OR tenant_revision > 0)
        AND (principal_revision IS NULL OR principal_revision > 0)
        AND (identity_revision IS NULL OR identity_revision > 0)
        AND (revoked_at IS NOT NULL OR (
            tenant_revision IS NOT NULL AND principal_revision IS NOT NULL AND identity_revision IS NOT NULL
        ))
    );
