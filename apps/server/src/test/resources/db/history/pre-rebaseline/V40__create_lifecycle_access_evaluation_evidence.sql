CREATE TABLE governance.lifecycle_access_evaluation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    lifecycle_rule_id uuid NOT NULL,
    governance_policy_version_id uuid NULL,
    target_kind varchar(16) NOT NULL,
    target_id uuid NOT NULL,
    decision varchar(24) NOT NULL,
    evaluation_code varchar(128) NOT NULL,
    conflict_count integer NOT NULL,
    evaluated_at timestamptz NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid NULL,
    CONSTRAINT governance_lifecycle_access_eval_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT governance_lifecycle_access_eval_version_fk
        FOREIGN KEY (tenant_id, governance_policy_version_id)
        REFERENCES governance.policy_version (tenant_id, id),
    CONSTRAINT governance_lifecycle_access_eval_tenant_id_uq
        UNIQUE (tenant_id, id),
    CONSTRAINT governance_lifecycle_access_eval_target_ck
        CHECK (target_kind IN ('ROLE','ENTITLEMENT')),
    CONSTRAINT governance_lifecycle_access_eval_decision_ck
        CHECK (decision IN ('AUTHORIZE','REQUIRE_APPROVAL','DENY','UNAVAILABLE')),
    CONSTRAINT governance_lifecycle_access_eval_code_ck
        CHECK (btrim(evaluation_code) <> ''),
    CONSTRAINT governance_lifecycle_access_eval_conflict_count_ck
        CHECK (conflict_count >= 0)
);

CREATE TABLE governance.lifecycle_access_sod_conflict (
    tenant_id uuid NOT NULL,
    evaluation_id uuid NOT NULL,
    sod_rule_id uuid NOT NULL,
    severity varchar(16) NOT NULL,
    enforcement_action varchar(24) NOT NULL,
    governance_exception_id uuid NULL,
    PRIMARY KEY (tenant_id, evaluation_id, sod_rule_id),
    CONSTRAINT governance_lifecycle_access_conflict_eval_fk
        FOREIGN KEY (tenant_id, evaluation_id)
        REFERENCES governance.lifecycle_access_evaluation (tenant_id, id),
    CONSTRAINT governance_lifecycle_access_conflict_rule_fk
        FOREIGN KEY (tenant_id, sod_rule_id)
        REFERENCES governance.sod_rule (tenant_id, id),
    CONSTRAINT governance_lifecycle_access_conflict_severity_ck
        CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT governance_lifecycle_access_conflict_action_ck
        CHECK (enforcement_action IN ('REQUIRE_APPROVAL','DENY'))
);

CREATE INDEX governance_lifecycle_access_eval_identity_idx
    ON governance.lifecycle_access_evaluation
        (tenant_id, identity_id, evaluated_at DESC, id);

CREATE INDEX governance_lifecycle_access_eval_rule_idx
    ON governance.lifecycle_access_evaluation
        (tenant_id, lifecycle_rule_id, evaluated_at DESC, id);

CREATE TRIGGER governance_lifecycle_access_eval_immutable
BEFORE UPDATE OR DELETE ON governance.lifecycle_access_evaluation
FOR EACH ROW EXECUTE FUNCTION governance.reject_governance_evidence_change();

CREATE TRIGGER governance_lifecycle_access_conflict_immutable
BEFORE UPDATE OR DELETE ON governance.lifecycle_access_sod_conflict
FOR EACH ROW EXECUTE FUNCTION governance.reject_governance_evidence_change();

COMMENT ON TABLE governance.lifecycle_access_evaluation IS
    'Immutable Governance evidence for lifecycle/birthright automatic privilege evaluation.';

COMMENT ON TABLE governance.lifecycle_access_sod_conflict IS
    'Immutable matched SoD-rule evidence for one lifecycle-access evaluation, including exact exception coverage when present.';
