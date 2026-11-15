-- Governance snapshots the material admission context at each positive RequestItem decision.
-- Existing pending/authorized items have no historical context: do not guess or backfill authority.
CREATE TABLE governance.request_admission_context (
    tenant_id uuid NOT NULL,
    request_item_id uuid NOT NULL,
    item_revision bigint NOT NULL CHECK (item_revision > 0),
    approval_case_id uuid,
    identity_epoch bigint NOT NULL CHECK (identity_epoch > 0),
    policy_version_id uuid NOT NULL,
    target_fingerprint varchar(71) NOT NULL CHECK (target_fingerprint ~ '^sha256:[0-9a-f]{64}$'),
    risk_fingerprint varchar(71) NOT NULL CHECK (risk_fingerprint ~ '^sha256:[0-9a-f]{64}$'),
    policy_evaluation_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, request_item_id, item_revision),
    FOREIGN KEY (tenant_id, request_item_id) REFERENCES governance.request_item(tenant_id, id),
    FOREIGN KEY (tenant_id, approval_case_id) REFERENCES governance.approval_case(tenant_id, id)
);
CREATE INDEX governance_request_admission_case_idx
    ON governance.request_admission_context (tenant_id, approval_case_id, item_revision)
    WHERE approval_case_id IS NOT NULL;
CREATE TRIGGER governance_request_admission_immutable
    BEFORE UPDATE OR DELETE ON governance.request_admission_context
    FOR EACH ROW EXECUTE FUNCTION governance.reject_governance_evidence_change();
