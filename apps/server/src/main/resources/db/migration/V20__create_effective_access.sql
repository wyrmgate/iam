CREATE TABLE access.effective_access (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    entitlement_id uuid NOT NULL,
    principal_constraint_key varchar(512) NOT NULL,
    support_count integer NOT NULL,
    computed_at timestamptz NOT NULL,
    projection_generation bigint NOT NULL,
    CONSTRAINT effective_access_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT effective_access_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT effective_access_tuple_uq
        UNIQUE (tenant_id, identity_id, entitlement_id, principal_constraint_key),
    CONSTRAINT effective_access_constraint_key_ck
        CHECK (btrim(principal_constraint_key) <> ''),
    CONSTRAINT effective_access_support_count_ck CHECK (support_count > 0),
    CONSTRAINT effective_access_generation_ck CHECK (projection_generation > 0)
);

CREATE TABLE access.effective_access_support (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    effective_access_id uuid NOT NULL,
    access_assignment_id uuid NOT NULL,
    role_version_id uuid NULL,
    path_hash varchar(128) NOT NULL,
    path_depth integer NOT NULL,
    CONSTRAINT effective_access_support_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT effective_access_support_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT effective_access_support_effective_fk
        FOREIGN KEY (tenant_id, effective_access_id)
        REFERENCES access.effective_access (tenant_id, id)
        ON DELETE CASCADE,
    CONSTRAINT effective_access_support_path_uq
        UNIQUE (tenant_id, effective_access_id, access_assignment_id, path_hash),
    CONSTRAINT effective_access_support_depth_ck CHECK (path_depth >= 0)
);

CREATE INDEX effective_access_identity_entitlement_idx
    ON access.effective_access (tenant_id, identity_id, entitlement_id);

CREATE INDEX effective_access_entitlement_identity_idx
    ON access.effective_access (tenant_id, entitlement_id, identity_id);

CREATE INDEX effective_access_support_assignment_idx
    ON access.effective_access_support (tenant_id, access_assignment_id);

COMMENT ON TABLE access.effective_access IS
    'Access-owned rebuildable entitlement-level projection derived from authoritative AccessAssignments.';
COMMENT ON TABLE access.effective_access_support IS
    'Explainable support paths for EffectiveAccess; current direct assignments use path_depth 0 and no role_version_id.';
