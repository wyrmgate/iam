-- Identity v3 first-class Organization and relationship semantics.
-- Organizations are tenant-local business structure, not isolation roots.
-- Relationships remain normalized authoritative state with temporal validity and typed provenance.

CREATE TABLE identity.organization (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    name character varying(512) NOT NULL,
    organization_type character varying(128) NOT NULL,
    parent_id uuid,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_organization_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_organization_name_ck CHECK (btrim(name) <> ''),
    CONSTRAINT identity_organization_type_ck CHECK (btrim(organization_type) <> ''),
    CONSTRAINT identity_organization_parent_self_ck CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT identity_organization_revision_ck CHECK (revision > 0),
    CONSTRAINT identity_organization_time_ck CHECK (updated_at >= created_at),
    CONSTRAINT identity_organization_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES tenancy.tenant(id),
    CONSTRAINT identity_organization_parent_fk
        FOREIGN KEY (tenant_id, parent_id)
        REFERENCES identity.organization(tenant_id, id)
);

CREATE INDEX identity_organization_parent_idx
    ON identity.organization (tenant_id, parent_id, id);

CREATE TABLE identity.manager_relationship (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    subject_identity_id uuid NOT NULL,
    manager_identity_id uuid NOT NULL,
    valid_from timestamp with time zone NOT NULL,
    valid_until timestamp with time zone,
    provenance_kind character varying(32) NOT NULL,
    source_record_id uuid,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_manager_relationship_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_manager_relationship_distinct_ck
        CHECK (subject_identity_id <> manager_identity_id),
    CONSTRAINT identity_manager_relationship_validity_ck
        CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT identity_manager_relationship_provenance_ck CHECK (
        (provenance_kind = 'MANUAL' AND source_record_id IS NULL)
        OR (provenance_kind = 'SOURCE_RECORD' AND source_record_id IS NOT NULL)),
    CONSTRAINT identity_manager_relationship_revision_ck CHECK (revision > 0),
    CONSTRAINT identity_manager_relationship_time_ck CHECK (updated_at >= created_at),
    CONSTRAINT identity_manager_relationship_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES tenancy.tenant(id),
    CONSTRAINT identity_manager_relationship_subject_fk
        FOREIGN KEY (tenant_id, subject_identity_id)
        REFERENCES identity.identity(tenant_id, id),
    CONSTRAINT identity_manager_relationship_manager_fk
        FOREIGN KEY (tenant_id, manager_identity_id)
        REFERENCES identity.identity(tenant_id, id),
    CONSTRAINT identity_manager_relationship_source_fk
        FOREIGN KEY (tenant_id, source_record_id)
        REFERENCES identity.source_record(tenant_id, id)
);

CREATE INDEX identity_manager_relationship_subject_idx
    ON identity.manager_relationship (tenant_id, subject_identity_id, valid_from DESC, id);
CREATE INDEX identity_manager_relationship_manager_idx
    ON identity.manager_relationship (tenant_id, manager_identity_id, valid_from DESC, id);

CREATE TABLE identity.organization_membership (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    subject_identity_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    valid_from timestamp with time zone NOT NULL,
    valid_until timestamp with time zone,
    provenance_kind character varying(32) NOT NULL,
    source_record_id uuid,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_organization_membership_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_organization_membership_validity_ck
        CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT identity_organization_membership_provenance_ck CHECK (
        (provenance_kind = 'MANUAL' AND source_record_id IS NULL)
        OR (provenance_kind = 'SOURCE_RECORD' AND source_record_id IS NOT NULL)),
    CONSTRAINT identity_organization_membership_revision_ck CHECK (revision > 0),
    CONSTRAINT identity_organization_membership_time_ck CHECK (updated_at >= created_at),
    CONSTRAINT identity_organization_membership_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES tenancy.tenant(id),
    CONSTRAINT identity_organization_membership_subject_fk
        FOREIGN KEY (tenant_id, subject_identity_id)
        REFERENCES identity.identity(tenant_id, id),
    CONSTRAINT identity_organization_membership_organization_fk
        FOREIGN KEY (tenant_id, organization_id)
        REFERENCES identity.organization(tenant_id, id),
    CONSTRAINT identity_organization_membership_source_fk
        FOREIGN KEY (tenant_id, source_record_id)
        REFERENCES identity.source_record(tenant_id, id)
);

CREATE INDEX identity_organization_membership_subject_idx
    ON identity.organization_membership (tenant_id, subject_identity_id, valid_from DESC, id);
CREATE INDEX identity_organization_membership_organization_idx
    ON identity.organization_membership (tenant_id, organization_id, valid_from DESC, id);

CREATE TABLE identity.ownership_relationship (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    subject_identity_id uuid NOT NULL,
    owner_identity_id uuid NOT NULL,
    valid_from timestamp with time zone NOT NULL,
    valid_until timestamp with time zone,
    provenance_kind character varying(32) NOT NULL,
    source_record_id uuid,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_ownership_relationship_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_ownership_relationship_distinct_ck
        CHECK (subject_identity_id <> owner_identity_id),
    CONSTRAINT identity_ownership_relationship_validity_ck
        CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT identity_ownership_relationship_provenance_ck CHECK (
        (provenance_kind = 'MANUAL' AND source_record_id IS NULL)
        OR (provenance_kind = 'SOURCE_RECORD' AND source_record_id IS NOT NULL)),
    CONSTRAINT identity_ownership_relationship_revision_ck CHECK (revision > 0),
    CONSTRAINT identity_ownership_relationship_time_ck CHECK (updated_at >= created_at),
    CONSTRAINT identity_ownership_relationship_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES tenancy.tenant(id),
    CONSTRAINT identity_ownership_relationship_subject_fk
        FOREIGN KEY (tenant_id, subject_identity_id)
        REFERENCES identity.identity(tenant_id, id),
    CONSTRAINT identity_ownership_relationship_owner_fk
        FOREIGN KEY (tenant_id, owner_identity_id)
        REFERENCES identity.identity(tenant_id, id),
    CONSTRAINT identity_ownership_relationship_source_fk
        FOREIGN KEY (tenant_id, source_record_id)
        REFERENCES identity.source_record(tenant_id, id)
);

CREATE INDEX identity_ownership_relationship_subject_idx
    ON identity.ownership_relationship (tenant_id, subject_identity_id, valid_from DESC, id);
CREATE INDEX identity_ownership_relationship_owner_idx
    ON identity.ownership_relationship (tenant_id, owner_identity_id, valid_from DESC, id);

COMMENT ON TABLE identity.organization IS
    'Identity-owned tenant-local business/governance structure. Organization is not a Tenant isolation boundary.';
COMMENT ON TABLE identity.manager_relationship IS
    'First-class temporal manager relationship with typed manual or SourceRecord provenance.';
COMMENT ON TABLE identity.organization_membership IS
    'First-class temporal Identity-to-Organization membership with typed provenance.';
COMMENT ON TABLE identity.ownership_relationship IS
    'First-class temporal accountable ownership relationship with typed provenance.';
