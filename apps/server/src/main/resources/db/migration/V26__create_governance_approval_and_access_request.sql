CREATE TABLE governance.approval_plan (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    subject_kind varchar(64) NOT NULL,
    subject_id uuid NOT NULL,
    lifecycle_state varchar(24) NOT NULL,
    current_stage_ordinal integer NOT NULL DEFAULT 0,
    deadline_at timestamptz NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz NULL,
    CONSTRAINT governance_approval_plan_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT governance_approval_plan_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT governance_approval_plan_subject_kind_ck
        CHECK (subject_kind IN (
            'ACCESS_REQUEST_ITEM',
            'ROLE_VERSION_ACTIVATION',
            'GOVERNANCE_EXCEPTION',
            'ADMINISTRATIVE_ELEVATION',
            'CREDENTIAL_ACTION'
        )),
    CONSTRAINT governance_approval_plan_state_ck
        CHECK (lifecycle_state IN (
            'PENDING','APPROVED','REJECTED','EXPIRED','SUPERSEDED'
        )),
    CONSTRAINT governance_approval_plan_stage_ck
        CHECK (current_stage_ordinal >= 0),
    CONSTRAINT governance_approval_plan_revision_ck CHECK (revision > 0),
    CONSTRAINT governance_approval_plan_timestamp_ck
        CHECK (updated_at >= created_at),
    CONSTRAINT governance_approval_plan_completion_ck CHECK (
        (lifecycle_state = 'PENDING' AND completed_at IS NULL)
        OR (lifecycle_state <> 'PENDING' AND completed_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX governance_approval_plan_active_subject_uq
    ON governance.approval_plan (tenant_id, subject_kind, subject_id)
    WHERE lifecycle_state = 'PENDING';

CREATE INDEX governance_approval_plan_subject_history_idx
    ON governance.approval_plan
        (tenant_id, subject_kind, subject_id, created_at, id);

CREATE TABLE governance.approval_stage (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    approval_plan_id uuid NOT NULL,
    stage_ordinal integer NOT NULL,
    decision_mode varchar(16) NOT NULL,
    lifecycle_state varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT governance_approval_stage_plan_fk
        FOREIGN KEY (tenant_id, approval_plan_id)
        REFERENCES governance.approval_plan (tenant_id, id),
    CONSTRAINT governance_approval_stage_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT governance_approval_stage_plan_ordinal_uq
        UNIQUE (tenant_id, approval_plan_id, stage_ordinal),
    CONSTRAINT governance_approval_stage_ordinal_ck CHECK (stage_ordinal >= 0),
    CONSTRAINT governance_approval_stage_mode_ck
        CHECK (decision_mode IN ('ANY_ONE','ALL')),
    CONSTRAINT governance_approval_stage_state_ck
        CHECK (lifecycle_state IN ('WAITING','ACTIVE','APPROVED','REJECTED','SKIPPED')),
    CONSTRAINT governance_approval_stage_timestamp_ck
        CHECK (updated_at >= created_at)
);

CREATE TABLE governance.approval_participant (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    approval_stage_id uuid NOT NULL,
    approver_identity_id uuid NOT NULL,
    participant_ordinal integer NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT governance_approval_participant_stage_fk
        FOREIGN KEY (tenant_id, approval_stage_id)
        REFERENCES governance.approval_stage (tenant_id, id),
    CONSTRAINT governance_approval_participant_tenant_id_uq
        UNIQUE (tenant_id, id),
    CONSTRAINT governance_approval_participant_stage_identity_uq
        UNIQUE (tenant_id, approval_stage_id, approver_identity_id),
    CONSTRAINT governance_approval_participant_stage_ordinal_uq
        UNIQUE (tenant_id, approval_stage_id, participant_ordinal),
    CONSTRAINT governance_approval_participant_ordinal_ck
        CHECK (participant_ordinal >= 0)
);

CREATE INDEX governance_approval_participant_inbox_idx
    ON governance.approval_participant
        (tenant_id, approver_identity_id, approval_stage_id);

CREATE TABLE governance.approval_decision (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    approval_plan_id uuid NOT NULL,
    approval_stage_id uuid NOT NULL,
    approver_identity_id uuid NOT NULL,
    decision varchar(16) NOT NULL,
    reason varchar(2048) NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid NULL,
    decided_at timestamptz NOT NULL,
    CONSTRAINT governance_approval_decision_plan_fk
        FOREIGN KEY (tenant_id, approval_plan_id)
        REFERENCES governance.approval_plan (tenant_id, id),
    CONSTRAINT governance_approval_decision_stage_fk
        FOREIGN KEY (tenant_id, approval_stage_id)
        REFERENCES governance.approval_stage (tenant_id, id),
    CONSTRAINT governance_approval_decision_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT governance_approval_decision_participant_uq
        UNIQUE (tenant_id, approval_stage_id, approver_identity_id),
    CONSTRAINT governance_approval_decision_value_ck
        CHECK (decision IN ('APPROVE','REJECT')),
    CONSTRAINT governance_approval_decision_reason_ck
        CHECK (reason IS NULL OR btrim(reason) <> '')
);

CREATE INDEX governance_approval_decision_plan_idx
    ON governance.approval_decision
        (tenant_id, approval_plan_id, approval_stage_id, decided_at, id);

CREATE OR REPLACE FUNCTION governance.reject_approval_plan_content_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.subject_kind <> OLD.subject_kind
       OR NEW.subject_id <> OLD.subject_id
       OR NEW.created_at <> OLD.created_at
       OR NEW.deadline_at IS DISTINCT FROM OLD.deadline_at THEN
        RAISE EXCEPTION 'ApprovalPlan content is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER governance_approval_plan_content_immutable_trg
BEFORE UPDATE ON governance.approval_plan
FOR EACH ROW EXECUTE FUNCTION governance.reject_approval_plan_content_change();

CREATE OR REPLACE FUNCTION governance.reject_approval_structure_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'ApprovalPlan stage/participant structure is immutable';
END;
$$;

CREATE TRIGGER governance_approval_stage_delete_immutable_trg
BEFORE DELETE ON governance.approval_stage
FOR EACH ROW EXECUTE FUNCTION governance.reject_approval_structure_change();

CREATE OR REPLACE FUNCTION governance.reject_approval_stage_content_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.approval_plan_id <> OLD.approval_plan_id
       OR NEW.stage_ordinal <> OLD.stage_ordinal
       OR NEW.decision_mode <> OLD.decision_mode
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'ApprovalStage content is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER governance_approval_stage_content_immutable_trg
BEFORE UPDATE ON governance.approval_stage
FOR EACH ROW EXECUTE FUNCTION governance.reject_approval_stage_content_change();

CREATE TRIGGER governance_approval_participant_immutable_trg
BEFORE UPDATE OR DELETE ON governance.approval_participant
FOR EACH ROW EXECUTE FUNCTION governance.reject_approval_structure_change();

CREATE OR REPLACE FUNCTION governance.reject_approval_decision_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'ApprovalDecision is immutable evidence';
END;
$$;

CREATE TRIGGER governance_approval_decision_immutable_trg
BEFORE UPDATE OR DELETE ON governance.approval_decision
FOR EACH ROW EXECUTE FUNCTION governance.reject_approval_decision_change();

CREATE TABLE governance.access_request (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    requester_identity_id uuid NOT NULL,
    beneficiary_identity_id uuid NOT NULL,
    lifecycle_state varchar(24) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    submitted_at timestamptz NULL,
    completed_at timestamptz NULL,
    CONSTRAINT governance_access_request_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT governance_access_request_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT governance_access_request_state_ck
        CHECK (lifecycle_state IN ('DRAFT','SUBMITTED','IN_PROGRESS','COMPLETED','CANCELLED')),
    CONSTRAINT governance_access_request_revision_ck CHECK (revision > 0),
    CONSTRAINT governance_access_request_timestamp_ck
        CHECK (updated_at >= created_at)
);

CREATE TABLE governance.request_item (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    access_request_id uuid NOT NULL,
    target_kind varchar(16) NOT NULL,
    role_id uuid NULL,
    entitlement_id uuid NULL,
    principal_constraint_kind varchar(16) NOT NULL,
    specific_principal_id uuid NULL,
    valid_from timestamptz NULL,
    valid_until timestamptz NULL,
    lifecycle_state varchar(24) NOT NULL,
    approval_plan_id uuid NULL,
    access_assignment_id uuid NULL,
    denial_code varchar(128) NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT governance_request_item_request_fk
        FOREIGN KEY (tenant_id, access_request_id)
        REFERENCES governance.access_request (tenant_id, id),
    CONSTRAINT governance_request_item_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT governance_request_item_target_kind_ck
        CHECK (target_kind IN ('ROLE','ENTITLEMENT')),
    CONSTRAINT governance_request_item_target_shape_ck CHECK (
        (target_kind = 'ROLE' AND role_id IS NOT NULL AND entitlement_id IS NULL)
        OR
        (target_kind = 'ENTITLEMENT' AND entitlement_id IS NOT NULL AND role_id IS NULL)
    ),
    CONSTRAINT governance_request_item_principal_kind_ck
        CHECK (principal_constraint_kind IN ('ANY','SPECIFIC')),
    CONSTRAINT governance_request_item_principal_shape_ck CHECK (
        (principal_constraint_kind = 'ANY' AND specific_principal_id IS NULL)
        OR
        (principal_constraint_kind = 'SPECIFIC' AND specific_principal_id IS NOT NULL)
    ),
    CONSTRAINT governance_request_item_validity_ck
        CHECK (valid_until IS NULL OR valid_from IS NULL OR valid_until > valid_from),
    CONSTRAINT governance_request_item_state_ck
        CHECK (lifecycle_state IN (
            'DRAFT','SUBMITTED','EVALUATING','PENDING_APPROVAL',
            'AUTHORIZED','APPLIED','DENIED','REJECTED','CANCELLED','EXPIRED'
        )),
    CONSTRAINT governance_request_item_revision_ck CHECK (revision > 0),
    CONSTRAINT governance_request_item_timestamp_ck
        CHECK (updated_at >= created_at)
);

CREATE INDEX governance_request_item_request_idx
    ON governance.request_item
        (tenant_id, access_request_id, created_at, id);

CREATE INDEX governance_request_item_plan_idx
    ON governance.request_item
        (tenant_id, approval_plan_id)
    WHERE approval_plan_id IS NOT NULL;

ALTER TABLE access.access_assignment
    DROP CONSTRAINT access_assignment_provenance_kind_ck,
    DROP CONSTRAINT access_assignment_manual_provenance_ck;

ALTER TABLE access.access_assignment
    ADD CONSTRAINT access_assignment_provenance_kind_ck
        CHECK (provenance_kind IN ('MANUAL','REQUEST_ITEM')),
    ADD CONSTRAINT access_assignment_provenance_shape_ck CHECK (
        (provenance_kind = 'MANUAL' AND provenance_ref_id IS NULL)
        OR
        (provenance_kind = 'REQUEST_ITEM' AND provenance_ref_id IS NOT NULL)
    );

CREATE UNIQUE INDEX access_assignment_request_item_provenance_uq
    ON access.access_assignment (tenant_id, provenance_ref_id)
    WHERE provenance_kind = 'REQUEST_ITEM';

COMMENT ON TABLE governance.approval_plan IS
    'Reusable Governance-owned immutable approval workflow snapshot for one typed subject.';
COMMENT ON TABLE governance.approval_decision IS
    'Immutable human approval/rejection evidence; business consequences remain consumer-owned.';
COMMENT ON TABLE governance.access_request IS
    'Governance-owned access-request envelope. Approval and provider fulfillment are separate concerns.';
COMMENT ON TABLE governance.request_item IS
    'Governable access-request unit with consumer-owned state and optional reusable ApprovalPlan reference.';
