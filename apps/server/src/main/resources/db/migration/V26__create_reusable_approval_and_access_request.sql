CREATE TABLE governance.approval_case (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    subject_type varchar(64) NOT NULL,
    subject_id uuid NOT NULL,
    subject_revision bigint NOT NULL,
    requester_identity_id uuid NULL,
    lifecycle_state varchar(24) NOT NULL,
    current_stage_ordinal integer NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz NULL,
    CONSTRAINT governance_approval_case_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT governance_approval_case_tenant_id_uq
        UNIQUE (tenant_id, id),
    CONSTRAINT governance_approval_case_subject_type_ck CHECK (
        subject_type IN (
            'REQUEST_ITEM',
            'ADMINISTRATIVE_ELEVATION',
            'ROLE_VERSION_ACTIVATION',
            'POLICY_VERSION_ACTIVATION',
            'GOVERNANCE_EXCEPTION',
            'CREDENTIAL_OPERATION'
        )
    ),
    CONSTRAINT governance_approval_case_subject_revision_ck
        CHECK (subject_revision > 0),
    CONSTRAINT governance_approval_case_state_ck
        CHECK (lifecycle_state IN (
            'PENDING','APPROVED','REJECTED','CANCELLED','SUPERSEDED'
        )),
    CONSTRAINT governance_approval_case_stage_ck
        CHECK (current_stage_ordinal >= 0),
    CONSTRAINT governance_approval_case_revision_ck
        CHECK (revision > 0),
    CONSTRAINT governance_approval_case_completion_ck CHECK (
        (lifecycle_state = 'PENDING' AND completed_at IS NULL)
        OR
        (lifecycle_state <> 'PENDING' AND completed_at IS NOT NULL)
    ),
    CONSTRAINT governance_approval_case_timestamp_ck
        CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX governance_approval_case_pending_subject_uq
    ON governance.approval_case
        (tenant_id, subject_type, subject_id)
    WHERE lifecycle_state = 'PENDING';

CREATE INDEX governance_approval_case_subject_idx
    ON governance.approval_case
        (tenant_id, subject_type, subject_id, created_at DESC);

CREATE TABLE governance.approval_plan (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    approval_case_id uuid NOT NULL,
    self_approval_policy varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT governance_approval_plan_tenant_id_uq
        UNIQUE (tenant_id, id),
    CONSTRAINT governance_approval_plan_case_uq
        UNIQUE (tenant_id, approval_case_id),
    CONSTRAINT governance_approval_plan_case_fk
        FOREIGN KEY (tenant_id, approval_case_id)
        REFERENCES governance.approval_case (tenant_id, id),
    CONSTRAINT governance_approval_plan_self_policy_ck
        CHECK (self_approval_policy IN (
            'DENY_REQUESTER','ALLOW_REQUESTER'
        ))
);

CREATE TABLE governance.approval_stage (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    approval_plan_id uuid NOT NULL,
    stage_ordinal integer NOT NULL,
    decision_mode varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT governance_approval_stage_tenant_id_uq
        UNIQUE (tenant_id, id),
    CONSTRAINT governance_approval_stage_plan_fk
        FOREIGN KEY (tenant_id, approval_plan_id)
        REFERENCES governance.approval_plan (tenant_id, id),
    CONSTRAINT governance_approval_stage_ordinal_uq
        UNIQUE (tenant_id, approval_plan_id, stage_ordinal),
    CONSTRAINT governance_approval_stage_ordinal_ck
        CHECK (stage_ordinal >= 0),
    CONSTRAINT governance_approval_stage_mode_ck
        CHECK (decision_mode IN ('ANY_ONE','ALL'))
);

CREATE TABLE governance.approval_participant (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    approval_stage_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT governance_approval_participant_tenant_id_uq
        UNIQUE (tenant_id, id),
    CONSTRAINT governance_approval_participant_stage_fk
        FOREIGN KEY (tenant_id, approval_stage_id)
        REFERENCES governance.approval_stage (tenant_id, id),
    CONSTRAINT governance_approval_participant_identity_uq
        UNIQUE (tenant_id, approval_stage_id, identity_id)
);

CREATE INDEX governance_approval_participant_inbox_idx
    ON governance.approval_participant
        (tenant_id, identity_id, approval_stage_id);

CREATE TABLE governance.approval_decision (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    approval_case_id uuid NOT NULL,
    approval_stage_id uuid NOT NULL,
    participant_identity_id uuid NOT NULL,
    decision varchar(16) NOT NULL,
    decided_at timestamptz NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid NULL,
    CONSTRAINT governance_approval_decision_tenant_id_uq
        UNIQUE (tenant_id, id),
    CONSTRAINT governance_approval_decision_case_fk
        FOREIGN KEY (tenant_id, approval_case_id)
        REFERENCES governance.approval_case (tenant_id, id),
    CONSTRAINT governance_approval_decision_stage_fk
        FOREIGN KEY (tenant_id, approval_stage_id)
        REFERENCES governance.approval_stage (tenant_id, id),
    CONSTRAINT governance_approval_decision_actor_uq
        UNIQUE (
            tenant_id,
            approval_case_id,
            approval_stage_id,
            participant_identity_id
        ),
    CONSTRAINT governance_approval_decision_value_ck
        CHECK (decision IN ('APPROVE','REJECT'))
);

CREATE TABLE governance.access_request (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    requester_identity_id uuid NOT NULL,
    beneficiary_identity_id uuid NOT NULL,
    lifecycle_state varchar(24) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz NULL,
    CONSTRAINT governance_access_request_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT governance_access_request_tenant_id_uq
        UNIQUE (tenant_id, id),
    CONSTRAINT governance_access_request_state_ck
        CHECK (lifecycle_state IN (
            'DRAFT','SUBMITTED','COMPLETED','CANCELLED'
        )),
    CONSTRAINT governance_access_request_revision_ck
        CHECK (revision > 0),
    CONSTRAINT governance_access_request_completion_ck CHECK (
        (lifecycle_state IN ('DRAFT','SUBMITTED')
            AND completed_at IS NULL)
        OR
        (lifecycle_state IN ('COMPLETED','CANCELLED')
            AND completed_at IS NOT NULL)
    ),
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
    lifecycle_state varchar(32) NOT NULL,
    approval_case_id uuid NULL,
    access_assignment_id uuid NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz NULL,
    CONSTRAINT governance_request_item_tenant_id_uq
        UNIQUE (tenant_id, id),
    CONSTRAINT governance_request_item_request_fk
        FOREIGN KEY (tenant_id, access_request_id)
        REFERENCES governance.access_request (tenant_id, id),
    CONSTRAINT governance_request_item_approval_case_fk
        FOREIGN KEY (tenant_id, approval_case_id)
        REFERENCES governance.approval_case (tenant_id, id),
    CONSTRAINT governance_request_item_target_kind_ck
        CHECK (target_kind IN ('ROLE','ENTITLEMENT')),
    CONSTRAINT governance_request_item_target_shape_ck CHECK (
        (target_kind = 'ROLE'
            AND role_id IS NOT NULL
            AND entitlement_id IS NULL)
        OR
        (target_kind = 'ENTITLEMENT'
            AND entitlement_id IS NOT NULL
            AND role_id IS NULL)
    ),
    CONSTRAINT governance_request_item_principal_kind_ck
        CHECK (principal_constraint_kind IN ('ANY','SPECIFIC')),
    CONSTRAINT governance_request_item_principal_shape_ck CHECK (
        (principal_constraint_kind = 'ANY'
            AND specific_principal_id IS NULL)
        OR
        (principal_constraint_kind = 'SPECIFIC'
            AND specific_principal_id IS NOT NULL)
    ),
    CONSTRAINT governance_request_item_validity_ck
        CHECK (
            valid_until IS NULL
            OR valid_from IS NULL
            OR valid_until > valid_from
        ),
    CONSTRAINT governance_request_item_state_ck
        CHECK (lifecycle_state IN (
            'DRAFT','SUBMITTED','EVALUATING','PENDING_APPROVAL',
            'AUTHORIZED','APPLIED','DENIED','REJECTED',
            'CANCELLED','EXPIRED'
        )),
    CONSTRAINT governance_request_item_approval_ck CHECK (
        lifecycle_state <> 'PENDING_APPROVAL'
        OR approval_case_id IS NOT NULL
    ),
    CONSTRAINT governance_request_item_assignment_ck CHECK (
        lifecycle_state <> 'APPLIED'
        OR access_assignment_id IS NOT NULL
    ),
    CONSTRAINT governance_request_item_revision_ck
        CHECK (revision > 0),
    CONSTRAINT governance_request_item_completion_ck CHECK (
        (lifecycle_state IN (
            'DRAFT','SUBMITTED','EVALUATING','PENDING_APPROVAL',
            'AUTHORIZED'
        ) AND completed_at IS NULL)
        OR
        (lifecycle_state IN (
            'APPLIED','DENIED','REJECTED','CANCELLED','EXPIRED'
        ) AND completed_at IS NOT NULL)
    ),
    CONSTRAINT governance_request_item_timestamp_ck
        CHECK (updated_at >= created_at)
);

CREATE INDEX governance_request_item_request_idx
    ON governance.request_item
        (tenant_id, access_request_id, created_at, id);

CREATE INDEX governance_request_item_approval_idx
    ON governance.request_item
        (tenant_id, approval_case_id)
    WHERE approval_case_id IS NOT NULL;

CREATE INDEX governance_request_item_authorized_idx
    ON governance.request_item
        (tenant_id, lifecycle_state, updated_at, id)
    WHERE lifecycle_state = 'AUTHORIZED';

ALTER TABLE access.access_assignment
    DROP CONSTRAINT access_assignment_provenance_kind_ck,
    DROP CONSTRAINT access_assignment_manual_provenance_ck;

ALTER TABLE access.access_assignment
    ADD CONSTRAINT access_assignment_provenance_kind_ck
        CHECK (provenance_kind IN ('MANUAL','APPROVED_REQUEST')),
    ADD CONSTRAINT access_assignment_provenance_shape_ck CHECK (
        (provenance_kind = 'MANUAL' AND provenance_ref_id IS NULL)
        OR
        (provenance_kind = 'APPROVED_REQUEST'
            AND provenance_ref_id IS NOT NULL)
    );

CREATE UNIQUE INDEX access_assignment_approved_request_provenance_uq
    ON access.access_assignment (tenant_id, provenance_ref_id)
    WHERE provenance_kind = 'APPROVED_REQUEST';

COMMENT ON TABLE governance.approval_case IS
    'Reusable Governance-owned approval process for one typed business subject and captured subject revision.';
COMMENT ON TABLE governance.approval_plan IS
    'Immutable approval requirement snapshot. Business subject state remains owned by its capability.';
COMMENT ON TABLE governance.approval_decision IS
    'Append-only approval decision evidence by a resolved governed Identity participant.';
COMMENT ON TABLE governance.access_request IS
    'Governance-owned request envelope with one beneficiary and scalable RequestItems.';
COMMENT ON TABLE governance.request_item IS
    'Governable access request unit. Authorization, Access application and provider fulfillment are distinct states.';


CREATE OR REPLACE FUNCTION governance.reject_approval_snapshot_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'approval plan snapshots and decision evidence are immutable';
END;
$$;

CREATE TRIGGER governance_approval_plan_immutable_trg
BEFORE UPDATE OR DELETE ON governance.approval_plan
FOR EACH ROW EXECUTE FUNCTION governance.reject_approval_snapshot_mutation();

CREATE TRIGGER governance_approval_stage_immutable_trg
BEFORE UPDATE OR DELETE ON governance.approval_stage
FOR EACH ROW EXECUTE FUNCTION governance.reject_approval_snapshot_mutation();

CREATE TRIGGER governance_approval_participant_immutable_trg
BEFORE UPDATE OR DELETE ON governance.approval_participant
FOR EACH ROW EXECUTE FUNCTION governance.reject_approval_snapshot_mutation();

CREATE TRIGGER governance_approval_decision_append_only_trg
BEFORE UPDATE OR DELETE ON governance.approval_decision
FOR EACH ROW EXECUTE FUNCTION governance.reject_approval_snapshot_mutation();

CREATE OR REPLACE FUNCTION governance.reject_approval_case_subject_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.subject_type <> OLD.subject_type
       OR NEW.subject_id <> OLD.subject_id
       OR NEW.subject_revision <> OLD.subject_revision
       OR NEW.requester_identity_id IS DISTINCT FROM OLD.requester_identity_id
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'ApprovalCase subject snapshot is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER governance_approval_case_subject_immutable_trg
BEFORE UPDATE ON governance.approval_case
FOR EACH ROW EXECUTE FUNCTION governance.reject_approval_case_subject_change();

CREATE OR REPLACE FUNCTION governance.reject_request_item_intent_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.access_request_id <> OLD.access_request_id
       OR NEW.target_kind <> OLD.target_kind
       OR NEW.role_id IS DISTINCT FROM OLD.role_id
       OR NEW.entitlement_id IS DISTINCT FROM OLD.entitlement_id
       OR NEW.principal_constraint_kind <> OLD.principal_constraint_kind
       OR NEW.specific_principal_id IS DISTINCT FROM OLD.specific_principal_id
       OR NEW.valid_from IS DISTINCT FROM OLD.valid_from
       OR NEW.valid_until IS DISTINCT FROM OLD.valid_until
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'RequestItem business intent is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER governance_request_item_intent_immutable_trg
BEFORE UPDATE ON governance.request_item
FOR EACH ROW EXECUTE FUNCTION governance.reject_request_item_intent_change();
