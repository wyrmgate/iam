ALTER TABLE governance.approval_case
    DROP CONSTRAINT approval_case_subject_kind_ck;

ALTER TABLE governance.approval_case
    ADD CONSTRAINT approval_case_subject_kind_ck CHECK (
        subject_kind IN (
            'ACCESS_REQUEST_ITEM',
            'ADMINISTRATIVE_ELEVATION',
            'ROLE_VERSION_ACTIVATION',
            'GOVERNANCE_EXCEPTION',
            'LIFECYCLE_ACCESS_CANDIDATE'));

CREATE TABLE governance.lifecycle_access_approval_candidate (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    lifecycle_rule_id uuid NOT NULL,
    target_kind varchar(32) NOT NULL,
    target_id uuid NOT NULL,
    policy_version_id uuid NOT NULL,
    approval_plan_hash varchar(64) NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    created_at timestamptz NOT NULL,
    CONSTRAINT governance_lifecycle_access_candidate_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT governance_lifecycle_access_candidate_target_kind_ck
        CHECK (target_kind IN ('ROLE', 'ENTITLEMENT')),
    CONSTRAINT governance_lifecycle_access_candidate_context_uq
        UNIQUE (tenant_id, identity_id, lifecycle_rule_id, target_kind, target_id, policy_version_id, approval_plan_hash),
    CONSTRAINT governance_lifecycle_access_candidate_policy_fk
        FOREIGN KEY (tenant_id, policy_version_id)
        REFERENCES governance.policy_version (tenant_id, id)
);

CREATE INDEX governance_lifecycle_access_candidate_identity_idx
    ON governance.lifecycle_access_approval_candidate (tenant_id, identity_id, created_at, id);

COMMENT ON TABLE governance.lifecycle_access_approval_candidate IS
    'Immutable Governance context for one lifecycle-policy privilege candidate requiring approval; never AccessAssignment authority.';
