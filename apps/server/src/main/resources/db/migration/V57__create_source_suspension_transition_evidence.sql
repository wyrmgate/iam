CREATE TABLE identity.source_suspension_transition_evidence (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    source_record_id uuid NOT NULL,
    identity_link_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    lifecycle_policy_version_id uuid NOT NULL,
    pre_identity_revision bigint NOT NULL,
    post_identity_revision bigint NOT NULL,
    suspended_at timestamptz NOT NULL,
    CONSTRAINT identity_source_suspension_transition_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_source_suspension_transition_source_fk
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES identity.source_system (tenant_id, id),
    CONSTRAINT identity_source_suspension_transition_source_record_fk
        FOREIGN KEY (tenant_id, source_record_id)
        REFERENCES identity.source_record (tenant_id, id),
    CONSTRAINT identity_source_suspension_transition_link_fk
        FOREIGN KEY (tenant_id, identity_link_id)
        REFERENCES identity.identity_link (tenant_id, id),
    CONSTRAINT identity_source_suspension_transition_identity_fk
        FOREIGN KEY (tenant_id, identity_id)
        REFERENCES identity.identity (tenant_id, id),
    CONSTRAINT identity_source_suspension_transition_policy_fk
        FOREIGN KEY (tenant_id, lifecycle_policy_version_id)
        REFERENCES identity.source_lifecycle_policy_version (tenant_id, id),
    CONSTRAINT identity_source_suspension_transition_revision_ck
        CHECK (pre_identity_revision > 0 AND post_identity_revision = pre_identity_revision + 1),
    CONSTRAINT identity_source_suspension_transition_context_uq
        UNIQUE (tenant_id, source_record_id, identity_id, post_identity_revision)
);

CREATE INDEX identity_source_suspension_transition_restore_idx
    ON identity.source_suspension_transition_evidence
        (tenant_id, source_record_id, identity_link_id, identity_id, post_identity_revision);

COMMENT ON TABLE identity.source_suspension_transition_evidence IS
    'Immutable evidence that explicit source lifecycle policy caused a specific Identity revision to become SUSPENDED; used only to fence same-source current-revision restoration.';
