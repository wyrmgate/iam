CREATE TABLE identity.identity_merge_operation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    survivor_identity_id uuid NOT NULL,
    absorbed_identity_id uuid NOT NULL,
    survivor_revision_before bigint NOT NULL,
    absorbed_revision_before bigint NOT NULL,
    moved_link_count integer NOT NULL,
    moved_principal_count integer NOT NULL,
    reason varchar(1024) NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid NULL,
    completed_at timestamptz NOT NULL,
    CONSTRAINT identity_merge_operation_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_merge_operation_absorbed_uq UNIQUE (tenant_id, absorbed_identity_id),
    CONSTRAINT identity_merge_operation_survivor_fk
        FOREIGN KEY (tenant_id, survivor_identity_id)
        REFERENCES identity.identity (tenant_id, id),
    CONSTRAINT identity_merge_operation_absorbed_fk
        FOREIGN KEY (tenant_id, absorbed_identity_id)
        REFERENCES identity.identity (tenant_id, id),
    CONSTRAINT identity_merge_operation_distinct_ck
        CHECK (survivor_identity_id <> absorbed_identity_id),
    CONSTRAINT identity_merge_operation_revision_ck
        CHECK (survivor_revision_before > 0 AND absorbed_revision_before > 0),
    CONSTRAINT identity_merge_operation_count_ck
        CHECK (moved_link_count >= 0 AND moved_principal_count >= 0),
    CONSTRAINT identity_merge_operation_reason_ck CHECK (btrim(reason) <> '')
);

CREATE TABLE identity.identity_split_operation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    source_identity_id uuid NOT NULL,
    new_identity_id uuid NOT NULL,
    source_revision_before bigint NOT NULL,
    reason varchar(1024) NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid NULL,
    completed_at timestamptz NOT NULL,
    CONSTRAINT identity_split_operation_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_split_operation_new_identity_uq UNIQUE (tenant_id, new_identity_id),
    CONSTRAINT identity_split_operation_source_fk
        FOREIGN KEY (tenant_id, source_identity_id)
        REFERENCES identity.identity (tenant_id, id),
    CONSTRAINT identity_split_operation_new_fk
        FOREIGN KEY (tenant_id, new_identity_id)
        REFERENCES identity.identity (tenant_id, id),
    CONSTRAINT identity_split_operation_distinct_ck
        CHECK (source_identity_id <> new_identity_id),
    CONSTRAINT identity_split_operation_revision_ck CHECK (source_revision_before > 0),
    CONSTRAINT identity_split_operation_reason_ck CHECK (btrim(reason) <> '')
);

CREATE TABLE identity.identity_split_source_record (
    tenant_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    source_record_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, operation_id, source_record_id),
    CONSTRAINT identity_split_source_record_operation_fk
        FOREIGN KEY (tenant_id, operation_id)
        REFERENCES identity.identity_split_operation (tenant_id, id),
    CONSTRAINT identity_split_source_record_source_fk
        FOREIGN KEY (tenant_id, source_record_id)
        REFERENCES identity.source_record (tenant_id, id)
);

CREATE TABLE identity.identity_split_principal (
    tenant_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, operation_id, principal_id),
    CONSTRAINT identity_split_principal_operation_fk
        FOREIGN KEY (tenant_id, operation_id)
        REFERENCES identity.identity_split_operation (tenant_id, id),
    CONSTRAINT identity_split_principal_principal_fk
        FOREIGN KEY (tenant_id, principal_id)
        REFERENCES identity.principal (tenant_id, id)
);

CREATE INDEX identity_merge_operation_survivor_idx
    ON identity.identity_merge_operation (tenant_id, survivor_identity_id, completed_at DESC, id);

CREATE INDEX identity_split_operation_source_idx
    ON identity.identity_split_operation (tenant_id, source_identity_id, completed_at DESC, id);

COMMENT ON TABLE identity.identity_merge_operation IS
    'Immutable Identity-owned evidence for explicit merge. Foreign-capability authority is not moved.';

COMMENT ON TABLE identity.identity_split_operation IS
    'Immutable Identity-owned evidence for explicit split into a new PENDING Identity.';

COMMENT ON TABLE identity.identity_split_source_record IS
    'Immutable selected SourceRecord relationship evidence for one split.';

COMMENT ON TABLE identity.identity_split_principal IS
    'Immutable selected Principal relationship evidence for one split.';
