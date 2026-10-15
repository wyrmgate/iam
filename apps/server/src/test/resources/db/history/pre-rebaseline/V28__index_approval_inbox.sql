CREATE INDEX approval_approver_identity_stage_idx
    ON governance.approval_approver (
        tenant_id,
        approver_identity_id,
        approval_stage_id);

COMMENT ON INDEX governance.approval_approver_identity_stage_idx IS
    'Supports deterministic reusable approval inbox lookup by tenant and governed approver Identity.';
