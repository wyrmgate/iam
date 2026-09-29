CREATE TABLE governance.governance_exception (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    scope_kind varchar(32) NOT NULL,
    subject_identity_id uuid NOT NULL,
    sod_rule_id uuid NOT NULL,
    requester_identity_id uuid NOT NULL,
    business_reason varchar(1000) NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_until timestamptz NOT NULL,
    lifecycle_state varchar(24) NOT NULL,
    approval_case_id uuid NULL,
    predecessor_exception_id uuid NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    approved_at timestamptz NULL,
    rejected_at timestamptz NULL,
    revoked_at timestamptz NULL,
    expired_at timestamptz NULL,
    CONSTRAINT governance_exception_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT governance_exception_rule_fk
        FOREIGN KEY (tenant_id, sod_rule_id)
        REFERENCES governance.sod_rule (tenant_id, id),
    CONSTRAINT governance_exception_approval_fk
        FOREIGN KEY (tenant_id, approval_case_id)
        REFERENCES governance.approval_case (tenant_id, id),
    CONSTRAINT governance_exception_predecessor_fk
        FOREIGN KEY (tenant_id, predecessor_exception_id)
        REFERENCES governance.governance_exception (tenant_id, id),
    CONSTRAINT governance_exception_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT governance_exception_scope_ck
        CHECK (scope_kind IN ('IDENTITY_SOD_RULE')),
    CONSTRAINT governance_exception_reason_ck
        CHECK (btrim(business_reason) <> ''),
    CONSTRAINT governance_exception_validity_ck
        CHECK (valid_until > valid_from),
    CONSTRAINT governance_exception_state_ck
        CHECK (lifecycle_state IN (
            'PENDING_APPROVAL','APPROVED','REJECTED','REVOKED','EXPIRED')),
    CONSTRAINT governance_exception_revision_ck CHECK (revision > 0),
    CONSTRAINT governance_exception_timestamp_ck
        CHECK (updated_at >= created_at),
    CONSTRAINT governance_exception_approval_state_ck CHECK (
        (lifecycle_state = 'PENDING_APPROVAL'
            AND approval_case_id IS NOT NULL
            AND approved_at IS NULL
            AND rejected_at IS NULL
            AND revoked_at IS NULL
            AND expired_at IS NULL)
        OR
        (lifecycle_state = 'APPROVED'
            AND approval_case_id IS NOT NULL
            AND approved_at IS NOT NULL
            AND rejected_at IS NULL
            AND revoked_at IS NULL
            AND expired_at IS NULL)
        OR
        (lifecycle_state = 'REJECTED'
            AND approval_case_id IS NOT NULL
            AND approved_at IS NULL
            AND rejected_at IS NOT NULL
            AND revoked_at IS NULL
            AND expired_at IS NULL)
        OR
        (lifecycle_state = 'REVOKED'
            AND approval_case_id IS NOT NULL
            AND approved_at IS NOT NULL
            AND rejected_at IS NULL
            AND revoked_at IS NOT NULL
            AND expired_at IS NULL)
        OR
        (lifecycle_state = 'EXPIRED'
            AND approval_case_id IS NOT NULL
            AND approved_at IS NOT NULL
            AND rejected_at IS NULL
            AND revoked_at IS NULL
            AND expired_at IS NOT NULL)
    )
);


CREATE OR REPLACE FUNCTION governance.guard_governance_exception_update()
RETURNS trigger
LANGUAGE plpgsql
AS $
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
$;

CREATE TRIGGER governance_exception_update_guard
BEFORE UPDATE ON governance.governance_exception
FOR EACH ROW EXECUTE FUNCTION governance.guard_governance_exception_update();

CREATE OR REPLACE FUNCTION governance.reject_governance_exception_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $
BEGIN
    RAISE EXCEPTION 'GovernanceException history is retained; use lifecycle state instead of delete';
END;
$;

CREATE TRIGGER governance_exception_delete_guard
BEFORE DELETE ON governance.governance_exception
FOR EACH ROW EXECUTE FUNCTION governance.reject_governance_exception_delete();

CREATE INDEX governance_exception_effective_lookup_idx
    ON governance.governance_exception (
        tenant_id, subject_identity_id, sod_rule_id,
        lifecycle_state, valid_from, valid_until);

CREATE INDEX governance_exception_predecessor_idx
    ON governance.governance_exception (
        tenant_id, predecessor_exception_id)
    WHERE predecessor_exception_id IS NOT NULL;

ALTER TABLE governance.sod_conflict
    ADD COLUMN governance_exception_id uuid NULL;

ALTER TABLE governance.sod_conflict
    ADD CONSTRAINT governance_sod_conflict_exception_fk
    FOREIGN KEY (tenant_id, governance_exception_id)
    REFERENCES governance.governance_exception (tenant_id, id);

COMMENT ON TABLE governance.governance_exception IS
    'Governance-owned scoped time-bound exception authority. Effectiveness requires APPROVED lifecycle plus semantic validity window.';
COMMENT ON COLUMN governance.governance_exception.subject_identity_id IS
    'Stable cross-capability Identity reference; intentionally no database foreign key.';
COMMENT ON COLUMN governance.governance_exception.sod_rule_id IS
    'Exact immutable SoDRule scope. Successor PolicyVersions do not inherit this exception.';
COMMENT ON COLUMN governance.sod_conflict.governance_exception_id IS
    'Optional immutable evidence reference to the exact effective GovernanceException that waived this matched rule action; the conflict remains persisted.';
