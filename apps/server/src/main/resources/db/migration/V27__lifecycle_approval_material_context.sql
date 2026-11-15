-- Historical candidates have no material context and cannot authorize new access.
ALTER TABLE governance.lifecycle_access_approval_candidate
    ADD COLUMN identity_access_epoch bigint,
    ADD COLUMN lifecycle_policy_version_id uuid,
    ADD COLUMN target_fingerprint varchar(71),
    ADD COLUMN risk_fingerprint varchar(71),
    ADD CONSTRAINT governance_lifecycle_candidate_material_ck CHECK (
       (identity_access_epoch IS NULL AND lifecycle_policy_version_id IS NULL AND target_fingerprint IS NULL AND risk_fingerprint IS NULL)
       OR (identity_access_epoch IS NOT NULL AND identity_access_epoch > 0 AND lifecycle_policy_version_id IS NOT NULL
          AND target_fingerprint IS NOT NULL AND target_fingerprint ~ '^sha256:[0-9a-f]{64}$'
          AND risk_fingerprint IS NOT NULL AND risk_fingerprint ~ '^sha256:[0-9a-f]{64}$'));
ALTER TABLE governance.lifecycle_access_approval_candidate DROP CONSTRAINT governance_lifecycle_access_candidate_context_uq;
ALTER TABLE governance.lifecycle_access_approval_candidate ADD CONSTRAINT governance_lifecycle_access_candidate_context_uq
    UNIQUE(tenant_id,identity_id,lifecycle_rule_id,target_kind,target_id,policy_version_id,approval_plan_hash,
           identity_access_epoch,lifecycle_policy_version_id,target_fingerprint,risk_fingerprint);
