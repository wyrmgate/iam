ALTER TABLE catalog.role_version
    ADD COLUMN revision bigint NOT NULL DEFAULT 1;

ALTER TABLE catalog.role_version
    ADD CONSTRAINT catalog_role_version_revision_ck
    CHECK (revision > 0);

COMMENT ON COLUMN catalog.role_version.revision IS
    'Optimistic authoritative revision for RoleVersion lifecycle transitions.';
