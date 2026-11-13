-- Exact same-cause successful ADD history, ordered provider route evidence.
CREATE INDEX integration_successful_add_cause_route_idx
    ON integration.provisioning_task (
        tenant_id, subject_id,
        (payload ->> 'providerEntitlementId') COLLATE "C",
        (payload ->> 'providerPrincipalId') COLLATE "C",
        created_at DESC, id DESC)
    WHERE subject_kind = 'DESIRED_GRANT'
      AND operation_type = 'ADD_GRANT'
      AND state = 'SUCCEEDED';
