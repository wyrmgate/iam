SET check_function_bodies = false;

CREATE SCHEMA access;

CREATE SCHEMA administration;

CREATE SCHEMA audit;

CREATE SCHEMA catalog;

CREATE SCHEMA credential;

COMMENT ON SCHEMA credential IS 'Credential capability authoritative metadata and durable rotation process state.';

CREATE SCHEMA governance;

CREATE SCHEMA identity;

CREATE SCHEMA integration;

CREATE SCHEMA platform;

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

CREATE FUNCTION catalog.reject_immutable_activated_role_version_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.role_id <> OLD.role_id
       OR NEW.version_number <> OLD.version_number THEN
        RAISE EXCEPTION 'RoleVersion identity is immutable';
    END IF;
    IF NEW.content_hash <> OLD.content_hash
       AND (
           OLD.state IN ('ACTIVE','SUPERSEDED')
           OR NEW.state IN ('ACTIVE','SUPERSEDED')
       ) THEN
        RAISE EXCEPTION 'activated RoleVersion content is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION catalog.reject_immutable_activated_role_version_member_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    version_id uuid;
    version_state varchar(24);
BEGIN
    version_id := CASE WHEN TG_OP = 'DELETE'
        THEN OLD.role_version_id ELSE NEW.role_version_id END;
    SELECT state INTO version_state
    FROM catalog.role_version
    WHERE tenant_id = CASE WHEN TG_OP = 'DELETE'
            THEN OLD.tenant_id ELSE NEW.tenant_id END
      AND id = version_id;

    IF version_state IN ('ACTIVE','SUPERSEDED') THEN
        RAISE EXCEPTION 'activated RoleVersion members are immutable';
    END IF;

    IF TG_OP = 'UPDATE'
       AND OLD.role_version_id <> NEW.role_version_id THEN
        RAISE EXCEPTION 'RoleVersion member ownership is immutable';
    END IF;

    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE FUNCTION credential.guard_credential_update() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.principal_id IS DISTINCT FROM OLD.principal_id
       OR NEW.credential_kind IS DISTINCT FROM OLD.credential_kind
       OR NEW.secret_provider_type IS DISTINCT FROM OLD.secret_provider_type
       OR NEW.secret_reference_key IS DISTINCT FROM OLD.secret_reference_key
       OR NEW.valid_from IS DISTINCT FROM OLD.valid_from
       OR NEW.valid_until IS DISTINCT FROM OLD.valid_until
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Credential ownership, kind, secret reference and validity are immutable in v1';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION credential.guard_rotation_update() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.old_credential_id IS DISTINCT FROM OLD.old_credential_id
       OR NEW.initiator_identity_id IS DISTINCT FROM OLD.initiator_identity_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'CredentialRotation subject/initiator is immutable';
    END IF;

    IF OLD.replacement_credential_id IS NOT NULL
       AND NEW.replacement_credential_id IS DISTINCT FROM OLD.replacement_credential_id THEN
        RAISE EXCEPTION 'CredentialRotation replacement Credential is immutable once set';
    END IF;

    IF OLD.process_state IN (
        'COMPLETED','FAILED',
        'MANUAL_REQUIRED','FAILED_REMEDIATION') THEN
        RAISE EXCEPTION 'terminal CredentialRotation is immutable';
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION credential.reject_credential_delete() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'Credential history is retained; use lifecycle state';
END;
$$;

CREATE FUNCTION governance.guard_governance_exception_update() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.scope_kind IS DISTINCT FROM OLD.scope_kind
       OR NEW.subject_identity_id IS DISTINCT FROM OLD.subject_identity_id
       OR NEW.sod_rule_id IS DISTINCT FROM OLD.sod_rule_id
       OR NEW.requester_identity_id IS DISTINCT FROM OLD.requester_identity_id
       OR NEW.business_reason IS DISTINCT FROM OLD.business_reason
       OR NEW.valid_from IS DISTINCT FROM OLD.valid_from
       OR NEW.valid_until IS DISTINCT FROM OLD.valid_until
       OR NEW.approval_case_id IS DISTINCT FROM OLD.approval_case_id
       OR NEW.predecessor_exception_id IS DISTINCT FROM OLD.predecessor_exception_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'GovernanceException scope/content is immutable; renewal creates a successor';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION governance.guard_policy_version_content() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    target_version uuid;
    version_state varchar(24);
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'policy version content is immutable; create a new version';
    END IF;

    IF TG_TABLE_NAME = 'policy_approval_approver' THEN
        SELECT s.policy_version_id
        INTO target_version
        FROM governance.policy_approval_stage s
        WHERE s.tenant_id = COALESCE(NEW.tenant_id, OLD.tenant_id)
          AND s.id = COALESCE(NEW.policy_approval_stage_id, OLD.policy_approval_stage_id);
    ELSE
        target_version := COALESCE(NEW.policy_version_id, OLD.policy_version_id);
    END IF;

    SELECT state
    INTO version_state
    FROM governance.policy_version
    WHERE tenant_id = COALESCE(NEW.tenant_id, OLD.tenant_id)
      AND id = target_version;

    IF version_state <> 'DRAFT' THEN
        RAISE EXCEPTION 'policy version content may only be inserted while DRAFT';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION governance.guard_policy_version_update() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.state <> 'DRAFT'
       AND (
           NEW.policy_id IS DISTINCT FROM OLD.policy_id
           OR NEW.version_number IS DISTINCT FROM OLD.version_number
           OR NEW.default_decision IS DISTINCT FROM OLD.default_decision
       ) THEN
        RAISE EXCEPTION 'activated/ready policy version content is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION governance.guard_review_campaign_update() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.campaign_kind IS DISTINCT FROM OLD.campaign_kind
       OR NEW.subject_identity_id IS DISTINCT FROM OLD.subject_identity_id
       OR NEW.reviewer_identity_id IS DISTINCT FROM OLD.reviewer_identity_id
       OR NEW.snapshot_at IS DISTINCT FROM OLD.snapshot_at
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'ReviewCampaign scope/snapshot is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION governance.guard_review_item_update() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.review_campaign_id IS DISTINCT FROM OLD.review_campaign_id
       OR NEW.reviewer_identity_id IS DISTINCT FROM OLD.reviewer_identity_id
       OR NEW.access_assignment_id IS DISTINCT FROM OLD.access_assignment_id
       OR NEW.assignment_revision IS DISTINCT FROM OLD.assignment_revision
       OR NEW.target_kind IS DISTINCT FROM OLD.target_kind
       OR NEW.role_id IS DISTINCT FROM OLD.role_id
       OR NEW.entitlement_id IS DISTINCT FROM OLD.entitlement_id
       OR NEW.principal_constraint_kind IS DISTINCT FROM OLD.principal_constraint_kind
       OR NEW.specific_principal_id IS DISTINCT FROM OLD.specific_principal_id
       OR NEW.provenance_kind IS DISTINCT FROM OLD.provenance_kind
       OR NEW.provenance_ref_id IS DISTINCT FROM OLD.provenance_ref_id
       OR NEW.snapshot_lifecycle_state IS DISTINCT FROM OLD.snapshot_lifecycle_state
       OR NEW.valid_from IS DISTINCT FROM OLD.valid_from
       OR NEW.valid_until IS DISTINCT FROM OLD.valid_until
       OR NEW.assignment_created_at IS DISTINCT FROM OLD.assignment_created_at
       OR NEW.snapshot_at IS DISTINCT FROM OLD.snapshot_at
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'ReviewItem snapshot is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION governance.guard_review_remediation_update() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.review_item_id IS DISTINCT FROM OLD.review_item_id
       OR NEW.access_assignment_id IS DISTINCT FROM OLD.access_assignment_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'ReviewRemediation subject is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION governance.reject_governance_evidence_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'governance evaluation evidence is immutable';
END;
$$;

CREATE FUNCTION governance.reject_governance_exception_delete() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'GovernanceException history is retained; use lifecycle state instead of delete';
END;
$$;

CREATE FUNCTION governance.reject_immutable_approval_evidence_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'approval plan/stage/approver/decision evidence is immutable';
END;
$$;

CREATE FUNCTION governance.reject_policy_version_delete() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'policy versions are retained; use lifecycle state instead of delete';
END;
$$;

CREATE FUNCTION governance.reject_review_decision_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'ReviewDecision is immutable evidence';
END;
$$;

CREATE FUNCTION integration.reject_immutable_provisioning_attempt_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'provisioning_attempt is immutable';
END;
$$;

CREATE TABLE access.access_assignment (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    target_kind character varying(16) NOT NULL,
    role_id uuid,
    entitlement_id uuid,
    principal_constraint_kind character varying(16) NOT NULL,
    specific_principal_id uuid,
    provenance_kind character varying(32) NOT NULL,
    provenance_ref_id uuid,
    lifecycle_state character varying(24) NOT NULL,
    valid_from timestamp with time zone,
    valid_until timestamp with time zone,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT access_assignment_lifecycle_state_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'ACTIVE'::character varying, 'SUSPENDED'::character varying, 'REVOKED'::character varying, 'EXPIRED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT access_assignment_principal_constraint_kind_ck CHECK (((principal_constraint_kind)::text = ANY ((ARRAY['ANY'::character varying, 'SPECIFIC'::character varying])::text[]))),
    CONSTRAINT access_assignment_principal_constraint_shape_ck CHECK (((((principal_constraint_kind)::text = 'SPECIFIC'::text) AND (specific_principal_id IS NOT NULL)) OR (((principal_constraint_kind)::text = 'ANY'::text) AND (specific_principal_id IS NULL)))),
    CONSTRAINT access_assignment_provenance_kind_ck CHECK (((provenance_kind)::text = ANY ((ARRAY['MANUAL'::character varying, 'REQUEST_ITEM'::character varying, 'LIFECYCLE_POLICY_RULE'::character varying])::text[]))),
    CONSTRAINT access_assignment_provenance_shape_ck CHECK (((((provenance_kind)::text = 'MANUAL'::text) AND (provenance_ref_id IS NULL)) OR (((provenance_kind)::text = ANY ((ARRAY['REQUEST_ITEM'::character varying, 'LIFECYCLE_POLICY_RULE'::character varying])::text[])) AND (provenance_ref_id IS NOT NULL)))),
    CONSTRAINT access_assignment_revision_ck CHECK ((revision > 0)),
    CONSTRAINT access_assignment_scheduled_valid_from_ck CHECK ((((lifecycle_state)::text <> 'SCHEDULED'::text) OR (valid_from IS NOT NULL))),
    CONSTRAINT access_assignment_target_kind_ck CHECK (((target_kind)::text = ANY ((ARRAY['ROLE'::character varying, 'ENTITLEMENT'::character varying])::text[]))),
    CONSTRAINT access_assignment_target_shape_ck CHECK (((((target_kind)::text = 'ROLE'::text) AND (role_id IS NOT NULL) AND (entitlement_id IS NULL)) OR (((target_kind)::text = 'ENTITLEMENT'::text) AND (entitlement_id IS NOT NULL) AND (role_id IS NULL)))),
    CONSTRAINT access_assignment_timestamp_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT access_assignment_validity_ck CHECK (((valid_until IS NULL) OR (valid_from IS NULL) OR (valid_until > valid_from)))
);

COMMENT ON TABLE access.access_assignment IS 'Access-owned authoritative business access intent. Technical fulfillment and provider observation are separate state dimensions.';

COMMENT ON COLUMN access.access_assignment.provenance_ref_id IS 'Stable causal reference within provenance kind. REQUEST_ITEM stores Governance RequestItem ID; LIFECYCLE_POLICY_RULE stores stable Access lifecycle-policy rule ID.';

CREATE TABLE access.desired_grant_state (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    application_target_id uuid NOT NULL,
    entitlement_id uuid NOT NULL,
    principal_id uuid,
    desired_state character varying(24) NOT NULL,
    desired_revision bigint NOT NULL,
    source_generation bigint NOT NULL,
    computed_at timestamp with time zone NOT NULL,
    principal_constraint_key character varying(512) NOT NULL,
    CONSTRAINT access_desired_grant_constraint_key_ck CHECK ((btrim((principal_constraint_key)::text) <> ''::text)),
    CONSTRAINT access_desired_grant_state_generation_ck CHECK ((source_generation > 0)),
    CONSTRAINT access_desired_grant_state_revision_ck CHECK ((desired_revision > 0)),
    CONSTRAINT access_desired_grant_state_state_ck CHECK (((desired_state)::text = ANY ((ARRAY['PRESENT'::character varying, 'ABSENT'::character varying])::text[])))
);

COMMENT ON COLUMN access.desired_grant_state.principal_constraint_key IS 'Stable Access projection tuple key aligned with EffectiveAccess: ANY or SPECIFIC:<principal UUID>.';

CREATE TABLE access.desired_principal_state (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    application_target_id uuid NOT NULL,
    desired_state character varying(24) NOT NULL,
    desired_revision bigint NOT NULL,
    source_generation bigint NOT NULL,
    computed_at timestamp with time zone NOT NULL,
    CONSTRAINT access_desired_principal_state_generation_ck CHECK ((source_generation > 0)),
    CONSTRAINT access_desired_principal_state_revision_ck CHECK ((desired_revision > 0)),
    CONSTRAINT access_desired_principal_state_state_ck CHECK (((desired_state)::text = ANY ((ARRAY['PRESENT'::character varying, 'ABSENT'::character varying])::text[])))
);

CREATE TABLE access.effective_access (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    entitlement_id uuid NOT NULL,
    principal_constraint_key character varying(512) NOT NULL,
    support_count integer NOT NULL,
    computed_at timestamp with time zone NOT NULL,
    projection_generation bigint NOT NULL,
    CONSTRAINT effective_access_constraint_key_ck CHECK ((btrim((principal_constraint_key)::text) <> ''::text)),
    CONSTRAINT effective_access_generation_ck CHECK ((projection_generation > 0)),
    CONSTRAINT effective_access_support_count_ck CHECK ((support_count > 0))
);

COMMENT ON TABLE access.effective_access IS 'Access-owned rebuildable entitlement-level projection derived from authoritative AccessAssignments.';

CREATE TABLE access.effective_access_support (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    effective_access_id uuid NOT NULL,
    access_assignment_id uuid NOT NULL,
    role_version_id uuid,
    path_hash character varying(128) NOT NULL,
    path_depth integer NOT NULL,
    CONSTRAINT effective_access_support_depth_ck CHECK ((path_depth >= 0))
);

COMMENT ON TABLE access.effective_access_support IS 'Explainable support paths for EffectiveAccess; current direct assignments use path_depth 0 and no role_version_id.';

CREATE TABLE access.effective_access_support_role_version (
    tenant_id uuid NOT NULL,
    effective_access_support_id uuid CONSTRAINT effective_access_support_ro_effective_access_support_i_not_null NOT NULL,
    path_ordinal integer NOT NULL,
    role_version_id uuid NOT NULL,
    CONSTRAINT effective_access_support_role_version_ordinal_ck CHECK ((path_ordinal >= 0))
);

COMMENT ON TABLE access.effective_access_support_role_version IS 'Normalized ordered RoleVersion derivation path for one role-derived EffectiveAccess support row.';

CREATE TABLE access.identity_access_reduction (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    source_identity_revision bigint NOT NULL,
    source_lifecycle_state character varying(24) NOT NULL,
    lifecycle_state character varying(16) NOT NULL,
    snapshot_at timestamp with time zone NOT NULL,
    after_created_at timestamp with time zone,
    after_assignment_id uuid,
    processed_assignment_count bigint DEFAULT 0 NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT identity_access_reduction_checkpoint_ck CHECK ((((after_created_at IS NULL) AND (after_assignment_id IS NULL)) OR ((after_created_at IS NOT NULL) AND (after_assignment_id IS NOT NULL)))),
    CONSTRAINT identity_access_reduction_completion_ck CHECK (((((lifecycle_state)::text = 'RUNNING'::text) AND (completed_at IS NULL)) OR (((lifecycle_state)::text = 'COMPLETED'::text) AND (completed_at IS NOT NULL)))),
    CONSTRAINT identity_access_reduction_processed_ck CHECK ((processed_assignment_count >= 0)),
    CONSTRAINT identity_access_reduction_revision_ck CHECK ((revision > 0)),
    CONSTRAINT identity_access_reduction_source_revision_ck CHECK ((source_identity_revision > 0)),
    CONSTRAINT identity_access_reduction_source_state_ck CHECK (((source_lifecycle_state)::text = ANY ((ARRAY['PENDING'::character varying, 'SUSPENDED'::character varying, 'INACTIVE'::character varying, 'DECOMMISSIONED'::character varying])::text[]))),
    CONSTRAINT identity_access_reduction_state_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['RUNNING'::character varying, 'COMPLETED'::character varying])::text[]))),
    CONSTRAINT identity_access_reduction_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE access.identity_access_reduction IS 'Access-owned durable Leaver reduction process causally keyed by Identity ID and source Identity revision. Identity references are stable cross-capability IDs without database foreign keys.';

CREATE TABLE access.lifecycle_access_policy_rule (
    policy_version_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    rule_id uuid NOT NULL,
    predicate_kind character varying(32) NOT NULL,
    canonical_key character varying(256),
    expected_string character varying(1024),
    target_kind character varying(16) NOT NULL,
    target_id uuid NOT NULL,
    expected_boolean boolean,
    expected_integer bigint,
    expected_enum character varying(256),
    expected_decimal numeric(38,12),
    expected_date date,
    expected_datetime timestamp with time zone,
    CONSTRAINT lifecycle_access_policy_rule_predicate_ck CHECK (((predicate_kind)::text = ANY ((ARRAY['ALWAYS'::character varying, 'CANONICAL_STRING_EQUALS'::character varying, 'CANONICAL_BOOLEAN_EQUALS'::character varying, 'CANONICAL_INTEGER_EQUALS'::character varying, 'CANONICAL_DECIMAL_EQUALS'::character varying, 'CANONICAL_DATE_EQUALS'::character varying, 'CANONICAL_DATETIME_EQUALS'::character varying, 'CANONICAL_ENUM_EQUALS'::character varying, 'CANONICAL_STRING_CONTAINS'::character varying, 'CANONICAL_BOOLEAN_CONTAINS'::character varying, 'CANONICAL_INTEGER_CONTAINS'::character varying, 'CANONICAL_DECIMAL_CONTAINS'::character varying, 'CANONICAL_DATE_CONTAINS'::character varying, 'CANONICAL_DATETIME_CONTAINS'::character varying, 'CANONICAL_ENUM_CONTAINS'::character varying, 'CANONICAL_STRING_CONTAINS_ANY'::character varying, 'CANONICAL_BOOLEAN_CONTAINS_ANY'::character varying, 'CANONICAL_INTEGER_CONTAINS_ANY'::character varying, 'CANONICAL_DECIMAL_CONTAINS_ANY'::character varying, 'CANONICAL_DATE_CONTAINS_ANY'::character varying, 'CANONICAL_DATETIME_CONTAINS_ANY'::character varying, 'CANONICAL_ENUM_CONTAINS_ANY'::character varying, 'CANONICAL_STRING_CONTAINS_ALL'::character varying, 'CANONICAL_BOOLEAN_CONTAINS_ALL'::character varying, 'CANONICAL_INTEGER_CONTAINS_ALL'::character varying, 'CANONICAL_DECIMAL_CONTAINS_ALL'::character varying, 'CANONICAL_DATE_CONTAINS_ALL'::character varying, 'CANONICAL_DATETIME_CONTAINS_ALL'::character varying, 'CANONICAL_ENUM_CONTAINS_ALL'::character varying])::text[]))),
    CONSTRAINT lifecycle_access_policy_rule_predicate_shape_ck CHECK (((((predicate_kind)::text = 'ALWAYS'::text) AND (canonical_key IS NULL) AND (num_nonnulls(expected_string, expected_boolean, expected_integer, expected_decimal, expected_date, expected_datetime, expected_enum) = 0)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_STRING_EQUALS'::character varying, 'CANONICAL_STRING_CONTAINS'::character varying])::text[])) AND (canonical_key IS NOT NULL) AND (btrim((canonical_key)::text) <> ''::text) AND (expected_string IS NOT NULL) AND (num_nonnulls(expected_boolean, expected_integer, expected_decimal, expected_date, expected_datetime, expected_enum) = 0)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_BOOLEAN_EQUALS'::character varying, 'CANONICAL_BOOLEAN_CONTAINS'::character varying])::text[])) AND (canonical_key IS NOT NULL) AND (btrim((canonical_key)::text) <> ''::text) AND (expected_boolean IS NOT NULL) AND (num_nonnulls(expected_string, expected_integer, expected_decimal, expected_date, expected_datetime, expected_enum) = 0)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_INTEGER_EQUALS'::character varying, 'CANONICAL_INTEGER_CONTAINS'::character varying])::text[])) AND (canonical_key IS NOT NULL) AND (btrim((canonical_key)::text) <> ''::text) AND (expected_integer IS NOT NULL) AND (num_nonnulls(expected_string, expected_boolean, expected_decimal, expected_date, expected_datetime, expected_enum) = 0)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_DECIMAL_EQUALS'::character varying, 'CANONICAL_DECIMAL_CONTAINS'::character varying])::text[])) AND (canonical_key IS NOT NULL) AND (btrim((canonical_key)::text) <> ''::text) AND (expected_decimal IS NOT NULL) AND (num_nonnulls(expected_string, expected_boolean, expected_integer, expected_date, expected_datetime, expected_enum) = 0)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_DATE_EQUALS'::character varying, 'CANONICAL_DATE_CONTAINS'::character varying])::text[])) AND (canonical_key IS NOT NULL) AND (btrim((canonical_key)::text) <> ''::text) AND (expected_date IS NOT NULL) AND (num_nonnulls(expected_string, expected_boolean, expected_integer, expected_decimal, expected_datetime, expected_enum) = 0)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_DATETIME_EQUALS'::character varying, 'CANONICAL_DATETIME_CONTAINS'::character varying])::text[])) AND (canonical_key IS NOT NULL) AND (btrim((canonical_key)::text) <> ''::text) AND (expected_datetime IS NOT NULL) AND (num_nonnulls(expected_string, expected_boolean, expected_integer, expected_decimal, expected_date, expected_enum) = 0)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_ENUM_EQUALS'::character varying, 'CANONICAL_ENUM_CONTAINS'::character varying])::text[])) AND (canonical_key IS NOT NULL) AND (btrim((canonical_key)::text) <> ''::text) AND (expected_enum IS NOT NULL) AND (btrim((expected_enum)::text) <> ''::text) AND (num_nonnulls(expected_string, expected_boolean, expected_integer, expected_decimal, expected_date, expected_datetime) = 0)) OR (((predicate_kind)::text ~~ 'CANONICAL_%_CONTAINS_ANY'::text) AND (canonical_key IS NOT NULL) AND (btrim((canonical_key)::text) <> ''::text) AND (num_nonnulls(expected_string, expected_boolean, expected_integer, expected_decimal, expected_date, expected_datetime, expected_enum) = 0)) OR (((predicate_kind)::text ~~ 'CANONICAL_%_CONTAINS_ALL'::text) AND (canonical_key IS NOT NULL) AND (btrim((canonical_key)::text) <> ''::text) AND (num_nonnulls(expected_string, expected_boolean, expected_integer, expected_decimal, expected_date, expected_datetime, expected_enum) = 0)))),
    CONSTRAINT lifecycle_access_policy_rule_target_kind_ck CHECK (((target_kind)::text = ANY ((ARRAY['ROLE'::character varying, 'ENTITLEMENT'::character varying])::text[])))
);

COMMENT ON TABLE access.lifecycle_access_policy_rule IS 'Typed bounded lifecycle access rule. rule_id is stable logical provenance reusable across policy versions.';

COMMENT ON COLUMN access.lifecycle_access_policy_rule.expected_boolean IS 'Typed BOOLEAN expected value for CANONICAL_BOOLEAN_EQUALS.';

COMMENT ON COLUMN access.lifecycle_access_policy_rule.expected_integer IS 'Typed INTEGER expected value for CANONICAL_INTEGER_EQUALS.';

COMMENT ON COLUMN access.lifecycle_access_policy_rule.expected_enum IS 'Typed ENUM key expected value for CANONICAL_ENUM_EQUALS.';

COMMENT ON COLUMN access.lifecycle_access_policy_rule.expected_decimal IS 'Exact normalized DECIMAL expected value for CANONICAL_DECIMAL_EQUALS; application validation prevents numeric(38,12) rounding.';

COMMENT ON COLUMN access.lifecycle_access_policy_rule.expected_date IS 'Exact DATE expected value for CANONICAL_DATE_EQUALS.';

COMMENT ON COLUMN access.lifecycle_access_policy_rule.expected_datetime IS 'Exact absolute microsecond-precision timestamp expected value for CANONICAL_DATETIME_EQUALS.';

CREATE TABLE access.lifecycle_access_policy_rule_expected_value (
    tenant_id uuid NOT NULL,
    policy_version_id uuid CONSTRAINT lifecycle_access_policy_rule_expecte_policy_version_id_not_null NOT NULL,
    rule_id uuid NOT NULL,
    predicate_kind character varying(32) CONSTRAINT lifecycle_access_policy_rule_expected_v_predicate_kind_not_null NOT NULL,
    value_ordinal integer CONSTRAINT lifecycle_access_policy_rule_expected_va_value_ordinal_not_null NOT NULL,
    value_type character varying(24) NOT NULL,
    value_string character varying(1024),
    value_boolean boolean,
    value_integer bigint,
    value_decimal numeric(38,12),
    value_date date,
    value_datetime timestamp with time zone,
    value_enum character varying(256),
    CONSTRAINT lifecycle_access_policy_rule_expected_value_ordinal_ck CHECK (((value_ordinal >= 0) AND (value_ordinal < 20))),
    CONSTRAINT lifecycle_access_policy_rule_expected_value_predicate_type_ck CHECK (((((predicate_kind)::text = ANY ((ARRAY['CANONICAL_STRING_CONTAINS_ANY'::character varying, 'CANONICAL_STRING_CONTAINS_ALL'::character varying])::text[])) AND ((value_type)::text = 'STRING'::text)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_BOOLEAN_CONTAINS_ANY'::character varying, 'CANONICAL_BOOLEAN_CONTAINS_ALL'::character varying])::text[])) AND ((value_type)::text = 'BOOLEAN'::text)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_INTEGER_CONTAINS_ANY'::character varying, 'CANONICAL_INTEGER_CONTAINS_ALL'::character varying])::text[])) AND ((value_type)::text = 'INTEGER'::text)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_DECIMAL_CONTAINS_ANY'::character varying, 'CANONICAL_DECIMAL_CONTAINS_ALL'::character varying])::text[])) AND ((value_type)::text = 'DECIMAL'::text)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_DATE_CONTAINS_ANY'::character varying, 'CANONICAL_DATE_CONTAINS_ALL'::character varying])::text[])) AND ((value_type)::text = 'DATE'::text)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_DATETIME_CONTAINS_ANY'::character varying, 'CANONICAL_DATETIME_CONTAINS_ALL'::character varying])::text[])) AND ((value_type)::text = 'DATETIME'::text)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_ENUM_CONTAINS_ANY'::character varying, 'CANONICAL_ENUM_CONTAINS_ALL'::character varying])::text[])) AND ((value_type)::text = 'ENUM'::text)))),
    CONSTRAINT lifecycle_access_policy_rule_expected_value_shape_ck CHECK (((((value_type)::text = 'STRING'::text) AND (value_string IS NOT NULL) AND (num_nonnulls(value_boolean, value_integer, value_decimal, value_date, value_datetime, value_enum) = 0)) OR (((value_type)::text = 'BOOLEAN'::text) AND (value_boolean IS NOT NULL) AND (num_nonnulls(value_string, value_integer, value_decimal, value_date, value_datetime, value_enum) = 0)) OR (((value_type)::text = 'INTEGER'::text) AND (value_integer IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_decimal, value_date, value_datetime, value_enum) = 0)) OR (((value_type)::text = 'DECIMAL'::text) AND (value_decimal IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_date, value_datetime, value_enum) = 0)) OR (((value_type)::text = 'DATE'::text) AND (value_date IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_datetime, value_enum) = 0)) OR (((value_type)::text = 'DATETIME'::text) AND (value_datetime IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_date, value_enum) = 0)) OR (((value_type)::text = 'ENUM'::text) AND (value_enum IS NOT NULL) AND (btrim((value_enum)::text) <> ''::text) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_date, value_datetime) = 0)))),
    CONSTRAINT lifecycle_access_policy_rule_expected_value_type_ck CHECK (((value_type)::text = ANY ((ARRAY['STRING'::character varying, 'BOOLEAN'::character varying, 'INTEGER'::character varying, 'DECIMAL'::character varying, 'DATE'::character varying, 'DATETIME'::character varying, 'ENUM'::character varying])::text[])))
);

COMMENT ON TABLE access.lifecycle_access_policy_rule_expected_value IS 'Typed relational expected-set members for lifecycle CONTAINS_ANY/CONTAINS_ALL predicates; application enforces 2-20 distinct homogeneous values.';

CREATE TABLE access.lifecycle_access_policy_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    version_number bigint NOT NULL,
    state character varying(24) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    activated_at timestamp with time zone NOT NULL,
    superseded_at timestamp with time zone,
    CONSTRAINT lifecycle_access_policy_version_number_ck CHECK ((version_number > 0)),
    CONSTRAINT lifecycle_access_policy_version_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUPERSEDED'::character varying])::text[]))),
    CONSTRAINT lifecycle_access_policy_version_time_ck CHECK (((((state)::text = 'ACTIVE'::text) AND (superseded_at IS NULL)) OR (((state)::text = 'SUPERSEDED'::text) AND (superseded_at IS NOT NULL) AND (superseded_at >= activated_at))))
);

COMMENT ON TABLE access.lifecycle_access_policy_version IS 'Immutable Access-owned Joiner/Mover lifecycle access policy version. At most one is ACTIVE per tenant.';

CREATE TABLE access.review_remediation_application (
    tenant_id uuid NOT NULL,
    review_remediation_id uuid NOT NULL,
    access_assignment_id uuid NOT NULL,
    outcome character varying(32) NOT NULL,
    resulting_lifecycle_state character varying(32) CONSTRAINT review_remediation_applicati_resulting_lifecycle_state_not_null NOT NULL,
    applied_at timestamp with time zone NOT NULL,
    CONSTRAINT access_review_remediation_application_outcome_ck CHECK (((outcome)::text = ANY ((ARRAY['APPLIED'::character varying, 'NO_ACTION_REQUIRED'::character varying])::text[]))),
    CONSTRAINT access_review_remediation_application_state_ck CHECK ((btrim((resulting_lifecycle_state)::text) <> ''::text))
);

COMMENT ON TABLE access.review_remediation_application IS 'Access-owned causal result for Governance review remediation. review_remediation_id is a stable cross-capability idempotency reference with no database foreign key.';

CREATE TABLE administration.administrative_break_glass_obligation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    break_glass_operation_id uuid CONSTRAINT administrative_break_glass_ob_break_glass_operation_id_not_null NOT NULL,
    obligation_type character varying(32) NOT NULL,
    state character varying(24) NOT NULL,
    completed_at timestamp with time zone,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT administrative_break_glass_obligation_revision_ck CHECK ((revision > 0)),
    CONSTRAINT administrative_break_glass_obligation_shape_ck CHECK (((((state)::text = ANY ((ARRAY['PENDING'::character varying, 'MANUAL_REQUIRED'::character varying])::text[])) AND (completed_at IS NULL)) OR (((state)::text = 'COMPLETED'::text) AND (completed_at IS NOT NULL)))),
    CONSTRAINT administrative_break_glass_obligation_state_ck CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'COMPLETED'::character varying, 'MANUAL_REQUIRED'::character varying])::text[]))),
    CONSTRAINT administrative_break_glass_obligation_timestamp_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT administrative_break_glass_obligation_type_ck CHECK (((obligation_type)::text = ANY ((ARRAY['SECURITY_NOTIFICATION'::character varying, 'POST_USE_REVIEW'::character varying])::text[])))
);

COMMENT ON TABLE administration.administrative_break_glass_obligation IS 'Durable SECURITY_NOTIFICATION and POST_USE_REVIEW work/evidence obligations. Obligation state never extends emergency authority.';

CREATE TABLE administration.administrative_break_glass_operation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    actor_identity_id uuid NOT NULL,
    role_id uuid NOT NULL,
    scope_type character varying(48) NOT NULL,
    scope_resource_type character varying(128),
    scope_ref_id uuid,
    scope_key character varying(64),
    reason character varying(2048) NOT NULL,
    incident_reference character varying(512) CONSTRAINT administrative_break_glass_operatio_incident_reference_not_null NOT NULL,
    valid_from timestamp with time zone NOT NULL,
    valid_until timestamp with time zone NOT NULL,
    activation_assurance_level character varying(16) CONSTRAINT administrative_break_glass__activation_assurance_level_not_null NOT NULL,
    activation_authenticated_at timestamp with time zone,
    activation_step_up_at timestamp with time zone CONSTRAINT administrative_break_glass_opera_activation_step_up_at_not_null NOT NULL,
    max_assurance_age_seconds bigint CONSTRAINT administrative_break_glass_o_max_assurance_age_seconds_not_null NOT NULL,
    state character varying(24) NOT NULL,
    activated_at timestamp with time zone NOT NULL,
    revoked_by_identity_id uuid,
    revoked_at timestamp with time zone,
    correlation_id uuid,
    causation_id uuid,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT administrative_break_glass_assurance_ck CHECK ((((activation_assurance_level)::text = 'STRONG'::text) AND (max_assurance_age_seconds > 0))),
    CONSTRAINT administrative_break_glass_incident_ck CHECK ((btrim((incident_reference)::text) <> ''::text)),
    CONSTRAINT administrative_break_glass_reason_ck CHECK ((btrim((reason)::text) <> ''::text)),
    CONSTRAINT administrative_break_glass_revision_ck CHECK ((revision > 0)),
    CONSTRAINT administrative_break_glass_revocation_shape_ck CHECK (((((state)::text = 'ACTIVE'::text) AND (revoked_by_identity_id IS NULL) AND (revoked_at IS NULL)) OR (((state)::text = 'REVOKED'::text) AND (revoked_by_identity_id IS NOT NULL) AND (revoked_at IS NOT NULL)))),
    CONSTRAINT administrative_break_glass_scope_shape_ck CHECK (((((scope_type)::text = 'GLOBAL'::text) AND (scope_resource_type IS NULL) AND (scope_ref_id IS NULL) AND (scope_key IS NULL)) OR (((scope_type)::text = 'SPECIFIC_RESOURCE'::text) AND (scope_resource_type IS NOT NULL) AND (btrim((scope_resource_type)::text) <> ''::text) AND (scope_ref_id IS NOT NULL) AND (scope_key IS NULL)) OR (((scope_type)::text = 'CANONICAL_ATTRIBUTE_CLASSIFICATION'::text) AND (scope_resource_type IS NULL) AND (scope_ref_id IS NULL) AND (scope_key IS NOT NULL) AND (btrim((scope_key)::text) <> ''::text)) OR (((scope_type)::text <> ALL ((ARRAY['GLOBAL'::character varying, 'SPECIFIC_RESOURCE'::character varying, 'CANONICAL_ATTRIBUTE_CLASSIFICATION'::character varying])::text[])) AND (scope_resource_type IS NULL) AND (scope_ref_id IS NOT NULL) AND (scope_key IS NULL)))),
    CONSTRAINT administrative_break_glass_scope_type_ck CHECK (((scope_type)::text = ANY ((ARRAY['GLOBAL'::character varying, 'ORGANIZATION'::character varying, 'APPLICATION'::character varying, 'APPLICATION_TARGET'::character varying, 'SOURCE_SYSTEM'::character varying, 'CONNECTOR_INSTANCE'::character varying, 'IDENTITY_POPULATION'::character varying, 'CANONICAL_ATTRIBUTE_CLASSIFICATION'::character varying, 'SPECIFIC_RESOURCE'::character varying])::text[]))),
    CONSTRAINT administrative_break_glass_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'REVOKED'::character varying])::text[]))),
    CONSTRAINT administrative_break_glass_timestamp_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT administrative_break_glass_validity_ck CHECK ((valid_until > valid_from))
);

COMMENT ON TABLE administration.administrative_break_glass_operation IS 'Administration-owned emergency authority. Strong assurance, short semantic validity, reason/incident evidence and durable obligations are explicit.';

CREATE TABLE administration.administrative_break_glass_review (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    break_glass_operation_id uuid CONSTRAINT administrative_break_glass_re_break_glass_operation_id_not_null NOT NULL,
    obligation_id uuid NOT NULL,
    reviewer_identity_id uuid NOT NULL,
    outcome character varying(48) NOT NULL,
    summary character varying(2048) NOT NULL,
    reviewed_at timestamp with time zone NOT NULL,
    correlation_id uuid,
    causation_id uuid,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT administrative_break_glass_review_outcome_ck CHECK (((outcome)::text = ANY ((ARRAY['APPROVED_USE'::character varying, 'POLICY_CONCERN'::character varying, 'INCIDENT_FOLLOW_UP_REQUIRED'::character varying])::text[]))),
    CONSTRAINT administrative_break_glass_review_summary_ck CHECK ((btrim((summary)::text) <> ''::text))
);

COMMENT ON TABLE administration.administrative_break_glass_review IS 'Immutable Administration-owned post-use review evidence. Review never retroactively changes emergency authority validity.';

CREATE TABLE administration.administrative_delegation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    delegate_identity_id uuid NOT NULL,
    delegator_identity_id uuid NOT NULL,
    source_grant_id uuid NOT NULL,
    role_id uuid NOT NULL,
    scope_type character varying(48) NOT NULL,
    scope_resource_type character varying(128),
    scope_ref_id uuid,
    scope_key character varying(64),
    state character varying(24) NOT NULL,
    valid_from timestamp with time zone,
    valid_until timestamp with time zone NOT NULL,
    created_by_identity_id uuid NOT NULL,
    revoked_by_identity_id uuid,
    revoked_at timestamp with time zone,
    correlation_id uuid,
    causation_id uuid,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT administration_delegation_revision_ck CHECK ((revision > 0)),
    CONSTRAINT administration_delegation_revocation_shape_ck CHECK (((((state)::text = 'ACTIVE'::text) AND (revoked_by_identity_id IS NULL) AND (revoked_at IS NULL)) OR (((state)::text = 'REVOKED'::text) AND (revoked_by_identity_id IS NOT NULL) AND (revoked_at IS NOT NULL)))),
    CONSTRAINT administration_delegation_scope_shape_ck CHECK (((((scope_type)::text = 'GLOBAL'::text) AND (scope_resource_type IS NULL) AND (scope_ref_id IS NULL) AND (scope_key IS NULL)) OR (((scope_type)::text = 'SPECIFIC_RESOURCE'::text) AND (scope_resource_type IS NOT NULL) AND (btrim((scope_resource_type)::text) <> ''::text) AND (scope_ref_id IS NOT NULL) AND (scope_key IS NULL)) OR (((scope_type)::text = 'CANONICAL_ATTRIBUTE_CLASSIFICATION'::text) AND (scope_resource_type IS NULL) AND (scope_ref_id IS NULL) AND (scope_key IS NOT NULL) AND (btrim((scope_key)::text) <> ''::text)) OR (((scope_type)::text <> ALL ((ARRAY['GLOBAL'::character varying, 'SPECIFIC_RESOURCE'::character varying, 'CANONICAL_ATTRIBUTE_CLASSIFICATION'::character varying])::text[])) AND (scope_resource_type IS NULL) AND (scope_ref_id IS NOT NULL) AND (scope_key IS NULL)))),
    CONSTRAINT administration_delegation_scope_type_ck CHECK (((scope_type)::text = ANY ((ARRAY['GLOBAL'::character varying, 'ORGANIZATION'::character varying, 'APPLICATION'::character varying, 'APPLICATION_TARGET'::character varying, 'SOURCE_SYSTEM'::character varying, 'CONNECTOR_INSTANCE'::character varying, 'IDENTITY_POPULATION'::character varying, 'CANONICAL_ATTRIBUTE_CLASSIFICATION'::character varying, 'SPECIFIC_RESOURCE'::character varying])::text[]))),
    CONSTRAINT administration_delegation_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'REVOKED'::character varying])::text[]))),
    CONSTRAINT administration_delegation_timestamp_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT administration_delegation_validity_ck CHECK (((valid_from IS NULL) OR (valid_until > valid_from)))
);

COMMENT ON TABLE administration.administrative_delegation IS 'Administration-owned explicit single-hop delegated authority. Authority remains dependent on the current direct source grant.';

COMMENT ON COLUMN administration.administrative_delegation.delegate_identity_id IS 'Actual governed acting Identity when delegated authority is used; cross-capability stable ID with no database FK.';

COMMENT ON COLUMN administration.administrative_delegation.delegator_identity_id IS 'Governed Identity that owns the direct source grant; provenance only, never substituted as the acting Identity.';

CREATE TABLE administration.administrative_elevation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    beneficiary_identity_id uuid NOT NULL,
    initiator_identity_id uuid NOT NULL,
    authority_basis_grant_id uuid NOT NULL,
    role_id uuid NOT NULL,
    scope_type character varying(48) NOT NULL,
    scope_resource_type character varying(128),
    scope_ref_id uuid,
    scope_key character varying(64),
    valid_from timestamp with time zone,
    valid_until timestamp with time zone NOT NULL,
    request_fingerprint character varying(64) NOT NULL,
    state character varying(32) NOT NULL,
    approval_case_id uuid,
    approval_plan_fingerprint character varying(64),
    activated_at timestamp with time zone,
    denied_at timestamp with time zone,
    cancelled_at timestamp with time zone,
    revoked_at timestamp with time zone,
    correlation_id uuid,
    causation_id uuid,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT administrative_elevation_approval_shape_ck CHECK ((((approval_case_id IS NULL) AND (approval_plan_fingerprint IS NULL)) OR ((approval_case_id IS NOT NULL) AND (approval_plan_fingerprint IS NOT NULL)))),
    CONSTRAINT administrative_elevation_revision_ck CHECK ((revision > 0)),
    CONSTRAINT administrative_elevation_state_ck CHECK (((state)::text = ANY ((ARRAY['REQUESTED'::character varying, 'PENDING_APPROVAL'::character varying, 'ACTIVE'::character varying, 'DENIED'::character varying, 'CANCELLED'::character varying, 'REVOKED'::character varying])::text[]))),
    CONSTRAINT administrative_elevation_timestamp_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT administrative_elevation_validity_ck CHECK (((valid_from IS NULL) OR (valid_until > valid_from)))
);

COMMENT ON TABLE administration.administrative_elevation IS 'Administration-owned temporary/JIT authority process; Governance ApprovalCase is referenced evidence only.';

CREATE TABLE administration.administrative_grant (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    actor_identity_id uuid NOT NULL,
    role_id uuid NOT NULL,
    scope_type character varying(48) NOT NULL,
    scope_resource_type character varying(128),
    scope_ref_id uuid,
    state character varying(24) NOT NULL,
    valid_from timestamp with time zone,
    valid_until timestamp with time zone,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    scope_key character varying(64),
    grantable boolean DEFAULT false NOT NULL,
    delegable boolean DEFAULT false NOT NULL,
    authority_basis_grant_id uuid,
    CONSTRAINT administration_grant_authority_basis_not_self_ck CHECK (((authority_basis_grant_id IS NULL) OR (authority_basis_grant_id <> id))),
    CONSTRAINT administration_grant_revision_ck CHECK ((revision > 0)),
    CONSTRAINT administration_grant_scope_shape_ck CHECK (((((scope_type)::text = 'GLOBAL'::text) AND (scope_resource_type IS NULL) AND (scope_ref_id IS NULL) AND (scope_key IS NULL)) OR (((scope_type)::text = 'SPECIFIC_RESOURCE'::text) AND (scope_resource_type IS NOT NULL) AND (btrim((scope_resource_type)::text) <> ''::text) AND (scope_ref_id IS NOT NULL) AND (scope_key IS NULL)) OR (((scope_type)::text = 'CANONICAL_ATTRIBUTE_CLASSIFICATION'::text) AND (scope_resource_type IS NULL) AND (scope_ref_id IS NULL) AND (scope_key IS NOT NULL) AND (btrim((scope_key)::text) <> ''::text)) OR (((scope_type)::text <> ALL ((ARRAY['GLOBAL'::character varying, 'SPECIFIC_RESOURCE'::character varying, 'CANONICAL_ATTRIBUTE_CLASSIFICATION'::character varying])::text[])) AND (scope_resource_type IS NULL) AND (scope_ref_id IS NOT NULL) AND (scope_key IS NULL)))),
    CONSTRAINT administration_grant_scope_type_ck CHECK (((scope_type)::text = ANY ((ARRAY['GLOBAL'::character varying, 'ORGANIZATION'::character varying, 'APPLICATION'::character varying, 'APPLICATION_TARGET'::character varying, 'SOURCE_SYSTEM'::character varying, 'CONNECTOR_INSTANCE'::character varying, 'IDENTITY_POPULATION'::character varying, 'CANONICAL_ATTRIBUTE_CLASSIFICATION'::character varying, 'SPECIFIC_RESOURCE'::character varying])::text[]))),
    CONSTRAINT administration_grant_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'REVOKED'::character varying])::text[]))),
    CONSTRAINT administration_grant_timestamp_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT administration_grant_validity_ck CHECK (((valid_until IS NULL) OR (valid_from IS NULL) OR (valid_until > valid_from)))
);

COMMENT ON TABLE administration.administrative_grant IS 'Tenant-scoped IAM control-plane authority. actor_identity_id is a cross-capability stable Identity ID and intentionally has no database FK.';

COMMENT ON COLUMN administration.administrative_grant.scope_key IS 'Typed opaque string scope key; currently used only for exact canonical attribute classification scope.';

COMMENT ON COLUMN administration.administrative_grant.grantable IS 'Whether this direct grant may be the explicit authority basis for bounded direct-grant creation/elevation. Does not imply manage-authorization permission.';

COMMENT ON COLUMN administration.administrative_grant.delegable IS 'Whether this direct grant may be the explicit authority basis for bounded delegation. Delegation remains a separate authority object.';

COMMENT ON COLUMN administration.administrative_grant.authority_basis_grant_id IS 'Creation-time provenance for a direct grant. The created direct grant remains independent if the basis is later revoked/expired.';

CREATE TABLE administration.administrative_permission (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    resource_type character varying(128) NOT NULL,
    action character varying(128) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT administration_permission_action_ck CHECK ((btrim((action)::text) <> ''::text)),
    CONSTRAINT administration_permission_resource_ck CHECK ((btrim((resource_type)::text) <> ''::text))
);

COMMENT ON TABLE administration.administrative_permission IS 'Administration-owned stable semantic IAM control-plane permissions.';

CREATE TABLE administration.administrative_role (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    code character varying(128) NOT NULL,
    name character varying(512) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT administration_role_code_ck CHECK ((btrim((code)::text) <> ''::text)),
    CONSTRAINT administration_role_name_ck CHECK ((btrim((name)::text) <> ''::text)),
    CONSTRAINT administration_role_revision_ck CHECK ((revision > 0)),
    CONSTRAINT administration_role_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE administration.administrative_role IS 'Administration-owned permission bundles; distinct from Catalog IAM Roles.';

CREATE TABLE administration.administrative_role_permission (
    tenant_id uuid NOT NULL,
    role_id uuid NOT NULL,
    permission_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL
);

CREATE TABLE administration.control_plane_actor_binding (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    external_subject_key character(64) NOT NULL,
    issuer character varying(512) NOT NULL,
    subject character varying(512) NOT NULL,
    actor_identity_id uuid NOT NULL,
    state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT administration_actor_binding_issuer_ck CHECK (((btrim((issuer)::text) <> ''::text) AND ((issuer)::text = btrim((issuer)::text)))),
    CONSTRAINT administration_actor_binding_revision_ck CHECK ((revision > 0)),
    CONSTRAINT administration_actor_binding_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'REVOKED'::character varying])::text[]))),
    CONSTRAINT administration_actor_binding_subject_ck CHECK (((btrim((subject)::text) <> ''::text) AND ((subject)::text = btrim((subject)::text)))),
    CONSTRAINT administration_actor_binding_subject_key_ck CHECK ((external_subject_key ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT administration_actor_binding_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE administration.control_plane_actor_binding IS 'Administration-owned mapping from a validated external issuer+subject to one tenant-scoped governed Identity. The binding never grants IAM permission by itself.';

COMMENT ON COLUMN administration.control_plane_actor_binding.external_subject_key IS 'SHA-256 lookup key over length-delimited issuer and subject. Exact issuer+subject remain authoritative and are rechecked on lookup.';

COMMENT ON COLUMN administration.control_plane_actor_binding.actor_identity_id IS 'Cross-capability stable Identity ID; intentionally no database FK to Identity.';

CREATE TABLE administration.initial_admin_bootstrap (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    actor_identity_id uuid NOT NULL,
    actor_binding_id uuid,
    administrative_role_id uuid NOT NULL,
    administrative_grant_id uuid NOT NULL,
    completed_at timestamp with time zone NOT NULL,
    correlation_id uuid NOT NULL,
    authentication_anchor_type character varying(32) NOT NULL,
    actor_principal_id uuid,
    CONSTRAINT administration_initial_bootstrap_anchor_shape_ck CHECK (((((authentication_anchor_type)::text = 'EXTERNAL_SUBJECT'::text) AND (actor_binding_id IS NOT NULL) AND (actor_principal_id IS NULL)) OR (((authentication_anchor_type)::text = 'FIRST_PARTY_PRINCIPAL'::text) AND (actor_binding_id IS NULL) AND (actor_principal_id IS NOT NULL)))),
    CONSTRAINT administration_initial_bootstrap_anchor_type_ck CHECK (((authentication_anchor_type)::text = ANY ((ARRAY['EXTERNAL_SUBJECT'::character varying, 'FIRST_PARTY_PRINCIPAL'::character varying])::text[])))
);

COMMENT ON TABLE administration.initial_admin_bootstrap IS 'Immutable burn-once tenant marker for first-administrator provisioning. Its unique tenant row permanently closes the bootstrap path after the successful transaction commits.';

COMMENT ON COLUMN administration.initial_admin_bootstrap.actor_identity_id IS 'Governed Identity selected as the first administrator; intentionally no cross-capability database FK.';

COMMENT ON COLUMN administration.initial_admin_bootstrap.authentication_anchor_type IS 'Typed authentication source for the immutable first-admin marker: external subject binding or first-party governed Principal.';

COMMENT ON COLUMN administration.initial_admin_bootstrap.actor_principal_id IS 'Cross-capability stable Principal ID used only for FIRST_PARTY_PRINCIPAL bootstrap; intentionally no database FK to Identity-owned state.';

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

CREATE TABLE catalog.application (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    code character varying(128) NOT NULL,
    name character varying(512) NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT catalog_application_code_ck CHECK ((btrim((code)::text) <> ''::text)),
    CONSTRAINT catalog_application_lifecycle_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'RETIRED'::character varying])::text[]))),
    CONSTRAINT catalog_application_name_ck CHECK ((btrim((name)::text) <> ''::text)),
    CONSTRAINT catalog_application_revision_ck CHECK ((revision > 0)),
    CONSTRAINT catalog_application_timestamp_order_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE catalog.application IS 'Catalog-owned authoritative governable business application/capability.';

CREATE TABLE catalog.application_target (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    application_id uuid NOT NULL,
    code character varying(128) NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT catalog_application_target_code_ck CHECK ((btrim((code)::text) <> ''::text)),
    CONSTRAINT catalog_application_target_lifecycle_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'RETIRED'::character varying])::text[]))),
    CONSTRAINT catalog_application_target_revision_ck CHECK ((revision > 0)),
    CONSTRAINT catalog_application_target_timestamp_order_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE catalog.application_target IS 'Catalog-owned authoritative technical target/environment belonging to one Application.';

CREATE TABLE catalog.entitlement (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    application_id uuid NOT NULL,
    application_target_id uuid,
    code character varying(256) NOT NULL,
    native_key character varying(1024),
    entitlement_type character varying(64) NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT catalog_entitlement_code_ck CHECK ((btrim((code)::text) <> ''::text)),
    CONSTRAINT catalog_entitlement_lifecycle_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'RETIRED'::character varying])::text[]))),
    CONSTRAINT catalog_entitlement_native_key_ck CHECK (((native_key IS NULL) OR (btrim((native_key)::text) <> ''::text))),
    CONSTRAINT catalog_entitlement_revision_ck CHECK ((revision > 0)),
    CONSTRAINT catalog_entitlement_timestamp_order_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT catalog_entitlement_type_ck CHECK ((btrim((entitlement_type)::text) <> ''::text))
);

COMMENT ON TABLE catalog.entitlement IS 'Catalog-owned authoritative smallest governable technical access unit; provider observations never mutate this table directly.';

CREATE TABLE catalog.role (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    role_type character varying(24) NOT NULL,
    application_id uuid,
    code character varying(128) NOT NULL,
    name character varying(512) NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT catalog_role_code_ck CHECK ((btrim((code)::text) <> ''::text)),
    CONSTRAINT catalog_role_lifecycle_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'RETIRED'::character varying])::text[]))),
    CONSTRAINT catalog_role_name_ck CHECK ((btrim((name)::text) <> ''::text)),
    CONSTRAINT catalog_role_revision_ck CHECK ((revision > 0)),
    CONSTRAINT catalog_role_shape_ck CHECK (((((role_type)::text = 'BUSINESS'::text) AND (application_id IS NULL)) OR (((role_type)::text = 'APPLICATION'::text) AND (application_id IS NOT NULL)))),
    CONSTRAINT catalog_role_timestamp_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT catalog_role_type_ck CHECK (((role_type)::text = ANY ((ARRAY['BUSINESS'::character varying, 'APPLICATION'::character varying])::text[])))
);

COMMENT ON TABLE catalog.role IS 'Catalog-owned governed BUSINESS or APPLICATION access package.';

CREATE TABLE catalog.role_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    role_id uuid NOT NULL,
    version_number bigint NOT NULL,
    state character varying(24) NOT NULL,
    content_hash character varying(128) NOT NULL,
    activated_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    CONSTRAINT catalog_role_version_activation_ck CHECK (((((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUPERSEDED'::character varying])::text[])) AND (activated_at IS NOT NULL)) OR (((state)::text <> ALL ((ARRAY['ACTIVE'::character varying, 'SUPERSEDED'::character varying])::text[])) AND (activated_at IS NULL)))),
    CONSTRAINT catalog_role_version_hash_ck CHECK ((btrim((content_hash)::text) <> ''::text)),
    CONSTRAINT catalog_role_version_number_ck CHECK ((version_number > 0)),
    CONSTRAINT catalog_role_version_revision_ck CHECK ((revision > 0)),
    CONSTRAINT catalog_role_version_state_ck CHECK (((state)::text = ANY ((ARRAY['DRAFT'::character varying, 'VALIDATING'::character varying, 'READY'::character varying, 'ACTIVE'::character varying, 'SUPERSEDED'::character varying, 'REJECTED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT catalog_role_version_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE catalog.role_version IS 'Versioned Role composition. Activated and superseded content is immutable.';

COMMENT ON COLUMN catalog.role_version.revision IS 'Optimistic authoritative revision for RoleVersion lifecycle transitions.';

CREATE TABLE catalog.role_version_member (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    role_version_id uuid NOT NULL,
    member_kind character varying(32) NOT NULL,
    member_role_id uuid,
    member_entitlement_id uuid,
    ordinal integer NOT NULL,
    CONSTRAINT catalog_role_version_member_kind_ck CHECK (((member_kind)::text = ANY ((ARRAY['APPLICATION_ROLE'::character varying, 'ENTITLEMENT'::character varying])::text[]))),
    CONSTRAINT catalog_role_version_member_ordinal_ck CHECK ((ordinal >= 0)),
    CONSTRAINT catalog_role_version_member_shape_ck CHECK (((((member_kind)::text = 'APPLICATION_ROLE'::text) AND (member_role_id IS NOT NULL) AND (member_entitlement_id IS NULL)) OR (((member_kind)::text = 'ENTITLEMENT'::text) AND (member_entitlement_id IS NOT NULL) AND (member_role_id IS NULL))))
);

COMMENT ON TABLE catalog.role_version_member IS 'Normalized typed RoleVersion composition; graph/type validity is enforced by Catalog domain logic.';

CREATE TABLE catalog.sso_client_redirect_uri (
    tenant_id uuid NOT NULL,
    sso_client_registration_id uuid NOT NULL,
    redirect_uri character varying(2048) NOT NULL,
    CONSTRAINT catalog_sso_client_redirect_uri_ck CHECK ((btrim((redirect_uri)::text) <> ''::text))
);

COMMENT ON TABLE catalog.sso_client_redirect_uri IS 'Exact registered redirect URIs for one governed SSO client; wildcard redirect matching is prohibited.';

CREATE TABLE catalog.sso_client_registration (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    application_id uuid NOT NULL,
    client_id character varying(200) NOT NULL,
    requires_governed_access boolean DEFAULT true NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT catalog_sso_client_client_id_ck CHECK ((btrim((client_id)::text) <> ''::text)),
    CONSTRAINT catalog_sso_client_lifecycle_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'RETIRED'::character varying])::text[]))),
    CONSTRAINT catalog_sso_client_revision_ck CHECK ((revision > 0)),
    CONSTRAINT catalog_sso_client_timestamp_order_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE catalog.sso_client_registration IS 'Catalog-owned governed public OIDC relying-party registration; framework client records are projections only.';

CREATE TABLE catalog.sso_client_scope (
    tenant_id uuid NOT NULL,
    sso_client_registration_id uuid NOT NULL,
    scope character varying(32) NOT NULL,
    CONSTRAINT catalog_sso_client_scope_value_ck CHECK (((scope)::text = ANY ((ARRAY['openid'::character varying, 'profile'::character varying, 'email'::character varying])::text[])))
);

COMMENT ON TABLE catalog.sso_client_scope IS 'Curated protocol scopes for one governed SSO client; these values are not Wyrmgate AdministrativePermission.';

CREATE TABLE credential.credential (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    credential_kind character varying(32) NOT NULL,
    secret_provider_type character varying(64) NOT NULL,
    secret_reference_key character varying(512) NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    valid_from timestamp with time zone,
    valid_until timestamp with time zone,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    compromised_at timestamp with time zone,
    revoked_at timestamp with time zone,
    expired_at timestamp with time zone,
    CONSTRAINT credential_credential_kind_ck CHECK (((credential_kind)::text = ANY ((ARRAY['PASSWORD'::character varying, 'API_KEY'::character varying, 'SSH_KEY'::character varying, 'CERTIFICATE'::character varying, 'OAUTH_CLIENT_SECRET'::character varying])::text[]))),
    CONSTRAINT credential_credential_provider_ck CHECK ((btrim((secret_provider_type)::text) <> ''::text)),
    CONSTRAINT credential_credential_reference_ck CHECK ((btrim((secret_reference_key)::text) <> ''::text)),
    CONSTRAINT credential_credential_revision_ck CHECK ((revision > 0)),
    CONSTRAINT credential_credential_scheduled_ck CHECK ((((lifecycle_state)::text <> 'SCHEDULED'::text) OR (valid_from IS NOT NULL))),
    CONSTRAINT credential_credential_state_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'ACTIVE'::character varying, 'REVOKED'::character varying, 'EXPIRED'::character varying, 'COMPROMISED'::character varying])::text[]))),
    CONSTRAINT credential_credential_terminal_ck CHECK (((((lifecycle_state)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'ACTIVE'::character varying])::text[])) AND (compromised_at IS NULL) AND (revoked_at IS NULL) AND (expired_at IS NULL)) OR (((lifecycle_state)::text = 'COMPROMISED'::text) AND (compromised_at IS NOT NULL) AND (revoked_at IS NULL) AND (expired_at IS NULL)) OR (((lifecycle_state)::text = 'REVOKED'::text) AND (revoked_at IS NOT NULL) AND (expired_at IS NULL)) OR (((lifecycle_state)::text = 'EXPIRED'::text) AND (expired_at IS NOT NULL) AND (revoked_at IS NULL)))),
    CONSTRAINT credential_credential_timestamp_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT credential_credential_validity_ck CHECK (((valid_from IS NULL) OR (valid_until IS NULL) OR (valid_until > valid_from)))
);

COMMENT ON TABLE credential.credential IS 'Credential metadata only. secret_provider_type + secret_reference_key are opaque external references; raw secret/private material is structurally absent.';

COMMENT ON COLUMN credential.credential.principal_id IS 'Stable cross-capability Principal reference; intentionally no Identity database foreign key.';

CREATE TABLE credential.credential_rotation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    old_credential_id uuid NOT NULL,
    replacement_credential_id uuid,
    initiator_identity_id uuid NOT NULL,
    process_state character varying(32) NOT NULL,
    checkpoint character varying(256),
    failure_code character varying(128),
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT credential_rotation_completion_ck CHECK (((((process_state)::text = ANY ((ARRAY['COMPLETED'::character varying, 'FAILED'::character varying, 'MANUAL_REQUIRED'::character varying, 'FAILED_REMEDIATION'::character varying])::text[])) AND (completed_at IS NOT NULL)) OR (((process_state)::text <> ALL ((ARRAY['COMPLETED'::character varying, 'FAILED'::character varying, 'MANUAL_REQUIRED'::character varying, 'FAILED_REMEDIATION'::character varying])::text[])) AND (completed_at IS NULL)))),
    CONSTRAINT credential_rotation_old_replacement_ck CHECK (((replacement_credential_id IS NULL) OR (replacement_credential_id <> old_credential_id))),
    CONSTRAINT credential_rotation_revision_ck CHECK ((revision > 0)),
    CONSTRAINT credential_rotation_state_ck CHECK (((process_state)::text = ANY ((ARRAY['PLANNED'::character varying, 'CREATING_REPLACEMENT'::character varying, 'DISTRIBUTING'::character varying, 'VERIFYING'::character varying, 'CUTOVER_COMPLETE'::character varying, 'REVOKING_OLD'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'MANUAL_REQUIRED'::character varying, 'FAILED_REMEDIATION'::character varying])::text[]))),
    CONSTRAINT credential_rotation_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE credential.credential_rotation IS 'Durable Credential-owned rotation orchestration. Process state is separate from Credential lifecycle and provider fulfillment.';

CREATE TABLE credential.local_password_verifier (
    reference_key character varying(64) NOT NULL,
    tenant_id uuid NOT NULL,
    credential_id uuid NOT NULL,
    algorithm character varying(64) NOT NULL,
    iterations integer NOT NULL,
    salt bytea NOT NULL,
    verifier bytea NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT credential_local_password_iterations_ck CHECK ((iterations > 0)),
    CONSTRAINT credential_local_password_salt_ck CHECK ((octet_length(salt) >= 16)),
    CONSTRAINT credential_local_password_verifier_ck CHECK ((octet_length(verifier) >= 32))
);

COMMENT ON TABLE credential.local_password_verifier IS 'Credential-private one-way verifier material for Wyrmgate first-party PASSWORD authentication. This table is not ordinary IAM state and is never exposed through ordinary APIs/events/audit.';

COMMENT ON COLUMN credential.local_password_verifier.reference_key IS 'Opaque system-managed SecretReference key. Ordinary Credential creation cannot select this provider type.';

COMMENT ON COLUMN credential.local_password_verifier.verifier IS 'Salted one-way verifier material; never plaintext password or reversible secret material.';

CREATE TABLE governance.access_request (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    requester_identity_id uuid NOT NULL,
    beneficiary_identity_id uuid NOT NULL,
    state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    submitted_at timestamp with time zone,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT access_request_revision_ck CHECK ((revision > 0)),
    CONSTRAINT access_request_state_ck CHECK (((state)::text = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[]))),
    CONSTRAINT access_request_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE governance.access_request IS 'Governance-owned access request envelope; authorization and downstream Access application remain separate.';

CREATE TABLE governance.approval_approver (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    approval_stage_id uuid NOT NULL,
    approver_identity_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL
);

CREATE TABLE governance.approval_case (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    subject_kind character varying(64) NOT NULL,
    subject_id uuid NOT NULL,
    initiator_identity_id uuid NOT NULL,
    state character varying(24) NOT NULL,
    current_stage_ordinal integer DEFAULT 0 NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT approval_case_revision_ck CHECK ((revision > 0)),
    CONSTRAINT approval_case_stage_ck CHECK ((current_stage_ordinal >= 0)),
    CONSTRAINT approval_case_state_ck CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'CANCELLED'::character varying, 'SUPERSEDED'::character varying])::text[]))),
    CONSTRAINT approval_case_subject_kind_ck CHECK (((subject_kind)::text = ANY ((ARRAY['ACCESS_REQUEST_ITEM'::character varying, 'ADMINISTRATIVE_ELEVATION'::character varying, 'ROLE_VERSION_ACTIVATION'::character varying, 'GOVERNANCE_EXCEPTION'::character varying, 'LIFECYCLE_ACCESS_CANDIDATE'::character varying])::text[]))),
    CONSTRAINT approval_case_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE governance.approval_case IS 'Governance-owned reusable typed approval process state. Calling capabilities retain ownership of the approved business object.';

CREATE TABLE governance.approval_decision (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    approval_case_id uuid NOT NULL,
    approval_plan_id uuid NOT NULL,
    approval_stage_id uuid NOT NULL,
    approver_identity_id uuid NOT NULL,
    decision character varying(16) NOT NULL,
    reason character varying(1000),
    decided_at timestamp with time zone NOT NULL,
    CONSTRAINT approval_decision_value_ck CHECK (((decision)::text = ANY ((ARRAY['APPROVE'::character varying, 'REJECT'::character varying])::text[])))
);

COMMENT ON TABLE governance.approval_decision IS 'Append-only immutable approval/rejection evidence.';

CREATE TABLE governance.approval_plan (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    approval_case_id uuid NOT NULL,
    plan_number bigint NOT NULL,
    content_hash character varying(128) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT approval_plan_number_ck CHECK ((plan_number > 0))
);

COMMENT ON TABLE governance.approval_plan IS 'Immutable approval plan snapshot. Replacement creates another plan rather than mutating prior plan semantics.';

CREATE TABLE governance.approval_stage (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    approval_plan_id uuid NOT NULL,
    stage_ordinal integer NOT NULL,
    decision_mode character varying(16) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT approval_stage_mode_ck CHECK (((decision_mode)::text = ANY ((ARRAY['ANY_ONE'::character varying, 'ALL'::character varying])::text[]))),
    CONSTRAINT approval_stage_ordinal_ck CHECK ((stage_ordinal >= 0))
);

CREATE TABLE governance.finding (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    finding_key character varying(128) NOT NULL,
    finding_type character varying(64) NOT NULL,
    subject_kind character varying(64) NOT NULL,
    connector_binding_id uuid NOT NULL,
    provider_stable_id character varying(512) NOT NULL,
    related_provider_stable_id character varying(512),
    lifecycle_state character varying(24) NOT NULL,
    first_observed_at timestamp with time zone NOT NULL,
    last_observed_at timestamp with time zone NOT NULL,
    resolved_at timestamp with time zone,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT governance_finding_observed_time_ck CHECK ((last_observed_at >= first_observed_at)),
    CONSTRAINT governance_finding_provider_ck CHECK ((btrim((provider_stable_id)::text) <> ''::text)),
    CONSTRAINT governance_finding_revision_ck CHECK ((revision > 0)),
    CONSTRAINT governance_finding_state_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['OPEN'::character varying, 'RESOLVED'::character varying])::text[]))),
    CONSTRAINT governance_finding_state_time_ck CHECK (((((lifecycle_state)::text = 'OPEN'::text) AND (resolved_at IS NULL)) OR (((lifecycle_state)::text = 'RESOLVED'::text) AND (resolved_at IS NOT NULL)))),
    CONSTRAINT governance_finding_subject_kind_ck CHECK (((subject_kind)::text = ANY ((ARRAY['OBSERVED_ENTITLEMENT'::character varying, 'OBSERVED_GRANT'::character varying])::text[]))),
    CONSTRAINT governance_finding_timestamp_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT governance_finding_type_ck CHECK (((finding_type)::text = ANY ((ARRAY['UNMAPPED_PROVIDER_ENTITLEMENT'::character varying, 'UNMAPPED_PROVIDER_GRANT_ENTITLEMENT'::character varying, 'UNRESOLVED_PROVIDER_GRANT_PRINCIPAL'::character varying])::text[])))
);

COMMENT ON TABLE governance.finding IS 'Governance-owned actionable issue state. Observation-derived findings contain minimized normalized identifiers, never provider payloads or secrets.';

CREATE TABLE governance.governance_exception (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    scope_kind character varying(32) NOT NULL,
    subject_identity_id uuid NOT NULL,
    sod_rule_id uuid NOT NULL,
    requester_identity_id uuid NOT NULL,
    business_reason character varying(1000) NOT NULL,
    valid_from timestamp with time zone NOT NULL,
    valid_until timestamp with time zone NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    approval_case_id uuid,
    predecessor_exception_id uuid,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    approved_at timestamp with time zone,
    rejected_at timestamp with time zone,
    revoked_at timestamp with time zone,
    expired_at timestamp with time zone,
    CONSTRAINT governance_exception_approval_state_ck CHECK (((((lifecycle_state)::text = 'PENDING_APPROVAL'::text) AND (approval_case_id IS NOT NULL) AND (approved_at IS NULL) AND (rejected_at IS NULL) AND (revoked_at IS NULL) AND (expired_at IS NULL)) OR (((lifecycle_state)::text = 'APPROVED'::text) AND (approval_case_id IS NOT NULL) AND (approved_at IS NOT NULL) AND (rejected_at IS NULL) AND (revoked_at IS NULL) AND (expired_at IS NULL)) OR (((lifecycle_state)::text = 'REJECTED'::text) AND (approval_case_id IS NOT NULL) AND (approved_at IS NULL) AND (rejected_at IS NOT NULL) AND (revoked_at IS NULL) AND (expired_at IS NULL)) OR (((lifecycle_state)::text = 'REVOKED'::text) AND (approval_case_id IS NOT NULL) AND (approved_at IS NOT NULL) AND (rejected_at IS NULL) AND (revoked_at IS NOT NULL) AND (expired_at IS NULL)) OR (((lifecycle_state)::text = 'EXPIRED'::text) AND (approval_case_id IS NOT NULL) AND (approved_at IS NOT NULL) AND (rejected_at IS NULL) AND (revoked_at IS NULL) AND (expired_at IS NOT NULL)))),
    CONSTRAINT governance_exception_reason_ck CHECK ((btrim((business_reason)::text) <> ''::text)),
    CONSTRAINT governance_exception_revision_ck CHECK ((revision > 0)),
    CONSTRAINT governance_exception_scope_ck CHECK (((scope_kind)::text = 'IDENTITY_SOD_RULE'::text)),
    CONSTRAINT governance_exception_state_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['PENDING_APPROVAL'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'REVOKED'::character varying, 'EXPIRED'::character varying])::text[]))),
    CONSTRAINT governance_exception_timestamp_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT governance_exception_validity_ck CHECK ((valid_until > valid_from))
);

COMMENT ON TABLE governance.governance_exception IS 'Governance-owned scoped time-bound exception authority. Effectiveness requires APPROVED lifecycle plus semantic validity window.';

COMMENT ON COLUMN governance.governance_exception.subject_identity_id IS 'Stable cross-capability Identity reference; intentionally no database foreign key.';

COMMENT ON COLUMN governance.governance_exception.sod_rule_id IS 'Exact immutable SoDRule scope. Successor PolicyVersions do not inherit this exception.';

CREATE TABLE governance.lifecycle_access_approval_candidate (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    lifecycle_rule_id uuid NOT NULL,
    target_kind character varying(32) NOT NULL,
    target_id uuid NOT NULL,
    policy_version_id uuid NOT NULL,
    approval_plan_hash character varying(64) NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT governance_lifecycle_access_candidate_target_kind_ck CHECK (((target_kind)::text = ANY ((ARRAY['ROLE'::character varying, 'ENTITLEMENT'::character varying])::text[])))
);

COMMENT ON TABLE governance.lifecycle_access_approval_candidate IS 'Immutable Governance context for one lifecycle-policy privilege candidate requiring approval; never AccessAssignment authority.';

CREATE TABLE governance.lifecycle_access_evaluation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    lifecycle_rule_id uuid NOT NULL,
    governance_policy_version_id uuid,
    target_kind character varying(16) NOT NULL,
    target_id uuid NOT NULL,
    decision character varying(24) NOT NULL,
    evaluation_code character varying(128) NOT NULL,
    conflict_count integer NOT NULL,
    evaluated_at timestamp with time zone NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    CONSTRAINT governance_lifecycle_access_eval_code_ck CHECK ((btrim((evaluation_code)::text) <> ''::text)),
    CONSTRAINT governance_lifecycle_access_eval_conflict_count_ck CHECK ((conflict_count >= 0)),
    CONSTRAINT governance_lifecycle_access_eval_decision_ck CHECK (((decision)::text = ANY ((ARRAY['AUTHORIZE'::character varying, 'REQUIRE_APPROVAL'::character varying, 'DENY'::character varying, 'UNAVAILABLE'::character varying])::text[]))),
    CONSTRAINT governance_lifecycle_access_eval_target_ck CHECK (((target_kind)::text = ANY ((ARRAY['ROLE'::character varying, 'ENTITLEMENT'::character varying])::text[])))
);

COMMENT ON TABLE governance.lifecycle_access_evaluation IS 'Immutable Governance evidence for lifecycle/birthright automatic privilege evaluation.';

CREATE TABLE governance.lifecycle_access_sod_conflict (
    tenant_id uuid NOT NULL,
    evaluation_id uuid NOT NULL,
    sod_rule_id uuid NOT NULL,
    severity character varying(16) NOT NULL,
    enforcement_action character varying(24) NOT NULL,
    governance_exception_id uuid,
    CONSTRAINT governance_lifecycle_access_conflict_action_ck CHECK (((enforcement_action)::text = ANY ((ARRAY['REQUIRE_APPROVAL'::character varying, 'DENY'::character varying])::text[]))),
    CONSTRAINT governance_lifecycle_access_conflict_severity_ck CHECK (((severity)::text = ANY ((ARRAY['LOW'::character varying, 'MEDIUM'::character varying, 'HIGH'::character varying, 'CRITICAL'::character varying])::text[])))
);

COMMENT ON TABLE governance.lifecycle_access_sod_conflict IS 'Immutable matched SoD-rule evidence for one lifecycle-access evaluation, including exact exception coverage when present.';

CREATE TABLE governance.policy (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    policy_kind character varying(32) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT governance_policy_kind_ck CHECK (((policy_kind)::text = 'ACCESS_REQUEST'::text)),
    CONSTRAINT governance_policy_revision_ck CHECK ((revision > 0)),
    CONSTRAINT governance_policy_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE governance.policy IS 'Governance-owned stable policy identity. Typed versions own immutable evaluated content.';

CREATE TABLE governance.policy_approval_approver (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    policy_approval_stage_id uuid NOT NULL,
    approver_identity_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL
);

CREATE TABLE governance.policy_approval_stage (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    policy_version_id uuid NOT NULL,
    stage_ordinal integer NOT NULL,
    decision_mode character varying(16) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT governance_policy_stage_mode_ck CHECK (((decision_mode)::text = ANY ((ARRAY['ANY_ONE'::character varying, 'ALL'::character varying])::text[]))),
    CONSTRAINT governance_policy_stage_ordinal_ck CHECK ((stage_ordinal >= 0))
);

CREATE TABLE governance.policy_evaluation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    access_request_id uuid NOT NULL,
    request_item_id uuid NOT NULL,
    request_item_revision bigint NOT NULL,
    policy_version_id uuid NOT NULL,
    risk_assessment_id uuid NOT NULL,
    decision character varying(24) NOT NULL,
    evaluation_code character varying(128) NOT NULL,
    evaluated_at timestamp with time zone NOT NULL,
    CONSTRAINT governance_policy_evaluation_code_ck CHECK ((btrim((evaluation_code)::text) <> ''::text)),
    CONSTRAINT governance_policy_evaluation_decision_ck CHECK (((decision)::text = ANY ((ARRAY['AUTHORIZE'::character varying, 'REQUIRE_APPROVAL'::character varying, 'DENY'::character varying])::text[]))),
    CONSTRAINT governance_policy_evaluation_revision_ck CHECK ((request_item_revision > 0))
);

COMMENT ON TABLE governance.policy_evaluation IS 'Immutable policy decision evidence for a RequestItem revision; risk explains factors while policy decides action.';

CREATE TABLE governance.policy_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    policy_id uuid NOT NULL,
    version_number bigint NOT NULL,
    state character varying(24) NOT NULL,
    default_decision character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    activated_at timestamp with time zone,
    superseded_at timestamp with time zone,
    CONSTRAINT governance_policy_version_activation_ck CHECK (((((state)::text = ANY ((ARRAY['DRAFT'::character varying, 'READY'::character varying, 'CANCELLED'::character varying])::text[])) AND (activated_at IS NULL) AND (superseded_at IS NULL)) OR (((state)::text = 'ACTIVE'::text) AND (activated_at IS NOT NULL) AND (superseded_at IS NULL)) OR (((state)::text = 'SUPERSEDED'::text) AND (activated_at IS NOT NULL) AND (superseded_at IS NOT NULL)))),
    CONSTRAINT governance_policy_version_decision_ck CHECK (((default_decision)::text = ANY ((ARRAY['AUTHORIZE'::character varying, 'REQUIRE_APPROVAL'::character varying, 'DENY'::character varying])::text[]))),
    CONSTRAINT governance_policy_version_number_ck CHECK ((version_number > 0)),
    CONSTRAINT governance_policy_version_revision_ck CHECK ((revision > 0)),
    CONSTRAINT governance_policy_version_state_ck CHECK (((state)::text = ANY ((ARRAY['DRAFT'::character varying, 'READY'::character varying, 'ACTIVE'::character varying, 'SUPERSEDED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT governance_policy_version_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE governance.policy_version IS 'Versioned ACCESS_REQUEST policy. Activated/superseded content is immutable; rollback creates a new version.';

CREATE TABLE governance.request_item (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    access_request_id uuid NOT NULL,
    target_kind character varying(16) NOT NULL,
    role_id uuid,
    entitlement_id uuid,
    state character varying(24) NOT NULL,
    approval_case_id uuid,
    evaluation_code character varying(128),
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    principal_constraint_kind character varying(16) NOT NULL,
    specific_principal_id uuid,
    valid_from timestamp with time zone,
    valid_until timestamp with time zone,
    access_assignment_id uuid,
    CONSTRAINT request_item_access_assignment_shape_ck CHECK (((((state)::text = 'APPLIED'::text) AND (access_assignment_id IS NOT NULL)) OR (((state)::text <> 'APPLIED'::text) AND (access_assignment_id IS NULL)))),
    CONSTRAINT request_item_principal_constraint_kind_ck CHECK (((principal_constraint_kind)::text = ANY ((ARRAY['ANY'::character varying, 'SPECIFIC'::character varying])::text[]))),
    CONSTRAINT request_item_principal_constraint_shape_ck CHECK (((((principal_constraint_kind)::text = 'SPECIFIC'::text) AND (specific_principal_id IS NOT NULL)) OR (((principal_constraint_kind)::text = 'ANY'::text) AND (specific_principal_id IS NULL)))),
    CONSTRAINT request_item_revision_ck CHECK ((revision > 0)),
    CONSTRAINT request_item_state_ck CHECK (((state)::text = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'EVALUATING'::character varying, 'PENDING_APPROVAL'::character varying, 'AUTHORIZED'::character varying, 'APPLIED'::character varying, 'DENIED'::character varying, 'REJECTED'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[]))),
    CONSTRAINT request_item_target_kind_ck CHECK (((target_kind)::text = ANY ((ARRAY['ROLE'::character varying, 'ENTITLEMENT'::character varying])::text[]))),
    CONSTRAINT request_item_target_shape_ck CHECK (((((target_kind)::text = 'ROLE'::text) AND (role_id IS NOT NULL) AND (entitlement_id IS NULL)) OR (((target_kind)::text = 'ENTITLEMENT'::text) AND (entitlement_id IS NOT NULL) AND (role_id IS NULL)))),
    CONSTRAINT request_item_timestamp_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT request_item_validity_ck CHECK (((valid_until IS NULL) OR (valid_from IS NULL) OR (valid_until > valid_from)))
);

COMMENT ON TABLE governance.request_item IS 'Scalable governable unit for requested Role-or-Entitlement access.';

COMMENT ON COLUMN governance.request_item.access_assignment_id IS 'Stable Access-owned AccessAssignment ID recorded after application. Intentionally no cross-capability foreign key.';

CREATE TABLE governance.review_campaign (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    campaign_kind character varying(32) NOT NULL,
    subject_identity_id uuid NOT NULL,
    reviewer_identity_id uuid NOT NULL,
    snapshot_at timestamp with time zone NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    generation_after_created_at timestamp with time zone,
    generation_after_id uuid,
    generated_item_count bigint DEFAULT 0 NOT NULL,
    decided_item_count bigint DEFAULT 0 NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    activated_at timestamp with time zone,
    completed_at timestamp with time zone,
    failure_code character varying(128),
    CONSTRAINT governance_review_campaign_checkpoint_ck CHECK ((((generation_after_created_at IS NULL) AND (generation_after_id IS NULL)) OR ((generation_after_created_at IS NOT NULL) AND (generation_after_id IS NOT NULL)))),
    CONSTRAINT governance_review_campaign_completion_ck CHECK (((((lifecycle_state)::text <> 'COMPLETED'::text) AND (completed_at IS NULL)) OR (((lifecycle_state)::text = 'COMPLETED'::text) AND (completed_at IS NOT NULL) AND (decided_item_count = generated_item_count)))),
    CONSTRAINT governance_review_campaign_counts_ck CHECK (((generated_item_count >= 0) AND (decided_item_count >= 0) AND (decided_item_count <= generated_item_count))),
    CONSTRAINT governance_review_campaign_kind_ck CHECK (((campaign_kind)::text = 'IDENTITY_ACCESS'::text)),
    CONSTRAINT governance_review_campaign_revision_ck CHECK ((revision > 0)),
    CONSTRAINT governance_review_campaign_state_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['DRAFT'::character varying, 'GENERATING'::character varying, 'ACTIVE'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT governance_review_campaign_subject_reviewer_ck CHECK ((subject_identity_id <> reviewer_identity_id)),
    CONSTRAINT governance_review_campaign_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE governance.review_campaign IS 'Governance-owned durable review campaign. COMPLETED means all generated items are decided, not that remediation/provider fulfillment completed.';

CREATE TABLE governance.review_decision (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    review_item_id uuid NOT NULL,
    reviewer_identity_id uuid NOT NULL,
    decision character varying(16) NOT NULL,
    reason character varying(1000),
    decided_at timestamp with time zone NOT NULL,
    CONSTRAINT governance_review_decision_value_ck CHECK (((decision)::text = ANY ((ARRAY['KEEP'::character varying, 'REVOKE'::character varying])::text[])))
);

COMMENT ON TABLE governance.review_decision IS 'Immutable reviewer evidence: KEEP or REVOKE.';

CREATE TABLE governance.review_item (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    review_campaign_id uuid NOT NULL,
    reviewer_identity_id uuid NOT NULL,
    access_assignment_id uuid NOT NULL,
    assignment_revision bigint NOT NULL,
    target_kind character varying(24) NOT NULL,
    role_id uuid,
    entitlement_id uuid,
    principal_constraint_kind character varying(16) NOT NULL,
    specific_principal_id uuid,
    provenance_kind character varying(24) NOT NULL,
    provenance_ref_id uuid,
    snapshot_lifecycle_state character varying(24) NOT NULL,
    valid_from timestamp with time zone,
    valid_until timestamp with time zone,
    assignment_created_at timestamp with time zone NOT NULL,
    snapshot_at timestamp with time zone NOT NULL,
    lifecycle_state character varying(16) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT governance_review_item_principal_ck CHECK (((((principal_constraint_kind)::text = 'ANY'::text) AND (specific_principal_id IS NULL)) OR (((principal_constraint_kind)::text = 'SPECIFIC'::text) AND (specific_principal_id IS NOT NULL)))),
    CONSTRAINT governance_review_item_provenance_ck CHECK (((((provenance_kind)::text = 'MANUAL'::text) AND (provenance_ref_id IS NULL)) OR (((provenance_kind)::text = 'REQUEST_ITEM'::text) AND (provenance_ref_id IS NOT NULL)))),
    CONSTRAINT governance_review_item_revision_ck CHECK (((assignment_revision > 0) AND (revision > 0))),
    CONSTRAINT governance_review_item_snapshot_state_ck CHECK (((snapshot_lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUSPENDED'::character varying, 'SCHEDULED'::character varying])::text[]))),
    CONSTRAINT governance_review_item_state_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['PENDING'::character varying, 'DECIDED'::character varying])::text[]))),
    CONSTRAINT governance_review_item_target_ck CHECK (((((target_kind)::text = 'ROLE'::text) AND (role_id IS NOT NULL) AND (entitlement_id IS NULL)) OR (((target_kind)::text = 'ENTITLEMENT'::text) AND (entitlement_id IS NOT NULL) AND (role_id IS NULL)))),
    CONSTRAINT governance_review_item_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE governance.review_item IS 'Scalable immutable review snapshot of one authoritative AccessAssignment at campaign snapshotAt. Access IDs are cross-capability references without database FKs.';

CREATE TABLE governance.review_remediation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    review_item_id uuid NOT NULL,
    access_assignment_id uuid NOT NULL,
    lifecycle_state character varying(32) NOT NULL,
    result_code character varying(128),
    resulting_access_state character varying(32),
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT governance_review_remediation_completion_ck CHECK (((((lifecycle_state)::text = 'PENDING'::text) AND (completed_at IS NULL)) OR (((lifecycle_state)::text <> 'PENDING'::text) AND (completed_at IS NOT NULL)))),
    CONSTRAINT governance_review_remediation_revision_ck CHECK ((revision > 0)),
    CONSTRAINT governance_review_remediation_state_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['PENDING'::character varying, 'APPLIED'::character varying, 'NO_ACTION_REQUIRED'::character varying, 'FAILED'::character varying, 'MANUAL_REQUIRED'::character varying])::text[]))),
    CONSTRAINT governance_review_remediation_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE governance.review_remediation IS 'Governance-owned remediation process state created from REVOKE decisions; Access remains authoritative for assignment mutation.';

CREATE TABLE governance.risk_assessment (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    request_item_id uuid NOT NULL,
    policy_version_id uuid NOT NULL,
    severity character varying(16) NOT NULL,
    factor_count integer NOT NULL,
    assessed_at timestamp with time zone NOT NULL,
    CONSTRAINT governance_risk_assessment_factor_ck CHECK ((factor_count >= 0)),
    CONSTRAINT governance_risk_assessment_severity_ck CHECK (((severity)::text = ANY ((ARRAY['NONE'::character varying, 'LOW'::character varying, 'MEDIUM'::character varying, 'HIGH'::character varying, 'CRITICAL'::character varying])::text[])))
);

COMMENT ON TABLE governance.risk_assessment IS 'Immutable risk result explaining severity/factor count for one completed RequestItem policy evaluation.';

CREATE TABLE governance.sod_conflict (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    risk_assessment_id uuid NOT NULL,
    sod_rule_id uuid NOT NULL,
    requested_entitlement_id uuid NOT NULL,
    conflicting_entitlement_id uuid NOT NULL,
    conflict_source character varying(24) NOT NULL,
    severity character varying(16) NOT NULL,
    enforcement_action character varying(24) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    governance_exception_id uuid,
    CONSTRAINT governance_sod_conflict_action_ck CHECK (((enforcement_action)::text = ANY ((ARRAY['REQUIRE_APPROVAL'::character varying, 'DENY'::character varying])::text[]))),
    CONSTRAINT governance_sod_conflict_severity_ck CHECK (((severity)::text = ANY ((ARRAY['LOW'::character varying, 'MEDIUM'::character varying, 'HIGH'::character varying, 'CRITICAL'::character varying])::text[]))),
    CONSTRAINT governance_sod_conflict_source_ck CHECK (((conflict_source)::text = ANY ((ARRAY['CURRENT_ACCESS'::character varying, 'REQUEST_ITEM'::character varying, 'REQUEST'::character varying])::text[])))
);

COMMENT ON TABLE governance.sod_conflict IS 'Immutable evidence of one matched SoD rule and its current/requested conflict context.';

COMMENT ON COLUMN governance.sod_conflict.governance_exception_id IS 'Optional immutable evidence reference to the exact effective GovernanceException that waived this matched rule action; the conflict remains persisted.';

CREATE TABLE governance.sod_rule (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    policy_version_id uuid NOT NULL,
    rule_code character varying(128) NOT NULL,
    left_entitlement_id uuid NOT NULL,
    right_entitlement_id uuid NOT NULL,
    severity character varying(16) NOT NULL,
    enforcement_action character varying(24) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT governance_sod_rule_action_ck CHECK (((enforcement_action)::text = ANY ((ARRAY['REQUIRE_APPROVAL'::character varying, 'DENY'::character varying])::text[]))),
    CONSTRAINT governance_sod_rule_code_ck CHECK ((btrim((rule_code)::text) <> ''::text)),
    CONSTRAINT governance_sod_rule_pair_ck CHECK (((left_entitlement_id <> right_entitlement_id) AND (left_entitlement_id < right_entitlement_id))),
    CONSTRAINT governance_sod_rule_severity_ck CHECK (((severity)::text = ANY ((ARRAY['LOW'::character varying, 'MEDIUM'::character varying, 'HIGH'::character varying, 'CRITICAL'::character varying])::text[])))
);

COMMENT ON TABLE governance.sod_rule IS 'Typed symmetric entitlement-pair SoD rule; Catalog stable IDs are cross-capability references without foreign keys.';

CREATE TABLE identity.attribute_authority_rule_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    attribute_definition_version_id uuid CONSTRAINT attribute_authority_rule_ve_attribute_definition_versi_not_null NOT NULL,
    source_system_id uuid NOT NULL,
    version_number bigint NOT NULL,
    priority integer NOT NULL,
    state character varying(24) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    activated_at timestamp with time zone,
    superseded_at timestamp with time zone,
    CONSTRAINT identity_attribute_authority_rule_number_ck CHECK ((version_number > 0)),
    CONSTRAINT identity_attribute_authority_rule_priority_ck CHECK ((priority >= 0)),
    CONSTRAINT identity_attribute_authority_rule_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUPERSEDED'::character varying])::text[]))),
    CONSTRAINT identity_attribute_authority_rule_time_ck CHECK (((((state)::text = 'ACTIVE'::text) AND (activated_at IS NOT NULL) AND (superseded_at IS NULL)) OR (((state)::text = 'SUPERSEDED'::text) AND (activated_at IS NOT NULL) AND (superseded_at IS NOT NULL) AND (superseded_at >= activated_at))))
);

CREATE TABLE identity.attribute_definition (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    canonical_key character varying(256) NOT NULL,
    subject_type character varying(32) NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_attribute_definition_key_ck CHECK ((btrim((canonical_key)::text) <> ''::text)),
    CONSTRAINT identity_attribute_definition_lifecycle_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'RETIRED'::character varying])::text[]))),
    CONSTRAINT identity_attribute_definition_revision_ck CHECK ((revision > 0)),
    CONSTRAINT identity_attribute_definition_subject_ck CHECK (((subject_type)::text = 'IDENTITY'::text)),
    CONSTRAINT identity_attribute_definition_time_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE identity.attribute_definition IS 'Stable governed canonical attribute identity/key. Versioned semantic shape is stored in attribute_definition_version.';

CREATE TABLE identity.attribute_definition_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    schema_version_id uuid NOT NULL,
    attribute_definition_id uuid NOT NULL,
    data_type character varying(24) NOT NULL,
    cardinality character varying(16) NOT NULL,
    classification character varying(64) NOT NULL,
    queryable boolean NOT NULL,
    searchable boolean NOT NULL,
    policy_addressable boolean NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_attribute_definition_version_cardinality_ck CHECK (((cardinality)::text = ANY ((ARRAY['SINGLE'::character varying, 'MULTI'::character varying])::text[]))),
    CONSTRAINT identity_attribute_definition_version_classification_ck CHECK ((btrim((classification)::text) <> ''::text)),
    CONSTRAINT identity_attribute_definition_version_type_ck CHECK (((data_type)::text = ANY ((ARRAY['STRING'::character varying, 'BOOLEAN'::character varying, 'INTEGER'::character varying, 'DECIMAL'::character varying, 'DATE'::character varying, 'DATETIME'::character varying, 'ENUM'::character varying])::text[])))
);

COMMENT ON TABLE identity.attribute_definition_version IS 'Typed canonical attribute semantics attached to one canonical schema version; activated schema content is immutable by application contract.';

CREATE TABLE identity.attribute_mapping_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    attribute_definition_version_id uuid CONSTRAINT attribute_mapping_version_attribute_definition_version_not_null NOT NULL,
    version_number bigint NOT NULL,
    source_path character varying(512) NOT NULL,
    state character varying(24) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    activated_at timestamp with time zone,
    superseded_at timestamp with time zone,
    CONSTRAINT identity_attribute_mapping_version_number_ck CHECK ((version_number > 0)),
    CONSTRAINT identity_attribute_mapping_version_source_path_ck CHECK ((btrim((source_path)::text) <> ''::text)),
    CONSTRAINT identity_attribute_mapping_version_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUPERSEDED'::character varying])::text[]))),
    CONSTRAINT identity_attribute_mapping_version_time_ck CHECK (((((state)::text = 'ACTIVE'::text) AND (activated_at IS NOT NULL) AND (superseded_at IS NULL)) OR (((state)::text = 'SUPERSEDED'::text) AND (activated_at IS NOT NULL) AND (superseded_at IS NOT NULL) AND (superseded_at >= activated_at))))
);

CREATE TABLE identity.canonical_attribute_candidate (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    attribute_definition_version_id uuid CONSTRAINT canonical_attribute_candida_attribute_definition_versi_not_null NOT NULL,
    source_system_id uuid NOT NULL,
    source_record_id uuid NOT NULL,
    mapping_version_id uuid NOT NULL,
    source_path character varying(512) NOT NULL,
    candidate_revision bigint DEFAULT 1 NOT NULL,
    source_updated_at timestamp with time zone,
    observed_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_canonical_attribute_candidate_path_ck CHECK ((btrim((source_path)::text) <> ''::text)),
    CONSTRAINT identity_canonical_attribute_candidate_revision_ck CHECK ((candidate_revision > 0)),
    CONSTRAINT identity_canonical_attribute_candidate_time_ck CHECK ((updated_at >= observed_at))
);

COMMENT ON TABLE identity.canonical_attribute_candidate IS 'Normalized candidate derived from source observation with explicit mapping provenance; never canonical state by itself.';

CREATE TABLE identity.canonical_attribute_candidate_value (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    candidate_id uuid NOT NULL,
    attribute_definition_version_id uuid CONSTRAINT canonical_attribute_candid_attribute_definition_versi_not_null1 NOT NULL,
    data_type character varying(24) NOT NULL,
    cardinality character varying(16) NOT NULL,
    value_ordinal integer NOT NULL,
    value_string text,
    value_boolean boolean,
    value_integer bigint,
    value_decimal numeric(38,12),
    value_date date,
    value_datetime timestamp with time zone,
    value_enum_key character varying(256),
    CONSTRAINT identity_candidate_value_ordinal_ck CHECK ((value_ordinal >= 0)),
    CONSTRAINT identity_candidate_value_shape_ck CHECK (((((data_type)::text = 'STRING'::text) AND (value_string IS NOT NULL) AND (num_nonnulls(value_boolean, value_integer, value_decimal, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'BOOLEAN'::text) AND (value_boolean IS NOT NULL) AND (num_nonnulls(value_string, value_integer, value_decimal, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'INTEGER'::text) AND (value_integer IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_decimal, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'DECIMAL'::text) AND (value_decimal IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'DATE'::text) AND (value_date IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'DATETIME'::text) AND (value_datetime IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_date, value_enum_key) = 0)) OR (((data_type)::text = 'ENUM'::text) AND (value_enum_key IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_date, value_datetime) = 0))))
);

CREATE TABLE identity.canonical_attribute_override (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    attribute_definition_id uuid NOT NULL,
    attribute_definition_version_id uuid CONSTRAINT canonical_attribute_overrid_attribute_definition_versi_not_null NOT NULL,
    state character varying(24) NOT NULL,
    reason character varying(1024) NOT NULL,
    valid_from timestamp with time zone,
    valid_until timestamp with time zone,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    superseded_at timestamp with time zone,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    CONSTRAINT identity_canonical_attribute_override_reason_ck CHECK ((btrim((reason)::text) <> ''::text)),
    CONSTRAINT identity_canonical_attribute_override_revision_ck CHECK ((revision > 0)),
    CONSTRAINT identity_canonical_attribute_override_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUPERSEDED'::character varying])::text[]))),
    CONSTRAINT identity_canonical_attribute_override_superseded_ck CHECK (((((state)::text = 'ACTIVE'::text) AND (superseded_at IS NULL)) OR (((state)::text = 'SUPERSEDED'::text) AND (superseded_at IS NOT NULL) AND (superseded_at >= created_at)))),
    CONSTRAINT identity_canonical_attribute_override_validity_ck CHECK (((valid_until IS NULL) OR (valid_from IS NULL) OR (valid_until > valid_from)))
);

COMMENT ON TABLE identity.canonical_attribute_override IS 'Explicit governed override history. Overrides supersede resolution while effective but never rewrite source observations or candidates.';

CREATE TABLE identity.canonical_attribute_override_value (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    override_id uuid NOT NULL,
    attribute_definition_version_id uuid CONSTRAINT canonical_attribute_overri_attribute_definition_versi_not_null1 NOT NULL,
    data_type character varying(24) NOT NULL,
    cardinality character varying(16) NOT NULL,
    value_ordinal integer NOT NULL,
    value_string text,
    value_boolean boolean,
    value_integer bigint,
    value_decimal numeric(38,12),
    value_date date,
    value_datetime timestamp with time zone,
    value_enum_key character varying(256),
    CONSTRAINT identity_override_value_ordinal_ck CHECK ((value_ordinal >= 0)),
    CONSTRAINT identity_override_value_shape_ck CHECK (((((data_type)::text = 'STRING'::text) AND (value_string IS NOT NULL) AND (num_nonnulls(value_boolean, value_integer, value_decimal, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'BOOLEAN'::text) AND (value_boolean IS NOT NULL) AND (num_nonnulls(value_string, value_integer, value_decimal, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'INTEGER'::text) AND (value_integer IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_decimal, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'DECIMAL'::text) AND (value_decimal IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'DATE'::text) AND (value_date IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'DATETIME'::text) AND (value_datetime IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_date, value_enum_key) = 0)) OR (((data_type)::text = 'ENUM'::text) AND (value_enum_key IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_date, value_datetime) = 0))))
);

CREATE TABLE identity.canonical_attribute_state (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    attribute_definition_id uuid NOT NULL,
    attribute_definition_version_id uuid CONSTRAINT canonical_attribute_state_attribute_definition_version_not_null NOT NULL,
    resolution_status character varying(24) NOT NULL,
    selected_candidate_id uuid,
    authority_rule_version_id uuid,
    value_revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_canonical_attribute_state_revision_ck CHECK ((value_revision > 0)),
    CONSTRAINT identity_canonical_attribute_state_status_ck CHECK (((resolution_status)::text = ANY ((ARRAY['RESOLVED'::character varying, 'OVERRIDDEN'::character varying, 'CONFLICT'::character varying, 'UNRESOLVED'::character varying, 'NO_VALUE'::character varying])::text[]))),
    CONSTRAINT identity_canonical_attribute_state_time_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE identity.canonical_attribute_state IS 'Current authoritative canonical attribute resolution state. CONFLICT may retain a previously trusted value while exposing degraded resolution status.';

CREATE TABLE identity.canonical_attribute_state_value (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    state_id uuid NOT NULL,
    attribute_definition_version_id uuid CONSTRAINT canonical_attribute_state_v_attribute_definition_versi_not_null NOT NULL,
    data_type character varying(24) NOT NULL,
    cardinality character varying(16) NOT NULL,
    value_ordinal integer NOT NULL,
    value_string text,
    value_boolean boolean,
    value_integer bigint,
    value_decimal numeric(38,12),
    value_date date,
    value_datetime timestamp with time zone,
    value_enum_key character varying(256),
    CONSTRAINT identity_state_value_ordinal_ck CHECK ((value_ordinal >= 0)),
    CONSTRAINT identity_state_value_shape_ck CHECK (((((data_type)::text = 'STRING'::text) AND (value_string IS NOT NULL) AND (num_nonnulls(value_boolean, value_integer, value_decimal, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'BOOLEAN'::text) AND (value_boolean IS NOT NULL) AND (num_nonnulls(value_string, value_integer, value_decimal, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'INTEGER'::text) AND (value_integer IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_decimal, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'DECIMAL'::text) AND (value_decimal IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'DATE'::text) AND (value_date IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'DATETIME'::text) AND (value_datetime IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_date, value_enum_key) = 0)) OR (((data_type)::text = 'ENUM'::text) AND (value_enum_key IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_date, value_datetime) = 0))))
);

CREATE TABLE identity.canonical_schema_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    version_number bigint NOT NULL,
    state character varying(24) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    activated_at timestamp with time zone,
    superseded_at timestamp with time zone,
    CONSTRAINT identity_canonical_schema_version_number_ck CHECK ((version_number > 0)),
    CONSTRAINT identity_canonical_schema_version_state_ck CHECK (((state)::text = ANY ((ARRAY['DRAFT'::character varying, 'ACTIVE'::character varying, 'SUPERSEDED'::character varying])::text[]))),
    CONSTRAINT identity_canonical_schema_version_time_ck CHECK (((((state)::text = 'DRAFT'::text) AND (activated_at IS NULL) AND (superseded_at IS NULL)) OR (((state)::text = 'ACTIVE'::text) AND (activated_at IS NOT NULL) AND (superseded_at IS NULL)) OR (((state)::text = 'SUPERSEDED'::text) AND (activated_at IS NOT NULL) AND (superseded_at IS NOT NULL) AND (superseded_at >= activated_at))))
);

CREATE TABLE identity.identity (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_type character varying(16) NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    display_name character varying(512) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_identity_display_name_ck CHECK ((btrim((display_name)::text) <> ''::text)),
    CONSTRAINT identity_identity_lifecycle_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, 'SUSPENDED'::character varying, 'INACTIVE'::character varying, 'DECOMMISSIONED'::character varying])::text[]))),
    CONSTRAINT identity_identity_revision_ck CHECK ((revision > 0)),
    CONSTRAINT identity_identity_timestamp_order_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT identity_identity_type_ck CHECK (((identity_type)::text = ANY ((ARRAY['PERSON'::character varying, 'SERVICE'::character varying, 'WORKLOAD'::character varying])::text[])))
);

COMMENT ON TABLE identity.identity IS 'Identity-owned authoritative governed subject. Identity type is stable for normal operations and determines exactly one compatible typed profile.';

CREATE TABLE identity.identity_link (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_record_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    link_state character varying(24) NOT NULL,
    linked_at timestamp with time zone NOT NULL,
    ended_at timestamp with time zone,
    correlation_reason character varying(512) NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    CONSTRAINT identity_identity_link_end_ck CHECK (((((link_state)::text = 'ACCEPTED'::text) AND (ended_at IS NULL)) OR (((link_state)::text = 'SUPERSEDED'::text) AND (ended_at IS NOT NULL) AND (ended_at >= linked_at)))),
    CONSTRAINT identity_identity_link_reason_ck CHECK ((btrim((correlation_reason)::text) <> ''::text)),
    CONSTRAINT identity_identity_link_state_ck CHECK (((link_state)::text = ANY ((ARRAY['ACCEPTED'::character varying, 'SUPERSEDED'::character varying])::text[])))
);

COMMENT ON TABLE identity.identity_link IS 'Authoritative correlation history. At most one active accepted canonical Identity link exists for a SourceRecord.';

CREATE TABLE identity.identity_merge_operation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    survivor_identity_id uuid NOT NULL,
    absorbed_identity_id uuid NOT NULL,
    survivor_revision_before bigint NOT NULL,
    absorbed_revision_before bigint NOT NULL,
    moved_link_count integer NOT NULL,
    moved_principal_count integer NOT NULL,
    reason character varying(1024) NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    completed_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_merge_operation_count_ck CHECK (((moved_link_count >= 0) AND (moved_principal_count >= 0))),
    CONSTRAINT identity_merge_operation_distinct_ck CHECK ((survivor_identity_id <> absorbed_identity_id)),
    CONSTRAINT identity_merge_operation_reason_ck CHECK ((btrim((reason)::text) <> ''::text)),
    CONSTRAINT identity_merge_operation_revision_ck CHECK (((survivor_revision_before > 0) AND (absorbed_revision_before > 0)))
);

COMMENT ON TABLE identity.identity_merge_operation IS 'Immutable Identity-owned evidence for explicit merge. Foreign-capability authority is not moved.';

CREATE TABLE identity.identity_split_operation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_identity_id uuid NOT NULL,
    new_identity_id uuid NOT NULL,
    source_revision_before bigint NOT NULL,
    reason character varying(1024) NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    completed_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_split_operation_distinct_ck CHECK ((source_identity_id <> new_identity_id)),
    CONSTRAINT identity_split_operation_reason_ck CHECK ((btrim((reason)::text) <> ''::text)),
    CONSTRAINT identity_split_operation_revision_ck CHECK ((source_revision_before > 0))
);

COMMENT ON TABLE identity.identity_split_operation IS 'Immutable Identity-owned evidence for explicit split into a new PENDING Identity.';

CREATE TABLE identity.identity_split_principal (
    tenant_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    principal_id uuid NOT NULL
);

COMMENT ON TABLE identity.identity_split_principal IS 'Immutable selected Principal relationship evidence for one split.';

CREATE TABLE identity.identity_split_source_record (
    tenant_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    source_record_id uuid NOT NULL
);

COMMENT ON TABLE identity.identity_split_source_record IS 'Immutable selected SourceRecord relationship evidence for one split.';

CREATE TABLE identity.person_profile (
    identity_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_type character varying(16) DEFAULT 'PERSON'::character varying NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_person_profile_type_ck CHECK (((identity_type)::text = 'PERSON'::text))
);

COMMENT ON TABLE identity.person_profile IS 'Typed PERSON profile boundary. Profile-specific fields are added only when governed requirements define them.';

CREATE TABLE identity.principal (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_id uuid,
    application_target_id uuid NOT NULL,
    principal_kind character varying(32) NOT NULL,
    native_principal_key character varying(512) NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_principal_kind_ck CHECK (((principal_kind)::text = 'ACCOUNT'::text)),
    CONSTRAINT identity_principal_native_key_ck CHECK ((btrim((native_principal_key)::text) <> ''::text)),
    CONSTRAINT identity_principal_revision_ck CHECK ((revision > 0)),
    CONSTRAINT identity_principal_state_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying])::text[]))),
    CONSTRAINT identity_principal_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE identity.principal IS 'Identity-owned technical representation/account on one ApplicationTarget. Provider observation does not create this authority automatically.';

CREATE TABLE identity.service_profile (
    identity_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_type character varying(16) DEFAULT 'SERVICE'::character varying NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_service_profile_type_ck CHECK (((identity_type)::text = 'SERVICE'::text))
);

COMMENT ON TABLE identity.service_profile IS 'Typed SERVICE profile boundary. Profile-specific fields are added only when governed requirements define them.';

CREATE TABLE identity.source_absence_inference (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    import_run_id uuid NOT NULL,
    policy_version_id uuid NOT NULL,
    max_inferred_transitions integer NOT NULL,
    process_state character varying(24) NOT NULL,
    after_first_observed_at timestamp with time zone,
    after_source_record_id uuid,
    processed_candidate_count bigint DEFAULT 0 NOT NULL,
    inferred_transition_count bigint DEFAULT 0 NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT identity_source_absence_inference_ceiling_ck CHECK ((max_inferred_transitions > 0)),
    CONSTRAINT identity_source_absence_inference_checkpoint_ck CHECK ((((after_first_observed_at IS NULL) AND (after_source_record_id IS NULL)) OR ((after_first_observed_at IS NOT NULL) AND (after_source_record_id IS NOT NULL)))),
    CONSTRAINT identity_source_absence_inference_counts_ck CHECK (((processed_candidate_count >= 0) AND (inferred_transition_count >= 0) AND (inferred_transition_count <= max_inferred_transitions))),
    CONSTRAINT identity_source_absence_inference_revision_ck CHECK ((revision > 0)),
    CONSTRAINT identity_source_absence_inference_state_ck CHECK (((process_state)::text = ANY ((ARRAY['RUNNING'::character varying, 'COMPLETED'::character varying, 'SUPERSEDED'::character varying, 'MANUAL_REQUIRED'::character varying])::text[]))),
    CONSTRAINT identity_source_absence_inference_time_ck CHECK (((updated_at >= created_at) AND ((((process_state)::text = 'RUNNING'::text) AND (completed_at IS NULL)) OR (((process_state)::text <> 'RUNNING'::text) AND (completed_at IS NOT NULL) AND (completed_at >= created_at)))))
);

COMMENT ON TABLE identity.source_absence_inference IS 'Durable bounded Identity-owned process for trusted COMPLETE source absence inference.';

CREATE TABLE identity.source_absence_policy_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    version_number bigint NOT NULL,
    max_inferred_transitions integer NOT NULL,
    state character varying(24) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    activated_at timestamp with time zone NOT NULL,
    superseded_at timestamp with time zone,
    CONSTRAINT identity_source_absence_policy_ceiling_ck CHECK ((max_inferred_transitions > 0)),
    CONSTRAINT identity_source_absence_policy_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUPERSEDED'::character varying])::text[]))),
    CONSTRAINT identity_source_absence_policy_time_ck CHECK (((((state)::text = 'ACTIVE'::text) AND (superseded_at IS NULL)) OR (((state)::text = 'SUPERSEDED'::text) AND (superseded_at IS NOT NULL) AND (superseded_at >= activated_at)))),
    CONSTRAINT identity_source_absence_policy_version_ck CHECK ((version_number > 0))
);

COMMENT ON TABLE identity.source_absence_policy_version IS 'Versioned Identity-owned policy enabling bounded inferred absence -> INACTIVE handling for one SourceSystem.';

CREATE TABLE identity.source_absence_transition_evidence (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    source_record_id uuid NOT NULL,
    identity_link_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    import_run_id uuid NOT NULL,
    inference_id uuid NOT NULL,
    pre_identity_revision bigint CONSTRAINT source_absence_transition_eviden_pre_identity_revision_not_null NOT NULL,
    post_identity_revision bigint CONSTRAINT source_absence_transition_evide_post_identity_revision_not_null NOT NULL,
    transitioned_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_source_absence_transition_revision_ck CHECK (((pre_identity_revision > 0) AND (post_identity_revision = (pre_identity_revision + 1))))
);

COMMENT ON TABLE identity.source_absence_transition_evidence IS 'Immutable evidence that trusted COMPLETE source absence inference caused a specific Identity revision to become INACTIVE; used only to fence safe source-driven restoration.';

CREATE TABLE identity.source_correlation_policy_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    match_attribute_definition_version_id uuid CONSTRAINT source_correlation_policy_v_match_attribute_definition_not_null NOT NULL,
    match_mapping_version_id uuid CONSTRAINT source_correlation_policy_ver_match_mapping_version_id_not_null NOT NULL,
    version_number bigint NOT NULL,
    create_identity_on_no_match boolean CONSTRAINT source_correlation_policy_v_create_identity_on_no_matc_not_null NOT NULL,
    created_identity_type character varying(24),
    display_name_source_path character varying(512),
    state character varying(24) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    activated_at timestamp with time zone NOT NULL,
    superseded_at timestamp with time zone,
    CONSTRAINT identity_source_correlation_policy_creation_ck CHECK (((create_identity_on_no_match AND ((created_identity_type)::text = ANY ((ARRAY['PERSON'::character varying, 'SERVICE'::character varying, 'WORKLOAD'::character varying])::text[])) AND (display_name_source_path IS NOT NULL) AND (btrim((display_name_source_path)::text) <> ''::text)) OR ((NOT create_identity_on_no_match) AND (created_identity_type IS NULL) AND (display_name_source_path IS NULL)))),
    CONSTRAINT identity_source_correlation_policy_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUPERSEDED'::character varying])::text[]))),
    CONSTRAINT identity_source_correlation_policy_time_ck CHECK (((((state)::text = 'ACTIVE'::text) AND (superseded_at IS NULL)) OR (((state)::text = 'SUPERSEDED'::text) AND (superseded_at IS NOT NULL) AND (superseded_at >= activated_at)))),
    CONSTRAINT identity_source_correlation_policy_version_ck CHECK ((version_number > 0))
);

COMMENT ON TABLE identity.source_correlation_policy_version IS 'Immutable activated Identity-owned policy for deterministic source correlation. The first version uses one exact STRING/SINGLE canonical key; optional no-match creation produces a PENDING Identity.';

CREATE TABLE identity.source_import_run (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    run_state character varying(24) NOT NULL,
    completeness character varying(16) NOT NULL,
    started_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    checkpoint_token character varying(1024),
    partial_reason character varying(1024),
    absence_trust character varying(16) DEFAULT 'UNTRUSTED'::character varying NOT NULL,
    absence_trust_reason character varying(1024),
    CONSTRAINT identity_source_import_run_absence_trust_ck CHECK (((absence_trust)::text = ANY ((ARRAY['UNTRUSTED'::character varying, 'TRUSTED'::character varying])::text[]))),
    CONSTRAINT identity_source_import_run_absence_trust_reason_ck CHECK ((((absence_trust)::text <> 'TRUSTED'::text) OR (((completeness)::text = 'COMPLETE'::text) AND (absence_trust_reason IS NOT NULL) AND (btrim((absence_trust_reason)::text) <> ''::text)))),
    CONSTRAINT identity_source_import_run_completeness_ck CHECK (((completeness)::text = ANY ((ARRAY['COMPLETE'::character varying, 'PARTIAL'::character varying, 'UNKNOWN'::character varying])::text[]))),
    CONSTRAINT identity_source_import_run_completion_ck CHECK (((((run_state)::text = 'RUNNING'::text) AND (completed_at IS NULL)) OR (((run_state)::text = ANY ((ARRAY['COMPLETED'::character varying, 'FAILED'::character varying])::text[])) AND (completed_at IS NOT NULL)))),
    CONSTRAINT identity_source_import_run_partial_reason_ck CHECK ((((completeness)::text <> 'PARTIAL'::text) OR ((partial_reason IS NOT NULL) AND (btrim((partial_reason)::text) <> ''::text)))),
    CONSTRAINT identity_source_import_run_state_ck CHECK (((run_state)::text = ANY ((ARRAY['RUNNING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[])))
);

COMMENT ON TABLE identity.source_import_run IS 'Identity-owned source-import process provenance. Execution outcome and coverage completeness are independent semantics.';

COMMENT ON COLUMN identity.source_import_run.absence_trust IS 'Run-scoped evidence gate for destructive absence inference. COMPLETE alone is insufficient.';

CREATE TABLE identity.source_lifecycle_policy_rule (
    policy_version_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_value character varying(512) NOT NULL,
    target_lifecycle_state character varying(24) NOT NULL,
    CONSTRAINT identity_source_lifecycle_policy_rule_target_ck CHECK (((target_lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUSPENDED'::character varying, 'INACTIVE'::character varying, 'DECOMMISSIONED'::character varying])::text[]))),
    CONSTRAINT identity_source_lifecycle_policy_rule_value_ck CHECK ((btrim((source_value)::text) <> ''::text))
);

COMMENT ON TABLE identity.source_lifecycle_policy_rule IS 'Typed exact text-value lifecycle rules. ACTIVE may only auto-advance PENDING in the first implementation; source-driven reactivation is deferred.';

CREATE TABLE identity.source_lifecycle_policy_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    source_path character varying(512) NOT NULL,
    version_number bigint NOT NULL,
    state character varying(24) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    activated_at timestamp with time zone NOT NULL,
    superseded_at timestamp with time zone,
    CONSTRAINT identity_source_lifecycle_policy_path_ck CHECK ((((source_path)::text ~~ '$.%'::text) AND (length((source_path)::text) >= 3))),
    CONSTRAINT identity_source_lifecycle_policy_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUPERSEDED'::character varying])::text[]))),
    CONSTRAINT identity_source_lifecycle_policy_time_ck CHECK (((((state)::text = 'ACTIVE'::text) AND (superseded_at IS NULL)) OR (((state)::text = 'SUPERSEDED'::text) AND (superseded_at IS NOT NULL) AND (superseded_at >= activated_at)))),
    CONSTRAINT identity_source_lifecycle_policy_version_ck CHECK ((version_number > 0))
);

COMMENT ON TABLE identity.source_lifecycle_policy_version IS 'Immutable activated Identity-owned policy mapping one bounded explicit source field to lifecycle intent. Destructive absence inference is not represented here.';

CREATE TABLE identity.source_record (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    native_key character varying(1024) NOT NULL,
    observed_attributes jsonb NOT NULL,
    source_updated_at timestamp with time zone,
    first_observed_at timestamp with time zone NOT NULL,
    last_observed_at timestamp with time zone NOT NULL,
    last_import_run_id uuid NOT NULL,
    last_complete_import_run_id uuid,
    CONSTRAINT identity_source_record_attributes_ck CHECK ((jsonb_typeof(observed_attributes) = 'object'::text)),
    CONSTRAINT identity_source_record_native_key_ck CHECK ((btrim((native_key)::text) <> ''::text)),
    CONSTRAINT identity_source_record_timestamp_order_ck CHECK ((last_observed_at >= first_observed_at))
);

COMMENT ON TABLE identity.source_record IS 'Current positive normalized source observation. observed_attributes contains bounded/filtered source-native fields, not retained raw connector payload. Partial imports never erase unseen records.';

CREATE TABLE identity.source_suspension_transition_evidence (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    source_record_id uuid NOT NULL,
    identity_link_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    lifecycle_policy_version_id uuid CONSTRAINT source_suspension_transitio_lifecycle_policy_version_i_not_null NOT NULL,
    pre_identity_revision bigint CONSTRAINT source_suspension_transition_evi_pre_identity_revision_not_null NOT NULL,
    post_identity_revision bigint CONSTRAINT source_suspension_transition_ev_post_identity_revision_not_null NOT NULL,
    suspended_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_source_suspension_transition_revision_ck CHECK (((pre_identity_revision > 0) AND (post_identity_revision = (pre_identity_revision + 1))))
);

COMMENT ON TABLE identity.source_suspension_transition_evidence IS 'Immutable evidence that explicit source lifecycle policy caused a specific Identity revision to become SUSPENDED; used only to fence same-source current-revision restoration.';

CREATE TABLE identity.source_system (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    code character varying(128) NOT NULL,
    name character varying(512) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_source_system_code_ck CHECK ((btrim((code)::text) <> ''::text)),
    CONSTRAINT identity_source_system_name_ck CHECK ((btrim((name)::text) <> ''::text)),
    CONSTRAINT identity_source_system_revision_ck CHECK ((revision > 0)),
    CONSTRAINT identity_source_system_timestamp_order_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE identity.source_system IS 'Identity-owned authoritative source definition. SourceSystem retains governance meaning independently from connector implementation.';

CREATE TABLE identity.workload_profile (
    identity_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_type character varying(16) DEFAULT 'WORKLOAD'::character varying NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_workload_profile_type_ck CHECK (((identity_type)::text = 'WORKLOAD'::text))
);

COMMENT ON TABLE identity.workload_profile IS 'Typed WORKLOAD profile boundary. Profile-specific fields are added only when governed requirements define them.';

CREATE TABLE integration.connector_binding (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    connector_instance_id uuid NOT NULL,
    target_kind character varying(32) NOT NULL,
    target_id uuid NOT NULL,
    contract_id character varying(128) NOT NULL,
    contract_version integer NOT NULL,
    supports_complete_principal_discovery boolean DEFAULT false CONSTRAINT connector_binding_supports_complete_principal_discover_not_null NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    supports_complete_entitlement_discovery boolean DEFAULT false CONSTRAINT connector_binding_supports_complete_entitlement_discov_not_null NOT NULL,
    supports_complete_grant_discovery boolean DEFAULT false NOT NULL,
    CONSTRAINT integration_connector_binding_contract_id_ck CHECK ((btrim((contract_id)::text) <> ''::text)),
    CONSTRAINT integration_connector_binding_contract_version_ck CHECK ((contract_version > 0)),
    CONSTRAINT integration_connector_binding_revision_ck CHECK ((revision > 0)),
    CONSTRAINT integration_connector_binding_state_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying, 'RETIRED'::character varying])::text[]))),
    CONSTRAINT integration_connector_binding_target_kind_ck CHECK (((target_kind)::text = ANY ((ARRAY['APPLICATION_TARGET'::character varying, 'SOURCE_SYSTEM'::character varying])::text[]))),
    CONSTRAINT integration_connector_binding_timestamp_ck CHECK ((updated_at >= created_at))
);

CREATE TABLE integration.connector_instance (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    connector_type character varying(128) NOT NULL,
    runtime_id character varying(128) NOT NULL,
    runtime_version character varying(64) NOT NULL,
    configuration_version bigint NOT NULL,
    configuration_json jsonb NOT NULL,
    secret_reference character varying(1024),
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_connector_instance_configuration_version_ck CHECK ((configuration_version > 0)),
    CONSTRAINT integration_connector_instance_revision_ck CHECK ((revision > 0)),
    CONSTRAINT integration_connector_instance_runtime_id_ck CHECK ((btrim((runtime_id)::text) <> ''::text)),
    CONSTRAINT integration_connector_instance_runtime_version_ck CHECK ((btrim((runtime_version)::text) <> ''::text)),
    CONSTRAINT integration_connector_instance_state_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying, 'RETIRED'::character varying])::text[]))),
    CONSTRAINT integration_connector_instance_timestamp_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT integration_connector_instance_type_ck CHECK ((btrim((connector_type)::text) <> ''::text))
);

CREATE TABLE integration.connector_worker_binding_scope (
    tenant_id uuid NOT NULL,
    worker_registration_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL
);

CREATE TABLE integration.connector_worker_registration (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    external_subject_key character(64) NOT NULL,
    issuer character varying(512) NOT NULL,
    subject character varying(512) NOT NULL,
    state character varying(16) NOT NULL,
    protocol_major_min integer DEFAULT 1 NOT NULL,
    protocol_major_max integer DEFAULT 1 NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_worker_registration_protocol_ck CHECK (((protocol_major_min > 0) AND (protocol_major_max >= protocol_major_min))),
    CONSTRAINT integration_worker_registration_revision_ck CHECK ((revision > 0)),
    CONSTRAINT integration_worker_registration_state_ck CHECK (((state)::text = ANY ((ARRAY['ENABLED'::character varying, 'DISABLED'::character varying])::text[]))),
    CONSTRAINT integration_worker_registration_timestamp_ck CHECK ((updated_at >= created_at))
);

CREATE TABLE integration.connector_worker_runtime_permission (
    tenant_id uuid NOT NULL,
    worker_registration_id uuid CONSTRAINT connector_worker_runtime_permis_worker_registration_id_not_null NOT NULL,
    runtime_id character varying(128) NOT NULL,
    runtime_version character varying(64) NOT NULL,
    capability character varying(48) NOT NULL,
    contract_id character varying(128) NOT NULL,
    contract_version integer NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_worker_permission_capability_ck CHECK (((capability)::text = ANY ((ARRAY['PROVISION'::character varying, 'RECONCILE'::character varying, 'SOURCE_DISCOVER'::character varying, 'CREDENTIAL_PROVIDER_OPERATION'::character varying])::text[]))),
    CONSTRAINT integration_worker_permission_contract_id_ck CHECK ((btrim((contract_id)::text) <> ''::text)),
    CONSTRAINT integration_worker_permission_contract_version_ck CHECK ((contract_version > 0)),
    CONSTRAINT integration_worker_permission_runtime_id_ck CHECK ((btrim((runtime_id)::text) <> ''::text)),
    CONSTRAINT integration_worker_permission_runtime_version_ck CHECK ((btrim((runtime_version)::text) <> ''::text))
);

CREATE TABLE integration.connector_worker_session (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    worker_registration_id uuid NOT NULL,
    worker_instance_id character varying(128) NOT NULL,
    selected_protocol_major integer NOT NULL,
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_worker_session_expiry_ck CHECK ((expires_at > created_at)),
    CONSTRAINT integration_worker_session_protocol_ck CHECK ((selected_protocol_major = 1)),
    CONSTRAINT integration_worker_session_worker_instance_ck CHECK ((btrim((worker_instance_id)::text) <> ''::text))
);

CREATE TABLE integration.connector_worker_session_capability (
    tenant_id uuid NOT NULL,
    session_id uuid NOT NULL,
    runtime_id character varying(128) NOT NULL,
    runtime_version character varying(64) NOT NULL,
    capability character varying(48) NOT NULL,
    CONSTRAINT integration_worker_session_capability_ck CHECK (((capability)::text = ANY ((ARRAY['PROVISION'::character varying, 'RECONCILE'::character varying, 'SOURCE_DISCOVER'::character varying, 'CREDENTIAL_PROVIDER_OPERATION'::character varying])::text[])))
);

CREATE TABLE integration.connector_worker_session_contract (
    tenant_id uuid NOT NULL,
    session_id uuid NOT NULL,
    runtime_id character varying(128) NOT NULL,
    runtime_version character varying(64) NOT NULL,
    contract_id character varying(128) NOT NULL,
    contract_version integer NOT NULL,
    CONSTRAINT integration_worker_session_contract_version_ck CHECK ((contract_version > 0))
);

CREATE TABLE integration.entitlement_observation_mapping (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    provider_stable_id character varying(512) NOT NULL,
    entitlement_id uuid NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    retired_at timestamp with time zone,
    CONSTRAINT integration_entitlement_mapping_provider_ck CHECK ((btrim((provider_stable_id)::text) <> ''::text)),
    CONSTRAINT integration_entitlement_mapping_retired_ck CHECK (((((lifecycle_state)::text = 'ACTIVE'::text) AND (retired_at IS NULL)) OR (((lifecycle_state)::text = 'RETIRED'::text) AND (retired_at IS NOT NULL)))),
    CONSTRAINT integration_entitlement_mapping_revision_ck CHECK ((revision > 0)),
    CONSTRAINT integration_entitlement_mapping_state_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'RETIRED'::character varying])::text[]))),
    CONSTRAINT integration_entitlement_mapping_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE integration.entitlement_observation_mapping IS 'Integration-owned explicit resolution from provider entitlement observation identity to existing Catalog Entitlement stable ID. Mapping is not Catalog authority.';

CREATE TABLE integration.observed_entitlement (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    provider_stable_id character varying(512) NOT NULL,
    provider_version character varying(512),
    observed_state jsonb NOT NULL,
    present boolean NOT NULL,
    last_observed_run_id uuid NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    absent_at timestamp with time zone,
    CONSTRAINT integration_observed_entitlement_presence_ck CHECK (((present AND (absent_at IS NULL)) OR ((NOT present) AND (absent_at IS NOT NULL))))
);

COMMENT ON TABLE integration.observed_entitlement IS 'Integration-owned provider observation of technical access units. Provider observation never creates Catalog authority.';

CREATE TABLE integration.observed_grant (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    provider_stable_id character varying(512) NOT NULL,
    provider_version character varying(512),
    principal_provider_id character varying(512) NOT NULL,
    entitlement_provider_id character varying(512) NOT NULL,
    observed_state jsonb NOT NULL,
    present boolean NOT NULL,
    last_observed_run_id uuid NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    absent_at timestamp with time zone,
    CONSTRAINT integration_observed_grant_presence_ck CHECK (((present AND (absent_at IS NULL)) OR ((NOT present) AND (absent_at IS NOT NULL))))
);

COMMENT ON TABLE integration.observed_grant IS 'Integration-owned provider observation of principal-to-entitlement membership. Observation never creates AccessAssignment authority.';

CREATE TABLE integration.observed_principal (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    provider_stable_id character varying(512) NOT NULL,
    provider_version character varying(512),
    observed_state jsonb NOT NULL,
    present boolean NOT NULL,
    last_observed_run_id uuid NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    absent_at timestamp with time zone,
    CONSTRAINT integration_observed_principal_presence_ck CHECK (((present AND (absent_at IS NULL)) OR ((NOT present) AND (absent_at IS NOT NULL))))
);

CREATE TABLE integration.provisioning_attempt (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    task_id uuid NOT NULL,
    attempt_number integer NOT NULL,
    lease_id uuid NOT NULL,
    lease_epoch bigint NOT NULL,
    started_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone NOT NULL,
    outcome character varying(32) NOT NULL,
    failure_category character varying(32),
    provider_error_code character varying(128),
    provider_request_id character varying(256),
    provider_object_id character varying(512),
    provider_version character varying(512),
    metadata jsonb NOT NULL,
    completion_fingerprint character varying(71) NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    CONSTRAINT integration_provisioning_attempt_epoch_ck CHECK ((lease_epoch > 0)),
    CONSTRAINT integration_provisioning_attempt_failure_category_ck CHECK (((failure_category IS NULL) OR ((failure_category)::text = ANY ((ARRAY['TRANSIENT'::character varying, 'RATE_LIMITED'::character varying, 'AUTHENTICATION'::character varying, 'AUTHORIZATION'::character varying, 'VALIDATION'::character varying, 'UNSUPPORTED'::character varying, 'PROVIDER'::character varying])::text[])))),
    CONSTRAINT integration_provisioning_attempt_number_ck CHECK ((attempt_number > 0)),
    CONSTRAINT integration_provisioning_attempt_outcome_ck CHECK (((outcome)::text = ANY ((ARRAY['SUCCEEDED'::character varying, 'FAILED_RETRYABLE'::character varying, 'FAILED_FINAL'::character varying, 'SUPERSEDED'::character varying, 'SKIPPED'::character varying])::text[]))),
    CONSTRAINT integration_provisioning_attempt_time_ck CHECK ((completed_at >= started_at))
);

CREATE TABLE integration.provisioning_job (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    plan_key character varying(200),
    CONSTRAINT integration_provisioning_job_plan_key_ck CHECK (((plan_key IS NULL) OR (btrim((plan_key)::text) <> ''::text))),
    CONSTRAINT integration_provisioning_job_revision_ck CHECK ((revision > 0)),
    CONSTRAINT integration_provisioning_job_state_ck CHECK (((state)::text = ANY ((ARRAY['PLANNED'::character varying, 'RUNNING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT integration_provisioning_job_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON COLUMN integration.provisioning_job.plan_key IS 'Optional deterministic Integration planner identity. Manual/test-created jobs may leave it null.';

CREATE TABLE integration.provisioning_task (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    provisioning_job_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    operation_type character varying(64) NOT NULL,
    subject_kind character varying(32) NOT NULL,
    subject_id uuid NOT NULL,
    desired_revision bigint NOT NULL,
    idempotency_key character varying(200) NOT NULL,
    contract_id character varying(128) NOT NULL,
    contract_version integer NOT NULL,
    payload jsonb NOT NULL,
    state character varying(32) NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone,
    failure_code character varying(128),
    revision bigint DEFAULT 1 NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_provisioning_task_attempt_count_ck CHECK ((attempt_count >= 0)),
    CONSTRAINT integration_provisioning_task_contract_id_ck CHECK ((btrim((contract_id)::text) <> ''::text)),
    CONSTRAINT integration_provisioning_task_contract_version_ck CHECK ((contract_version > 0)),
    CONSTRAINT integration_provisioning_task_desired_revision_ck CHECK ((desired_revision > 0)),
    CONSTRAINT integration_provisioning_task_operation_type_ck CHECK ((btrim((operation_type)::text) <> ''::text)),
    CONSTRAINT integration_provisioning_task_revision_ck CHECK ((revision > 0)),
    CONSTRAINT integration_provisioning_task_state_ck CHECK (((state)::text = ANY ((ARRAY['READY'::character varying, 'RUNNING'::character varying, 'SUCCEEDED'::character varying, 'FAILED_RETRYABLE'::character varying, 'FAILED_FINAL'::character varying, 'SUPERSEDED'::character varying, 'SKIPPED'::character varying, 'BLOCKED'::character varying])::text[]))),
    CONSTRAINT integration_provisioning_task_subject_kind_ck CHECK ((btrim((subject_kind)::text) <> ''::text)),
    CONSTRAINT integration_provisioning_task_timestamp_ck CHECK ((updated_at >= created_at))
);

CREATE TABLE integration.provisioning_task_dependency (
    tenant_id uuid NOT NULL,
    provisioning_job_id uuid NOT NULL,
    task_id uuid NOT NULL,
    depends_on_task_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_provisioning_dependency_not_self_ck CHECK ((task_id <> depends_on_task_id))
);

CREATE TABLE integration.reconciliation_entitlement_staging (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    reconciliation_run_id uuid CONSTRAINT reconciliation_entitlement_stagi_reconciliation_run_id_not_null NOT NULL,
    batch_id uuid NOT NULL,
    provider_stable_id character varying(512) NOT NULL,
    provider_version character varying(512),
    observed_state jsonb NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_reconciliation_entitlement_stage_provider_ck CHECK ((btrim((provider_stable_id)::text) <> ''::text))
);

CREATE TABLE integration.reconciliation_grant_staging (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    reconciliation_run_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    provider_stable_id character varying(512) NOT NULL,
    provider_version character varying(512),
    principal_provider_id character varying(512) NOT NULL,
    entitlement_provider_id character varying(512) NOT NULL,
    observed_state jsonb NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_reconciliation_grant_stage_provider_ck CHECK (((btrim((provider_stable_id)::text) <> ''::text) AND (btrim((principal_provider_id)::text) <> ''::text) AND (btrim((entitlement_provider_id)::text) <> ''::text)))
);

CREATE TABLE integration.reconciliation_observation_batch (
    tenant_id uuid NOT NULL,
    reconciliation_run_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    sequence integer NOT NULL,
    request_fingerprint character varying(71) NOT NULL,
    received_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_reconciliation_batch_sequence_ck CHECK ((sequence >= 0))
);

CREATE TABLE integration.reconciliation_principal_staging (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    reconciliation_run_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    provider_stable_id character varying(512) NOT NULL,
    provider_version character varying(512),
    observed_state jsonb NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_reconciliation_staging_provider_ck CHECK ((btrim((provider_stable_id)::text) <> ''::text))
);

CREATE TABLE integration.reconciliation_run (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    scope_object_class character varying(32) NOT NULL,
    state character varying(24) NOT NULL,
    reported_coverage character varying(16) DEFAULT 'UNKNOWN'::character varying NOT NULL,
    effective_completeness character varying(16) DEFAULT 'UNKNOWN'::character varying NOT NULL,
    configuration_version bigint NOT NULL,
    runtime_id character varying(128) NOT NULL,
    runtime_version character varying(64) NOT NULL,
    contract_id character varying(128) NOT NULL,
    contract_version integer NOT NULL,
    checkpoint_start character varying(2048),
    checkpoint_end character varying(2048),
    next_attempt_at timestamp with time zone,
    failure_code character varying(128),
    completion_lease_epoch bigint,
    completion_fingerprint character varying(71),
    correlation_id uuid NOT NULL,
    causation_id uuid,
    revision bigint DEFAULT 1 NOT NULL,
    started_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_reconciliation_run_complete_ck CHECK ((((effective_completeness)::text <> 'COMPLETE'::text) OR (((state)::text = 'SUCCEEDED'::text) AND ((reported_coverage)::text = 'COMPLETE'::text)))),
    CONSTRAINT integration_reconciliation_run_completed_ck CHECK (((((state)::text = 'RUNNING'::text) AND (completed_at IS NULL)) OR (((state)::text <> 'RUNNING'::text) AND (completed_at IS NOT NULL)))),
    CONSTRAINT integration_reconciliation_run_configuration_version_ck CHECK ((configuration_version > 0)),
    CONSTRAINT integration_reconciliation_run_contract_version_ck CHECK ((contract_version > 0)),
    CONSTRAINT integration_reconciliation_run_effective_completeness_ck CHECK (((effective_completeness)::text = ANY ((ARRAY['COMPLETE'::character varying, 'PARTIAL'::character varying, 'UNKNOWN'::character varying])::text[]))),
    CONSTRAINT integration_reconciliation_run_reported_coverage_ck CHECK (((reported_coverage)::text = ANY ((ARRAY['COMPLETE'::character varying, 'PARTIAL'::character varying, 'UNKNOWN'::character varying])::text[]))),
    CONSTRAINT integration_reconciliation_run_revision_ck CHECK ((revision > 0)),
    CONSTRAINT integration_reconciliation_run_scope_ck CHECK (((scope_object_class)::text = ANY ((ARRAY['PRINCIPAL'::character varying, 'ENTITLEMENT'::character varying, 'GRANT'::character varying])::text[]))),
    CONSTRAINT integration_reconciliation_run_state_ck CHECK (((state)::text = ANY ((ARRAY['RUNNING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT integration_reconciliation_run_timestamp_ck CHECK (((updated_at >= created_at) AND ((completed_at IS NULL) OR (completed_at >= started_at))))
);

CREATE TABLE platform.connector_work_lease (
    tenant_id uuid NOT NULL,
    work_kind character varying(24) NOT NULL,
    work_id uuid NOT NULL,
    execution_owner_id uuid CONSTRAINT connector_work_lease_session_id_not_null NOT NULL,
    lease_id uuid NOT NULL,
    lease_epoch bigint NOT NULL,
    claimed_at timestamp with time zone NOT NULL,
    lease_expires_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT platform_connector_work_lease_epoch_ck CHECK ((lease_epoch > 0)),
    CONSTRAINT platform_connector_work_lease_expiry_ck CHECK ((lease_expires_at > claimed_at)),
    CONSTRAINT platform_connector_work_lease_kind_ck CHECK (((work_kind)::text = ANY ((ARRAY['PROVISION'::character varying, 'RECONCILE'::character varying])::text[])))
);

COMMENT ON COLUMN platform.connector_work_lease.execution_owner_id IS 'Opaque technical execution owner. Remote connector workers use negotiated session IDs; in-process executors use stable runtime owner IDs.';

CREATE TABLE platform.idempotency_record (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    operation_namespace character varying(256) NOT NULL,
    idempotency_key character varying(256) NOT NULL,
    request_fingerprint character varying(160) NOT NULL,
    operation_state character varying(24) DEFAULT 'IN_PROGRESS'::character varying NOT NULL,
    resource_type character varying(128),
    resource_id uuid,
    created_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    expires_at timestamp with time zone,
    CONSTRAINT idempotency_record_completion_consistent CHECK (((((operation_state)::text = 'COMPLETED'::text) AND (completed_at IS NOT NULL)) OR (((operation_state)::text = 'IN_PROGRESS'::text) AND (completed_at IS NULL)))),
    CONSTRAINT idempotency_record_expiry_after_creation CHECK (((expires_at IS NULL) OR (expires_at > created_at))),
    CONSTRAINT idempotency_record_fingerprint_not_blank CHECK ((btrim((request_fingerprint)::text) <> ''::text)),
    CONSTRAINT idempotency_record_key_not_blank CHECK ((btrim((idempotency_key)::text) <> ''::text)),
    CONSTRAINT idempotency_record_namespace_not_blank CHECK ((btrim((operation_namespace)::text) <> ''::text)),
    CONSTRAINT idempotency_record_resource_reference_complete CHECK ((((resource_type IS NULL) AND (resource_id IS NULL)) OR ((resource_type IS NOT NULL) AND (btrim((resource_type)::text) <> ''::text) AND (resource_id IS NOT NULL)))),
    CONSTRAINT idempotency_record_state CHECK (((operation_state)::text = ANY ((ARRAY['IN_PROGRESS'::character varying, 'COMPLETED'::character varying])::text[])))
);

CREATE TABLE platform.idp_authorization_code (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    client_registration_id uuid NOT NULL,
    client_registration_revision bigint NOT NULL,
    application_id uuid NOT NULL,
    client_id character varying(200) NOT NULL,
    browser_session_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    code_hash character varying(64) NOT NULL,
    redirect_uri character varying(2048) NOT NULL,
    scopes character varying(128) NOT NULL,
    pkce_challenge character varying(64) NOT NULL,
    nonce_value character varying(512),
    auth_time timestamp with time zone NOT NULL,
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    CONSTRAINT idp_authorization_code_client_revision_ck CHECK ((client_registration_revision > 0)),
    CONSTRAINT idp_authorization_code_hash_ck CHECK (((code_hash)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT idp_authorization_pkce_ck CHECK (((pkce_challenge)::text ~ '^[A-Za-z0-9_-]{43}$'::text)),
    CONSTRAINT idp_authorization_time_ck CHECK (((auth_time <= created_at) AND (expires_at > created_at) AND ((consumed_at IS NULL) OR (consumed_at >= created_at))))
);

COMMENT ON TABLE platform.idp_authorization_code IS 'Platform-owned short-lived OAuth authorization-code state; not IAM governance or access authority.';

COMMENT ON COLUMN platform.idp_authorization_code.client_registration_id IS 'Stable cross-capability reference to Catalog SSO registration; intentionally no database foreign key.';

COMMENT ON COLUMN platform.idp_authorization_code.code_hash IS 'SHA-256 digest of a high-entropy single-use authorization code. Raw codes are never persisted.';

CREATE TABLE platform.idp_browser_session (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    credential_id uuid NOT NULL,
    credential_revision bigint NOT NULL,
    token_hash character varying(64) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    last_seen_at timestamp with time zone NOT NULL,
    idle_expires_at timestamp with time zone NOT NULL,
    absolute_expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    CONSTRAINT idp_browser_session_credential_revision_ck CHECK ((credential_revision > 0)),
    CONSTRAINT idp_browser_session_time_ck CHECK (((last_seen_at >= created_at) AND (idle_expires_at > created_at) AND (absolute_expires_at > created_at) AND (idle_expires_at <= absolute_expires_at) AND ((revoked_at IS NULL) OR (revoked_at >= created_at)))),
    CONSTRAINT idp_browser_session_token_hash_ck CHECK (((token_hash)::text ~ '^[0-9a-f]{64}$'::text))
);

COMMENT ON TABLE platform.idp_browser_session IS 'Platform-owned first-party IdP browser authentication sessions. This is authentication context, not IAM governance or access authority.';

COMMENT ON COLUMN platform.idp_browser_session.credential_id IS 'Stable cross-capability reference to the Credential that established the session; intentionally no database foreign key.';

COMMENT ON COLUMN platform.idp_browser_session.token_hash IS 'SHA-256 digest of a high-entropy opaque browser token. Raw session tokens are never persisted.';

CREATE TABLE platform.inbox_message (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    consumer_name character varying(256) NOT NULL,
    message_id character varying(256) NOT NULL,
    processing_state character varying(24) DEFAULT 'RECEIVED'::character varying NOT NULL,
    first_seen_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    outcome_code character varying(128),
    CONSTRAINT inbox_message_completion_consistent CHECK (((((processing_state)::text = 'COMPLETED'::text) AND (completed_at IS NOT NULL)) OR (((processing_state)::text = 'RECEIVED'::text) AND (completed_at IS NULL)))),
    CONSTRAINT inbox_message_consumer_not_blank CHECK ((btrim((consumer_name)::text) <> ''::text)),
    CONSTRAINT inbox_message_message_id_not_blank CHECK ((btrim((message_id)::text) <> ''::text)),
    CONSTRAINT inbox_message_processing_state CHECK (((processing_state)::text = ANY ((ARRAY['RECEIVED'::character varying, 'COMPLETED'::character varying])::text[])))
);

CREATE TABLE platform.installation_bootstrap (
    singleton_key boolean DEFAULT true NOT NULL,
    state character varying(32) NOT NULL,
    tenant_id uuid NOT NULL,
    tenant_display_name character varying(512) NOT NULL,
    identity_id uuid NOT NULL,
    identity_display_name character varying(512) NOT NULL,
    console_application_id uuid NOT NULL,
    console_target_id uuid NOT NULL,
    console_sso_registration_id uuid NOT NULL,
    console_client_id character varying(200) NOT NULL,
    console_redirect_uri character varying(2048) NOT NULL,
    principal_id uuid NOT NULL,
    principal_key character varying(512) NOT NULL,
    credential_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    bootstrap_version integer NOT NULL,
    started_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    sealed_at timestamp with time zone,
    CONSTRAINT platform_installation_bootstrap_client_id_ck CHECK ((btrim((console_client_id)::text) <> ''::text)),
    CONSTRAINT platform_installation_bootstrap_identity_name_ck CHECK ((btrim((identity_display_name)::text) <> ''::text)),
    CONSTRAINT platform_installation_bootstrap_principal_key_ck CHECK ((btrim((principal_key)::text) <> ''::text)),
    CONSTRAINT platform_installation_bootstrap_redirect_ck CHECK ((btrim((console_redirect_uri)::text) <> ''::text)),
    CONSTRAINT platform_installation_bootstrap_seal_ck CHECK (((((state)::text = 'SEALED'::text) AND (sealed_at IS NOT NULL)) OR (((state)::text <> 'SEALED'::text) AND (sealed_at IS NULL)))),
    CONSTRAINT platform_installation_bootstrap_singleton_ck CHECK (singleton_key),
    CONSTRAINT platform_installation_bootstrap_state_ck CHECK (((state)::text = ANY ((ARRAY['PLANNED'::character varying, 'TENANT_CREATED'::character varying, 'IDENTITY_CREATED'::character varying, 'CONSOLE_REGISTERED'::character varying, 'PRINCIPAL_CREATED'::character varying, 'CREDENTIAL_ENROLLED'::character varying, 'ADMIN_BOOTSTRAPPED'::character varying, 'VERIFIED'::character varying, 'SEALED'::character varying])::text[]))),
    CONSTRAINT platform_installation_bootstrap_tenant_name_ck CHECK ((btrim((tenant_display_name)::text) <> ''::text)),
    CONSTRAINT platform_installation_bootstrap_time_ck CHECK ((updated_at >= started_at)),
    CONSTRAINT platform_installation_bootstrap_version_ck CHECK ((bootstrap_version > 0))
);

COMMENT ON TABLE platform.installation_bootstrap IS 'Deployment-local ADR-0044 fresh-install plan and irreversible seal. It stores stable identifiers and non-secret inputs only; no password or verifier material is permitted.';

COMMENT ON COLUMN platform.installation_bootstrap.tenant_id IS 'Preallocated first Tenant ID. No FK is intentional because the plan is committed before the Tenant exists.';

COMMENT ON COLUMN platform.installation_bootstrap.sealed_at IS 'Permanent terminal installation seal. Loss of later administrative authority never reopens this process.';

CREATE TABLE platform.outbox_event (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    event_type character varying(256) NOT NULL,
    event_version integer NOT NULL,
    aggregate_type character varying(128),
    aggregate_id uuid,
    aggregate_revision bigint,
    occurred_at timestamp with time zone NOT NULL,
    correlation_id uuid,
    causation_id uuid,
    payload jsonb NOT NULL,
    publication_state character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone,
    last_attempt_at timestamp with time zone,
    published_at timestamp with time zone,
    last_error_code character varying(128),
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT outbox_event_aggregate_reference_complete CHECK ((((aggregate_id IS NULL) AND (aggregate_revision IS NULL) AND (aggregate_type IS NULL)) OR ((aggregate_id IS NOT NULL) AND (aggregate_revision IS NOT NULL) AND (aggregate_type IS NOT NULL) AND (btrim((aggregate_type)::text) <> ''::text)))),
    CONSTRAINT outbox_event_aggregate_revision_positive CHECK (((aggregate_revision IS NULL) OR (aggregate_revision > 0))),
    CONSTRAINT outbox_event_attempt_count_nonnegative CHECK ((attempt_count >= 0)),
    CONSTRAINT outbox_event_failed_state_consistent CHECK ((((publication_state)::text <> 'FAILED'::text) OR ((next_attempt_at IS NULL) AND (last_error_code IS NOT NULL) AND (btrim((last_error_code)::text) <> ''::text)))),
    CONSTRAINT outbox_event_payload_object CHECK ((jsonb_typeof(payload) = 'object'::text)),
    CONSTRAINT outbox_event_publication_state CHECK (((publication_state)::text = ANY ((ARRAY['PENDING'::character varying, 'PUBLISHED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT outbox_event_published_state_consistent CHECK (((((publication_state)::text = 'PUBLISHED'::text) AND (published_at IS NOT NULL)) OR (((publication_state)::text = ANY ((ARRAY['PENDING'::character varying, 'FAILED'::character varying])::text[])) AND (published_at IS NULL)))),
    CONSTRAINT outbox_event_type_not_blank CHECK ((btrim((event_type)::text) <> ''::text)),
    CONSTRAINT outbox_event_version_positive CHECK ((event_version > 0))
);

CREATE TABLE platform.scheduled_work (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    handler_type character varying(256) NOT NULL,
    work_key character varying(256) NOT NULL,
    subject_type character varying(128),
    subject_id uuid,
    subject_revision bigint,
    available_at timestamp with time zone NOT NULL,
    delivery_state character varying(24) DEFAULT 'READY'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    lease_owner character varying(256),
    lease_until timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    last_error_code character varying(128),
    CONSTRAINT scheduled_work_attempt_count_nonnegative CHECK ((attempt_count >= 0)),
    CONSTRAINT scheduled_work_delivery_state CHECK (((delivery_state)::text = ANY ((ARRAY['READY'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT scheduled_work_handler_not_blank CHECK ((btrim((handler_type)::text) <> ''::text)),
    CONSTRAINT scheduled_work_key_not_blank CHECK ((btrim((work_key)::text) <> ''::text)),
    CONSTRAINT scheduled_work_last_error_code_ck CHECK (((last_error_code IS NULL) OR (btrim((last_error_code)::text) <> ''::text))),
    CONSTRAINT scheduled_work_lease_complete CHECK ((((lease_owner IS NULL) AND (lease_until IS NULL)) OR ((lease_owner IS NOT NULL) AND (btrim((lease_owner)::text) <> ''::text) AND (lease_until IS NOT NULL)))),
    CONSTRAINT scheduled_work_subject_reference_complete CHECK ((((subject_type IS NULL) AND (subject_id IS NULL) AND (subject_revision IS NULL)) OR ((subject_type IS NOT NULL) AND (btrim((subject_type)::text) <> ''::text) AND (subject_id IS NOT NULL) AND (subject_revision IS NOT NULL) AND (subject_revision > 0)))),
    CONSTRAINT scheduled_work_timestamp_order CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE platform.scheduled_work IS 'Technical timers/delivery leases only. Referenced domain capabilities retain business/process state ownership.';

COMMENT ON COLUMN platform.scheduled_work.last_error_code IS 'Normalized technical delivery/retry code only. Domain capabilities retain business/process outcome ownership.';

CREATE TABLE platform.tenant (
    id uuid NOT NULL,
    display_name character varying(512) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT tenant_display_name_not_blank CHECK ((btrim((display_name)::text) <> ''::text)),
    CONSTRAINT tenant_revision_positive CHECK ((revision > 0)),
    CONSTRAINT tenant_timestamp_order CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE platform.tenant IS 'Explicit tenant isolation roots. IDs are application-generated opaque UUIDv7 values.';

ALTER TABLE ONLY access.access_assignment
    ADD CONSTRAINT access_assignment_pkey PRIMARY KEY (id);

ALTER TABLE ONLY access.access_assignment
    ADD CONSTRAINT access_assignment_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY access.desired_grant_state
    ADD CONSTRAINT access_desired_grant_state_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY access.desired_grant_state
    ADD CONSTRAINT access_desired_grant_tuple_uq UNIQUE (tenant_id, identity_id, application_target_id, entitlement_id, principal_constraint_key);

ALTER TABLE ONLY access.desired_principal_state
    ADD CONSTRAINT access_desired_principal_state_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY access.desired_principal_state
    ADD CONSTRAINT access_desired_principal_tuple_uq UNIQUE (tenant_id, identity_id, application_target_id);

ALTER TABLE ONLY access.desired_grant_state
    ADD CONSTRAINT desired_grant_state_pkey PRIMARY KEY (id);

ALTER TABLE ONLY access.desired_principal_state
    ADD CONSTRAINT desired_principal_state_pkey PRIMARY KEY (id);

ALTER TABLE ONLY access.effective_access
    ADD CONSTRAINT effective_access_pkey PRIMARY KEY (id);

ALTER TABLE ONLY access.effective_access_support
    ADD CONSTRAINT effective_access_support_path_uq UNIQUE (tenant_id, effective_access_id, access_assignment_id, path_hash);

ALTER TABLE ONLY access.effective_access_support
    ADD CONSTRAINT effective_access_support_pkey PRIMARY KEY (id);

ALTER TABLE ONLY access.effective_access_support_role_version
    ADD CONSTRAINT effective_access_support_role_version_pkey PRIMARY KEY (tenant_id, effective_access_support_id, path_ordinal);

ALTER TABLE ONLY access.effective_access_support
    ADD CONSTRAINT effective_access_support_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY access.effective_access
    ADD CONSTRAINT effective_access_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY access.effective_access
    ADD CONSTRAINT effective_access_tuple_uq UNIQUE (tenant_id, identity_id, entitlement_id, principal_constraint_key);

ALTER TABLE ONLY access.identity_access_reduction
    ADD CONSTRAINT identity_access_reduction_pkey PRIMARY KEY (id);

ALTER TABLE ONLY access.identity_access_reduction
    ADD CONSTRAINT identity_access_reduction_source_uq UNIQUE (tenant_id, identity_id, source_identity_revision);

ALTER TABLE ONLY access.identity_access_reduction
    ADD CONSTRAINT identity_access_reduction_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY access.lifecycle_access_policy_rule_expected_value
    ADD CONSTRAINT lifecycle_access_policy_rule_expected_value_pkey PRIMARY KEY (tenant_id, policy_version_id, rule_id, value_ordinal);

ALTER TABLE ONLY access.lifecycle_access_policy_rule
    ADD CONSTRAINT lifecycle_access_policy_rule_pkey PRIMARY KEY (tenant_id, policy_version_id, rule_id);

ALTER TABLE ONLY access.lifecycle_access_policy_rule
    ADD CONSTRAINT lifecycle_access_policy_rule_predicate_ref_uq UNIQUE (tenant_id, policy_version_id, rule_id, predicate_kind);

ALTER TABLE ONLY access.lifecycle_access_policy_version
    ADD CONSTRAINT lifecycle_access_policy_version_number_uq UNIQUE (tenant_id, version_number);

ALTER TABLE ONLY access.lifecycle_access_policy_version
    ADD CONSTRAINT lifecycle_access_policy_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY access.lifecycle_access_policy_version
    ADD CONSTRAINT lifecycle_access_policy_version_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY access.review_remediation_application
    ADD CONSTRAINT review_remediation_application_pkey PRIMARY KEY (tenant_id, review_remediation_id);

ALTER TABLE ONLY administration.control_plane_actor_binding
    ADD CONSTRAINT administration_actor_binding_subject_key_uq UNIQUE (external_subject_key);

ALTER TABLE ONLY administration.control_plane_actor_binding
    ADD CONSTRAINT administration_actor_binding_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY administration.administrative_delegation
    ADD CONSTRAINT administration_delegation_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY administration.administrative_grant
    ADD CONSTRAINT administration_grant_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY administration.initial_admin_bootstrap
    ADD CONSTRAINT administration_initial_bootstrap_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY administration.initial_admin_bootstrap
    ADD CONSTRAINT administration_initial_bootstrap_tenant_uq UNIQUE (tenant_id);

ALTER TABLE ONLY administration.administrative_permission
    ADD CONSTRAINT administration_permission_key_uq UNIQUE (tenant_id, resource_type, action);

ALTER TABLE ONLY administration.administrative_permission
    ADD CONSTRAINT administration_permission_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY administration.administrative_role
    ADD CONSTRAINT administration_role_code_uq UNIQUE (tenant_id, code);

ALTER TABLE ONLY administration.administrative_role
    ADD CONSTRAINT administration_role_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY administration.administrative_break_glass_obligation
    ADD CONSTRAINT administrative_break_glass_obligation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY administration.administrative_break_glass_obligation
    ADD CONSTRAINT administrative_break_glass_obligation_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY administration.administrative_break_glass_obligation
    ADD CONSTRAINT administrative_break_glass_obligation_uq UNIQUE (tenant_id, break_glass_operation_id, obligation_type);

ALTER TABLE ONLY administration.administrative_break_glass_operation
    ADD CONSTRAINT administrative_break_glass_operation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY administration.administrative_break_glass_review
    ADD CONSTRAINT administrative_break_glass_review_one_per_obligation_uq UNIQUE (tenant_id, obligation_id);

ALTER TABLE ONLY administration.administrative_break_glass_review
    ADD CONSTRAINT administrative_break_glass_review_one_per_operation_uq UNIQUE (tenant_id, break_glass_operation_id);

ALTER TABLE ONLY administration.administrative_break_glass_review
    ADD CONSTRAINT administrative_break_glass_review_pkey PRIMARY KEY (id);

ALTER TABLE ONLY administration.administrative_break_glass_review
    ADD CONSTRAINT administrative_break_glass_review_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY administration.administrative_break_glass_operation
    ADD CONSTRAINT administrative_break_glass_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY administration.administrative_delegation
    ADD CONSTRAINT administrative_delegation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY administration.administrative_elevation
    ADD CONSTRAINT administrative_elevation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY administration.administrative_elevation
    ADD CONSTRAINT administrative_elevation_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY administration.administrative_grant
    ADD CONSTRAINT administrative_grant_pkey PRIMARY KEY (id);

ALTER TABLE ONLY administration.administrative_permission
    ADD CONSTRAINT administrative_permission_pkey PRIMARY KEY (id);

ALTER TABLE ONLY administration.administrative_role_permission
    ADD CONSTRAINT administrative_role_permission_pkey PRIMARY KEY (tenant_id, role_id, permission_id);

ALTER TABLE ONLY administration.administrative_role
    ADD CONSTRAINT administrative_role_pkey PRIMARY KEY (id);

ALTER TABLE ONLY administration.control_plane_actor_binding
    ADD CONSTRAINT control_plane_actor_binding_pkey PRIMARY KEY (id);

ALTER TABLE ONLY administration.initial_admin_bootstrap
    ADD CONSTRAINT initial_admin_bootstrap_pkey PRIMARY KEY (id);

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

ALTER TABLE ONLY catalog.application
    ADD CONSTRAINT application_pkey PRIMARY KEY (id);

ALTER TABLE ONLY catalog.application_target
    ADD CONSTRAINT application_target_pkey PRIMARY KEY (id);

ALTER TABLE ONLY catalog.application
    ADD CONSTRAINT catalog_application_code_uq UNIQUE (tenant_id, code);

ALTER TABLE ONLY catalog.application_target
    ADD CONSTRAINT catalog_application_target_app_id_uq UNIQUE (tenant_id, id, application_id);

ALTER TABLE ONLY catalog.application_target
    ADD CONSTRAINT catalog_application_target_code_uq UNIQUE (tenant_id, application_id, code);

ALTER TABLE ONLY catalog.application_target
    ADD CONSTRAINT catalog_application_target_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY catalog.application
    ADD CONSTRAINT catalog_application_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY catalog.entitlement
    ADD CONSTRAINT catalog_entitlement_code_uq UNIQUE (tenant_id, application_id, code);

ALTER TABLE ONLY catalog.entitlement
    ADD CONSTRAINT catalog_entitlement_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY catalog.role
    ADD CONSTRAINT catalog_role_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY catalog.role_version_member
    ADD CONSTRAINT catalog_role_version_member_ordinal_uq UNIQUE (tenant_id, role_version_id, ordinal);

ALTER TABLE ONLY catalog.role_version_member
    ADD CONSTRAINT catalog_role_version_member_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY catalog.role_version
    ADD CONSTRAINT catalog_role_version_number_uq UNIQUE (tenant_id, role_id, version_number);

ALTER TABLE ONLY catalog.role_version
    ADD CONSTRAINT catalog_role_version_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY catalog.sso_client_registration
    ADD CONSTRAINT catalog_sso_client_client_id_uq UNIQUE (tenant_id, client_id);

ALTER TABLE ONLY catalog.sso_client_registration
    ADD CONSTRAINT catalog_sso_client_public_client_id_uq UNIQUE (client_id);

ALTER TABLE ONLY catalog.sso_client_registration
    ADD CONSTRAINT catalog_sso_client_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY catalog.entitlement
    ADD CONSTRAINT entitlement_pkey PRIMARY KEY (id);

ALTER TABLE ONLY catalog.role
    ADD CONSTRAINT role_pkey PRIMARY KEY (id);

ALTER TABLE ONLY catalog.role_version_member
    ADD CONSTRAINT role_version_member_pkey PRIMARY KEY (id);

ALTER TABLE ONLY catalog.role_version
    ADD CONSTRAINT role_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY catalog.sso_client_redirect_uri
    ADD CONSTRAINT sso_client_redirect_uri_pkey PRIMARY KEY (tenant_id, sso_client_registration_id, redirect_uri);

ALTER TABLE ONLY catalog.sso_client_registration
    ADD CONSTRAINT sso_client_registration_pkey PRIMARY KEY (id);

ALTER TABLE ONLY catalog.sso_client_scope
    ADD CONSTRAINT sso_client_scope_pkey PRIMARY KEY (tenant_id, sso_client_registration_id, scope);

ALTER TABLE ONLY credential.credential
    ADD CONSTRAINT credential_credential_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY credential.local_password_verifier
    ADD CONSTRAINT credential_local_password_credential_uq UNIQUE (credential_id);

ALTER TABLE ONLY credential.credential
    ADD CONSTRAINT credential_pkey PRIMARY KEY (id);

ALTER TABLE ONLY credential.credential_rotation
    ADD CONSTRAINT credential_rotation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY credential.credential_rotation
    ADD CONSTRAINT credential_rotation_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY credential.local_password_verifier
    ADD CONSTRAINT local_password_verifier_pkey PRIMARY KEY (reference_key);

ALTER TABLE ONLY governance.access_request
    ADD CONSTRAINT access_request_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.access_request
    ADD CONSTRAINT access_request_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.approval_approver
    ADD CONSTRAINT approval_approver_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.approval_approver
    ADD CONSTRAINT approval_approver_stage_identity_uq UNIQUE (tenant_id, approval_stage_id, approver_identity_id);

ALTER TABLE ONLY governance.approval_approver
    ADD CONSTRAINT approval_approver_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.approval_case
    ADD CONSTRAINT approval_case_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.approval_case
    ADD CONSTRAINT approval_case_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.approval_decision
    ADD CONSTRAINT approval_decision_once_uq UNIQUE (tenant_id, approval_stage_id, approver_identity_id);

ALTER TABLE ONLY governance.approval_decision
    ADD CONSTRAINT approval_decision_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.approval_decision
    ADD CONSTRAINT approval_decision_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.approval_plan
    ADD CONSTRAINT approval_plan_case_number_uq UNIQUE (tenant_id, approval_case_id, plan_number);

ALTER TABLE ONLY governance.approval_plan
    ADD CONSTRAINT approval_plan_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.approval_plan
    ADD CONSTRAINT approval_plan_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.approval_stage
    ADD CONSTRAINT approval_stage_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.approval_stage
    ADD CONSTRAINT approval_stage_plan_ordinal_uq UNIQUE (tenant_id, approval_plan_id, stage_ordinal);

ALTER TABLE ONLY governance.approval_stage
    ADD CONSTRAINT approval_stage_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.finding
    ADD CONSTRAINT finding_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.governance_exception
    ADD CONSTRAINT governance_exception_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.governance_exception
    ADD CONSTRAINT governance_exception_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.finding
    ADD CONSTRAINT governance_finding_key_uq UNIQUE (tenant_id, finding_key);

ALTER TABLE ONLY governance.finding
    ADD CONSTRAINT governance_finding_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.lifecycle_access_approval_candidate
    ADD CONSTRAINT governance_lifecycle_access_candidate_context_uq UNIQUE (tenant_id, identity_id, lifecycle_rule_id, target_kind, target_id, policy_version_id, approval_plan_hash);

ALTER TABLE ONLY governance.lifecycle_access_approval_candidate
    ADD CONSTRAINT governance_lifecycle_access_candidate_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.lifecycle_access_evaluation
    ADD CONSTRAINT governance_lifecycle_access_eval_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.policy_approval_approver
    ADD CONSTRAINT governance_policy_approver_identity_uq UNIQUE (tenant_id, policy_approval_stage_id, approver_identity_id);

ALTER TABLE ONLY governance.policy_approval_approver
    ADD CONSTRAINT governance_policy_approver_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.policy_evaluation
    ADD CONSTRAINT governance_policy_evaluation_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.policy
    ADD CONSTRAINT governance_policy_kind_uq UNIQUE (tenant_id, policy_kind);

ALTER TABLE ONLY governance.policy_approval_stage
    ADD CONSTRAINT governance_policy_stage_ordinal_uq UNIQUE (tenant_id, policy_version_id, stage_ordinal);

ALTER TABLE ONLY governance.policy_approval_stage
    ADD CONSTRAINT governance_policy_stage_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.policy
    ADD CONSTRAINT governance_policy_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.policy_version
    ADD CONSTRAINT governance_policy_version_number_uq UNIQUE (tenant_id, policy_id, version_number);

ALTER TABLE ONLY governance.policy_version
    ADD CONSTRAINT governance_policy_version_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.review_campaign
    ADD CONSTRAINT governance_review_campaign_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.review_decision
    ADD CONSTRAINT governance_review_decision_item_uq UNIQUE (tenant_id, review_item_id);

ALTER TABLE ONLY governance.review_decision
    ADD CONSTRAINT governance_review_decision_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.review_item
    ADD CONSTRAINT governance_review_item_assignment_uq UNIQUE (tenant_id, review_campaign_id, access_assignment_id);

ALTER TABLE ONLY governance.review_item
    ADD CONSTRAINT governance_review_item_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.review_remediation
    ADD CONSTRAINT governance_review_remediation_item_uq UNIQUE (tenant_id, review_item_id);

ALTER TABLE ONLY governance.review_remediation
    ADD CONSTRAINT governance_review_remediation_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.risk_assessment
    ADD CONSTRAINT governance_risk_assessment_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.sod_conflict
    ADD CONSTRAINT governance_sod_conflict_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.sod_rule
    ADD CONSTRAINT governance_sod_rule_code_uq UNIQUE (tenant_id, policy_version_id, rule_code);

ALTER TABLE ONLY governance.sod_rule
    ADD CONSTRAINT governance_sod_rule_pair_uq UNIQUE (tenant_id, policy_version_id, left_entitlement_id, right_entitlement_id);

ALTER TABLE ONLY governance.sod_rule
    ADD CONSTRAINT governance_sod_rule_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.lifecycle_access_approval_candidate
    ADD CONSTRAINT lifecycle_access_approval_candidate_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.lifecycle_access_evaluation
    ADD CONSTRAINT lifecycle_access_evaluation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.lifecycle_access_sod_conflict
    ADD CONSTRAINT lifecycle_access_sod_conflict_pkey PRIMARY KEY (tenant_id, evaluation_id, sod_rule_id);

ALTER TABLE ONLY governance.policy_approval_approver
    ADD CONSTRAINT policy_approval_approver_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.policy_approval_stage
    ADD CONSTRAINT policy_approval_stage_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.policy_evaluation
    ADD CONSTRAINT policy_evaluation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.policy
    ADD CONSTRAINT policy_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.policy_version
    ADD CONSTRAINT policy_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.request_item
    ADD CONSTRAINT request_item_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.request_item
    ADD CONSTRAINT request_item_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY governance.review_campaign
    ADD CONSTRAINT review_campaign_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.review_decision
    ADD CONSTRAINT review_decision_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.review_item
    ADD CONSTRAINT review_item_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.review_remediation
    ADD CONSTRAINT review_remediation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.risk_assessment
    ADD CONSTRAINT risk_assessment_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.sod_conflict
    ADD CONSTRAINT sod_conflict_pkey PRIMARY KEY (id);

ALTER TABLE ONLY governance.sod_rule
    ADD CONSTRAINT sod_rule_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.attribute_authority_rule_version
    ADD CONSTRAINT attribute_authority_rule_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.attribute_definition
    ADD CONSTRAINT attribute_definition_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.attribute_definition_version
    ADD CONSTRAINT attribute_definition_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.attribute_mapping_version
    ADD CONSTRAINT attribute_mapping_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT canonical_attribute_candidate_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.canonical_attribute_candidate_value
    ADD CONSTRAINT canonical_attribute_candidate_value_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.canonical_attribute_override
    ADD CONSTRAINT canonical_attribute_override_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.canonical_attribute_override_value
    ADD CONSTRAINT canonical_attribute_override_value_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT canonical_attribute_state_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.canonical_attribute_state_value
    ADD CONSTRAINT canonical_attribute_state_value_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.canonical_schema_version
    ADD CONSTRAINT canonical_schema_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.attribute_authority_rule_version
    ADD CONSTRAINT identity_attribute_authority_rule_definition_ref_uq UNIQUE (tenant_id, id, attribute_definition_version_id);

ALTER TABLE ONLY identity.attribute_authority_rule_version
    ADD CONSTRAINT identity_attribute_authority_rule_number_uq UNIQUE (tenant_id, attribute_definition_version_id, source_system_id, version_number);

ALTER TABLE ONLY identity.attribute_authority_rule_version
    ADD CONSTRAINT identity_attribute_authority_rule_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.attribute_definition
    ADD CONSTRAINT identity_attribute_definition_key_uq UNIQUE (tenant_id, canonical_key);

ALTER TABLE ONLY identity.attribute_definition
    ADD CONSTRAINT identity_attribute_definition_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.attribute_definition_version
    ADD CONSTRAINT identity_attribute_definition_version_definition_ref_uq UNIQUE (tenant_id, id, attribute_definition_id);

ALTER TABLE ONLY identity.attribute_definition_version
    ADD CONSTRAINT identity_attribute_definition_version_schema_definition_uq UNIQUE (tenant_id, schema_version_id, attribute_definition_id);

ALTER TABLE ONLY identity.attribute_definition_version
    ADD CONSTRAINT identity_attribute_definition_version_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.attribute_definition_version
    ADD CONSTRAINT identity_attribute_definition_version_type_ref_uq UNIQUE (tenant_id, id, data_type, cardinality);

ALTER TABLE ONLY identity.attribute_mapping_version
    ADD CONSTRAINT identity_attribute_mapping_version_number_uq UNIQUE (tenant_id, source_system_id, attribute_definition_version_id, version_number);

ALTER TABLE ONLY identity.attribute_mapping_version
    ADD CONSTRAINT identity_attribute_mapping_version_provenance_ref_uq UNIQUE (tenant_id, id, source_system_id, attribute_definition_version_id);

ALTER TABLE ONLY identity.attribute_mapping_version
    ADD CONSTRAINT identity_attribute_mapping_version_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_candidate_value
    ADD CONSTRAINT identity_candidate_value_ordinal_uq UNIQUE (tenant_id, candidate_id, value_ordinal);

ALTER TABLE ONLY identity.canonical_attribute_candidate_value
    ADD CONSTRAINT identity_candidate_value_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_definition_ref_uq UNIQUE (tenant_id, id, attribute_definition_version_id);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_source_uq UNIQUE (tenant_id, identity_id, attribute_definition_version_id, source_record_id, mapping_version_id);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_state_ref_uq UNIQUE (tenant_id, id, identity_id, attribute_definition_version_id);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_override
    ADD CONSTRAINT identity_canonical_attribute_override_definition_ref_uq UNIQUE (tenant_id, id, attribute_definition_version_id);

ALTER TABLE ONLY identity.canonical_attribute_override
    ADD CONSTRAINT identity_canonical_attribute_override_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_definition_ref_uq UNIQUE (tenant_id, id, attribute_definition_version_id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_identity_definition_uq UNIQUE (tenant_id, identity_id, attribute_definition_id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.canonical_schema_version
    ADD CONSTRAINT identity_canonical_schema_version_number_uq UNIQUE (tenant_id, version_number);

ALTER TABLE ONLY identity.canonical_schema_version
    ADD CONSTRAINT identity_canonical_schema_version_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.identity_link
    ADD CONSTRAINT identity_identity_link_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.identity
    ADD CONSTRAINT identity_identity_profile_ref_uq UNIQUE (tenant_id, id, identity_type);

ALTER TABLE ONLY identity.identity
    ADD CONSTRAINT identity_identity_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.identity_link
    ADD CONSTRAINT identity_link_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.identity_merge_operation
    ADD CONSTRAINT identity_merge_operation_absorbed_uq UNIQUE (tenant_id, absorbed_identity_id);

ALTER TABLE ONLY identity.identity_merge_operation
    ADD CONSTRAINT identity_merge_operation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.identity_merge_operation
    ADD CONSTRAINT identity_merge_operation_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_override_value
    ADD CONSTRAINT identity_override_value_ordinal_uq UNIQUE (tenant_id, override_id, value_ordinal);

ALTER TABLE ONLY identity.canonical_attribute_override_value
    ADD CONSTRAINT identity_override_value_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.person_profile
    ADD CONSTRAINT identity_person_profile_tenant_id_uq UNIQUE (tenant_id, identity_id);

ALTER TABLE ONLY identity.identity
    ADD CONSTRAINT identity_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.principal
    ADD CONSTRAINT identity_principal_target_native_uq UNIQUE (tenant_id, application_target_id, native_principal_key);

ALTER TABLE ONLY identity.principal
    ADD CONSTRAINT identity_principal_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.service_profile
    ADD CONSTRAINT identity_service_profile_tenant_id_uq UNIQUE (tenant_id, identity_id);

ALTER TABLE ONLY identity.source_absence_inference
    ADD CONSTRAINT identity_source_absence_inference_run_uq UNIQUE (tenant_id, import_run_id);

ALTER TABLE ONLY identity.source_absence_inference
    ADD CONSTRAINT identity_source_absence_inference_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.source_absence_policy_version
    ADD CONSTRAINT identity_source_absence_policy_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.source_absence_policy_version
    ADD CONSTRAINT identity_source_absence_policy_version_uq UNIQUE (tenant_id, source_system_id, version_number);

ALTER TABLE ONLY identity.source_absence_transition_evidence
    ADD CONSTRAINT identity_source_absence_transition_context_uq UNIQUE (tenant_id, inference_id, source_record_id, identity_id, post_identity_revision);

ALTER TABLE ONLY identity.source_absence_transition_evidence
    ADD CONSTRAINT identity_source_absence_transition_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.source_correlation_policy_version
    ADD CONSTRAINT identity_source_correlation_policy_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.source_correlation_policy_version
    ADD CONSTRAINT identity_source_correlation_policy_version_uq UNIQUE (tenant_id, source_system_id, version_number);

ALTER TABLE ONLY identity.source_import_run
    ADD CONSTRAINT identity_source_import_run_source_ref_uq UNIQUE (tenant_id, id, source_system_id);

ALTER TABLE ONLY identity.source_import_run
    ADD CONSTRAINT identity_source_import_run_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.source_lifecycle_policy_version
    ADD CONSTRAINT identity_source_lifecycle_policy_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.source_lifecycle_policy_version
    ADD CONSTRAINT identity_source_lifecycle_policy_version_uq UNIQUE (tenant_id, source_system_id, version_number);

ALTER TABLE ONLY identity.source_record
    ADD CONSTRAINT identity_source_record_native_key_uq UNIQUE (tenant_id, source_system_id, native_key);

ALTER TABLE ONLY identity.source_record
    ADD CONSTRAINT identity_source_record_source_ref_uq UNIQUE (tenant_id, id, source_system_id);

ALTER TABLE ONLY identity.source_record
    ADD CONSTRAINT identity_source_record_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.source_suspension_transition_evidence
    ADD CONSTRAINT identity_source_suspension_transition_context_uq UNIQUE (tenant_id, source_record_id, identity_id, post_identity_revision);

ALTER TABLE ONLY identity.source_suspension_transition_evidence
    ADD CONSTRAINT identity_source_suspension_transition_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.source_system
    ADD CONSTRAINT identity_source_system_code_uq UNIQUE (tenant_id, code);

ALTER TABLE ONLY identity.source_system
    ADD CONSTRAINT identity_source_system_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.identity_split_operation
    ADD CONSTRAINT identity_split_operation_new_identity_uq UNIQUE (tenant_id, new_identity_id);

ALTER TABLE ONLY identity.identity_split_operation
    ADD CONSTRAINT identity_split_operation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.identity_split_operation
    ADD CONSTRAINT identity_split_operation_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.identity_split_principal
    ADD CONSTRAINT identity_split_principal_pkey PRIMARY KEY (tenant_id, operation_id, principal_id);

ALTER TABLE ONLY identity.identity_split_source_record
    ADD CONSTRAINT identity_split_source_record_pkey PRIMARY KEY (tenant_id, operation_id, source_record_id);

ALTER TABLE ONLY identity.canonical_attribute_state_value
    ADD CONSTRAINT identity_state_value_ordinal_uq UNIQUE (tenant_id, state_id, value_ordinal);

ALTER TABLE ONLY identity.canonical_attribute_state_value
    ADD CONSTRAINT identity_state_value_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.workload_profile
    ADD CONSTRAINT identity_workload_profile_tenant_id_uq UNIQUE (tenant_id, identity_id);

ALTER TABLE ONLY identity.person_profile
    ADD CONSTRAINT person_profile_pkey PRIMARY KEY (identity_id);

ALTER TABLE ONLY identity.principal
    ADD CONSTRAINT principal_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.service_profile
    ADD CONSTRAINT service_profile_pkey PRIMARY KEY (identity_id);

ALTER TABLE ONLY identity.source_absence_inference
    ADD CONSTRAINT source_absence_inference_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.source_absence_policy_version
    ADD CONSTRAINT source_absence_policy_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.source_absence_transition_evidence
    ADD CONSTRAINT source_absence_transition_evidence_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.source_correlation_policy_version
    ADD CONSTRAINT source_correlation_policy_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.source_import_run
    ADD CONSTRAINT source_import_run_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.source_lifecycle_policy_rule
    ADD CONSTRAINT source_lifecycle_policy_rule_pkey PRIMARY KEY (tenant_id, policy_version_id, source_value);

ALTER TABLE ONLY identity.source_lifecycle_policy_version
    ADD CONSTRAINT source_lifecycle_policy_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.source_record
    ADD CONSTRAINT source_record_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.source_suspension_transition_evidence
    ADD CONSTRAINT source_suspension_transition_evidence_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.source_system
    ADD CONSTRAINT source_system_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.workload_profile
    ADD CONSTRAINT workload_profile_pkey PRIMARY KEY (identity_id);

ALTER TABLE ONLY integration.connector_binding
    ADD CONSTRAINT connector_binding_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.connector_instance
    ADD CONSTRAINT connector_instance_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.connector_worker_binding_scope
    ADD CONSTRAINT connector_worker_binding_scope_pkey PRIMARY KEY (tenant_id, worker_registration_id, connector_binding_id);

ALTER TABLE ONLY integration.connector_worker_registration
    ADD CONSTRAINT connector_worker_registration_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.connector_worker_runtime_permission
    ADD CONSTRAINT connector_worker_runtime_permission_pkey PRIMARY KEY (tenant_id, worker_registration_id, runtime_id, runtime_version, capability, contract_id, contract_version);

ALTER TABLE ONLY integration.connector_worker_session_capability
    ADD CONSTRAINT connector_worker_session_capability_pkey PRIMARY KEY (tenant_id, session_id, runtime_id, runtime_version, capability);

ALTER TABLE ONLY integration.connector_worker_session_contract
    ADD CONSTRAINT connector_worker_session_contract_pkey PRIMARY KEY (tenant_id, session_id, runtime_id, runtime_version, contract_id, contract_version);

ALTER TABLE ONLY integration.connector_worker_session
    ADD CONSTRAINT connector_worker_session_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.entitlement_observation_mapping
    ADD CONSTRAINT entitlement_observation_mapping_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.connector_binding
    ADD CONSTRAINT integration_connector_binding_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.connector_instance
    ADD CONSTRAINT integration_connector_instance_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.entitlement_observation_mapping
    ADD CONSTRAINT integration_entitlement_mapping_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.observed_entitlement
    ADD CONSTRAINT integration_observed_entitlement_provider_uq UNIQUE (tenant_id, connector_binding_id, provider_stable_id);

ALTER TABLE ONLY integration.observed_entitlement
    ADD CONSTRAINT integration_observed_entitlement_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.observed_grant
    ADD CONSTRAINT integration_observed_grant_pair_uq UNIQUE (tenant_id, connector_binding_id, principal_provider_id, entitlement_provider_id);

ALTER TABLE ONLY integration.observed_grant
    ADD CONSTRAINT integration_observed_grant_provider_uq UNIQUE (tenant_id, connector_binding_id, provider_stable_id);

ALTER TABLE ONLY integration.observed_grant
    ADD CONSTRAINT integration_observed_grant_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.observed_principal
    ADD CONSTRAINT integration_observed_principal_provider_uq UNIQUE (tenant_id, connector_binding_id, provider_stable_id);

ALTER TABLE ONLY integration.observed_principal
    ADD CONSTRAINT integration_observed_principal_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.provisioning_attempt
    ADD CONSTRAINT integration_provisioning_attempt_epoch_uq UNIQUE (tenant_id, task_id, lease_epoch);

ALTER TABLE ONLY integration.provisioning_attempt
    ADD CONSTRAINT integration_provisioning_attempt_number_uq UNIQUE (tenant_id, task_id, attempt_number);

ALTER TABLE ONLY integration.provisioning_attempt
    ADD CONSTRAINT integration_provisioning_attempt_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.provisioning_job
    ADD CONSTRAINT integration_provisioning_job_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.provisioning_task
    ADD CONSTRAINT integration_provisioning_task_idempotency_uq UNIQUE (tenant_id, idempotency_key);

ALTER TABLE ONLY integration.provisioning_task
    ADD CONSTRAINT integration_provisioning_task_job_id_uq UNIQUE (tenant_id, provisioning_job_id, id);

ALTER TABLE ONLY integration.provisioning_task
    ADD CONSTRAINT integration_provisioning_task_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.reconciliation_observation_batch
    ADD CONSTRAINT integration_reconciliation_batch_sequence_uq UNIQUE (tenant_id, reconciliation_run_id, sequence);

ALTER TABLE ONLY integration.reconciliation_entitlement_staging
    ADD CONSTRAINT integration_reconciliation_entitlement_stage_provider_uq UNIQUE (tenant_id, reconciliation_run_id, provider_stable_id);

ALTER TABLE ONLY integration.reconciliation_entitlement_staging
    ADD CONSTRAINT integration_reconciliation_entitlement_stage_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.reconciliation_grant_staging
    ADD CONSTRAINT integration_reconciliation_grant_stage_pair_uq UNIQUE (tenant_id, reconciliation_run_id, principal_provider_id, entitlement_provider_id);

ALTER TABLE ONLY integration.reconciliation_grant_staging
    ADD CONSTRAINT integration_reconciliation_grant_stage_provider_uq UNIQUE (tenant_id, reconciliation_run_id, provider_stable_id);

ALTER TABLE ONLY integration.reconciliation_grant_staging
    ADD CONSTRAINT integration_reconciliation_grant_stage_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.reconciliation_run
    ADD CONSTRAINT integration_reconciliation_run_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.reconciliation_principal_staging
    ADD CONSTRAINT integration_reconciliation_staging_provider_uq UNIQUE (tenant_id, reconciliation_run_id, provider_stable_id);

ALTER TABLE ONLY integration.reconciliation_principal_staging
    ADD CONSTRAINT integration_reconciliation_staging_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.connector_worker_registration
    ADD CONSTRAINT integration_worker_registration_subject_uq UNIQUE (external_subject_key);

ALTER TABLE ONLY integration.connector_worker_registration
    ADD CONSTRAINT integration_worker_registration_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.connector_worker_session
    ADD CONSTRAINT integration_worker_session_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.observed_entitlement
    ADD CONSTRAINT observed_entitlement_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.observed_grant
    ADD CONSTRAINT observed_grant_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.observed_principal
    ADD CONSTRAINT observed_principal_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.provisioning_attempt
    ADD CONSTRAINT provisioning_attempt_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.provisioning_job
    ADD CONSTRAINT provisioning_job_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.provisioning_task_dependency
    ADD CONSTRAINT provisioning_task_dependency_pkey PRIMARY KEY (tenant_id, provisioning_job_id, task_id, depends_on_task_id);

ALTER TABLE ONLY integration.provisioning_task
    ADD CONSTRAINT provisioning_task_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.reconciliation_entitlement_staging
    ADD CONSTRAINT reconciliation_entitlement_staging_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.reconciliation_grant_staging
    ADD CONSTRAINT reconciliation_grant_staging_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.reconciliation_observation_batch
    ADD CONSTRAINT reconciliation_observation_batch_pkey PRIMARY KEY (tenant_id, reconciliation_run_id, batch_id);

ALTER TABLE ONLY integration.reconciliation_principal_staging
    ADD CONSTRAINT reconciliation_principal_staging_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.reconciliation_run
    ADD CONSTRAINT reconciliation_run_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform.connector_work_lease
    ADD CONSTRAINT connector_work_lease_pkey PRIMARY KEY (tenant_id, work_kind, work_id);

ALTER TABLE ONLY platform.idempotency_record
    ADD CONSTRAINT idempotency_record_causal_key_unique UNIQUE (tenant_id, operation_namespace, idempotency_key);

ALTER TABLE ONLY platform.idempotency_record
    ADD CONSTRAINT idempotency_record_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform.idempotency_record
    ADD CONSTRAINT idempotency_record_tenant_id_unique UNIQUE (tenant_id, id);

ALTER TABLE ONLY platform.idp_authorization_code
    ADD CONSTRAINT idp_authorization_code_hash_uq UNIQUE (tenant_id, code_hash);

ALTER TABLE ONLY platform.idp_authorization_code
    ADD CONSTRAINT idp_authorization_code_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform.idp_browser_session
    ADD CONSTRAINT idp_browser_session_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform.idp_browser_session
    ADD CONSTRAINT idp_browser_session_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY platform.idp_browser_session
    ADD CONSTRAINT idp_browser_session_token_hash_uq UNIQUE (tenant_id, token_hash);

ALTER TABLE ONLY platform.inbox_message
    ADD CONSTRAINT inbox_message_dedup_unique UNIQUE (tenant_id, consumer_name, message_id);

ALTER TABLE ONLY platform.inbox_message
    ADD CONSTRAINT inbox_message_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform.inbox_message
    ADD CONSTRAINT inbox_message_tenant_id_unique UNIQUE (tenant_id, id);

ALTER TABLE ONLY platform.installation_bootstrap
    ADD CONSTRAINT installation_bootstrap_pkey PRIMARY KEY (singleton_key);

ALTER TABLE ONLY platform.outbox_event
    ADD CONSTRAINT outbox_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform.outbox_event
    ADD CONSTRAINT outbox_event_tenant_id_unique UNIQUE (tenant_id, id);

ALTER TABLE ONLY platform.connector_work_lease
    ADD CONSTRAINT platform_connector_work_lease_id_uq UNIQUE (lease_id);

ALTER TABLE ONLY platform.scheduled_work
    ADD CONSTRAINT scheduled_work_causal_key_unique UNIQUE (tenant_id, handler_type, work_key);

ALTER TABLE ONLY platform.scheduled_work
    ADD CONSTRAINT scheduled_work_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform.scheduled_work
    ADD CONSTRAINT scheduled_work_tenant_id_unique UNIQUE (tenant_id, id);

ALTER TABLE ONLY platform.tenant
    ADD CONSTRAINT tenant_pkey PRIMARY KEY (id);

CREATE INDEX access_assignment_entitlement_idx ON access.access_assignment USING btree (tenant_id, entitlement_id, identity_id) WHERE ((target_kind)::text = 'ENTITLEMENT'::text);

CREATE INDEX access_assignment_identity_reduction_page_idx ON access.access_assignment USING btree (tenant_id, identity_id, created_at, id) WHERE ((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUSPENDED'::character varying, 'SCHEDULED'::character varying])::text[]));

CREATE INDEX access_assignment_identity_state_idx ON access.access_assignment USING btree (tenant_id, identity_id, lifecycle_state, valid_until) WHERE ((lifecycle_state)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'ACTIVE'::character varying, 'SUSPENDED'::character varying])::text[]));

CREATE UNIQUE INDEX access_assignment_lifecycle_policy_rule_active_uq ON access.access_assignment USING btree (tenant_id, identity_id, provenance_ref_id) WHERE (((provenance_kind)::text = 'LIFECYCLE_POLICY_RULE'::text) AND ((lifecycle_state)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'ACTIVE'::character varying, 'SUSPENDED'::character varying])::text[])));

CREATE UNIQUE INDEX access_assignment_request_item_provenance_uq ON access.access_assignment USING btree (tenant_id, provenance_ref_id) WHERE ((provenance_kind)::text = 'REQUEST_ITEM'::text);

CREATE INDEX access_assignment_specific_principal_idx ON access.access_assignment USING btree (tenant_id, specific_principal_id, lifecycle_state) WHERE (specific_principal_id IS NOT NULL);

CREATE INDEX access_desired_grant_present_target_idx ON access.desired_grant_state USING btree (tenant_id, identity_id, application_target_id, desired_state);

CREATE INDEX access_desired_grant_state_target_idx ON access.desired_grant_state USING btree (tenant_id, application_target_id, identity_id, entitlement_id);

CREATE INDEX access_desired_principal_state_target_idx ON access.desired_principal_state USING btree (tenant_id, application_target_id, identity_id);

CREATE INDEX access_review_remediation_application_assignment_idx ON access.review_remediation_application USING btree (tenant_id, access_assignment_id, applied_at DESC);

CREATE INDEX effective_access_entitlement_identity_idx ON access.effective_access USING btree (tenant_id, entitlement_id, identity_id);

CREATE INDEX effective_access_identity_entitlement_idx ON access.effective_access USING btree (tenant_id, identity_id, entitlement_id);

CREATE INDEX effective_access_support_assignment_idx ON access.effective_access_support USING btree (tenant_id, access_assignment_id);

CREATE INDEX effective_access_support_role_version_lookup_idx ON access.effective_access_support_role_version USING btree (tenant_id, role_version_id, effective_access_support_id);

CREATE INDEX identity_access_reduction_state_idx ON access.identity_access_reduction USING btree (tenant_id, lifecycle_state, created_at, id);

CREATE UNIQUE INDEX lifecycle_access_policy_one_active_idx ON access.lifecycle_access_policy_version USING btree (tenant_id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE INDEX administration_actor_binding_tenant_actor_idx ON administration.control_plane_actor_binding USING btree (tenant_id, actor_identity_id, state);

CREATE INDEX administration_delegation_delegate_state_idx ON administration.administrative_delegation USING btree (tenant_id, delegate_identity_id, state, role_id, valid_until);

CREATE INDEX administration_delegation_role_state_idx ON administration.administrative_delegation USING btree (tenant_id, role_id, state, valid_until);

CREATE INDEX administration_delegation_source_state_idx ON administration.administrative_delegation USING btree (tenant_id, source_grant_id, state, valid_until);

CREATE INDEX administration_grant_actor_state_idx ON administration.administrative_grant USING btree (tenant_id, actor_identity_id, state, role_id);

CREATE INDEX administration_grant_authority_basis_idx ON administration.administrative_grant USING btree (tenant_id, authority_basis_grant_id) WHERE (authority_basis_grant_id IS NOT NULL);

CREATE INDEX administration_role_permission_permission_idx ON administration.administrative_role_permission USING btree (tenant_id, permission_id, role_id);

CREATE INDEX administrative_break_glass_actor_state_idx ON administration.administrative_break_glass_operation USING btree (tenant_id, actor_identity_id, state, valid_until);

CREATE INDEX administrative_break_glass_obligation_pending_idx ON administration.administrative_break_glass_obligation USING btree (tenant_id, state, obligation_type, created_at, id);

CREATE INDEX administrative_break_glass_review_reviewer_idx ON administration.administrative_break_glass_review USING btree (tenant_id, reviewer_identity_id, reviewed_at DESC, id DESC);

CREATE INDEX administrative_break_glass_role_state_idx ON administration.administrative_break_glass_operation USING btree (tenant_id, role_id, state, valid_until);

CREATE INDEX administrative_elevation_beneficiary_state_idx ON administration.administrative_elevation USING btree (tenant_id, beneficiary_identity_id, state, valid_until);

CREATE INDEX administrative_elevation_role_state_idx ON administration.administrative_elevation USING btree (tenant_id, role_id, state, valid_until);

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

CREATE UNIQUE INDEX catalog_application_role_code_uq ON catalog.role USING btree (tenant_id, application_id, code) WHERE ((role_type)::text = 'APPLICATION'::text);

CREATE INDEX catalog_application_target_tenant_app_idx ON catalog.application_target USING btree (tenant_id, application_id, lifecycle_state, created_at, id);

CREATE INDEX catalog_application_tenant_lifecycle_idx ON catalog.application USING btree (tenant_id, lifecycle_state, created_at, id);

CREATE UNIQUE INDEX catalog_business_role_code_uq ON catalog.role USING btree (tenant_id, code) WHERE ((role_type)::text = 'BUSINESS'::text);

CREATE INDEX catalog_entitlement_target_idx ON catalog.entitlement USING btree (tenant_id, application_target_id, lifecycle_state, created_at, id) WHERE (application_target_id IS NOT NULL);

CREATE INDEX catalog_entitlement_tenant_app_idx ON catalog.entitlement USING btree (tenant_id, application_id, lifecycle_state, created_at, id);

CREATE INDEX catalog_role_member_child_role_idx ON catalog.role_version_member USING btree (tenant_id, member_role_id) WHERE (member_role_id IS NOT NULL);

CREATE INDEX catalog_role_member_entitlement_idx ON catalog.role_version_member USING btree (tenant_id, member_entitlement_id) WHERE (member_entitlement_id IS NOT NULL);

CREATE UNIQUE INDEX catalog_role_one_active_version_uq ON catalog.role_version USING btree (tenant_id, role_id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE INDEX catalog_role_version_role_idx ON catalog.role_version USING btree (tenant_id, role_id, version_number DESC);

CREATE INDEX catalog_sso_client_active_client_id_idx ON catalog.sso_client_registration USING btree (tenant_id, client_id) WHERE ((lifecycle_state)::text = 'ACTIVE'::text);

CREATE INDEX catalog_sso_client_application_page_idx ON catalog.sso_client_registration USING btree (tenant_id, application_id, created_at, id);

CREATE INDEX credential_credential_principal_created_idx ON credential.credential USING btree (tenant_id, principal_id, created_at, id);

COMMENT ON INDEX credential.credential_credential_principal_created_idx IS 'Supports deterministic bounded public Credential listing by Principal using immutable createdAt + id continuation.';

CREATE INDEX credential_credential_principal_idx ON credential.credential USING btree (tenant_id, principal_id, lifecycle_state, created_at, id);

CREATE INDEX credential_rotation_old_idx ON credential.credential_rotation USING btree (tenant_id, old_credential_id, created_at DESC);

CREATE UNIQUE INDEX credential_rotation_open_old_uq ON credential.credential_rotation USING btree (tenant_id, old_credential_id) WHERE ((process_state)::text <> ALL ((ARRAY['COMPLETED'::character varying, 'FAILED'::character varying, 'MANUAL_REQUIRED'::character varying, 'FAILED_REMEDIATION'::character varying])::text[]));

CREATE INDEX credential_rotation_replacement_idx ON credential.credential_rotation USING btree (tenant_id, replacement_credential_id, created_at DESC) WHERE (replacement_credential_id IS NOT NULL);

CREATE INDEX approval_approver_identity_stage_idx ON governance.approval_approver USING btree (tenant_id, approver_identity_id, approval_stage_id);

COMMENT ON INDEX governance.approval_approver_identity_stage_idx IS 'Supports deterministic reusable approval inbox lookup by tenant and governed approver Identity.';

CREATE UNIQUE INDEX approval_case_active_subject_uq ON governance.approval_case USING btree (tenant_id, subject_kind, subject_id) WHERE ((state)::text = 'PENDING'::text);

CREATE INDEX approval_case_subject_idx ON governance.approval_case USING btree (tenant_id, subject_kind, subject_id, state);

CREATE INDEX approval_decision_case_idx ON governance.approval_decision USING btree (tenant_id, approval_case_id, decided_at);

CREATE INDEX governance_access_request_actor_updated_idx ON governance.access_request USING btree (tenant_id, requester_identity_id, beneficiary_identity_id, updated_at DESC, id DESC);

COMMENT ON INDEX governance.governance_access_request_actor_updated_idx IS 'Supports actor-scoped bounded Requests & Approvals request summaries ordered by current update time.';

CREATE INDEX governance_approval_decision_actor_time_idx ON governance.approval_decision USING btree (tenant_id, approver_identity_id, decided_at DESC);

COMMENT ON INDEX governance.governance_approval_decision_actor_time_idx IS 'Supports actor-scoped IAM Operations Governance approval decision counts by time window.';

CREATE INDEX governance_exception_effective_lookup_idx ON governance.governance_exception USING btree (tenant_id, subject_identity_id, sod_rule_id, lifecycle_state, valid_from, valid_until);

CREATE INDEX governance_exception_predecessor_idx ON governance.governance_exception USING btree (tenant_id, predecessor_exception_id) WHERE (predecessor_exception_id IS NOT NULL);

CREATE INDEX governance_finding_open_binding_idx ON governance.finding USING btree (tenant_id, connector_binding_id, finding_type, provider_stable_id) WHERE ((lifecycle_state)::text = 'OPEN'::text);

CREATE INDEX governance_lifecycle_access_candidate_identity_idx ON governance.lifecycle_access_approval_candidate USING btree (tenant_id, identity_id, created_at, id);

CREATE INDEX governance_lifecycle_access_eval_identity_idx ON governance.lifecycle_access_evaluation USING btree (tenant_id, identity_id, evaluated_at DESC, id);

CREATE INDEX governance_lifecycle_access_eval_rule_idx ON governance.lifecycle_access_evaluation USING btree (tenant_id, lifecycle_rule_id, evaluated_at DESC, id);

CREATE INDEX governance_policy_evaluation_item_idx ON governance.policy_evaluation USING btree (tenant_id, request_item_id, evaluated_at DESC);

CREATE UNIQUE INDEX governance_policy_version_active_uq ON governance.policy_version USING btree (tenant_id, policy_id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE INDEX governance_request_item_request_state_updated_idx ON governance.request_item USING btree (tenant_id, access_request_id, state, updated_at DESC);

COMMENT ON INDEX governance.governance_request_item_request_state_updated_idx IS 'Supports bounded Requests & Approvals item-state composition and recent APPLIED reads without browser aggregation.';

CREATE INDEX governance_review_campaign_api_page_idx ON governance.review_campaign USING btree (tenant_id, created_at, id);

CREATE INDEX governance_review_campaign_reviewer_idx ON governance.review_campaign USING btree (tenant_id, reviewer_identity_id, lifecycle_state, created_at DESC);

CREATE INDEX governance_review_campaign_subject_idx ON governance.review_campaign USING btree (tenant_id, subject_identity_id, created_at DESC);

CREATE INDEX governance_review_decision_actor_time_idx ON governance.review_decision USING btree (tenant_id, reviewer_identity_id, decided_at DESC);

COMMENT ON INDEX governance.governance_review_decision_actor_time_idx IS 'Supports actor-scoped IAM Operations review decision counts by time window.';

CREATE INDEX governance_review_item_campaign_api_page_idx ON governance.review_item USING btree (tenant_id, review_campaign_id, created_at, id);

CREATE INDEX governance_review_item_reviewer_idx ON governance.review_item USING btree (tenant_id, reviewer_identity_id, lifecycle_state, created_at, id);

CREATE INDEX governance_review_item_reviewer_state_created_idx ON governance.review_item USING btree (tenant_id, reviewer_identity_id, lifecycle_state, created_at, id);

CREATE INDEX governance_review_remediation_item_idx ON governance.review_remediation USING btree (tenant_id, review_item_id);

CREATE INDEX governance_review_remediation_state_idx ON governance.review_remediation USING btree (tenant_id, lifecycle_state, created_at, id);

CREATE INDEX governance_sod_conflict_assessment_idx ON governance.sod_conflict USING btree (tenant_id, risk_assessment_id, severity);

CREATE INDEX request_item_access_assignment_idx ON governance.request_item USING btree (tenant_id, access_assignment_id) WHERE (access_assignment_id IS NOT NULL);

CREATE INDEX request_item_approval_case_idx ON governance.request_item USING btree (tenant_id, approval_case_id) WHERE (approval_case_id IS NOT NULL);

CREATE INDEX request_item_request_state_idx ON governance.request_item USING btree (tenant_id, access_request_id, state);

CREATE UNIQUE INDEX identity_attribute_authority_one_active_idx ON identity.attribute_authority_rule_version USING btree (tenant_id, attribute_definition_version_id, source_system_id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE UNIQUE INDEX identity_attribute_mapping_one_active_idx ON identity.attribute_mapping_version USING btree (tenant_id, source_system_id, attribute_definition_version_id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE INDEX identity_candidate_resolution_idx ON identity.canonical_attribute_candidate USING btree (tenant_id, identity_id, attribute_definition_version_id, source_system_id, id);

CREATE UNIQUE INDEX identity_canonical_attribute_override_one_active_idx ON identity.canonical_attribute_override USING btree (tenant_id, identity_id, attribute_definition_id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE UNIQUE INDEX identity_canonical_schema_one_active_idx ON identity.canonical_schema_version USING btree (tenant_id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE INDEX identity_identity_link_identity_idx ON identity.identity_link USING btree (tenant_id, identity_id, linked_at, id);

CREATE UNIQUE INDEX identity_identity_link_one_active_accepted_idx ON identity.identity_link USING btree (tenant_id, source_record_id) WHERE (((link_state)::text = 'ACCEPTED'::text) AND (ended_at IS NULL));

CREATE INDEX identity_identity_tenant_lifecycle_idx ON identity.identity USING btree (tenant_id, lifecycle_state, id);

CREATE INDEX identity_merge_operation_survivor_idx ON identity.identity_merge_operation USING btree (tenant_id, survivor_identity_id, completed_at DESC, id);

CREATE INDEX identity_override_resolution_idx ON identity.canonical_attribute_override USING btree (tenant_id, identity_id, attribute_definition_id, state, valid_from, valid_until, id);

CREATE INDEX identity_principal_identity_idx ON identity.principal USING btree (tenant_id, identity_id, application_target_id) WHERE (identity_id IS NOT NULL);

CREATE INDEX identity_principal_target_idx ON identity.principal USING btree (tenant_id, application_target_id, lifecycle_state, id);

CREATE INDEX identity_source_absence_candidate_idx ON identity.source_record USING btree (tenant_id, source_system_id, first_observed_at, id);

CREATE UNIQUE INDEX identity_source_absence_policy_one_active_idx ON identity.source_absence_policy_version USING btree (tenant_id, source_system_id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE INDEX identity_source_absence_transition_restore_idx ON identity.source_absence_transition_evidence USING btree (tenant_id, source_record_id, identity_link_id, identity_id, post_identity_revision);

CREATE UNIQUE INDEX identity_source_correlation_policy_one_active_idx ON identity.source_correlation_policy_version USING btree (tenant_id, source_system_id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE UNIQUE INDEX identity_source_lifecycle_policy_one_active_idx ON identity.source_lifecycle_policy_version USING btree (tenant_id, source_system_id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE INDEX identity_source_record_source_observed_idx ON identity.source_record USING btree (tenant_id, source_system_id, last_observed_at, id);

CREATE INDEX identity_source_suspension_transition_restore_idx ON identity.source_suspension_transition_evidence USING btree (tenant_id, source_record_id, identity_link_id, identity_id, post_identity_revision);

CREATE INDEX identity_split_operation_source_idx ON identity.identity_split_operation USING btree (tenant_id, source_identity_id, completed_at DESC, id);

CREATE INDEX identity_state_identity_idx ON identity.canonical_attribute_state USING btree (tenant_id, identity_id, attribute_definition_id);

CREATE INDEX identity_state_single_string_correlation_lookup_idx ON identity.canonical_attribute_state_value USING btree (tenant_id, attribute_definition_version_id, value_string, state_id) WHERE (((data_type)::text = 'STRING'::text) AND ((cardinality)::text = 'SINGLE'::text) AND (value_ordinal = 0));

CREATE INDEX integration_connector_instance_lifecycle_idx ON integration.connector_instance USING btree (tenant_id, lifecycle_state);

CREATE UNIQUE INDEX integration_entitlement_mapping_active_provider_uq ON integration.entitlement_observation_mapping USING btree (tenant_id, connector_binding_id, provider_stable_id) WHERE ((lifecycle_state)::text = 'ACTIVE'::text);

CREATE INDEX integration_entitlement_mapping_entitlement_idx ON integration.entitlement_observation_mapping USING btree (tenant_id, entitlement_id, lifecycle_state);

CREATE INDEX integration_observed_entitlement_binding_present_idx ON integration.observed_entitlement USING btree (tenant_id, connector_binding_id, present, provider_stable_id);

CREATE INDEX integration_observed_grant_binding_present_idx ON integration.observed_grant USING btree (tenant_id, connector_binding_id, present, provider_stable_id);

CREATE INDEX integration_observed_grant_pair_idx ON integration.observed_grant USING btree (tenant_id, principal_provider_id, entitlement_provider_id);

CREATE INDEX integration_observed_principal_present_idx ON integration.observed_principal USING btree (tenant_id, connector_binding_id, present);

CREATE INDEX integration_provisioning_attempt_overview_time_idx ON integration.provisioning_attempt USING btree (tenant_id, completed_at DESC, outcome, failure_category);

COMMENT ON INDEX integration.integration_provisioning_attempt_overview_time_idx IS 'Supports bounded IAM Operations provisioning attempt summaries and trend reads by tenant and completion window.';

CREATE UNIQUE INDEX integration_provisioning_job_plan_key_uq ON integration.provisioning_job USING btree (tenant_id, plan_key) WHERE (plan_key IS NOT NULL);

CREATE INDEX integration_provisioning_task_claim_idx ON integration.provisioning_task USING btree (tenant_id, state, next_attempt_at, created_at);

CREATE INDEX integration_reconciliation_entitlement_stage_run_idx ON integration.reconciliation_entitlement_staging USING btree (tenant_id, reconciliation_run_id, provider_stable_id);

CREATE INDEX integration_reconciliation_grant_stage_run_idx ON integration.reconciliation_grant_staging USING btree (tenant_id, reconciliation_run_id, provider_stable_id);

CREATE INDEX integration_reconciliation_run_claim_idx ON integration.reconciliation_run USING btree (tenant_id, state, next_attempt_at, started_at);

CREATE INDEX integration_reconciliation_run_overview_time_idx ON integration.reconciliation_run USING btree (tenant_id, completed_at DESC, state, effective_completeness);

COMMENT ON INDEX integration.integration_reconciliation_run_overview_time_idx IS 'Supports bounded IAM Operations reconciliation summaries and recent-run reads by tenant and completion window.';

CREATE INDEX integration_worker_registration_subject_idx ON integration.connector_worker_registration USING btree (external_subject_key, state);

CREATE INDEX integration_worker_scope_binding_idx ON integration.connector_worker_binding_scope USING btree (tenant_id, connector_binding_id);

CREATE INDEX idempotency_record_expiry_idx ON platform.idempotency_record USING btree (expires_at) WHERE (expires_at IS NOT NULL);

CREATE INDEX idp_authorization_code_expiry_idx ON platform.idp_authorization_code USING btree (tenant_id, expires_at) WHERE (consumed_at IS NULL);

CREATE INDEX idp_browser_session_expiry_idx ON platform.idp_browser_session USING btree (tenant_id, idle_expires_at, absolute_expires_at) WHERE (revoked_at IS NULL);

CREATE UNIQUE INDEX idp_browser_session_token_hash_global_uq ON platform.idp_browser_session USING btree (token_hash);

COMMENT ON INDEX platform.idp_browser_session_token_hash_global_uq IS 'Globally unique opaque-session digest used to derive Tenant server-side from possession of a valid first-party browser session token.';

CREATE INDEX outbox_event_pending_idx ON platform.outbox_event USING btree (next_attempt_at, occurred_at, id) WHERE ((publication_state)::text = 'PENDING'::text);

CREATE INDEX outbox_event_tenant_aggregate_idx ON platform.outbox_event USING btree (tenant_id, aggregate_type, aggregate_id, aggregate_revision) WHERE (aggregate_id IS NOT NULL);

CREATE INDEX platform_connector_work_lease_expiry_idx ON platform.connector_work_lease USING btree (lease_expires_at);

CREATE INDEX scheduled_work_due_idx ON platform.scheduled_work USING btree (available_at, id) WHERE ((delivery_state)::text = 'READY'::text);

CREATE TRIGGER audit_archive_history_delete_trg BEFORE DELETE ON audit.audit_archive_segment FOR EACH ROW EXECUTE FUNCTION audit.reject_evidence_history_delete();

CREATE TRIGGER audit_archived_record_index_immutable_trg BEFORE DELETE OR UPDATE ON audit.audit_archived_record_index FOR EACH ROW EXECUTE FUNCTION audit.reject_immutable_evidence_mutation();

CREATE TRIGGER audit_export_history_delete_trg BEFORE DELETE ON audit.audit_export_operation FOR EACH ROW EXECUTE FUNCTION audit.reject_evidence_history_delete();

CREATE TRIGGER audit_legal_hold_history_delete_trg BEFORE DELETE ON audit.audit_legal_hold FOR EACH ROW EXECUTE FUNCTION audit.reject_evidence_history_delete();

CREATE TRIGGER audit_purge_history_delete_trg BEFORE DELETE ON audit.audit_purge_operation FOR EACH ROW EXECUTE FUNCTION audit.reject_evidence_history_delete();

CREATE TRIGGER audit_record_append_only_trg BEFORE DELETE OR UPDATE ON audit.audit_record FOR EACH ROW EXECUTE FUNCTION audit.reject_audit_record_mutation();

CREATE TRIGGER audit_retention_policy_immutable_trg BEFORE DELETE OR UPDATE ON audit.audit_retention_policy_version FOR EACH ROW EXECUTE FUNCTION audit.reject_immutable_evidence_mutation();

CREATE TRIGGER evidence_snapshot_immutable_trg BEFORE DELETE OR UPDATE ON audit.evidence_snapshot FOR EACH ROW EXECUTE FUNCTION audit.reject_immutable_evidence_mutation();

CREATE TRIGGER catalog_role_version_immutable_trg BEFORE UPDATE ON catalog.role_version FOR EACH ROW EXECUTE FUNCTION catalog.reject_immutable_activated_role_version_change();

CREATE TRIGGER catalog_role_version_member_immutable_trg BEFORE INSERT OR DELETE OR UPDATE ON catalog.role_version_member FOR EACH ROW EXECUTE FUNCTION catalog.reject_immutable_activated_role_version_member_change();

CREATE TRIGGER credential_delete_guard BEFORE DELETE ON credential.credential FOR EACH ROW EXECUTE FUNCTION credential.reject_credential_delete();

CREATE TRIGGER credential_rotation_delete_guard BEFORE DELETE ON credential.credential_rotation FOR EACH ROW EXECUTE FUNCTION credential.reject_credential_delete();

CREATE TRIGGER credential_rotation_update_guard BEFORE UPDATE ON credential.credential_rotation FOR EACH ROW EXECUTE FUNCTION credential.guard_rotation_update();

CREATE TRIGGER credential_update_guard BEFORE UPDATE ON credential.credential FOR EACH ROW EXECUTE FUNCTION credential.guard_credential_update();

CREATE TRIGGER approval_approver_immutable_trg BEFORE DELETE OR UPDATE ON governance.approval_approver FOR EACH ROW EXECUTE FUNCTION governance.reject_immutable_approval_evidence_change();

CREATE TRIGGER approval_decision_immutable_trg BEFORE DELETE OR UPDATE ON governance.approval_decision FOR EACH ROW EXECUTE FUNCTION governance.reject_immutable_approval_evidence_change();

CREATE TRIGGER approval_plan_immutable_trg BEFORE DELETE OR UPDATE ON governance.approval_plan FOR EACH ROW EXECUTE FUNCTION governance.reject_immutable_approval_evidence_change();

CREATE TRIGGER approval_stage_immutable_trg BEFORE DELETE OR UPDATE ON governance.approval_stage FOR EACH ROW EXECUTE FUNCTION governance.reject_immutable_approval_evidence_change();

CREATE TRIGGER governance_exception_delete_guard BEFORE DELETE ON governance.governance_exception FOR EACH ROW EXECUTE FUNCTION governance.reject_governance_exception_delete();

CREATE TRIGGER governance_exception_update_guard BEFORE UPDATE ON governance.governance_exception FOR EACH ROW EXECUTE FUNCTION governance.guard_governance_exception_update();

CREATE TRIGGER governance_lifecycle_access_conflict_immutable BEFORE DELETE OR UPDATE ON governance.lifecycle_access_sod_conflict FOR EACH ROW EXECUTE FUNCTION governance.reject_governance_evidence_change();

CREATE TRIGGER governance_lifecycle_access_eval_immutable BEFORE DELETE OR UPDATE ON governance.lifecycle_access_evaluation FOR EACH ROW EXECUTE FUNCTION governance.reject_governance_evidence_change();

CREATE TRIGGER governance_policy_approver_content_guard BEFORE INSERT OR DELETE OR UPDATE ON governance.policy_approval_approver FOR EACH ROW EXECUTE FUNCTION governance.guard_policy_version_content();

CREATE TRIGGER governance_policy_evaluation_immutable BEFORE DELETE OR UPDATE ON governance.policy_evaluation FOR EACH ROW EXECUTE FUNCTION governance.reject_governance_evidence_change();

CREATE TRIGGER governance_policy_stage_content_guard BEFORE INSERT OR DELETE OR UPDATE ON governance.policy_approval_stage FOR EACH ROW EXECUTE FUNCTION governance.guard_policy_version_content();

CREATE TRIGGER governance_policy_version_delete_guard BEFORE DELETE ON governance.policy_version FOR EACH ROW EXECUTE FUNCTION governance.reject_policy_version_delete();

CREATE TRIGGER governance_policy_version_update_guard BEFORE UPDATE ON governance.policy_version FOR EACH ROW EXECUTE FUNCTION governance.guard_policy_version_update();

CREATE TRIGGER governance_review_campaign_update_guard BEFORE UPDATE ON governance.review_campaign FOR EACH ROW EXECUTE FUNCTION governance.guard_review_campaign_update();

CREATE TRIGGER governance_review_decision_immutable BEFORE DELETE OR UPDATE ON governance.review_decision FOR EACH ROW EXECUTE FUNCTION governance.reject_review_decision_change();

CREATE TRIGGER governance_review_item_update_guard BEFORE UPDATE ON governance.review_item FOR EACH ROW EXECUTE FUNCTION governance.guard_review_item_update();

CREATE TRIGGER governance_review_remediation_update_guard BEFORE UPDATE ON governance.review_remediation FOR EACH ROW EXECUTE FUNCTION governance.guard_review_remediation_update();

CREATE TRIGGER governance_risk_assessment_immutable BEFORE DELETE OR UPDATE ON governance.risk_assessment FOR EACH ROW EXECUTE FUNCTION governance.reject_governance_evidence_change();

CREATE TRIGGER governance_sod_conflict_immutable BEFORE DELETE OR UPDATE ON governance.sod_conflict FOR EACH ROW EXECUTE FUNCTION governance.reject_governance_evidence_change();

CREATE TRIGGER governance_sod_rule_content_guard BEFORE INSERT OR DELETE OR UPDATE ON governance.sod_rule FOR EACH ROW EXECUTE FUNCTION governance.guard_policy_version_content();

CREATE TRIGGER integration_provisioning_attempt_immutable_trg BEFORE DELETE OR UPDATE ON integration.provisioning_attempt FOR EACH ROW EXECUTE FUNCTION integration.reject_immutable_provisioning_attempt_change();

ALTER TABLE ONLY access.access_assignment
    ADD CONSTRAINT access_assignment_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY access.desired_grant_state
    ADD CONSTRAINT access_desired_grant_state_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY access.desired_principal_state
    ADD CONSTRAINT access_desired_principal_state_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY access.review_remediation_application
    ADD CONSTRAINT access_review_remediation_application_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY access.effective_access_support
    ADD CONSTRAINT effective_access_support_effective_fk FOREIGN KEY (tenant_id, effective_access_id) REFERENCES access.effective_access(tenant_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY access.effective_access_support_role_version
    ADD CONSTRAINT effective_access_support_role_version_support_fk FOREIGN KEY (tenant_id, effective_access_support_id) REFERENCES access.effective_access_support(tenant_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY access.effective_access_support
    ADD CONSTRAINT effective_access_support_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY access.effective_access
    ADD CONSTRAINT effective_access_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY access.identity_access_reduction
    ADD CONSTRAINT identity_access_reduction_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY access.lifecycle_access_policy_rule_expected_value
    ADD CONSTRAINT lifecycle_access_policy_rule_expected_value_rule_fk FOREIGN KEY (tenant_id, policy_version_id, rule_id, predicate_kind) REFERENCES access.lifecycle_access_policy_rule(tenant_id, policy_version_id, rule_id, predicate_kind);

ALTER TABLE ONLY access.lifecycle_access_policy_rule
    ADD CONSTRAINT lifecycle_access_policy_rule_version_fk FOREIGN KEY (tenant_id, policy_version_id) REFERENCES access.lifecycle_access_policy_version(tenant_id, id);

ALTER TABLE ONLY access.lifecycle_access_policy_version
    ADD CONSTRAINT lifecycle_access_policy_version_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY administration.control_plane_actor_binding
    ADD CONSTRAINT administration_actor_binding_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY administration.administrative_delegation
    ADD CONSTRAINT administration_delegation_role_fk FOREIGN KEY (tenant_id, role_id) REFERENCES administration.administrative_role(tenant_id, id);

ALTER TABLE ONLY administration.administrative_delegation
    ADD CONSTRAINT administration_delegation_source_grant_fk FOREIGN KEY (tenant_id, source_grant_id) REFERENCES administration.administrative_grant(tenant_id, id);

ALTER TABLE ONLY administration.administrative_delegation
    ADD CONSTRAINT administration_delegation_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY administration.administrative_grant
    ADD CONSTRAINT administration_grant_authority_basis_fk FOREIGN KEY (tenant_id, authority_basis_grant_id) REFERENCES administration.administrative_grant(tenant_id, id);

ALTER TABLE ONLY administration.administrative_grant
    ADD CONSTRAINT administration_grant_role_fk FOREIGN KEY (tenant_id, role_id) REFERENCES administration.administrative_role(tenant_id, id);

ALTER TABLE ONLY administration.administrative_grant
    ADD CONSTRAINT administration_grant_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY administration.initial_admin_bootstrap
    ADD CONSTRAINT administration_initial_bootstrap_binding_fk FOREIGN KEY (tenant_id, actor_binding_id) REFERENCES administration.control_plane_actor_binding(tenant_id, id) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY administration.initial_admin_bootstrap
    ADD CONSTRAINT administration_initial_bootstrap_grant_fk FOREIGN KEY (tenant_id, administrative_grant_id) REFERENCES administration.administrative_grant(tenant_id, id) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY administration.initial_admin_bootstrap
    ADD CONSTRAINT administration_initial_bootstrap_role_fk FOREIGN KEY (tenant_id, administrative_role_id) REFERENCES administration.administrative_role(tenant_id, id) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY administration.initial_admin_bootstrap
    ADD CONSTRAINT administration_initial_bootstrap_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY administration.administrative_permission
    ADD CONSTRAINT administration_permission_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY administration.administrative_role_permission
    ADD CONSTRAINT administration_role_permission_permission_fk FOREIGN KEY (tenant_id, permission_id) REFERENCES administration.administrative_permission(tenant_id, id);

ALTER TABLE ONLY administration.administrative_role_permission
    ADD CONSTRAINT administration_role_permission_role_fk FOREIGN KEY (tenant_id, role_id) REFERENCES administration.administrative_role(tenant_id, id);

ALTER TABLE ONLY administration.administrative_role
    ADD CONSTRAINT administration_role_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY administration.administrative_break_glass_obligation
    ADD CONSTRAINT administrative_break_glass_obligation_operation_fk FOREIGN KEY (tenant_id, break_glass_operation_id) REFERENCES administration.administrative_break_glass_operation(tenant_id, id);

ALTER TABLE ONLY administration.administrative_break_glass_obligation
    ADD CONSTRAINT administrative_break_glass_obligation_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY administration.administrative_break_glass_review
    ADD CONSTRAINT administrative_break_glass_review_obligation_fk FOREIGN KEY (tenant_id, obligation_id) REFERENCES administration.administrative_break_glass_obligation(tenant_id, id);

ALTER TABLE ONLY administration.administrative_break_glass_review
    ADD CONSTRAINT administrative_break_glass_review_operation_fk FOREIGN KEY (tenant_id, break_glass_operation_id) REFERENCES administration.administrative_break_glass_operation(tenant_id, id);

ALTER TABLE ONLY administration.administrative_break_glass_review
    ADD CONSTRAINT administrative_break_glass_review_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY administration.administrative_break_glass_operation
    ADD CONSTRAINT administrative_break_glass_role_fk FOREIGN KEY (tenant_id, role_id) REFERENCES administration.administrative_role(tenant_id, id);

ALTER TABLE ONLY administration.administrative_break_glass_operation
    ADD CONSTRAINT administrative_break_glass_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY administration.administrative_elevation
    ADD CONSTRAINT administrative_elevation_basis_fk FOREIGN KEY (tenant_id, authority_basis_grant_id) REFERENCES administration.administrative_grant(tenant_id, id);

ALTER TABLE ONLY administration.administrative_elevation
    ADD CONSTRAINT administrative_elevation_role_fk FOREIGN KEY (tenant_id, role_id) REFERENCES administration.administrative_role(tenant_id, id);

ALTER TABLE ONLY administration.administrative_elevation
    ADD CONSTRAINT administrative_elevation_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

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

ALTER TABLE ONLY catalog.application_target
    ADD CONSTRAINT catalog_application_target_application_fk FOREIGN KEY (tenant_id, application_id) REFERENCES catalog.application(tenant_id, id);

ALTER TABLE ONLY catalog.application_target
    ADD CONSTRAINT catalog_application_target_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY catalog.application
    ADD CONSTRAINT catalog_application_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY catalog.entitlement
    ADD CONSTRAINT catalog_entitlement_application_fk FOREIGN KEY (tenant_id, application_id) REFERENCES catalog.application(tenant_id, id);

ALTER TABLE ONLY catalog.entitlement
    ADD CONSTRAINT catalog_entitlement_target_fk FOREIGN KEY (tenant_id, application_target_id, application_id) REFERENCES catalog.application_target(tenant_id, id, application_id);

ALTER TABLE ONLY catalog.entitlement
    ADD CONSTRAINT catalog_entitlement_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY catalog.role
    ADD CONSTRAINT catalog_role_application_fk FOREIGN KEY (tenant_id, application_id) REFERENCES catalog.application(tenant_id, id);

ALTER TABLE ONLY catalog.role
    ADD CONSTRAINT catalog_role_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY catalog.role_version_member
    ADD CONSTRAINT catalog_role_version_member_entitlement_fk FOREIGN KEY (tenant_id, member_entitlement_id) REFERENCES catalog.entitlement(tenant_id, id);

ALTER TABLE ONLY catalog.role_version_member
    ADD CONSTRAINT catalog_role_version_member_role_fk FOREIGN KEY (tenant_id, member_role_id) REFERENCES catalog.role(tenant_id, id);

ALTER TABLE ONLY catalog.role_version_member
    ADD CONSTRAINT catalog_role_version_member_role_version_fk FOREIGN KEY (tenant_id, role_version_id) REFERENCES catalog.role_version(tenant_id, id);

ALTER TABLE ONLY catalog.role_version
    ADD CONSTRAINT catalog_role_version_role_fk FOREIGN KEY (tenant_id, role_id) REFERENCES catalog.role(tenant_id, id);

ALTER TABLE ONLY catalog.sso_client_registration
    ADD CONSTRAINT catalog_sso_client_application_fk FOREIGN KEY (tenant_id, application_id) REFERENCES catalog.application(tenant_id, id);

ALTER TABLE ONLY catalog.sso_client_redirect_uri
    ADD CONSTRAINT catalog_sso_client_redirect_parent_fk FOREIGN KEY (tenant_id, sso_client_registration_id) REFERENCES catalog.sso_client_registration(tenant_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY catalog.sso_client_scope
    ADD CONSTRAINT catalog_sso_client_scope_parent_fk FOREIGN KEY (tenant_id, sso_client_registration_id) REFERENCES catalog.sso_client_registration(tenant_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY catalog.sso_client_registration
    ADD CONSTRAINT catalog_sso_client_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY credential.credential
    ADD CONSTRAINT credential_credential_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY credential.local_password_verifier
    ADD CONSTRAINT credential_local_password_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY credential.credential_rotation
    ADD CONSTRAINT credential_rotation_old_fk FOREIGN KEY (tenant_id, old_credential_id) REFERENCES credential.credential(tenant_id, id);

ALTER TABLE ONLY credential.credential_rotation
    ADD CONSTRAINT credential_rotation_replacement_fk FOREIGN KEY (tenant_id, replacement_credential_id) REFERENCES credential.credential(tenant_id, id);

ALTER TABLE ONLY credential.credential_rotation
    ADD CONSTRAINT credential_rotation_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.access_request
    ADD CONSTRAINT access_request_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.approval_approver
    ADD CONSTRAINT approval_approver_stage_fk FOREIGN KEY (tenant_id, approval_stage_id) REFERENCES governance.approval_stage(tenant_id, id);

ALTER TABLE ONLY governance.approval_approver
    ADD CONSTRAINT approval_approver_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.approval_case
    ADD CONSTRAINT approval_case_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.approval_decision
    ADD CONSTRAINT approval_decision_case_fk FOREIGN KEY (tenant_id, approval_case_id) REFERENCES governance.approval_case(tenant_id, id);

ALTER TABLE ONLY governance.approval_decision
    ADD CONSTRAINT approval_decision_plan_fk FOREIGN KEY (tenant_id, approval_plan_id) REFERENCES governance.approval_plan(tenant_id, id);

ALTER TABLE ONLY governance.approval_decision
    ADD CONSTRAINT approval_decision_stage_fk FOREIGN KEY (tenant_id, approval_stage_id) REFERENCES governance.approval_stage(tenant_id, id);

ALTER TABLE ONLY governance.approval_decision
    ADD CONSTRAINT approval_decision_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.approval_plan
    ADD CONSTRAINT approval_plan_case_fk FOREIGN KEY (tenant_id, approval_case_id) REFERENCES governance.approval_case(tenant_id, id);

ALTER TABLE ONLY governance.approval_plan
    ADD CONSTRAINT approval_plan_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.approval_stage
    ADD CONSTRAINT approval_stage_plan_fk FOREIGN KEY (tenant_id, approval_plan_id) REFERENCES governance.approval_plan(tenant_id, id);

ALTER TABLE ONLY governance.approval_stage
    ADD CONSTRAINT approval_stage_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.governance_exception
    ADD CONSTRAINT governance_exception_approval_fk FOREIGN KEY (tenant_id, approval_case_id) REFERENCES governance.approval_case(tenant_id, id);

ALTER TABLE ONLY governance.governance_exception
    ADD CONSTRAINT governance_exception_predecessor_fk FOREIGN KEY (tenant_id, predecessor_exception_id) REFERENCES governance.governance_exception(tenant_id, id);

ALTER TABLE ONLY governance.governance_exception
    ADD CONSTRAINT governance_exception_rule_fk FOREIGN KEY (tenant_id, sod_rule_id) REFERENCES governance.sod_rule(tenant_id, id);

ALTER TABLE ONLY governance.governance_exception
    ADD CONSTRAINT governance_exception_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.finding
    ADD CONSTRAINT governance_finding_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.lifecycle_access_approval_candidate
    ADD CONSTRAINT governance_lifecycle_access_candidate_policy_fk FOREIGN KEY (tenant_id, policy_version_id) REFERENCES governance.policy_version(tenant_id, id);

ALTER TABLE ONLY governance.lifecycle_access_sod_conflict
    ADD CONSTRAINT governance_lifecycle_access_conflict_eval_fk FOREIGN KEY (tenant_id, evaluation_id) REFERENCES governance.lifecycle_access_evaluation(tenant_id, id);

ALTER TABLE ONLY governance.lifecycle_access_sod_conflict
    ADD CONSTRAINT governance_lifecycle_access_conflict_rule_fk FOREIGN KEY (tenant_id, sod_rule_id) REFERENCES governance.sod_rule(tenant_id, id);

ALTER TABLE ONLY governance.lifecycle_access_evaluation
    ADD CONSTRAINT governance_lifecycle_access_eval_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.lifecycle_access_evaluation
    ADD CONSTRAINT governance_lifecycle_access_eval_version_fk FOREIGN KEY (tenant_id, governance_policy_version_id) REFERENCES governance.policy_version(tenant_id, id);

ALTER TABLE ONLY governance.policy_approval_approver
    ADD CONSTRAINT governance_policy_approver_stage_fk FOREIGN KEY (tenant_id, policy_approval_stage_id) REFERENCES governance.policy_approval_stage(tenant_id, id);

ALTER TABLE ONLY governance.policy_approval_approver
    ADD CONSTRAINT governance_policy_approver_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.policy_evaluation
    ADD CONSTRAINT governance_policy_evaluation_assessment_fk FOREIGN KEY (tenant_id, risk_assessment_id) REFERENCES governance.risk_assessment(tenant_id, id);

ALTER TABLE ONLY governance.policy_evaluation
    ADD CONSTRAINT governance_policy_evaluation_item_fk FOREIGN KEY (tenant_id, request_item_id) REFERENCES governance.request_item(tenant_id, id);

ALTER TABLE ONLY governance.policy_evaluation
    ADD CONSTRAINT governance_policy_evaluation_request_fk FOREIGN KEY (tenant_id, access_request_id) REFERENCES governance.access_request(tenant_id, id);

ALTER TABLE ONLY governance.policy_evaluation
    ADD CONSTRAINT governance_policy_evaluation_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.policy_evaluation
    ADD CONSTRAINT governance_policy_evaluation_version_fk FOREIGN KEY (tenant_id, policy_version_id) REFERENCES governance.policy_version(tenant_id, id);

ALTER TABLE ONLY governance.policy_approval_stage
    ADD CONSTRAINT governance_policy_stage_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.policy_approval_stage
    ADD CONSTRAINT governance_policy_stage_version_fk FOREIGN KEY (tenant_id, policy_version_id) REFERENCES governance.policy_version(tenant_id, id);

ALTER TABLE ONLY governance.policy
    ADD CONSTRAINT governance_policy_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.policy_version
    ADD CONSTRAINT governance_policy_version_policy_fk FOREIGN KEY (tenant_id, policy_id) REFERENCES governance.policy(tenant_id, id);

ALTER TABLE ONLY governance.policy_version
    ADD CONSTRAINT governance_policy_version_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.review_campaign
    ADD CONSTRAINT governance_review_campaign_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.review_decision
    ADD CONSTRAINT governance_review_decision_item_fk FOREIGN KEY (tenant_id, review_item_id) REFERENCES governance.review_item(tenant_id, id);

ALTER TABLE ONLY governance.review_decision
    ADD CONSTRAINT governance_review_decision_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.review_item
    ADD CONSTRAINT governance_review_item_campaign_fk FOREIGN KEY (tenant_id, review_campaign_id) REFERENCES governance.review_campaign(tenant_id, id);

ALTER TABLE ONLY governance.review_item
    ADD CONSTRAINT governance_review_item_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.review_remediation
    ADD CONSTRAINT governance_review_remediation_item_fk FOREIGN KEY (tenant_id, review_item_id) REFERENCES governance.review_item(tenant_id, id);

ALTER TABLE ONLY governance.review_remediation
    ADD CONSTRAINT governance_review_remediation_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.risk_assessment
    ADD CONSTRAINT governance_risk_assessment_item_fk FOREIGN KEY (tenant_id, request_item_id) REFERENCES governance.request_item(tenant_id, id);

ALTER TABLE ONLY governance.risk_assessment
    ADD CONSTRAINT governance_risk_assessment_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.risk_assessment
    ADD CONSTRAINT governance_risk_assessment_version_fk FOREIGN KEY (tenant_id, policy_version_id) REFERENCES governance.policy_version(tenant_id, id);

ALTER TABLE ONLY governance.sod_conflict
    ADD CONSTRAINT governance_sod_conflict_assessment_fk FOREIGN KEY (tenant_id, risk_assessment_id) REFERENCES governance.risk_assessment(tenant_id, id);

ALTER TABLE ONLY governance.sod_conflict
    ADD CONSTRAINT governance_sod_conflict_exception_fk FOREIGN KEY (tenant_id, governance_exception_id) REFERENCES governance.governance_exception(tenant_id, id);

ALTER TABLE ONLY governance.sod_conflict
    ADD CONSTRAINT governance_sod_conflict_rule_fk FOREIGN KEY (tenant_id, sod_rule_id) REFERENCES governance.sod_rule(tenant_id, id);

ALTER TABLE ONLY governance.sod_conflict
    ADD CONSTRAINT governance_sod_conflict_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.sod_rule
    ADD CONSTRAINT governance_sod_rule_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY governance.sod_rule
    ADD CONSTRAINT governance_sod_rule_version_fk FOREIGN KEY (tenant_id, policy_version_id) REFERENCES governance.policy_version(tenant_id, id);

ALTER TABLE ONLY governance.request_item
    ADD CONSTRAINT request_item_approval_case_fk FOREIGN KEY (tenant_id, approval_case_id) REFERENCES governance.approval_case(tenant_id, id);

ALTER TABLE ONLY governance.request_item
    ADD CONSTRAINT request_item_request_fk FOREIGN KEY (tenant_id, access_request_id) REFERENCES governance.access_request(tenant_id, id);

ALTER TABLE ONLY governance.request_item
    ADD CONSTRAINT request_item_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY identity.attribute_authority_rule_version
    ADD CONSTRAINT identity_attribute_authority_rule_definition_fk FOREIGN KEY (tenant_id, attribute_definition_version_id) REFERENCES identity.attribute_definition_version(tenant_id, id);

ALTER TABLE ONLY identity.attribute_authority_rule_version
    ADD CONSTRAINT identity_attribute_authority_rule_source_fk FOREIGN KEY (tenant_id, source_system_id) REFERENCES identity.source_system(tenant_id, id);

ALTER TABLE ONLY identity.attribute_definition
    ADD CONSTRAINT identity_attribute_definition_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY identity.attribute_definition_version
    ADD CONSTRAINT identity_attribute_definition_version_definition_fk FOREIGN KEY (tenant_id, attribute_definition_id) REFERENCES identity.attribute_definition(tenant_id, id);

ALTER TABLE ONLY identity.attribute_definition_version
    ADD CONSTRAINT identity_attribute_definition_version_schema_fk FOREIGN KEY (tenant_id, schema_version_id) REFERENCES identity.canonical_schema_version(tenant_id, id);

ALTER TABLE ONLY identity.attribute_mapping_version
    ADD CONSTRAINT identity_attribute_mapping_version_definition_fk FOREIGN KEY (tenant_id, attribute_definition_version_id) REFERENCES identity.attribute_definition_version(tenant_id, id);

ALTER TABLE ONLY identity.attribute_mapping_version
    ADD CONSTRAINT identity_attribute_mapping_version_source_fk FOREIGN KEY (tenant_id, source_system_id) REFERENCES identity.source_system(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_candidate_value
    ADD CONSTRAINT identity_candidate_value_candidate_definition_fk FOREIGN KEY (tenant_id, candidate_id, attribute_definition_version_id) REFERENCES identity.canonical_attribute_candidate(tenant_id, id, attribute_definition_version_id);

ALTER TABLE ONLY identity.canonical_attribute_candidate_value
    ADD CONSTRAINT identity_candidate_value_candidate_fk FOREIGN KEY (tenant_id, candidate_id) REFERENCES identity.canonical_attribute_candidate(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_candidate_value
    ADD CONSTRAINT identity_candidate_value_definition_type_fk FOREIGN KEY (tenant_id, attribute_definition_version_id, data_type, cardinality) REFERENCES identity.attribute_definition_version(tenant_id, id, data_type, cardinality);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_definition_fk FOREIGN KEY (tenant_id, attribute_definition_version_id) REFERENCES identity.attribute_definition_version(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_identity_fk FOREIGN KEY (tenant_id, identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_mapping_fk FOREIGN KEY (tenant_id, mapping_version_id) REFERENCES identity.attribute_mapping_version(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_mapping_tuple_fk FOREIGN KEY (tenant_id, mapping_version_id, source_system_id, attribute_definition_version_id) REFERENCES identity.attribute_mapping_version(tenant_id, id, source_system_id, attribute_definition_version_id);

COMMENT ON CONSTRAINT identity_canonical_attribute_candidate_mapping_tuple_fk ON identity.canonical_attribute_candidate IS 'Candidate provenance must use a mapping for the same SourceSystem and canonical definition version.';

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_source_fk FOREIGN KEY (tenant_id, source_system_id) REFERENCES identity.source_system(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_source_record_fk FOREIGN KEY (tenant_id, source_record_id) REFERENCES identity.source_record(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_source_tuple_fk FOREIGN KEY (tenant_id, source_record_id, source_system_id) REFERENCES identity.source_record(tenant_id, id, source_system_id);

ALTER TABLE ONLY identity.canonical_attribute_override
    ADD CONSTRAINT identity_canonical_attribute_override_definition_fk FOREIGN KEY (tenant_id, attribute_definition_id) REFERENCES identity.attribute_definition(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_override
    ADD CONSTRAINT identity_canonical_attribute_override_definition_tuple_fk FOREIGN KEY (tenant_id, attribute_definition_version_id, attribute_definition_id) REFERENCES identity.attribute_definition_version(tenant_id, id, attribute_definition_id);

ALTER TABLE ONLY identity.canonical_attribute_override
    ADD CONSTRAINT identity_canonical_attribute_override_definition_version_fk FOREIGN KEY (tenant_id, attribute_definition_version_id) REFERENCES identity.attribute_definition_version(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_override
    ADD CONSTRAINT identity_canonical_attribute_override_identity_fk FOREIGN KEY (tenant_id, identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_authority_fk FOREIGN KEY (tenant_id, authority_rule_version_id) REFERENCES identity.attribute_authority_rule_version(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_authority_tuple_fk FOREIGN KEY (tenant_id, authority_rule_version_id, attribute_definition_version_id) REFERENCES identity.attribute_authority_rule_version(tenant_id, id, attribute_definition_version_id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_candidate_fk FOREIGN KEY (tenant_id, selected_candidate_id) REFERENCES identity.canonical_attribute_candidate(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_candidate_tuple_fk FOREIGN KEY (tenant_id, selected_candidate_id, identity_id, attribute_definition_version_id) REFERENCES identity.canonical_attribute_candidate(tenant_id, id, identity_id, attribute_definition_version_id);

COMMENT ON CONSTRAINT identity_canonical_attribute_state_candidate_tuple_fk ON identity.canonical_attribute_state IS 'Selected candidate, when present, must belong to the same Identity and canonical definition version as the resolved state.';

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_definition_fk FOREIGN KEY (tenant_id, attribute_definition_id) REFERENCES identity.attribute_definition(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_definition_tuple_fk FOREIGN KEY (tenant_id, attribute_definition_version_id, attribute_definition_id) REFERENCES identity.attribute_definition_version(tenant_id, id, attribute_definition_id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_definition_version_fk FOREIGN KEY (tenant_id, attribute_definition_version_id) REFERENCES identity.attribute_definition_version(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_identity_fk FOREIGN KEY (tenant_id, identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.canonical_schema_version
    ADD CONSTRAINT identity_canonical_schema_version_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY identity.identity_link
    ADD CONSTRAINT identity_identity_link_identity_fk FOREIGN KEY (tenant_id, identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.identity_link
    ADD CONSTRAINT identity_identity_link_source_record_fk FOREIGN KEY (tenant_id, source_record_id) REFERENCES identity.source_record(tenant_id, id);

ALTER TABLE ONLY identity.identity
    ADD CONSTRAINT identity_identity_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY identity.identity_merge_operation
    ADD CONSTRAINT identity_merge_operation_absorbed_fk FOREIGN KEY (tenant_id, absorbed_identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.identity_merge_operation
    ADD CONSTRAINT identity_merge_operation_survivor_fk FOREIGN KEY (tenant_id, survivor_identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_override_value
    ADD CONSTRAINT identity_override_value_definition_type_fk FOREIGN KEY (tenant_id, attribute_definition_version_id, data_type, cardinality) REFERENCES identity.attribute_definition_version(tenant_id, id, data_type, cardinality);

ALTER TABLE ONLY identity.canonical_attribute_override_value
    ADD CONSTRAINT identity_override_value_override_definition_fk FOREIGN KEY (tenant_id, override_id, attribute_definition_version_id) REFERENCES identity.canonical_attribute_override(tenant_id, id, attribute_definition_version_id);

ALTER TABLE ONLY identity.canonical_attribute_override_value
    ADD CONSTRAINT identity_override_value_override_fk FOREIGN KEY (tenant_id, override_id) REFERENCES identity.canonical_attribute_override(tenant_id, id);

ALTER TABLE ONLY identity.person_profile
    ADD CONSTRAINT identity_person_profile_identity_fk FOREIGN KEY (tenant_id, identity_id, identity_type) REFERENCES identity.identity(tenant_id, id, identity_type);

ALTER TABLE ONLY identity.principal
    ADD CONSTRAINT identity_principal_identity_fk FOREIGN KEY (tenant_id, identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.principal
    ADD CONSTRAINT identity_principal_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY identity.service_profile
    ADD CONSTRAINT identity_service_profile_identity_fk FOREIGN KEY (tenant_id, identity_id, identity_type) REFERENCES identity.identity(tenant_id, id, identity_type);

ALTER TABLE ONLY identity.source_absence_inference
    ADD CONSTRAINT identity_source_absence_inference_policy_fk FOREIGN KEY (tenant_id, policy_version_id) REFERENCES identity.source_absence_policy_version(tenant_id, id);

ALTER TABLE ONLY identity.source_absence_inference
    ADD CONSTRAINT identity_source_absence_inference_run_fk FOREIGN KEY (tenant_id, import_run_id, source_system_id) REFERENCES identity.source_import_run(tenant_id, id, source_system_id);

ALTER TABLE ONLY identity.source_absence_policy_version
    ADD CONSTRAINT identity_source_absence_policy_source_fk FOREIGN KEY (tenant_id, source_system_id) REFERENCES identity.source_system(tenant_id, id);

ALTER TABLE ONLY identity.source_absence_transition_evidence
    ADD CONSTRAINT identity_source_absence_transition_identity_fk FOREIGN KEY (tenant_id, identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.source_absence_transition_evidence
    ADD CONSTRAINT identity_source_absence_transition_inference_fk FOREIGN KEY (tenant_id, inference_id) REFERENCES identity.source_absence_inference(tenant_id, id);

ALTER TABLE ONLY identity.source_absence_transition_evidence
    ADD CONSTRAINT identity_source_absence_transition_link_fk FOREIGN KEY (tenant_id, identity_link_id) REFERENCES identity.identity_link(tenant_id, id);

ALTER TABLE ONLY identity.source_absence_transition_evidence
    ADD CONSTRAINT identity_source_absence_transition_source_record_fk FOREIGN KEY (tenant_id, source_record_id) REFERENCES identity.source_record(tenant_id, id);

ALTER TABLE ONLY identity.source_correlation_policy_version
    ADD CONSTRAINT identity_source_correlation_policy_definition_fk FOREIGN KEY (tenant_id, match_attribute_definition_version_id) REFERENCES identity.attribute_definition_version(tenant_id, id);

ALTER TABLE ONLY identity.source_correlation_policy_version
    ADD CONSTRAINT identity_source_correlation_policy_mapping_tuple_fk FOREIGN KEY (tenant_id, match_mapping_version_id, source_system_id, match_attribute_definition_version_id) REFERENCES identity.attribute_mapping_version(tenant_id, id, source_system_id, attribute_definition_version_id);

ALTER TABLE ONLY identity.source_correlation_policy_version
    ADD CONSTRAINT identity_source_correlation_policy_source_fk FOREIGN KEY (tenant_id, source_system_id) REFERENCES identity.source_system(tenant_id, id);

ALTER TABLE ONLY identity.source_import_run
    ADD CONSTRAINT identity_source_import_run_source_fk FOREIGN KEY (tenant_id, source_system_id) REFERENCES identity.source_system(tenant_id, id);

ALTER TABLE ONLY identity.source_lifecycle_policy_rule
    ADD CONSTRAINT identity_source_lifecycle_policy_rule_policy_fk FOREIGN KEY (tenant_id, policy_version_id) REFERENCES identity.source_lifecycle_policy_version(tenant_id, id);

ALTER TABLE ONLY identity.source_lifecycle_policy_version
    ADD CONSTRAINT identity_source_lifecycle_policy_source_fk FOREIGN KEY (tenant_id, source_system_id) REFERENCES identity.source_system(tenant_id, id);

ALTER TABLE ONLY identity.source_record
    ADD CONSTRAINT identity_source_record_last_complete_run_fk FOREIGN KEY (tenant_id, last_complete_import_run_id, source_system_id) REFERENCES identity.source_import_run(tenant_id, id, source_system_id);

ALTER TABLE ONLY identity.source_record
    ADD CONSTRAINT identity_source_record_last_run_fk FOREIGN KEY (tenant_id, last_import_run_id, source_system_id) REFERENCES identity.source_import_run(tenant_id, id, source_system_id);

ALTER TABLE ONLY identity.source_record
    ADD CONSTRAINT identity_source_record_source_fk FOREIGN KEY (tenant_id, source_system_id) REFERENCES identity.source_system(tenant_id, id);

ALTER TABLE ONLY identity.source_suspension_transition_evidence
    ADD CONSTRAINT identity_source_suspension_transition_identity_fk FOREIGN KEY (tenant_id, identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.source_suspension_transition_evidence
    ADD CONSTRAINT identity_source_suspension_transition_link_fk FOREIGN KEY (tenant_id, identity_link_id) REFERENCES identity.identity_link(tenant_id, id);

ALTER TABLE ONLY identity.source_suspension_transition_evidence
    ADD CONSTRAINT identity_source_suspension_transition_policy_fk FOREIGN KEY (tenant_id, lifecycle_policy_version_id) REFERENCES identity.source_lifecycle_policy_version(tenant_id, id);

ALTER TABLE ONLY identity.source_suspension_transition_evidence
    ADD CONSTRAINT identity_source_suspension_transition_source_fk FOREIGN KEY (tenant_id, source_system_id) REFERENCES identity.source_system(tenant_id, id);

ALTER TABLE ONLY identity.source_suspension_transition_evidence
    ADD CONSTRAINT identity_source_suspension_transition_source_record_fk FOREIGN KEY (tenant_id, source_record_id) REFERENCES identity.source_record(tenant_id, id);

ALTER TABLE ONLY identity.source_system
    ADD CONSTRAINT identity_source_system_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY identity.identity_split_operation
    ADD CONSTRAINT identity_split_operation_new_fk FOREIGN KEY (tenant_id, new_identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.identity_split_operation
    ADD CONSTRAINT identity_split_operation_source_fk FOREIGN KEY (tenant_id, source_identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.identity_split_principal
    ADD CONSTRAINT identity_split_principal_operation_fk FOREIGN KEY (tenant_id, operation_id) REFERENCES identity.identity_split_operation(tenant_id, id);

ALTER TABLE ONLY identity.identity_split_principal
    ADD CONSTRAINT identity_split_principal_principal_fk FOREIGN KEY (tenant_id, principal_id) REFERENCES identity.principal(tenant_id, id);

ALTER TABLE ONLY identity.identity_split_source_record
    ADD CONSTRAINT identity_split_source_record_operation_fk FOREIGN KEY (tenant_id, operation_id) REFERENCES identity.identity_split_operation(tenant_id, id);

ALTER TABLE ONLY identity.identity_split_source_record
    ADD CONSTRAINT identity_split_source_record_source_fk FOREIGN KEY (tenant_id, source_record_id) REFERENCES identity.source_record(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_state_value
    ADD CONSTRAINT identity_state_value_definition_type_fk FOREIGN KEY (tenant_id, attribute_definition_version_id, data_type, cardinality) REFERENCES identity.attribute_definition_version(tenant_id, id, data_type, cardinality);

ALTER TABLE ONLY identity.canonical_attribute_state_value
    ADD CONSTRAINT identity_state_value_state_definition_fk FOREIGN KEY (tenant_id, state_id, attribute_definition_version_id) REFERENCES identity.canonical_attribute_state(tenant_id, id, attribute_definition_version_id);

ALTER TABLE ONLY identity.canonical_attribute_state_value
    ADD CONSTRAINT identity_state_value_state_fk FOREIGN KEY (tenant_id, state_id) REFERENCES identity.canonical_attribute_state(tenant_id, id);

ALTER TABLE ONLY identity.workload_profile
    ADD CONSTRAINT identity_workload_profile_identity_fk FOREIGN KEY (tenant_id, identity_id, identity_type) REFERENCES identity.identity(tenant_id, id, identity_type);

ALTER TABLE ONLY integration.connector_binding
    ADD CONSTRAINT integration_connector_binding_instance_fk FOREIGN KEY (tenant_id, connector_instance_id) REFERENCES integration.connector_instance(tenant_id, id);

ALTER TABLE ONLY integration.connector_binding
    ADD CONSTRAINT integration_connector_binding_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY integration.connector_instance
    ADD CONSTRAINT integration_connector_instance_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY integration.entitlement_observation_mapping
    ADD CONSTRAINT integration_entitlement_mapping_binding_fk FOREIGN KEY (tenant_id, connector_binding_id) REFERENCES integration.connector_binding(tenant_id, id);

ALTER TABLE ONLY integration.observed_entitlement
    ADD CONSTRAINT integration_observed_entitlement_binding_fk FOREIGN KEY (tenant_id, connector_binding_id) REFERENCES integration.connector_binding(tenant_id, id);

ALTER TABLE ONLY integration.observed_entitlement
    ADD CONSTRAINT integration_observed_entitlement_run_fk FOREIGN KEY (tenant_id, last_observed_run_id) REFERENCES integration.reconciliation_run(tenant_id, id);

ALTER TABLE ONLY integration.observed_grant
    ADD CONSTRAINT integration_observed_grant_binding_fk FOREIGN KEY (tenant_id, connector_binding_id) REFERENCES integration.connector_binding(tenant_id, id);

ALTER TABLE ONLY integration.observed_grant
    ADD CONSTRAINT integration_observed_grant_run_fk FOREIGN KEY (tenant_id, last_observed_run_id) REFERENCES integration.reconciliation_run(tenant_id, id);

ALTER TABLE ONLY integration.observed_principal
    ADD CONSTRAINT integration_observed_principal_binding_fk FOREIGN KEY (tenant_id, connector_binding_id) REFERENCES integration.connector_binding(tenant_id, id);

ALTER TABLE ONLY integration.observed_principal
    ADD CONSTRAINT integration_observed_principal_run_fk FOREIGN KEY (tenant_id, last_observed_run_id) REFERENCES integration.reconciliation_run(tenant_id, id);

ALTER TABLE ONLY integration.provisioning_attempt
    ADD CONSTRAINT integration_provisioning_attempt_task_fk FOREIGN KEY (tenant_id, task_id) REFERENCES integration.provisioning_task(tenant_id, id);

ALTER TABLE ONLY integration.provisioning_attempt
    ADD CONSTRAINT integration_provisioning_attempt_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY integration.provisioning_task_dependency
    ADD CONSTRAINT integration_provisioning_dependency_parent_fk FOREIGN KEY (tenant_id, provisioning_job_id, depends_on_task_id) REFERENCES integration.provisioning_task(tenant_id, provisioning_job_id, id);

ALTER TABLE ONLY integration.provisioning_task_dependency
    ADD CONSTRAINT integration_provisioning_dependency_task_fk FOREIGN KEY (tenant_id, provisioning_job_id, task_id) REFERENCES integration.provisioning_task(tenant_id, provisioning_job_id, id);

ALTER TABLE ONLY integration.provisioning_job
    ADD CONSTRAINT integration_provisioning_job_binding_fk FOREIGN KEY (tenant_id, connector_binding_id) REFERENCES integration.connector_binding(tenant_id, id);

ALTER TABLE ONLY integration.provisioning_job
    ADD CONSTRAINT integration_provisioning_job_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY integration.provisioning_task
    ADD CONSTRAINT integration_provisioning_task_job_fk FOREIGN KEY (tenant_id, provisioning_job_id) REFERENCES integration.provisioning_job(tenant_id, id);

ALTER TABLE ONLY integration.provisioning_task
    ADD CONSTRAINT integration_provisioning_task_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY integration.reconciliation_observation_batch
    ADD CONSTRAINT integration_reconciliation_batch_run_fk FOREIGN KEY (tenant_id, reconciliation_run_id) REFERENCES integration.reconciliation_run(tenant_id, id);

ALTER TABLE ONLY integration.reconciliation_entitlement_staging
    ADD CONSTRAINT integration_reconciliation_entitlement_stage_batch_fk FOREIGN KEY (tenant_id, reconciliation_run_id, batch_id) REFERENCES integration.reconciliation_observation_batch(tenant_id, reconciliation_run_id, batch_id);

ALTER TABLE ONLY integration.reconciliation_grant_staging
    ADD CONSTRAINT integration_reconciliation_grant_stage_batch_fk FOREIGN KEY (tenant_id, reconciliation_run_id, batch_id) REFERENCES integration.reconciliation_observation_batch(tenant_id, reconciliation_run_id, batch_id);

ALTER TABLE ONLY integration.reconciliation_run
    ADD CONSTRAINT integration_reconciliation_run_binding_fk FOREIGN KEY (tenant_id, connector_binding_id) REFERENCES integration.connector_binding(tenant_id, id);

ALTER TABLE ONLY integration.reconciliation_run
    ADD CONSTRAINT integration_reconciliation_run_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY integration.reconciliation_principal_staging
    ADD CONSTRAINT integration_reconciliation_staging_batch_fk FOREIGN KEY (tenant_id, reconciliation_run_id, batch_id) REFERENCES integration.reconciliation_observation_batch(tenant_id, reconciliation_run_id, batch_id);

ALTER TABLE ONLY integration.connector_worker_runtime_permission
    ADD CONSTRAINT integration_worker_permission_registration_fk FOREIGN KEY (tenant_id, worker_registration_id) REFERENCES integration.connector_worker_registration(tenant_id, id);

ALTER TABLE ONLY integration.connector_worker_registration
    ADD CONSTRAINT integration_worker_registration_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY integration.connector_worker_binding_scope
    ADD CONSTRAINT integration_worker_scope_binding_fk FOREIGN KEY (tenant_id, connector_binding_id) REFERENCES integration.connector_binding(tenant_id, id);

ALTER TABLE ONLY integration.connector_worker_binding_scope
    ADD CONSTRAINT integration_worker_scope_registration_fk FOREIGN KEY (tenant_id, worker_registration_id) REFERENCES integration.connector_worker_registration(tenant_id, id);

ALTER TABLE ONLY integration.connector_worker_session_capability
    ADD CONSTRAINT integration_worker_session_capability_session_fk FOREIGN KEY (tenant_id, session_id) REFERENCES integration.connector_worker_session(tenant_id, id);

ALTER TABLE ONLY integration.connector_worker_session_contract
    ADD CONSTRAINT integration_worker_session_contract_session_fk FOREIGN KEY (tenant_id, session_id) REFERENCES integration.connector_worker_session(tenant_id, id);

ALTER TABLE ONLY integration.connector_worker_session
    ADD CONSTRAINT integration_worker_session_registration_fk FOREIGN KEY (tenant_id, worker_registration_id) REFERENCES integration.connector_worker_registration(tenant_id, id);

ALTER TABLE ONLY platform.idempotency_record
    ADD CONSTRAINT idempotency_record_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY platform.idp_authorization_code
    ADD CONSTRAINT idp_authorization_code_session_fk FOREIGN KEY (tenant_id, browser_session_id) REFERENCES platform.idp_browser_session(tenant_id, id);

ALTER TABLE ONLY platform.idp_authorization_code
    ADD CONSTRAINT idp_authorization_code_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY platform.idp_browser_session
    ADD CONSTRAINT idp_browser_session_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY platform.inbox_message
    ADD CONSTRAINT inbox_message_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY platform.outbox_event
    ADD CONSTRAINT outbox_event_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY platform.connector_work_lease
    ADD CONSTRAINT platform_connector_work_lease_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY platform.scheduled_work
    ADD CONSTRAINT scheduled_work_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);
