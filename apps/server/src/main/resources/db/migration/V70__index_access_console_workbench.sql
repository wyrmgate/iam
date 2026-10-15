-- Bounded Access golden-page collection and selector paths.
-- These indexes support server-side filtering/search; no new authoritative state is introduced.

CREATE INDEX access_assignment_console_page_idx
    ON access.access_assignment (tenant_id, created_at, id);

CREATE INDEX access_assignment_console_lifecycle_idx
    ON access.access_assignment (tenant_id, lifecycle_state, created_at, id);

CREATE INDEX access_assignment_console_provenance_idx
    ON access.access_assignment (tenant_id, provenance_kind, created_at, id);

CREATE INDEX access_assignment_console_role_idx
    ON access.access_assignment (tenant_id, role_id, created_at, id)
    WHERE target_kind = 'ROLE';

CREATE INDEX access_assignment_console_entitlement_idx
    ON access.access_assignment (tenant_id, entitlement_id, created_at, id)
    WHERE target_kind = 'ENTITLEMENT';

CREATE INDEX access_assignment_console_valid_from_idx
    ON access.access_assignment (tenant_id, valid_from, created_at, id)
    WHERE valid_from IS NOT NULL;

CREATE INDEX access_assignment_console_valid_until_idx
    ON access.access_assignment (tenant_id, valid_until, created_at, id)
    WHERE valid_until IS NOT NULL;

CREATE INDEX catalog_role_access_search_name_idx
    ON catalog.role (tenant_id, lower(name) text_pattern_ops, id)
    WHERE lifecycle_state = 'ACTIVE';

CREATE INDEX catalog_role_access_search_code_idx
    ON catalog.role (tenant_id, lower(code) text_pattern_ops, id)
    WHERE lifecycle_state = 'ACTIVE';

CREATE INDEX catalog_entitlement_access_search_code_idx
    ON catalog.entitlement (tenant_id, lower(code) text_pattern_ops, id)
    WHERE lifecycle_state = 'ACTIVE';
