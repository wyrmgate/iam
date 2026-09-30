CREATE TABLE identity.source_absence_transition_evidence (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    source_record_id uuid NOT NULL,
    identity_link_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    import_run_id uuid NOT NULL,
    inference_id uuid NOT NULL,
    pre_identity_revision bigint NOT NULL,
    post_identity_revision bigint NOT NULL,
    transitioned_at timestamptz NOT NULL,
    CONSTRAINT identity_source_absence_transition_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_source_absence_transition_inference_fk
        FOREIGN KEY (tenant_id, inference_id)
        REFERENCES identity.source_absence_inference (tenant_id, id),
    CONSTRAINT identity_source_absence_transition_source_record_fk
        FOREIGN KEY (tenant_id, source_record_id)
        REFERENCES identity.source_record (tenant_id, id),
    CONSTRAINT identity_source_absence_transition_link_fk
        FOREIGN KEY (tenant_id, identity_link_id)
        REFERENCES identity.identity_link (tenant_id, id),
    CONSTRAINT identity_source_absence_transition_identity_fk
        FOREIGN KEY (tenant_id, identity_id)
        REFERENCES identity.identity (tenant_id, id),
    CONSTRAINT identity_source_absence_transition_revision_ck
        CHECK (pre_identity_revision > 0 AND post_identity_revision = pre_identity_revision + 1),
    CONSTRAINT identity_source_absence_transition_context_uq
        UNIQUE (tenant_id, inference_id, source_record_id, identity_id, post_identity_revision)
);

CREATE INDEX identity_source_absence_transition_restore_idx
    ON identity.source_absence_transition_evidence
        (tenant_id, source_record_id, identity_link_id, identity_id, post_identity_revision);

COMMENT ON TABLE identity.source_absence_transition_evidence IS
    'Immutable evidence that trusted COMPLETE source absence inference caused a specific Identity revision to become INACTIVE; used only to fence safe source-driven restoration.';
