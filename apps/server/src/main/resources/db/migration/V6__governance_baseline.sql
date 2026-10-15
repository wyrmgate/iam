-- Governance capability current-state persistence baseline.
-- Generated once from the verified pre-rebaseline schema, then maintained as reviewed source.
-- Some function bodies reference tables created later in this same capability baseline.
SET check_function_bodies = false;

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
