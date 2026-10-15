CREATE TABLE access.review_remediation_application (
    tenant_id uuid NOT NULL,
    review_remediation_id uuid NOT NULL,
    access_assignment_id uuid NOT NULL,
    outcome varchar(32) NOT NULL,
    resulting_lifecycle_state varchar(32) NOT NULL,
    applied_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, review_remediation_id),
    CONSTRAINT access_review_remediation_application_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT access_review_remediation_application_outcome_ck
        CHECK (outcome IN ('APPLIED','NO_ACTION_REQUIRED')),
    CONSTRAINT access_review_remediation_application_state_ck
        CHECK (btrim(resulting_lifecycle_state) <> '')
);

CREATE INDEX access_review_remediation_application_assignment_idx
    ON access.review_remediation_application (
        tenant_id, access_assignment_id, applied_at DESC);

CREATE TABLE governance.review_campaign (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    campaign_kind varchar(32) NOT NULL,
    subject_identity_id uuid NOT NULL,
    reviewer_identity_id uuid NOT NULL,
    snapshot_at timestamptz NOT NULL,
    lifecycle_state varchar(24) NOT NULL,
    generation_after_created_at timestamptz NULL,
    generation_after_id uuid NULL,
    generated_item_count bigint NOT NULL DEFAULT 0,
    decided_item_count bigint NOT NULL DEFAULT 0,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    activated_at timestamptz NULL,
    completed_at timestamptz NULL,
    failure_code varchar(128) NULL,
    CONSTRAINT governance_review_campaign_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT governance_review_campaign_tenant_id_uq
        UNIQUE (tenant_id, id),
    CONSTRAINT governance_review_campaign_kind_ck
        CHECK (campaign_kind IN ('IDENTITY_ACCESS')),
    CONSTRAINT governance_review_campaign_subject_reviewer_ck
        CHECK (subject_identity_id <> reviewer_identity_id),
    CONSTRAINT governance_review_campaign_state_ck
        CHECK (lifecycle_state IN (
            'DRAFT','GENERATING','ACTIVE','COMPLETED','FAILED')),
    CONSTRAINT governance_review_campaign_checkpoint_ck
        CHECK (
            (generation_after_created_at IS NULL
                AND generation_after_id IS NULL)
            OR
            (generation_after_created_at IS NOT NULL
                AND generation_after_id IS NOT NULL)
        ),
    CONSTRAINT governance_review_campaign_counts_ck
        CHECK (
            generated_item_count >= 0
            AND decided_item_count >= 0
            AND decided_item_count <= generated_item_count
        ),
    CONSTRAINT governance_review_campaign_revision_ck
        CHECK (revision > 0),
    CONSTRAINT governance_review_campaign_timestamp_ck
        CHECK (updated_at >= created_at),
    CONSTRAINT governance_review_campaign_completion_ck CHECK (
        (lifecycle_state <> 'COMPLETED'
            AND completed_at IS NULL)
        OR
        (lifecycle_state = 'COMPLETED'
            AND completed_at IS NOT NULL
            AND decided_item_count = generated_item_count)
    )
);

CREATE INDEX governance_review_campaign_subject_idx
    ON governance.review_campaign (
        tenant_id, subject_identity_id, created_at DESC);

CREATE INDEX governance_review_campaign_reviewer_idx
    ON governance.review_campaign (
        tenant_id, reviewer_identity_id,
        lifecycle_state, created_at DESC);

CREATE TABLE governance.review_item (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    review_campaign_id uuid NOT NULL,
    reviewer_identity_id uuid NOT NULL,
    access_assignment_id uuid NOT NULL,
    assignment_revision bigint NOT NULL,
    target_kind varchar(24) NOT NULL,
    role_id uuid NULL,
    entitlement_id uuid NULL,
    principal_constraint_kind varchar(16) NOT NULL,
    specific_principal_id uuid NULL,
    provenance_kind varchar(24) NOT NULL,
    provenance_ref_id uuid NULL,
    snapshot_lifecycle_state varchar(24) NOT NULL,
    valid_from timestamptz NULL,
    valid_until timestamptz NULL,
    assignment_created_at timestamptz NOT NULL,
    snapshot_at timestamptz NOT NULL,
    lifecycle_state varchar(16) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT governance_review_item_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT governance_review_item_campaign_fk
        FOREIGN KEY (tenant_id, review_campaign_id)
        REFERENCES governance.review_campaign (tenant_id, id),
    CONSTRAINT governance_review_item_tenant_id_uq
        UNIQUE (tenant_id, id),
    CONSTRAINT governance_review_item_assignment_uq
        UNIQUE (tenant_id, review_campaign_id, access_assignment_id),
    CONSTRAINT governance_review_item_revision_ck
        CHECK (assignment_revision > 0 AND revision > 0),
    CONSTRAINT governance_review_item_target_ck CHECK (
        (target_kind = 'ROLE'
            AND role_id IS NOT NULL
            AND entitlement_id IS NULL)
        OR
        (target_kind = 'ENTITLEMENT'
            AND entitlement_id IS NOT NULL
            AND role_id IS NULL)
    ),
    CONSTRAINT governance_review_item_principal_ck CHECK (
        (principal_constraint_kind = 'ANY'
            AND specific_principal_id IS NULL)
        OR
        (principal_constraint_kind = 'SPECIFIC'
            AND specific_principal_id IS NOT NULL)
    ),
    CONSTRAINT governance_review_item_provenance_ck CHECK (
        (provenance_kind = 'MANUAL'
            AND provenance_ref_id IS NULL)
        OR
        (provenance_kind = 'REQUEST_ITEM'
            AND provenance_ref_id IS NOT NULL)
    ),
    CONSTRAINT governance_review_item_snapshot_state_ck
        CHECK (snapshot_lifecycle_state IN (
            'ACTIVE','SUSPENDED','SCHEDULED')),
    CONSTRAINT governance_review_item_state_ck
        CHECK (lifecycle_state IN ('PENDING','DECIDED')),
    CONSTRAINT governance_review_item_timestamp_ck
        CHECK (updated_at >= created_at)
);

CREATE INDEX governance_review_item_reviewer_idx
    ON governance.review_item (
        tenant_id, reviewer_identity_id,
        lifecycle_state, created_at, id);

CREATE TABLE governance.review_decision (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    review_item_id uuid NOT NULL,
    reviewer_identity_id uuid NOT NULL,
    decision varchar(16) NOT NULL,
    reason varchar(1000) NULL,
    decided_at timestamptz NOT NULL,
    CONSTRAINT governance_review_decision_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT governance_review_decision_item_fk
        FOREIGN KEY (tenant_id, review_item_id)
        REFERENCES governance.review_item (tenant_id, id),
    CONSTRAINT governance_review_decision_tenant_id_uq
        UNIQUE (tenant_id, id),
    CONSTRAINT governance_review_decision_item_uq
        UNIQUE (tenant_id, review_item_id),
    CONSTRAINT governance_review_decision_value_ck
        CHECK (decision IN ('KEEP','REVOKE'))
);

CREATE TABLE governance.review_remediation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    review_item_id uuid NOT NULL,
    access_assignment_id uuid NOT NULL,
    lifecycle_state varchar(32) NOT NULL,
    result_code varchar(128) NULL,
    resulting_access_state varchar(32) NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz NULL,
    CONSTRAINT governance_review_remediation_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT governance_review_remediation_item_fk
        FOREIGN KEY (tenant_id, review_item_id)
        REFERENCES governance.review_item (tenant_id, id),
    CONSTRAINT governance_review_remediation_tenant_id_uq
        UNIQUE (tenant_id, id),
    CONSTRAINT governance_review_remediation_item_uq
        UNIQUE (tenant_id, review_item_id),
    CONSTRAINT governance_review_remediation_state_ck
        CHECK (lifecycle_state IN (
            'PENDING','APPLIED','NO_ACTION_REQUIRED',
            'FAILED','MANUAL_REQUIRED')),
    CONSTRAINT governance_review_remediation_revision_ck
        CHECK (revision > 0),
    CONSTRAINT governance_review_remediation_timestamp_ck
        CHECK (updated_at >= created_at),
    CONSTRAINT governance_review_remediation_completion_ck CHECK (
        (lifecycle_state = 'PENDING'
            AND completed_at IS NULL)
        OR
        (lifecycle_state <> 'PENDING'
            AND completed_at IS NOT NULL)
    )
);

CREATE INDEX governance_review_remediation_state_idx
    ON governance.review_remediation (
        tenant_id, lifecycle_state, created_at, id);

CREATE OR REPLACE FUNCTION governance.guard_review_campaign_update()
RETURNS trigger
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

CREATE TRIGGER governance_review_campaign_update_guard
BEFORE UPDATE ON governance.review_campaign
FOR EACH ROW EXECUTE FUNCTION governance.guard_review_campaign_update();

CREATE OR REPLACE FUNCTION governance.guard_review_item_update()
RETURNS trigger
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

CREATE TRIGGER governance_review_item_update_guard
BEFORE UPDATE ON governance.review_item
FOR EACH ROW EXECUTE FUNCTION governance.guard_review_item_update();

CREATE OR REPLACE FUNCTION governance.reject_review_decision_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'ReviewDecision is immutable evidence';
END;
$$;

CREATE TRIGGER governance_review_decision_immutable
BEFORE UPDATE OR DELETE ON governance.review_decision
FOR EACH ROW EXECUTE FUNCTION governance.reject_review_decision_change();

CREATE OR REPLACE FUNCTION governance.guard_review_remediation_update()
RETURNS trigger
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

CREATE TRIGGER governance_review_remediation_update_guard
BEFORE UPDATE ON governance.review_remediation
FOR EACH ROW EXECUTE FUNCTION governance.guard_review_remediation_update();

COMMENT ON TABLE access.review_remediation_application IS
    'Access-owned causal result for Governance review remediation. review_remediation_id is a stable cross-capability idempotency reference with no database foreign key.';
COMMENT ON TABLE governance.review_campaign IS
    'Governance-owned durable review campaign. COMPLETED means all generated items are decided, not that remediation/provider fulfillment completed.';
COMMENT ON TABLE governance.review_item IS
    'Scalable immutable review snapshot of one authoritative AccessAssignment at campaign snapshotAt. Access IDs are cross-capability references without database FKs.';
COMMENT ON TABLE governance.review_decision IS
    'Immutable reviewer evidence: KEEP or REVOKE.';
COMMENT ON TABLE governance.review_remediation IS
    'Governance-owned remediation process state created from REVOKE decisions; Access remains authoritative for assignment mutation.';
