-- Bounded Access projection refresh and terminal Role target reduction share
-- the same non-terminal assignment scan order. No authoritative rows are rewritten.
CREATE INDEX access_assignment_role_refresh_page_idx
    ON access.access_assignment (tenant_id, role_id, created_at, id)
    WHERE target_kind = 'ROLE'
      AND lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'SCHEDULED');
