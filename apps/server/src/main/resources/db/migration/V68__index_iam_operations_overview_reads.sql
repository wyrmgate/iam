CREATE INDEX integration_provisioning_attempt_overview_time_idx
    ON integration.provisioning_attempt (
        tenant_id,
        completed_at DESC,
        outcome,
        failure_category);

CREATE INDEX integration_reconciliation_run_overview_time_idx
    ON integration.reconciliation_run (
        tenant_id,
        completed_at DESC,
        state,
        effective_completeness);

CREATE INDEX integration_connector_instance_lifecycle_idx
    ON integration.connector_instance (
        tenant_id,
        lifecycle_state);

CREATE INDEX governance_approval_decision_actor_time_idx
    ON governance.approval_decision (
        tenant_id,
        approver_identity_id,
        decided_at DESC);

CREATE INDEX governance_review_decision_actor_time_idx
    ON governance.review_decision (
        tenant_id,
        reviewer_identity_id,
        decided_at DESC);

CREATE INDEX governance_review_item_reviewer_state_created_idx
    ON governance.review_item (
        tenant_id,
        reviewer_identity_id,
        lifecycle_state,
        created_at,
        id);

COMMENT ON INDEX integration.integration_provisioning_attempt_overview_time_idx IS
    'Supports bounded IAM Operations provisioning attempt summaries and trend reads by tenant and completion window.';

COMMENT ON INDEX integration.integration_reconciliation_run_overview_time_idx IS
    'Supports bounded IAM Operations reconciliation summaries and recent-run reads by tenant and completion window.';

COMMENT ON INDEX governance.governance_approval_decision_actor_time_idx IS
    'Supports actor-scoped IAM Operations Governance approval decision counts by time window.';

COMMENT ON INDEX governance.governance_review_decision_actor_time_idx IS
    'Supports actor-scoped IAM Operations review decision counts by time window.';
