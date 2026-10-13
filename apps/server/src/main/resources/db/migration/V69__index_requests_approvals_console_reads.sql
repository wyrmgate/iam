CREATE INDEX governance_access_request_actor_updated_idx
    ON governance.access_request (
        tenant_id,
        requester_identity_id,
        beneficiary_identity_id,
        updated_at DESC,
        id DESC);

CREATE INDEX governance_request_item_request_state_updated_idx
    ON governance.request_item (
        tenant_id,
        access_request_id,
        state,
        updated_at DESC);

COMMENT ON INDEX governance.governance_access_request_actor_updated_idx IS
    'Supports actor-scoped bounded Requests & Approvals request summaries ordered by current update time.';

COMMENT ON INDEX governance.governance_request_item_request_state_updated_idx IS
    'Supports bounded Requests & Approvals item-state composition and recent APPLIED reads without browser aggregation.';
