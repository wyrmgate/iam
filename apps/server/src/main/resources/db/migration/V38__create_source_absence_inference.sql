ALTER TABLE identity.source_import_run
    ADD COLUMN absence_trust varchar(16) NOT NULL DEFAULT 'UNTRUSTED',
    ADD COLUMN absence_trust_reason varchar(1024) NULL;

ALTER TABLE identity.source_import_run
    ADD CONSTRAINT identity_source_import_run_absence_trust_ck
        CHECK (absence_trust IN ('UNTRUSTED', 'TRUSTED')),
    ADD CONSTRAINT identity_source_import_run_absence_trust_reason_ck
        CHECK (
            absence_trust <> 'TRUSTED'
            OR (
                completeness = 'COMPLETE'
                AND absence_trust_reason IS NOT NULL
                AND btrim(absence_trust_reason) <> ''
            )
        );

CREATE TABLE identity.source_absence_policy_version (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    version_number bigint NOT NULL,
    max_inferred_transitions integer NOT NULL,
    state varchar(24) NOT NULL,
    created_at timestamptz NOT NULL,
    activated_at timestamptz NOT NULL,
    superseded_at timestamptz NULL,
    CONSTRAINT identity_source_absence_policy_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_source_absence_policy_version_uq
        UNIQUE (tenant_id, source_system_id, version_number),
    CONSTRAINT identity_source_absence_policy_source_fk
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES identity.source_system (tenant_id, id),
    CONSTRAINT identity_source_absence_policy_version_ck CHECK (version_number > 0),
    CONSTRAINT identity_source_absence_policy_ceiling_ck CHECK (max_inferred_transitions > 0),
    CONSTRAINT identity_source_absence_policy_state_ck
        CHECK (state IN ('ACTIVE', 'SUPERSEDED')),
    CONSTRAINT identity_source_absence_policy_time_ck CHECK (
        (state = 'ACTIVE' AND superseded_at IS NULL)
        OR (
            state = 'SUPERSEDED'
            AND superseded_at IS NOT NULL
            AND superseded_at >= activated_at
        )
    )
);

CREATE UNIQUE INDEX identity_source_absence_policy_one_active_idx
    ON identity.source_absence_policy_version (tenant_id, source_system_id)
    WHERE state = 'ACTIVE';

CREATE TABLE identity.source_absence_inference (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    import_run_id uuid NOT NULL,
    policy_version_id uuid NOT NULL,
    max_inferred_transitions integer NOT NULL,
    process_state varchar(24) NOT NULL,
    after_first_observed_at timestamptz NULL,
    after_source_record_id uuid NULL,
    processed_candidate_count bigint NOT NULL DEFAULT 0,
    inferred_transition_count bigint NOT NULL DEFAULT 0,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz NULL,
    CONSTRAINT identity_source_absence_inference_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_source_absence_inference_run_uq UNIQUE (tenant_id, import_run_id),
    CONSTRAINT identity_source_absence_inference_run_fk
        FOREIGN KEY (tenant_id, import_run_id, source_system_id)
        REFERENCES identity.source_import_run (tenant_id, id, source_system_id),
    CONSTRAINT identity_source_absence_inference_policy_fk
        FOREIGN KEY (tenant_id, policy_version_id)
        REFERENCES identity.source_absence_policy_version (tenant_id, id),
    CONSTRAINT identity_source_absence_inference_ceiling_ck CHECK (max_inferred_transitions > 0),
    CONSTRAINT identity_source_absence_inference_state_ck
        CHECK (process_state IN ('RUNNING', 'COMPLETED', 'SUPERSEDED', 'MANUAL_REQUIRED')),
    CONSTRAINT identity_source_absence_inference_checkpoint_ck CHECK (
        (after_first_observed_at IS NULL AND after_source_record_id IS NULL)
        OR (after_first_observed_at IS NOT NULL AND after_source_record_id IS NOT NULL)
    ),
    CONSTRAINT identity_source_absence_inference_counts_ck CHECK (
        processed_candidate_count >= 0
        AND inferred_transition_count >= 0
        AND inferred_transition_count <= max_inferred_transitions
    ),
    CONSTRAINT identity_source_absence_inference_revision_ck CHECK (revision > 0),
    CONSTRAINT identity_source_absence_inference_time_ck CHECK (
        updated_at >= created_at
        AND (
            (process_state = 'RUNNING' AND completed_at IS NULL)
            OR (process_state <> 'RUNNING' AND completed_at IS NOT NULL AND completed_at >= created_at)
        )
    )
);

CREATE INDEX identity_source_absence_candidate_idx
    ON identity.source_record (tenant_id, source_system_id, first_observed_at, id);

COMMENT ON COLUMN identity.source_import_run.absence_trust IS
    'Run-scoped evidence gate for destructive absence inference. COMPLETE alone is insufficient.';

COMMENT ON TABLE identity.source_absence_policy_version IS
    'Versioned Identity-owned policy enabling bounded inferred absence -> INACTIVE handling for one SourceSystem.';

COMMENT ON TABLE identity.source_absence_inference IS
    'Durable bounded Identity-owned process for trusted COMPLETE source absence inference.';
