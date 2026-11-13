-- Current technical demand is an Access-owned derived question over PRESENT
-- desired causes for one governed Principal + Entitlement tuple.
CREATE INDEX access_desired_grant_technical_demand_idx
    ON access.desired_grant_state (
        tenant_id,
        application_target_id,
        entitlement_id,
        principal_id)
    WHERE desired_state = 'PRESENT'
      AND principal_id IS NOT NULL;

-- ADR-0056: cause-preserving desired state with coalesced technical grant demand.
-- New planner task payloads carry governed Principal/Entitlement IDs. This partial
-- expression index makes cause-independent successful-ADD evidence lookup bounded by
-- the exact governed technical tuple instead of scanning provisioning history.
CREATE INDEX integration_successful_grant_evidence_idx
    ON integration.provisioning_task (
        tenant_id,
        (payload ->> 'principalId'),
        (payload ->> 'entitlementId'),
        created_at,
        id)
    WHERE subject_kind = 'DESIRED_GRANT'
      AND operation_type = 'ADD_GRANT'
      AND state = 'SUCCEEDED'
      AND jsonb_exists(payload, 'principalId')
      AND jsonb_exists(payload, 'entitlementId');
