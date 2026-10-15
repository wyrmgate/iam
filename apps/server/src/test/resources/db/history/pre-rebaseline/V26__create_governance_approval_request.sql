CREATE TABLE governance.approval_case (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    subject_kind varchar(64) NOT NULL,
    subject_id uuid NOT NULL,
    initiator_identity_id uuid NOT NULL,
    state varchar(24) NOT NULL,
    current_stage_ordinal integer NOT NULL DEFAULT 0,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz NULL,
    CONSTRAINT approval_case_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT approval_case_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT approval_case_subject_kind_ck CHECK (
        subject_kind IN (
            'ACCESS_REQUEST_ITEM',
            'ADMINISTRATIVE_ELEVATION',
            'ROLE_VERSION_ACTIVATION',
            'GOVERNANCE_EXCEPTION')),
    CONSTRAINT approval_case_state_ck CHECK (
        state IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'SUPERSEDED')),
    CONSTRAINT approval_case_stage_ck CHECK (current_stage_ordinal >= 0),
    CONSTRAINT approval_case_revision_ck CHECK (revision > 0),
    CONSTRAINT approval_case_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX approval_case_active_subject_uq
    ON governance.approval_case (tenant_id, subject_kind, subject_id)
    WHERE state = 'PENDING';

CREATE TABLE governance.approval_plan (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    approval_case_id uuid NOT NULL,
    plan_number bigint NOT NULL,
    content_hash varchar(128) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT approval_plan_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT approval_plan_case_fk
        FOREIGN KEY (tenant_id, approval_case_id)
        REFERENCES governance.approval_case (tenant_id, id),
    CONSTRAINT approval_plan_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT approval_plan_number_ck CHECK (plan_number > 0),
    CONSTRAINT approval_plan_case_number_uq
        UNIQUE (tenant_id, approval_case_id, plan_number)
);

CREATE TABLE governance.approval_stage (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    approval_plan_id uuid NOT NULL,
    stage_ordinal integer NOT NULL,
    decision_mode varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT approval_stage_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT approval_stage_plan_fk
        FOREIGN KEY (tenant_id, approval_plan_id)
        REFERENCES governance.approval_plan (tenant_id, id),
    CONSTRAINT approval_stage_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT approval_stage_ordinal_ck CHECK (stage_ordinal >= 0),
    CONSTRAINT approval_stage_mode_ck CHECK (decision_mode IN ('ANY_ONE', 'ALL')),
    CONSTRAINT approval_stage_plan_ordinal_uq
        UNIQUE (tenant_id, approval_plan_id, stage_ordinal)
);

CREATE TABLE governance.approval_approver (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    approval_stage_id uuid NOT NULL,
    approver_identity_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT approval_approver_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT approval_approver_stage_fk
        FOREIGN KEY (tenant_id, approval_stage_id)
        REFERENCES governance.approval_stage (tenant_id, id),
    CONSTRAINT approval_approver_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT approval_approver_stage_identity_uq
        UNIQUE (tenant_id, approval_stage_id, approver_identity_id)
);

CREATE TABLE governance.approval_decision (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    approval_case_id uuid NOT NULL,
    approval_plan_id uuid NOT NULL,
    approval_stage_id uuid NOT NULL,
    approver_identity_id uuid NOT NULL,
    decision varchar(16) NOT NULL,
    reason varchar(1000) NULL,
    decided_at timestamptz NOT NULL,
    CONSTRAINT approval_decision_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT approval_decision_case_fk
        FOREIGN KEY (tenant_id, approval_case_id)
        REFERENCES governance.approval_case (tenant_id, id),
    CONSTRAINT approval_decision_plan_fk
        FOREIGN KEY (tenant_id, approval_plan_id)
        REFERENCES governance.approval_plan (tenant_id, id),
    CONSTRAINT approval_decision_stage_fk
        FOREIGN KEY (tenant_id, approval_stage_id)
        REFERENCES governance.approval_stage (tenant_id, id),
    CONSTRAINT approval_decision_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT approval_decision_value_ck CHECK (decision IN ('APPROVE', 'REJECT')),
    CONSTRAINT approval_decision_once_uq
        UNIQUE (tenant_id, approval_stage_id, approver_identity_id)
);

CREATE INDEX approval_case_subject_idx
    ON governance.approval_case (tenant_id, subject_kind, subject_id, state);

CREATE INDEX approval_decision_case_idx
    ON governance.approval_decision (tenant_id, approval_case_id, decided_at);

CREATE TABLE governance.access_request (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    requester_identity_id uuid NOT NULL,
    beneficiary_identity_id uuid NOT NULL,
    state varchar(24) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    submitted_at timestamptz NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT access_request_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT access_request_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT access_request_state_ck CHECK (
        state IN ('DRAFT', 'SUBMITTED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT access_request_revision_ck CHECK (revision > 0),
    CONSTRAINT access_request_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE TABLE governance.request_item (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    access_request_id uuid NOT NULL,
    target_kind varchar(16) NOT NULL,
    role_id uuid NULL,
    entitlement_id uuid NULL,
    state varchar(24) NOT NULL,
    approval_case_id uuid NULL,
    evaluation_code varchar(128) NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT request_item_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT request_item_request_fk
        FOREIGN KEY (tenant_id, access_request_id)
        REFERENCES governance.access_request (tenant_id, id),
    CONSTRAINT request_item_approval_case_fk
        FOREIGN KEY (tenant_id, approval_case_id)
        REFERENCES governance.approval_case (tenant_id, id),
    CONSTRAINT request_item_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT request_item_target_kind_ck CHECK (target_kind IN ('ROLE', 'ENTITLEMENT')),
    CONSTRAINT request_item_target_shape_ck CHECK (
        (target_kind = 'ROLE' AND role_id IS NOT NULL AND entitlement_id IS NULL)
        OR
        (target_kind = 'ENTITLEMENT' AND entitlement_id IS NOT NULL AND role_id IS NULL)),
    CONSTRAINT request_item_state_ck CHECK (
        state IN (
            'DRAFT', 'SUBMITTED', 'EVALUATING', 'PENDING_APPROVAL',
            'AUTHORIZED', 'APPLIED', 'DENIED', 'REJECTED',
            'CANCELLED', 'EXPIRED')),
    CONSTRAINT request_item_revision_ck CHECK (revision > 0),
    CONSTRAINT request_item_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE INDEX request_item_request_state_idx
    ON governance.request_item (tenant_id, access_request_id, state);

CREATE INDEX request_item_approval_case_idx
    ON governance.request_item (tenant_id, approval_case_id)
    WHERE approval_case_id IS NOT NULL;

COMMENT ON TABLE governance.approval_case IS
    'Governance-owned reusable typed approval process state. Calling capabilities retain ownership of the approved business object.';
COMMENT ON TABLE governance.approval_plan IS
    'Immutable approval plan snapshot. Replacement creates another plan rather than mutating prior plan semantics.';
COMMENT ON TABLE governance.approval_decision IS
    'Append-only immutable approval/rejection evidence.';
COMMENT ON TABLE governance.access_request IS
    'Governance-owned access request envelope; authorization and downstream Access application remain separate.';
COMMENT ON TABLE governance.request_item IS
    'Scalable governable unit for requested Role-or-Entitlement access.';

CREATE OR REPLACE FUNCTION governance.reject_immutable_approval_evidence_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'approval plan/stage/approver/decision evidence is immutable';
END;
$$;

CREATE TRIGGER approval_plan_immutable_trg
BEFORE UPDATE OR DELETE ON governance.approval_plan
FOR EACH ROW EXECUTE FUNCTION governance.reject_immutable_approval_evidence_change();

CREATE TRIGGER approval_stage_immutable_trg
BEFORE UPDATE OR DELETE ON governance.approval_stage
FOR EACH ROW EXECUTE FUNCTION governance.reject_immutable_approval_evidence_change();

CREATE TRIGGER approval_approver_immutable_trg
BEFORE UPDATE OR DELETE ON governance.approval_approver
FOR EACH ROW EXECUTE FUNCTION governance.reject_immutable_approval_evidence_change();

CREATE TRIGGER approval_decision_immutable_trg
BEFORE UPDATE OR DELETE ON governance.approval_decision
FOR EACH ROW EXECUTE FUNCTION governance.reject_immutable_approval_evidence_change();
