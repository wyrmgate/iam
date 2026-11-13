-- ADR-0057: unique (tenant_id, role_version_id, ordinal) bounds direct membership.
-- Existing invalid ordinals fail migration; do not truncate authoritative composition.
ALTER TABLE catalog.role_version_member
    ADD CONSTRAINT catalog_role_version_member_max_ordinal_ck CHECK (ordinal < 1000);
