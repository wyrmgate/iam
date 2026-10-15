CREATE TABLE access.effective_access_support_role_version (
    tenant_id uuid NOT NULL,
    effective_access_support_id uuid NOT NULL,
    path_ordinal integer NOT NULL,
    role_version_id uuid NOT NULL,
    PRIMARY KEY (
        tenant_id,
        effective_access_support_id,
        path_ordinal
    ),
    CONSTRAINT effective_access_support_role_version_support_fk
        FOREIGN KEY (tenant_id, effective_access_support_id)
        REFERENCES access.effective_access_support (tenant_id, id)
        ON DELETE CASCADE,
    CONSTRAINT effective_access_support_role_version_ordinal_ck
        CHECK (path_ordinal >= 0)
);

CREATE INDEX effective_access_support_role_version_lookup_idx
    ON access.effective_access_support_role_version (
        tenant_id, role_version_id, effective_access_support_id);

COMMENT ON TABLE access.effective_access_support_role_version IS
    'Normalized ordered RoleVersion derivation path for one role-derived EffectiveAccess support row.';
