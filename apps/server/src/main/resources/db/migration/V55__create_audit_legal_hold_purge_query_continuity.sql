CREATE TABLE audit.audit_legal_hold (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    occurred_from timestamptz NOT NULL,
    occurred_until timestamptz NOT NULL,
    actor_filter_id uuid NULL,
    action_type_filter varchar(128) NULL,
    resource_type_filter varchar(128) NULL,
    resource_id_filter uuid NULL,
    outcome_filter varchar(32) NULL,
    correlation_id_filter uuid NULL,
    reason_code varchar(128) NOT NULL,
    case_reference varchar(256) NOT NULL,
    state varchar(24) NOT NULL,
    created_by_identity_id uuid NOT NULL,
    released_by_identity_id uuid NULL,
    correlation_id uuid NULL,
    causation_id uuid NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    released_at timestamptz NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT audit_legal_hold_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT audit_legal_hold_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT audit_legal_hold_window_ck CHECK (occurred_until > occurred_from),
    CONSTRAINT audit_legal_hold_action_filter_ck CHECK (
        action_type_filter IS NULL OR btrim(action_type_filter) <> ''
    ),
    CONSTRAINT audit_legal_hold_resource_filter_ck CHECK (
        resource_type_filter IS NULL OR btrim(resource_type_filter) <> ''
    ),
    CONSTRAINT audit_legal_hold_outcome_ck CHECK (
        outcome_filter IS NULL OR outcome_filter IN ('SUCCESS','DENIED','FAILURE')
    ),
    CONSTRAINT audit_legal_hold_reason_ck CHECK (btrim(reason_code) <> ''),
    CONSTRAINT audit_legal_hold_case_ck CHECK (btrim(case_reference) <> ''),
    CONSTRAINT audit_legal_hold_state_ck CHECK (state IN ('ACTIVE','RELEASED')),
    CONSTRAINT audit_legal_hold_release_ck CHECK (
        (state = 'ACTIVE' AND released_by_identity_id IS NULL AND released_at IS NULL)
        OR
        (state = 'RELEASED' AND released_by_identity_id IS NOT NULL AND released_at IS NOT NULL)
    )
);

CREATE INDEX audit_legal_hold_active_range_idx
    ON audit.audit_legal_hold (tenant_id, occurred_from, occurred_until, id)
    WHERE state = 'ACTIVE';

CREATE TABLE audit.audit_archived_record_index (
    tenant_id uuid NOT NULL,
    record_id uuid NOT NULL,
    archive_segment_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    actor_id uuid NULL,
    action_type varchar(128) NOT NULL,
    resource_type varchar(128) NOT NULL,
    resource_id uuid NULL,
    outcome varchar(32) NOT NULL,
    correlation_id uuid NULL,
    causation_id uuid NULL,
    archived_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, record_id),
    CONSTRAINT audit_archived_record_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT audit_archived_record_segment_fk
        FOREIGN KEY (tenant_id, archive_segment_id)
        REFERENCES audit.audit_archive_segment (tenant_id, id),
    CONSTRAINT audit_archived_record_action_ck CHECK (btrim(action_type) <> ''),
    CONSTRAINT audit_archived_record_resource_ck CHECK (btrim(resource_type) <> ''),
    CONSTRAINT audit_archived_record_outcome_ck CHECK (outcome IN ('SUCCESS','DENIED','FAILURE'))
);

CREATE INDEX audit_archived_record_time_idx
    ON audit.audit_archived_record_index (tenant_id, occurred_at DESC, record_id DESC);
CREATE INDEX audit_archived_record_actor_time_idx
    ON audit.audit_archived_record_index (tenant_id, actor_id, occurred_at DESC, record_id DESC)
    WHERE actor_id IS NOT NULL;
CREATE INDEX audit_archived_record_resource_time_idx
    ON audit.audit_archived_record_index
        (tenant_id, resource_type, resource_id, occurred_at DESC, record_id DESC);
CREATE INDEX audit_archived_record_correlation_time_idx
    ON audit.audit_archived_record_index
        (tenant_id, correlation_id, occurred_at DESC, record_id DESC)
    WHERE correlation_id IS NOT NULL;

CREATE TABLE audit.audit_purge_operation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    retention_policy_version_id uuid NOT NULL,
    archive_segment_id uuid NOT NULL,
    requested_by_identity_id uuid NOT NULL,
    approved_by_identity_id uuid NULL,
    occurred_from timestamptz NOT NULL,
    occurred_until timestamptz NOT NULL,
    snapshot_recorded_at timestamptz NOT NULL,
    actor_filter_id uuid NULL,
    action_type_filter varchar(128) NULL,
    resource_type_filter varchar(128) NULL,
    resource_id_filter uuid NULL,
    outcome_filter varchar(32) NULL,
    correlation_id_filter uuid NULL,
    reason_code varchar(128) NOT NULL,
    state varchar(24) NOT NULL,
    deleted_record_count bigint NOT NULL DEFAULT 0,
    failure_code varchar(128) NULL,
    correlation_id uuid NULL,
    causation_id uuid NULL,
    revision bigint NOT NULL DEFAULT 1,
    approved_at timestamptz NULL,
    completed_at timestamptz NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT audit_purge_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT audit_purge_policy_fk
        FOREIGN KEY (tenant_id, retention_policy_version_id)
        REFERENCES audit.audit_retention_policy_version (tenant_id, id),
    CONSTRAINT audit_purge_archive_fk
        FOREIGN KEY (tenant_id, archive_segment_id)
        REFERENCES audit.audit_archive_segment (tenant_id, id),
    CONSTRAINT audit_purge_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT audit_purge_window_ck CHECK (occurred_until > occurred_from),
    CONSTRAINT audit_purge_action_filter_ck CHECK (
        action_type_filter IS NULL OR btrim(action_type_filter) <> ''
    ),
    CONSTRAINT audit_purge_resource_filter_ck CHECK (
        resource_type_filter IS NULL OR btrim(resource_type_filter) <> ''
    ),
    CONSTRAINT audit_purge_outcome_ck CHECK (
        outcome_filter IS NULL OR outcome_filter IN ('SUCCESS','DENIED','FAILURE')
    ),
    CONSTRAINT audit_purge_reason_ck CHECK (btrim(reason_code) <> ''),
    CONSTRAINT audit_purge_state_ck CHECK (
        state IN ('REQUESTED','APPROVED','RUNNING','SUCCEEDED','FAILED','BLOCKED')
    ),
    CONSTRAINT audit_purge_dual_control_ck CHECK (
        approved_by_identity_id IS NULL
        OR approved_by_identity_id <> requested_by_identity_id
    ),
    CONSTRAINT audit_purge_approval_ck CHECK (
        (state = 'REQUESTED'
            AND approved_by_identity_id IS NULL
            AND approved_at IS NULL
            AND completed_at IS NULL)
        OR
        (state IN ('APPROVED','RUNNING')
            AND approved_by_identity_id IS NOT NULL
            AND approved_at IS NOT NULL
            AND completed_at IS NULL)
        OR
        (state IN ('SUCCEEDED','FAILED','BLOCKED')
            AND completed_at IS NOT NULL)
    ),
    CONSTRAINT audit_purge_count_ck CHECK (deleted_record_count >= 0)
);

CREATE INDEX audit_purge_state_idx
    ON audit.audit_purge_operation (tenant_id, state, created_at, id);

CREATE OR REPLACE FUNCTION audit.audit_row_matches_purge(
    p_tenant_id uuid,
    p_record_id uuid,
    p_occurred_at timestamptz,
    p_recorded_at timestamptz,
    p_actor_id uuid,
    p_action_type varchar,
    p_resource_type varchar,
    p_resource_id uuid,
    p_outcome varchar,
    p_correlation_id uuid,
    p_purge_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
AS $audit$
    SELECT EXISTS (
        SELECT 1
        FROM audit.audit_purge_operation p
        JOIN audit.audit_retention_policy_version policy
          ON policy.tenant_id = p.tenant_id
         AND policy.id = p.retention_policy_version_id
        JOIN audit.audit_archive_segment segment
          ON segment.tenant_id = p.tenant_id
         AND segment.id = p.archive_segment_id
        JOIN audit.audit_archived_record_index archived
          ON archived.tenant_id = p.tenant_id
         AND archived.record_id = p_record_id
         AND archived.archive_segment_id = p.archive_segment_id
        WHERE p.id = p_purge_id
          AND p.tenant_id = p_tenant_id
          AND p.state = 'RUNNING'
          AND p.approved_by_identity_id IS NOT NULL
          AND p.approved_by_identity_id <> p.requested_by_identity_id
          AND segment.state = 'SUCCEEDED'
          AND segment.verified_at IS NOT NULL
          AND segment.occurred_from <= p.occurred_from
          AND segment.occurred_until >= p.occurred_until
          AND segment.snapshot_recorded_at >= p.snapshot_recorded_at
          AND p_occurred_at >= p.occurred_from
          AND p_occurred_at < p.occurred_until
          AND p_recorded_at <= p.snapshot_recorded_at
          AND p_occurred_at <= clock_timestamp()
                - make_interval(secs => policy.minimum_online_retention_seconds::int)
          AND (p.actor_filter_id IS NULL OR p_actor_id = p.actor_filter_id)
          AND (p.action_type_filter IS NULL OR p_action_type = p.action_type_filter)
          AND (p.resource_type_filter IS NULL OR p_resource_type = p.resource_type_filter)
          AND (p.resource_id_filter IS NULL OR p_resource_id = p.resource_id_filter)
          AND (p.outcome_filter IS NULL OR p_outcome = p.outcome_filter)
          AND (p.correlation_id_filter IS NULL OR p_correlation_id = p.correlation_id_filter)
          AND NOT EXISTS (
              SELECT 1
              FROM audit.audit_legal_hold h
              WHERE h.tenant_id = p_tenant_id
                AND h.state = 'ACTIVE'
                AND p_occurred_at >= h.occurred_from
                AND p_occurred_at < h.occurred_until
                AND (h.actor_filter_id IS NULL OR p_actor_id = h.actor_filter_id)
                AND (h.action_type_filter IS NULL OR p_action_type = h.action_type_filter)
                AND (h.resource_type_filter IS NULL OR p_resource_type = h.resource_type_filter)
                AND (h.resource_id_filter IS NULL OR p_resource_id = h.resource_id_filter)
                AND (h.outcome_filter IS NULL OR p_outcome = h.outcome_filter)
                AND (h.correlation_id_filter IS NULL OR p_correlation_id = h.correlation_id_filter)
          )
    );
$audit$;

CREATE OR REPLACE FUNCTION audit.reject_audit_record_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $audit$
DECLARE
    purge_setting text;
    purge_id uuid;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'AuditRecord is append-only';
    END IF;

    purge_setting := current_setting('wyrmgate.audit_purge_operation_id', true);
    IF purge_setting IS NULL OR btrim(purge_setting) = '' THEN
        RAISE EXCEPTION 'AuditRecord is append-only';
    END IF;

    BEGIN
        purge_id := purge_setting::uuid;
    EXCEPTION WHEN invalid_text_representation THEN
        RAISE EXCEPTION 'invalid Audit purge fence';
    END;

    IF NOT audit.audit_row_matches_purge(
        OLD.tenant_id,
        OLD.id,
        OLD.occurred_at,
        OLD.recorded_at,
        OLD.actor_id,
        OLD.action_type,
        OLD.resource_type,
        OLD.resource_id,
        OLD.outcome,
        OLD.correlation_id,
        purge_id) THEN
        RAISE EXCEPTION 'AuditRecord purge prerequisites are not satisfied';
    END IF;

    RETURN OLD;
END;
$audit$;

COMMENT ON TABLE audit.audit_legal_hold IS
    'Audit-owned legal/retention hold. ACTIVE matching evidence blocks destructive purge regardless of retention age.';
COMMENT ON TABLE audit.audit_archived_record_index IS
    'Immutable derived Audit query projection for records copied into a verified archive segment; not business authority.';
COMMENT ON TABLE audit.audit_purge_operation IS
    'Dual-control Audit-owned destructive purge process. Retention policy alone never grants deletion authority.';
