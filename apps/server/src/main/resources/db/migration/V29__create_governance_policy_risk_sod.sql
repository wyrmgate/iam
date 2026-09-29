CREATE TABLE governance.policy (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    policy_kind varchar(32) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT governance_policy_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT governance_policy_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT governance_policy_kind_ck
        CHECK (policy_kind IN ('ACCESS_REQUEST')),
    CONSTRAINT governance_policy_kind_uq
        UNIQUE (tenant_id, policy_kind),
    CONSTRAINT governance_policy_revision_ck CHECK (revision > 0),
    CONSTRAINT governance_policy_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE TABLE governance.policy_version (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    policy_id uuid NOT NULL,
    version_number bigint NOT NULL,
    state varchar(24) NOT NULL,
    default_decision varchar(24) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    activated_at timestamptz NULL,
    superseded_at timestamptz NULL,
    CONSTRAINT governance_policy_version_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT governance_policy_version_policy_fk
        FOREIGN KEY (tenant_id, policy_id)
        REFERENCES governance.policy (tenant_id, id),
    CONSTRAINT governance_policy_version_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT governance_policy_version_number_ck CHECK (version_number > 0),
    CONSTRAINT governance_policy_version_state_ck
        CHECK (state IN ('DRAFT','READY','ACTIVE','SUPERSEDED','CANCELLED')),
    CONSTRAINT governance_policy_version_decision_ck
        CHECK (default_decision IN ('AUTHORIZE','REQUIRE_APPROVAL','DENY')),
    CONSTRAINT governance_policy_version_revision_ck CHECK (revision > 0),
    CONSTRAINT governance_policy_version_number_uq
        UNIQUE (tenant_id, policy_id, version_number),
    CONSTRAINT governance_policy_version_timestamp_ck CHECK (updated_at >= created_at),
    CONSTRAINT governance_policy_version_activation_ck CHECK (
        (state IN ('DRAFT','READY','CANCELLED') AND activated_at IS NULL AND superseded_at IS NULL)
        OR (state = 'ACTIVE' AND activated_at IS NOT NULL AND superseded_at IS NULL)
        OR (state = 'SUPERSEDED' AND activated_at IS NOT NULL AND superseded_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX governance_policy_version_active_uq
    ON governance.policy_version (tenant_id, policy_id)
    WHERE state = 'ACTIVE';

CREATE TABLE governance.sod_rule (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    policy_version_id uuid NOT NULL,
    rule_code varchar(128) NOT NULL,
    left_entitlement_id uuid NOT NULL,
    right_entitlement_id uuid NOT NULL,
    severity varchar(16) NOT NULL,
    enforcement_action varchar(24) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT governance_sod_rule_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT governance_sod_rule_version_fk
        FOREIGN KEY (tenant_id, policy_version_id)
        REFERENCES governance.policy_version (tenant_id, id),
    CONSTRAINT governance_sod_rule_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT governance_sod_rule_code_ck CHECK (btrim(rule_code) <> ''),
    CONSTRAINT governance_sod_rule_pair_ck CHECK (
        left_entitlement_id <> right_entitlement_id
        AND left_entitlement_id < right_entitlement_id
    ),
    CONSTRAINT governance_sod_rule_severity_ck
        CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT governance_sod_rule_action_ck
        CHECK (enforcement_action IN ('REQUIRE_APPROVAL','DENY')),
    CONSTRAINT governance_sod_rule_code_uq
        UNIQUE (tenant_id, policy_version_id, rule_code),
    CONSTRAINT governance_sod_rule_pair_uq
        UNIQUE (tenant_id, policy_version_id, left_entitlement_id, right_entitlement_id)
);

CREATE TABLE governance.policy_approval_stage (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    policy_version_id uuid NOT NULL,
    stage_ordinal integer NOT NULL,
    decision_mode varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT governance_policy_stage_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT governance_policy_stage_version_fk
        FOREIGN KEY (tenant_id, policy_version_id)
        REFERENCES governance.policy_version (tenant_id, id),
    CONSTRAINT governance_policy_stage_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT governance_policy_stage_ordinal_ck CHECK (stage_ordinal >= 0),
    CONSTRAINT governance_policy_stage_mode_ck CHECK (decision_mode IN ('ANY_ONE','ALL')),
    CONSTRAINT governance_policy_stage_ordinal_uq
        UNIQUE (tenant_id, policy_version_id, stage_ordinal)
);

CREATE TABLE governance.policy_approval_approver (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    policy_approval_stage_id uuid NOT NULL,
    approver_identity_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT governance_policy_approver_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT governance_policy_approver_stage_fk
        FOREIGN KEY (tenant_id, policy_approval_stage_id)
        REFERENCES governance.policy_approval_stage (tenant_id, id),
    CONSTRAINT governance_policy_approver_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT governance_policy_approver_identity_uq
        UNIQUE (tenant_id, policy_approval_stage_id, approver_identity_id)
);

CREATE TABLE governance.risk_assessment (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    request_item_id uuid NOT NULL,
    policy_version_id uuid NOT NULL,
    severity varchar(16) NOT NULL,
    factor_count integer NOT NULL,
    assessed_at timestamptz NOT NULL,
    CONSTRAINT governance_risk_assessment_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT governance_risk_assessment_item_fk
        FOREIGN KEY (tenant_id, request_item_id)
        REFERENCES governance.request_item (tenant_id, id),
    CONSTRAINT governance_risk_assessment_version_fk
        FOREIGN KEY (tenant_id, policy_version_id)
        REFERENCES governance.policy_version (tenant_id, id),
    CONSTRAINT governance_risk_assessment_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT governance_risk_assessment_severity_ck
        CHECK (severity IN ('NONE','LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT governance_risk_assessment_factor_ck CHECK (factor_count >= 0)
);

CREATE TABLE governance.sod_conflict (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    risk_assessment_id uuid NOT NULL,
    sod_rule_id uuid NOT NULL,
    requested_entitlement_id uuid NOT NULL,
    conflicting_entitlement_id uuid NOT NULL,
    conflict_source varchar(24) NOT NULL,
    severity varchar(16) NOT NULL,
    enforcement_action varchar(24) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT governance_sod_conflict_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT governance_sod_conflict_assessment_fk
        FOREIGN KEY (tenant_id, risk_assessment_id)
        REFERENCES governance.risk_assessment (tenant_id, id),
    CONSTRAINT governance_sod_conflict_rule_fk
        FOREIGN KEY (tenant_id, sod_rule_id)
        REFERENCES governance.sod_rule (tenant_id, id),
    CONSTRAINT governance_sod_conflict_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT governance_sod_conflict_source_ck
        CHECK (conflict_source IN ('CURRENT_ACCESS','REQUEST_ITEM','REQUEST')),
    CONSTRAINT governance_sod_conflict_severity_ck
        CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT governance_sod_conflict_action_ck
        CHECK (enforcement_action IN ('REQUIRE_APPROVAL','DENY'))
);

CREATE TABLE governance.policy_evaluation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    access_request_id uuid NOT NULL,
    request_item_id uuid NOT NULL,
    request_item_revision bigint NOT NULL,
    policy_version_id uuid NOT NULL,
    risk_assessment_id uuid NOT NULL,
    decision varchar(24) NOT NULL,
    evaluation_code varchar(128) NOT NULL,
    evaluated_at timestamptz NOT NULL,
    CONSTRAINT governance_policy_evaluation_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT governance_policy_evaluation_request_fk
        FOREIGN KEY (tenant_id, access_request_id)
        REFERENCES governance.access_request (tenant_id, id),
    CONSTRAINT governance_policy_evaluation_item_fk
        FOREIGN KEY (tenant_id, request_item_id)
        REFERENCES governance.request_item (tenant_id, id),
    CONSTRAINT governance_policy_evaluation_version_fk
        FOREIGN KEY (tenant_id, policy_version_id)
        REFERENCES governance.policy_version (tenant_id, id),
    CONSTRAINT governance_policy_evaluation_assessment_fk
        FOREIGN KEY (tenant_id, risk_assessment_id)
        REFERENCES governance.risk_assessment (tenant_id, id),
    CONSTRAINT governance_policy_evaluation_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT governance_policy_evaluation_revision_ck CHECK (request_item_revision > 0),
    CONSTRAINT governance_policy_evaluation_decision_ck
        CHECK (decision IN ('AUTHORIZE','REQUIRE_APPROVAL','DENY')),
    CONSTRAINT governance_policy_evaluation_code_ck CHECK (btrim(evaluation_code) <> '')
);

CREATE INDEX governance_policy_evaluation_item_idx
    ON governance.policy_evaluation
        (tenant_id, request_item_id, evaluated_at DESC);

CREATE INDEX governance_sod_conflict_assessment_idx
    ON governance.sod_conflict
        (tenant_id, risk_assessment_id, severity);

CREATE OR REPLACE FUNCTION governance.guard_policy_version_content()
RETURNS trigger
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

CREATE TRIGGER governance_sod_rule_content_guard
BEFORE INSERT OR UPDATE OR DELETE ON governance.sod_rule
FOR EACH ROW EXECUTE FUNCTION governance.guard_policy_version_content();

CREATE TRIGGER governance_policy_stage_content_guard
BEFORE INSERT OR UPDATE OR DELETE ON governance.policy_approval_stage
FOR EACH ROW EXECUTE FUNCTION governance.guard_policy_version_content();

CREATE TRIGGER governance_policy_approver_content_guard
BEFORE INSERT OR UPDATE OR DELETE ON governance.policy_approval_approver
FOR EACH ROW EXECUTE FUNCTION governance.guard_policy_version_content();

CREATE OR REPLACE FUNCTION governance.guard_policy_version_update()
RETURNS trigger
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

CREATE TRIGGER governance_policy_version_update_guard
BEFORE UPDATE ON governance.policy_version
FOR EACH ROW EXECUTE FUNCTION governance.guard_policy_version_update();

CREATE OR REPLACE FUNCTION governance.reject_policy_version_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $
BEGIN
    RAISE EXCEPTION 'policy versions are retained; use lifecycle state instead of delete';
END;
$;

CREATE TRIGGER governance_policy_version_delete_guard
BEFORE DELETE ON governance.policy_version
FOR EACH ROW EXECUTE FUNCTION governance.reject_policy_version_delete();

CREATE OR REPLACE FUNCTION governance.reject_governance_evidence_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'governance evaluation evidence is immutable';
END;
$$;

CREATE TRIGGER governance_risk_assessment_immutable
BEFORE UPDATE OR DELETE ON governance.risk_assessment
FOR EACH ROW EXECUTE FUNCTION governance.reject_governance_evidence_change();

CREATE TRIGGER governance_sod_conflict_immutable
BEFORE UPDATE OR DELETE ON governance.sod_conflict
FOR EACH ROW EXECUTE FUNCTION governance.reject_governance_evidence_change();

CREATE TRIGGER governance_policy_evaluation_immutable
BEFORE UPDATE OR DELETE ON governance.policy_evaluation
FOR EACH ROW EXECUTE FUNCTION governance.reject_governance_evidence_change();

COMMENT ON TABLE governance.policy IS
    'Governance-owned stable policy identity. Typed versions own immutable evaluated content.';
COMMENT ON TABLE governance.policy_version IS
    'Versioned ACCESS_REQUEST policy. Activated/superseded content is immutable; rollback creates a new version.';
COMMENT ON TABLE governance.sod_rule IS
    'Typed symmetric entitlement-pair SoD rule; Catalog stable IDs are cross-capability references without foreign keys.';
COMMENT ON TABLE governance.risk_assessment IS
    'Immutable risk result explaining severity/factor count for one completed RequestItem policy evaluation.';
COMMENT ON TABLE governance.sod_conflict IS
    'Immutable evidence of one matched SoD rule and its current/requested conflict context.';
COMMENT ON TABLE governance.policy_evaluation IS
    'Immutable policy decision evidence for a RequestItem revision; risk explains factors while policy decides action.';
