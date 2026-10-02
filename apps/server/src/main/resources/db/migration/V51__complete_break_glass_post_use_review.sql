ALTER TABLE administration.administrative_break_glass_obligation
    DROP CONSTRAINT administrative_break_glass_obligation_state_ck;

ALTER TABLE administration.administrative_break_glass_obligation
    ADD CONSTRAINT administrative_break_glass_obligation_state_ck CHECK (
        state IN ('PENDING','COMPLETED','MANUAL_REQUIRED')
    );

ALTER TABLE administration.administrative_break_glass_obligation
    DROP CONSTRAINT administrative_break_glass_obligation_shape_ck;

ALTER TABLE administration.administrative_break_glass_obligation
    ADD CONSTRAINT administrative_break_glass_obligation_shape_ck CHECK (
        (state IN ('PENDING','MANUAL_REQUIRED') AND completed_at IS NULL)
        OR
        (state = 'COMPLETED' AND completed_at IS NOT NULL)
    );

CREATE TABLE administration.administrative_break_glass_review (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    break_glass_operation_id uuid NOT NULL,
    obligation_id uuid NOT NULL,
    reviewer_identity_id uuid NOT NULL,
    outcome varchar(48) NOT NULL,
    summary varchar(2048) NOT NULL,
    reviewed_at timestamptz NOT NULL,
    correlation_id uuid NULL,
    causation_id uuid NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT administrative_break_glass_review_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT administrative_break_glass_review_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT administrative_break_glass_review_operation_fk
        FOREIGN KEY (tenant_id, break_glass_operation_id)
        REFERENCES administration.administrative_break_glass_operation (tenant_id, id),
    CONSTRAINT administrative_break_glass_review_obligation_fk
        FOREIGN KEY (tenant_id, obligation_id)
        REFERENCES administration.administrative_break_glass_obligation (tenant_id, id),
    CONSTRAINT administrative_break_glass_review_one_per_operation_uq
        UNIQUE (tenant_id, break_glass_operation_id),
    CONSTRAINT administrative_break_glass_review_one_per_obligation_uq
        UNIQUE (tenant_id, obligation_id),
    CONSTRAINT administrative_break_glass_review_outcome_ck CHECK (
        outcome IN ('APPROVED_USE','POLICY_CONCERN','INCIDENT_FOLLOW_UP_REQUIRED')
    ),
    CONSTRAINT administrative_break_glass_review_summary_ck CHECK (
        btrim(summary) <> ''
    )
);

CREATE INDEX administrative_break_glass_review_reviewer_idx
    ON administration.administrative_break_glass_review
       (tenant_id, reviewer_identity_id, reviewed_at DESC, id DESC);

COMMENT ON TABLE administration.administrative_break_glass_review IS
    'Immutable Administration-owned post-use review evidence. Review never retroactively changes emergency authority validity.';
