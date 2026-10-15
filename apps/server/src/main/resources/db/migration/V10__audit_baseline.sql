-- Audit capability current-state persistence baseline.
-- Generated once from the verified pre-rebaseline schema, then maintained as reviewed source.
-- Some function bodies reference tables created later in this same capability baseline.
SET check_function_bodies = false;

CREATE FUNCTION audit.audit_row_matches_purge(p_tenant_id uuid, p_record_id uuid, p_occurred_at timestamp with time zone, p_recorded_at timestamp with time zone, p_actor_id uuid, p_action_type character varying, p_resource_type character varying, p_resource_id uuid, p_outcome character varying, p_correlation_id uuid, p_material_snapshot jsonb, p_integrity_metadata jsonb, p_purge_id uuid) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
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
                - (policy.minimum_online_retention_seconds * interval '1 second')
          AND archived.material_snapshot IS NOT DISTINCT FROM p_material_snapshot
          AND archived.integrity_metadata IS NOT DISTINCT FROM p_integrity_metadata
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
$$;

CREATE FUNCTION audit.reject_audit_record_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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
        OLD.material_snapshot,
        OLD.integrity_metadata,
        purge_id) THEN
        RAISE EXCEPTION 'AuditRecord purge prerequisites are not satisfied';
    END IF;

    RETURN OLD;
END;
$$;

CREATE FUNCTION audit.reject_evidence_history_delete() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'Audit evidence/process history cannot be deleted';
END;
$$;

CREATE FUNCTION audit.reject_immutable_evidence_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'immutable Audit evidence cannot be updated or deleted';
END;
$$;

CREATE FUNCTION audit.validate_integrity_metadata(value jsonb) RETURNS boolean
    LANGUAGE sql IMMUTABLE
    AS $_$
    SELECT value IS NULL OR (
        jsonb_typeof(value) = 'object'
        AND value ->> 'schemaVersion' = 'audit-integrity-v1'
        AND value ->> 'algorithm' = 'SHA-256'
        AND (value - ARRAY[
            'schemaVersion',
            'algorithm',
            'contentSha256',
            'materialSnapshotSha256'
        ]) = '{}'::jsonb
        AND value ->> 'contentSha256' ~ '^[0-9a-f]{64}$'
        AND (
            NOT (value ? 'materialSnapshotSha256')
            OR value ->> 'materialSnapshotSha256' ~ '^[0-9a-f]{64}$'
        )
    );
$_$;

CREATE FUNCTION audit.validate_material_snapshot(value jsonb) RETURNS boolean
    LANGUAGE sql IMMUTABLE
    AS $$
    SELECT value IS NULL OR (
        jsonb_typeof(value) = 'object'
        AND value ->> 'schemaVersion' = 'audit-material-v1'
        AND (value - ARRAY[
            'schemaVersion',
            'actorDisplayLabel',
            'resourceDisplayLabel',
            'resourceRevision',
            'resourceState'
        ]) = '{}'::jsonb
        AND (
            NOT (value ? 'actorDisplayLabel')
            OR jsonb_typeof(value -> 'actorDisplayLabel') = 'string'
        )
        AND (
            NOT (value ? 'resourceDisplayLabel')
            OR jsonb_typeof(value -> 'resourceDisplayLabel') = 'string'
        )
        AND (
            NOT (value ? 'resourceRevision')
            OR (
                jsonb_typeof(value -> 'resourceRevision') = 'number'
                AND (value ->> 'resourceRevision')::bigint > 0
            )
        )
        AND (
            NOT (value ? 'resourceState')
            OR jsonb_typeof(value -> 'resourceState') = 'string'
        )
    );
$$;

CREATE TABLE audit.audit_archive_segment (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    retention_policy_version_id uuid NOT NULL,
    occurred_from timestamp with time zone NOT NULL,
    occurred_until timestamp with time zone NOT NULL,
    snapshot_recorded_at timestamp with time zone NOT NULL,
    schema_version character varying(64) NOT NULL,
    state character varying(32) NOT NULL,
    continuation_occurred_at timestamp with time zone,
    continuation_id uuid,
    record_count bigint DEFAULT 0 NOT NULL,
    byte_count bigint DEFAULT 0 NOT NULL,
    sha256_hex character varying(64),
    artifact_reference character varying(512),
    failure_code character varying(128),
    correlation_id uuid,
    causation_id uuid,
    revision bigint DEFAULT 1 NOT NULL,
    verified_at timestamp with time zone,
    minimum_retain_until timestamp with time zone,
    completed_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT audit_archive_segment_continuation_ck CHECK ((((continuation_occurred_at IS NULL) AND (continuation_id IS NULL)) OR ((continuation_occurred_at IS NOT NULL) AND (continuation_id IS NOT NULL)))),
    CONSTRAINT audit_archive_segment_counts_ck CHECK (((record_count >= 0) AND (byte_count >= 0))),
    CONSTRAINT audit_archive_segment_schema_ck CHECK ((btrim((schema_version)::text) <> ''::text)),
    CONSTRAINT audit_archive_segment_sha_ck CHECK (((sha256_hex IS NULL) OR ((sha256_hex)::text ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT audit_archive_segment_state_ck CHECK (((state)::text = ANY ((ARRAY['REQUESTED'::character varying, 'RUNNING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT audit_archive_segment_terminal_ck CHECK (((((state)::text = 'SUCCEEDED'::text) AND (artifact_reference IS NOT NULL) AND (sha256_hex IS NOT NULL) AND (verified_at IS NOT NULL) AND (minimum_retain_until IS NOT NULL) AND (completed_at IS NOT NULL) AND (failure_code IS NULL)) OR (((state)::text = 'FAILED'::text) AND (failure_code IS NOT NULL) AND (artifact_reference IS NULL) AND (verified_at IS NULL) AND (minimum_retain_until IS NULL) AND (completed_at IS NOT NULL)) OR (((state)::text = ANY ((ARRAY['REQUESTED'::character varying, 'RUNNING'::character varying])::text[])) AND (verified_at IS NULL) AND (minimum_retain_until IS NULL) AND (completed_at IS NULL) AND (failure_code IS NULL)))),
    CONSTRAINT audit_archive_segment_window_ck CHECK ((occurred_until > occurred_from))
);

COMMENT ON TABLE audit.audit_archive_segment IS 'Audit-owned durable immutable archive-segment generation metadata. Source audit_record rows remain append-only.';

COMMENT ON COLUMN audit.audit_archive_segment.artifact_reference IS 'Opaque deployment adapter reference; never a credential, signed URL, or public authoritative identifier.';

CREATE TABLE audit.audit_archived_record_index (
    tenant_id uuid NOT NULL,
    record_id uuid NOT NULL,
    archive_segment_id uuid NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    recorded_at timestamp with time zone NOT NULL,
    actor_id uuid,
    action_type character varying(128) NOT NULL,
    resource_type character varying(128) NOT NULL,
    resource_id uuid,
    outcome character varying(32) NOT NULL,
    correlation_id uuid,
    causation_id uuid,
    archived_at timestamp with time zone NOT NULL,
    material_snapshot jsonb,
    integrity_metadata jsonb,
    CONSTRAINT audit_archived_record_action_ck CHECK ((btrim((action_type)::text) <> ''::text)),
    CONSTRAINT audit_archived_record_integrity_metadata_v1_ck CHECK (audit.validate_integrity_metadata(integrity_metadata)),
    CONSTRAINT audit_archived_record_material_snapshot_v1_ck CHECK (audit.validate_material_snapshot(material_snapshot)),
    CONSTRAINT audit_archived_record_outcome_ck CHECK (((outcome)::text = ANY ((ARRAY['SUCCESS'::character varying, 'DENIED'::character varying, 'FAILURE'::character varying])::text[]))),
    CONSTRAINT audit_archived_record_resource_ck CHECK ((btrim((resource_type)::text) <> ''::text))
);

COMMENT ON TABLE audit.audit_archived_record_index IS 'Immutable derived Audit query projection for records copied into a verified archive segment; not business authority.';

CREATE TABLE audit.audit_export_operation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    requested_by_identity_id uuid NOT NULL,
    actor_filter_id uuid,
    action_type_filter character varying(128),
    resource_type_filter character varying(128),
    resource_id_filter uuid,
    outcome_filter character varying(32),
    correlation_id_filter uuid,
    occurred_from timestamp with time zone NOT NULL,
    occurred_until timestamp with time zone NOT NULL,
    snapshot_recorded_at timestamp with time zone NOT NULL,
    schema_version character varying(64) NOT NULL,
    state character varying(32) NOT NULL,
    continuation_occurred_at timestamp with time zone,
    continuation_id uuid,
    record_count bigint DEFAULT 0 NOT NULL,
    byte_count bigint DEFAULT 0 NOT NULL,
    sha256_hex character varying(64),
    artifact_reference character varying(512),
    artifact_expires_at timestamp with time zone,
    failure_code character varying(128),
    revision bigint DEFAULT 1 NOT NULL,
    completed_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT audit_export_operation_continuation_ck CHECK ((((continuation_occurred_at IS NULL) AND (continuation_id IS NULL)) OR ((continuation_occurred_at IS NOT NULL) AND (continuation_id IS NOT NULL)))),
    CONSTRAINT audit_export_operation_counts_ck CHECK (((record_count >= 0) AND (byte_count >= 0))),
    CONSTRAINT audit_export_operation_outcome_ck CHECK (((outcome_filter IS NULL) OR ((outcome_filter)::text = ANY ((ARRAY['SUCCESS'::character varying, 'DENIED'::character varying, 'FAILURE'::character varying])::text[])))),
    CONSTRAINT audit_export_operation_schema_ck CHECK ((btrim((schema_version)::text) <> ''::text)),
    CONSTRAINT audit_export_operation_sha_ck CHECK (((sha256_hex IS NULL) OR ((sha256_hex)::text ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT audit_export_operation_state_ck CHECK (((state)::text = ANY ((ARRAY['REQUESTED'::character varying, 'RUNNING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT audit_export_operation_terminal_ck CHECK (((((state)::text = 'SUCCEEDED'::text) AND (artifact_reference IS NOT NULL) AND (sha256_hex IS NOT NULL) AND (completed_at IS NOT NULL) AND (failure_code IS NULL)) OR (((state)::text = 'FAILED'::text) AND (failure_code IS NOT NULL) AND (completed_at IS NOT NULL) AND (artifact_reference IS NULL)) OR (((state)::text = ANY ((ARRAY['REQUESTED'::character varying, 'RUNNING'::character varying])::text[])) AND (completed_at IS NULL) AND (failure_code IS NULL)))),
    CONSTRAINT audit_export_operation_window_ck CHECK ((occurred_until > occurred_from))
);

COMMENT ON TABLE audit.audit_export_operation IS 'Audit-owned durable ADR-0034 export process. Artifact bytes remain outside the authoritative database.';

COMMENT ON COLUMN audit.audit_export_operation.artifact_reference IS 'Opaque deployment adapter reference; never a credential or public provider-native object identifier.';

CREATE TABLE audit.audit_legal_hold (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    occurred_from timestamp with time zone NOT NULL,
    occurred_until timestamp with time zone NOT NULL,
    actor_filter_id uuid,
    action_type_filter character varying(128),
    resource_type_filter character varying(128),
    resource_id_filter uuid,
    outcome_filter character varying(32),
    correlation_id_filter uuid,
    reason_code character varying(128) NOT NULL,
    case_reference character varying(256) NOT NULL,
    state character varying(24) NOT NULL,
    created_by_identity_id uuid NOT NULL,
    released_by_identity_id uuid,
    correlation_id uuid,
    causation_id uuid,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    released_at timestamp with time zone,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT audit_legal_hold_action_filter_ck CHECK (((action_type_filter IS NULL) OR (btrim((action_type_filter)::text) <> ''::text))),
    CONSTRAINT audit_legal_hold_case_ck CHECK ((btrim((case_reference)::text) <> ''::text)),
    CONSTRAINT audit_legal_hold_outcome_ck CHECK (((outcome_filter IS NULL) OR ((outcome_filter)::text = ANY ((ARRAY['SUCCESS'::character varying, 'DENIED'::character varying, 'FAILURE'::character varying])::text[])))),
    CONSTRAINT audit_legal_hold_reason_ck CHECK ((btrim((reason_code)::text) <> ''::text)),
    CONSTRAINT audit_legal_hold_release_ck CHECK (((((state)::text = 'ACTIVE'::text) AND (released_by_identity_id IS NULL) AND (released_at IS NULL)) OR (((state)::text = 'RELEASED'::text) AND (released_by_identity_id IS NOT NULL) AND (released_at IS NOT NULL)))),
    CONSTRAINT audit_legal_hold_resource_filter_ck CHECK (((resource_type_filter IS NULL) OR (btrim((resource_type_filter)::text) <> ''::text))),
    CONSTRAINT audit_legal_hold_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'RELEASED'::character varying])::text[]))),
    CONSTRAINT audit_legal_hold_window_ck CHECK ((occurred_until > occurred_from))
);

COMMENT ON TABLE audit.audit_legal_hold IS 'Audit-owned legal/retention hold. ACTIVE matching evidence blocks destructive purge regardless of retention age.';

CREATE TABLE audit.audit_purge_operation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    retention_policy_version_id uuid NOT NULL,
    archive_segment_id uuid NOT NULL,
    requested_by_identity_id uuid NOT NULL,
    approved_by_identity_id uuid,
    occurred_from timestamp with time zone NOT NULL,
    occurred_until timestamp with time zone NOT NULL,
    snapshot_recorded_at timestamp with time zone NOT NULL,
    actor_filter_id uuid,
    action_type_filter character varying(128),
    resource_type_filter character varying(128),
    resource_id_filter uuid,
    outcome_filter character varying(32),
    correlation_id_filter uuid,
    reason_code character varying(128) NOT NULL,
    state character varying(24) NOT NULL,
    deleted_record_count bigint DEFAULT 0 NOT NULL,
    failure_code character varying(128),
    correlation_id uuid,
    causation_id uuid,
    revision bigint DEFAULT 1 NOT NULL,
    approved_at timestamp with time zone,
    completed_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT audit_purge_action_filter_ck CHECK (((action_type_filter IS NULL) OR (btrim((action_type_filter)::text) <> ''::text))),
    CONSTRAINT audit_purge_approval_ck CHECK (((((state)::text = 'REQUESTED'::text) AND (approved_by_identity_id IS NULL) AND (approved_at IS NULL) AND (completed_at IS NULL)) OR (((state)::text = ANY ((ARRAY['APPROVED'::character varying, 'RUNNING'::character varying])::text[])) AND (approved_by_identity_id IS NOT NULL) AND (approved_at IS NOT NULL) AND (completed_at IS NULL)) OR (((state)::text = ANY ((ARRAY['SUCCEEDED'::character varying, 'FAILED'::character varying, 'BLOCKED'::character varying])::text[])) AND (completed_at IS NOT NULL)))),
    CONSTRAINT audit_purge_count_ck CHECK ((deleted_record_count >= 0)),
    CONSTRAINT audit_purge_dual_control_ck CHECK (((approved_by_identity_id IS NULL) OR (approved_by_identity_id <> requested_by_identity_id))),
    CONSTRAINT audit_purge_outcome_ck CHECK (((outcome_filter IS NULL) OR ((outcome_filter)::text = ANY ((ARRAY['SUCCESS'::character varying, 'DENIED'::character varying, 'FAILURE'::character varying])::text[])))),
    CONSTRAINT audit_purge_reason_ck CHECK ((btrim((reason_code)::text) <> ''::text)),
    CONSTRAINT audit_purge_resource_filter_ck CHECK (((resource_type_filter IS NULL) OR (btrim((resource_type_filter)::text) <> ''::text))),
    CONSTRAINT audit_purge_state_ck CHECK (((state)::text = ANY ((ARRAY['REQUESTED'::character varying, 'APPROVED'::character varying, 'RUNNING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying, 'BLOCKED'::character varying])::text[]))),
    CONSTRAINT audit_purge_window_ck CHECK ((occurred_until > occurred_from))
);

COMMENT ON TABLE audit.audit_purge_operation IS 'Dual-control Audit-owned destructive purge process. Retention policy alone never grants deletion authority.';

CREATE TABLE audit.audit_record (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    recorded_at timestamp with time zone NOT NULL,
    actor_id uuid,
    action_type character varying(128) NOT NULL,
    resource_type character varying(128) NOT NULL,
    resource_id uuid,
    outcome character varying(32) NOT NULL,
    correlation_id uuid,
    causation_id uuid,
    material_snapshot jsonb,
    integrity_metadata jsonb,
    CONSTRAINT audit_record_action_type_ck CHECK ((btrim((action_type)::text) <> ''::text)),
    CONSTRAINT audit_record_integrity_metadata_v1_ck CHECK (audit.validate_integrity_metadata(integrity_metadata)),
    CONSTRAINT audit_record_material_snapshot_v1_ck CHECK (audit.validate_material_snapshot(material_snapshot)),
    CONSTRAINT audit_record_outcome_ck CHECK (((outcome)::text = ANY ((ARRAY['SUCCESS'::character varying, 'DENIED'::character varying, 'FAILURE'::character varying])::text[]))),
    CONSTRAINT audit_record_resource_type_ck CHECK ((btrim((resource_type)::text) <> ''::text))
);

COMMENT ON TABLE audit.audit_record IS 'Append-only Audit-owned security/governance evidence. Domain facts, evidence snapshots and operational logs remain separate.';

COMMENT ON COLUMN audit.audit_record.material_snapshot IS 'Closed ADR-0036 audit-material-v1 explanatory display snapshot; stable IDs remain authoritative references.';

COMMENT ON COLUMN audit.audit_record.integrity_metadata IS 'Derived ADR-0036 audit-integrity-v1 SHA-256 tamper-detection metadata; never authorization or ordering authority.';

CREATE VIEW audit.audit_record_query AS
 SELECT r.tenant_id,
    r.id,
    r.occurred_at,
    r.recorded_at,
    r.actor_id,
    r.action_type,
    r.resource_type,
    r.resource_id,
    r.outcome,
    r.correlation_id,
    r.causation_id,
    r.material_snapshot,
    r.integrity_metadata
   FROM audit.audit_record r
UNION ALL
 SELECT archived.tenant_id,
    archived.record_id AS id,
    archived.occurred_at,
    archived.recorded_at,
    archived.actor_id,
    archived.action_type,
    archived.resource_type,
    archived.resource_id,
    archived.outcome,
    archived.correlation_id,
    archived.causation_id,
    archived.material_snapshot,
    archived.integrity_metadata
   FROM ( SELECT DISTINCT ON (i.tenant_id, i.record_id) i.tenant_id,
            i.record_id,
            i.occurred_at,
            i.recorded_at,
            i.actor_id,
            i.action_type,
            i.resource_type,
            i.resource_id,
            i.outcome,
            i.correlation_id,
            i.causation_id,
            i.material_snapshot,
            i.integrity_metadata,
            s.completed_at,
            s.id AS segment_id
           FROM (audit.audit_archived_record_index i
             JOIN audit.audit_archive_segment s ON (((s.tenant_id = i.tenant_id) AND (s.id = i.archive_segment_id) AND ((s.state)::text = 'SUCCEEDED'::text))))
          WHERE (NOT (EXISTS ( SELECT 1
                   FROM audit.audit_record online
                  WHERE ((online.tenant_id = i.tenant_id) AND (online.id = i.record_id)))))
          ORDER BY i.tenant_id, i.record_id, s.completed_at DESC, s.id DESC) archived;

CREATE TABLE audit.audit_retention_policy_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    policy_version bigint NOT NULL,
    export_artifact_retention_seconds bigint CONSTRAINT audit_retention_policy_vers_export_artifact_retention__not_null NOT NULL,
    archive_eligible_after_seconds bigint CONSTRAINT audit_retention_policy_vers_archive_eligible_after_sec_not_null NOT NULL,
    minimum_online_retention_seconds bigint CONSTRAINT audit_retention_policy_vers_minimum_online_retention_s_not_null NOT NULL,
    minimum_archive_retention_seconds bigint CONSTRAINT audit_retention_policy_vers_minimum_archive_retention__not_null NOT NULL,
    effective_from timestamp with time zone NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT audit_retention_policy_durations_ck CHECK (((export_artifact_retention_seconds > 0) AND (archive_eligible_after_seconds > 0) AND (minimum_online_retention_seconds > 0) AND (minimum_archive_retention_seconds > 0))),
    CONSTRAINT audit_retention_policy_version_ck CHECK ((policy_version > 0))
);

COMMENT ON TABLE audit.audit_retention_policy_version IS 'Immutable tenant-scoped ADR-0034 retention-policy versions. Durations are reviewed deployment/security/legal inputs; policy grants no deletion authority.';

CREATE TABLE audit.evidence_snapshot (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    recorded_at timestamp with time zone NOT NULL,
    actor_id uuid,
    snapshot_type character varying(64) NOT NULL,
    subject_resource_type character varying(128) NOT NULL,
    subject_resource_id uuid NOT NULL,
    subject_revision bigint,
    subject_display_label character varying(256),
    policy_resource_type character varying(128),
    policy_resource_id uuid,
    policy_revision bigint,
    policy_display_label character varying(256),
    related_resource_type character varying(128),
    related_resource_id uuid,
    related_revision bigint,
    related_display_label character varying(256),
    decision_label character varying(64) NOT NULL,
    correlation_id uuid,
    causation_id uuid,
    CONSTRAINT evidence_snapshot_decision_ck CHECK ((btrim((decision_label)::text) <> ''::text)),
    CONSTRAINT evidence_snapshot_policy_pair_ck CHECK ((((policy_resource_type IS NULL) AND (policy_resource_id IS NULL) AND (policy_revision IS NULL)) OR ((policy_resource_type IS NOT NULL) AND (btrim((policy_resource_type)::text) <> ''::text) AND (policy_resource_id IS NOT NULL) AND ((policy_revision IS NULL) OR (policy_revision > 0))))),
    CONSTRAINT evidence_snapshot_related_pair_ck CHECK ((((related_resource_type IS NULL) AND (related_resource_id IS NULL) AND (related_revision IS NULL)) OR ((related_resource_type IS NOT NULL) AND (btrim((related_resource_type)::text) <> ''::text) AND (related_resource_id IS NOT NULL) AND ((related_revision IS NULL) OR (related_revision > 0))))),
    CONSTRAINT evidence_snapshot_subject_revision_ck CHECK (((subject_revision IS NULL) OR (subject_revision > 0))),
    CONSTRAINT evidence_snapshot_subject_type_ck CHECK ((btrim((subject_resource_type)::text) <> ''::text)),
    CONSTRAINT evidence_snapshot_type_ck CHECK ((btrim((snapshot_type)::text) <> ''::text))
);

COMMENT ON TABLE audit.evidence_snapshot IS 'Audit-owned immutable ADR-0036 decision-time contextual reference snapshot. Capability-owned decision evidence remains with its owner.';

ALTER TABLE ONLY audit.audit_archive_segment
    ADD CONSTRAINT audit_archive_segment_pkey PRIMARY KEY (id);

ALTER TABLE ONLY audit.audit_archive_segment
    ADD CONSTRAINT audit_archive_segment_range_uq UNIQUE (tenant_id, retention_policy_version_id, occurred_from, occurred_until);

ALTER TABLE ONLY audit.audit_archive_segment
    ADD CONSTRAINT audit_archive_segment_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY audit.audit_archived_record_index
    ADD CONSTRAINT audit_archived_record_index_pkey PRIMARY KEY (tenant_id, archive_segment_id, record_id);

ALTER TABLE ONLY audit.audit_export_operation
    ADD CONSTRAINT audit_export_operation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY audit.audit_export_operation
    ADD CONSTRAINT audit_export_operation_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY audit.audit_legal_hold
    ADD CONSTRAINT audit_legal_hold_pkey PRIMARY KEY (id);

ALTER TABLE ONLY audit.audit_legal_hold
    ADD CONSTRAINT audit_legal_hold_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY audit.audit_purge_operation
    ADD CONSTRAINT audit_purge_operation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY audit.audit_purge_operation
    ADD CONSTRAINT audit_purge_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY audit.audit_record
    ADD CONSTRAINT audit_record_pkey PRIMARY KEY (id);

ALTER TABLE ONLY audit.audit_record
    ADD CONSTRAINT audit_record_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY audit.audit_retention_policy_version
    ADD CONSTRAINT audit_retention_policy_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY audit.audit_retention_policy_version
    ADD CONSTRAINT audit_retention_policy_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY audit.audit_retention_policy_version
    ADD CONSTRAINT audit_retention_policy_version_uq UNIQUE (tenant_id, policy_version);

ALTER TABLE ONLY audit.evidence_snapshot
    ADD CONSTRAINT evidence_snapshot_pkey PRIMARY KEY (id);

ALTER TABLE ONLY audit.evidence_snapshot
    ADD CONSTRAINT evidence_snapshot_tenant_id_uq UNIQUE (tenant_id, id);

CREATE INDEX audit_archive_segment_range_idx ON audit.audit_archive_segment USING btree (tenant_id, occurred_from, occurred_until, id);

CREATE INDEX audit_archive_segment_state_idx ON audit.audit_archive_segment USING btree (tenant_id, state, created_at, id);

CREATE INDEX audit_archived_record_actor_time_idx ON audit.audit_archived_record_index USING btree (tenant_id, actor_id, occurred_at DESC, record_id DESC) WHERE (actor_id IS NOT NULL);

CREATE INDEX audit_archived_record_correlation_time_idx ON audit.audit_archived_record_index USING btree (tenant_id, correlation_id, occurred_at DESC, record_id DESC) WHERE (correlation_id IS NOT NULL);

CREATE INDEX audit_archived_record_record_idx ON audit.audit_archived_record_index USING btree (tenant_id, record_id);

CREATE INDEX audit_archived_record_resource_time_idx ON audit.audit_archived_record_index USING btree (tenant_id, resource_type, resource_id, occurred_at DESC, record_id DESC);

CREATE INDEX audit_archived_record_time_idx ON audit.audit_archived_record_index USING btree (tenant_id, occurred_at DESC, record_id DESC);

CREATE INDEX audit_export_operation_requester_idx ON audit.audit_export_operation USING btree (tenant_id, requested_by_identity_id, created_at DESC, id DESC);

CREATE INDEX audit_export_operation_state_idx ON audit.audit_export_operation USING btree (tenant_id, state, created_at, id);

CREATE INDEX audit_legal_hold_active_range_idx ON audit.audit_legal_hold USING btree (tenant_id, occurred_from, occurred_until, id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE INDEX audit_purge_state_idx ON audit.audit_purge_operation USING btree (tenant_id, state, created_at, id);

CREATE INDEX audit_record_action_time_idx ON audit.audit_record USING btree (tenant_id, action_type, occurred_at DESC, id DESC);

CREATE INDEX audit_record_actor_time_idx ON audit.audit_record USING btree (tenant_id, actor_id, occurred_at DESC, id DESC) WHERE (actor_id IS NOT NULL);

CREATE INDEX audit_record_correlation_time_idx ON audit.audit_record USING btree (tenant_id, correlation_id, occurred_at DESC, id DESC) WHERE (correlation_id IS NOT NULL);

CREATE INDEX audit_record_outcome_time_idx ON audit.audit_record USING btree (tenant_id, outcome, occurred_at DESC, id DESC);

CREATE INDEX audit_record_resource_id_time_idx ON audit.audit_record USING btree (tenant_id, resource_id, occurred_at DESC, id DESC) WHERE (resource_id IS NOT NULL);

CREATE INDEX audit_record_resource_time_idx ON audit.audit_record USING btree (tenant_id, resource_type, resource_id, occurred_at DESC, id DESC);

CREATE INDEX audit_record_time_idx ON audit.audit_record USING btree (tenant_id, occurred_at DESC, id DESC);

CREATE INDEX audit_retention_policy_effective_idx ON audit.audit_retention_policy_version USING btree (tenant_id, effective_from DESC, policy_version DESC);

CREATE INDEX evidence_snapshot_correlation_idx ON audit.evidence_snapshot USING btree (tenant_id, correlation_id, occurred_at DESC, id DESC) WHERE (correlation_id IS NOT NULL);

CREATE INDEX evidence_snapshot_subject_idx ON audit.evidence_snapshot USING btree (tenant_id, subject_resource_type, subject_resource_id, occurred_at DESC, id DESC);

CREATE INDEX evidence_snapshot_time_idx ON audit.evidence_snapshot USING btree (tenant_id, occurred_at DESC, id DESC);

CREATE INDEX evidence_snapshot_type_idx ON audit.evidence_snapshot USING btree (tenant_id, snapshot_type, occurred_at DESC, id DESC);

CREATE TRIGGER audit_archive_history_delete_trg BEFORE DELETE ON audit.audit_archive_segment FOR EACH ROW EXECUTE FUNCTION audit.reject_evidence_history_delete();

CREATE TRIGGER audit_archived_record_index_immutable_trg BEFORE DELETE OR UPDATE ON audit.audit_archived_record_index FOR EACH ROW EXECUTE FUNCTION audit.reject_immutable_evidence_mutation();

CREATE TRIGGER audit_export_history_delete_trg BEFORE DELETE ON audit.audit_export_operation FOR EACH ROW EXECUTE FUNCTION audit.reject_evidence_history_delete();

CREATE TRIGGER audit_legal_hold_history_delete_trg BEFORE DELETE ON audit.audit_legal_hold FOR EACH ROW EXECUTE FUNCTION audit.reject_evidence_history_delete();

CREATE TRIGGER audit_purge_history_delete_trg BEFORE DELETE ON audit.audit_purge_operation FOR EACH ROW EXECUTE FUNCTION audit.reject_evidence_history_delete();

CREATE TRIGGER audit_record_append_only_trg BEFORE DELETE OR UPDATE ON audit.audit_record FOR EACH ROW EXECUTE FUNCTION audit.reject_audit_record_mutation();

CREATE TRIGGER audit_retention_policy_immutable_trg BEFORE DELETE OR UPDATE ON audit.audit_retention_policy_version FOR EACH ROW EXECUTE FUNCTION audit.reject_immutable_evidence_mutation();

CREATE TRIGGER evidence_snapshot_immutable_trg BEFORE DELETE OR UPDATE ON audit.evidence_snapshot FOR EACH ROW EXECUTE FUNCTION audit.reject_immutable_evidence_mutation();

ALTER TABLE ONLY audit.audit_archive_segment
    ADD CONSTRAINT audit_archive_segment_policy_fk FOREIGN KEY (tenant_id, retention_policy_version_id) REFERENCES audit.audit_retention_policy_version(tenant_id, id);

ALTER TABLE ONLY audit.audit_archive_segment
    ADD CONSTRAINT audit_archive_segment_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY audit.audit_archived_record_index
    ADD CONSTRAINT audit_archived_record_segment_fk FOREIGN KEY (tenant_id, archive_segment_id) REFERENCES audit.audit_archive_segment(tenant_id, id);

ALTER TABLE ONLY audit.audit_archived_record_index
    ADD CONSTRAINT audit_archived_record_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY audit.audit_export_operation
    ADD CONSTRAINT audit_export_operation_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY audit.audit_legal_hold
    ADD CONSTRAINT audit_legal_hold_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY audit.audit_purge_operation
    ADD CONSTRAINT audit_purge_archive_fk FOREIGN KEY (tenant_id, archive_segment_id) REFERENCES audit.audit_archive_segment(tenant_id, id);

ALTER TABLE ONLY audit.audit_purge_operation
    ADD CONSTRAINT audit_purge_policy_fk FOREIGN KEY (tenant_id, retention_policy_version_id) REFERENCES audit.audit_retention_policy_version(tenant_id, id);

ALTER TABLE ONLY audit.audit_purge_operation
    ADD CONSTRAINT audit_purge_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY audit.audit_record
    ADD CONSTRAINT audit_record_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY audit.audit_retention_policy_version
    ADD CONSTRAINT audit_retention_policy_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY audit.evidence_snapshot
    ADD CONSTRAINT evidence_snapshot_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);
