-- Bounded SPECIFIC assignment refresh and one normalized RoleVersion path definition.
CREATE INDEX access_assignment_specific_refresh_page_idx
    ON access.access_assignment (tenant_id, specific_principal_id, created_at, id)
    WHERE principal_constraint_kind = 'SPECIFIC'
      AND lifecycle_state IN ('ACTIVE','SUSPENDED','SCHEDULED');

ALTER TABLE access.effective_access_support DROP COLUMN role_version_id;

COMMENT ON TABLE access.effective_access_support IS
    'Explainable support paths; ordered RoleVersion IDs live only in effective_access_support_role_version.';
COMMENT ON COLUMN access.effective_access_support.path_depth IS
    'Derived presentation metadata equal to the normalized path length; direct support has depth zero.';
