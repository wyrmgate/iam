CREATE TEMP TABLE standalone_tenant_owner_permission (
    resource_type varchar(128) NOT NULL,
    action varchar(128) NOT NULL,
    PRIMARY KEY (resource_type, action)
) ON COMMIT DROP;

INSERT INTO standalone_tenant_owner_permission (resource_type, action) VALUES
    ('administration', 'manage-authorization'),
    ('identity', 'read'),
    ('identity', 'create'),
    ('identity', 'update'),
    ('identity', 'activate'),
    ('identity', 'suspend'),
    ('identity', 'deactivate'),
    ('identity', 'decommission'),
    ('identity', 'merge'),
    ('identity', 'split'),
    ('canonical-attribute-value', 'read'),
    ('principal', 'read'),
    ('principal', 'register'),
    ('principal', 'correlate'),
    ('source-correlation-policy', 'read'),
    ('source-correlation-policy', 'activate'),
    ('source-lifecycle-policy', 'read'),
    ('source-lifecycle-policy', 'activate'),
    ('source-absence-policy', 'read'),
    ('source-absence-policy', 'activate'),
    ('application', 'read'),
    ('application', 'create'),
    ('application', 'update'),
    ('application', 'retire'),
    ('sso-client', 'read'),
    ('sso-client', 'create'),
    ('sso-client', 'update'),
    ('sso-client', 'retire'),
    ('application-target', 'read'),
    ('application-target', 'create'),
    ('application-target', 'retire'),
    ('entitlement', 'read'),
    ('entitlement', 'create'),
    ('entitlement', 'retire'),
    ('role', 'read'),
    ('role', 'create'),
    ('role', 'update'),
    ('role', 'retire'),
    ('role-version', 'read'),
    ('role-version', 'create'),
    ('role-version', 'validate'),
    ('role-version', 'activate'),
    ('access-assignment', 'read'),
    ('access-assignment', 'create'),
    ('access-assignment', 'suspend'),
    ('access-assignment', 'resume'),
    ('access-assignment', 'cancel'),
    ('access-assignment', 'revoke'),
    ('effective-access', 'read'),
    ('lifecycle-access-policy', 'read'),
    ('lifecycle-access-policy', 'activate'),
    ('credential', 'read'),
    ('credential', 'create'),
    ('credential', 'revoke'),
    ('credential', 'compromise'),
    ('credential', 'rotate'),
    ('credential-rotation', 'read'),
    ('governance-policy', 'read'),
    ('governance-policy', 'create'),
    ('governance-policy', 'ready'),
    ('governance-policy', 'activate'),
    ('governance-policy', 'cancel'),
    ('governance-exception', 'read'),
    ('governance-exception', 'create'),
    ('governance-exception', 'revoke'),
    ('review-campaign', 'read'),
    ('review-campaign', 'create'),
    ('review-campaign', 'start'),
    ('audit', 'read'),
    ('audit', 'export'),
    ('audit', 'hold'),
    ('audit', 'purge'),
    ('evidence-snapshot', 'read'),
    ('connector', 'read'),
    ('connector', 'create'),
    ('connector', 'update'),
    ('connector', 'disable'),
    ('connector-binding', 'read'),
    ('connector-binding', 'create'),
    ('connector-binding', 'update'),
    ('connector-binding', 'disable'),
    ('connector-worker', 'read'),
    ('connector-worker', 'create'),
    ('connector-worker', 'update'),
    ('connector-worker', 'disable'),
    ('entitlement-observation-mapping', 'read'),
    ('entitlement-observation-mapping', 'create'),
    ('entitlement-observation-mapping', 'retire');

-- Ensure the explicit owner permissions exist only for tenants that consumed the
-- ADR-0044 first-party bootstrap. UUIDs are deterministic so this migration has
-- no dependency on a database UUID-generation extension.
INSERT INTO administration.administrative_permission (
    id,
    tenant_id,
    resource_type,
    action,
    created_at
)
SELECT
    format(
        '%s-%s-%s-%s-%s',
        substr(h.hash, 1, 8),
        substr(h.hash, 9, 4),
        substr(h.hash, 13, 4),
        substr(h.hash, 17, 4),
        substr(h.hash, 21, 12)
    )::uuid,
    b.tenant_id,
    p.resource_type,
    p.action,
    now()
FROM administration.initial_admin_bootstrap b
CROSS JOIN standalone_tenant_owner_permission p
CROSS JOIN LATERAL (
    SELECT md5(
        'wyrmgate:standalone-tenant-owner:'
        || b.tenant_id::text || ':' || p.resource_type || ':' || p.action
    ) AS hash
) h
WHERE b.authentication_anchor_type = 'FIRST_PARTY_PRINCIPAL'
  AND NOT EXISTS (
      SELECT 1
      FROM administration.administrative_permission existing
      WHERE existing.tenant_id = b.tenant_id
        AND existing.resource_type = p.resource_type
        AND existing.action = p.action
  );

-- Expand only the immutable marker-linked role. External-subject bootstrap roles
-- deliberately retain ADR-0011's narrow authority.
INSERT INTO administration.administrative_role_permission (
    tenant_id,
    role_id,
    permission_id,
    created_at
)
SELECT
    b.tenant_id,
    b.administrative_role_id,
    permission.id,
    now()
FROM administration.initial_admin_bootstrap b
JOIN standalone_tenant_owner_permission p ON true
JOIN administration.administrative_permission permission
  ON permission.tenant_id = b.tenant_id
 AND permission.resource_type = p.resource_type
 AND permission.action = p.action
WHERE b.authentication_anchor_type = 'FIRST_PARTY_PRINCIPAL'
ON CONFLICT (tenant_id, role_id, permission_id) DO NOTHING;

-- This migration is a one-time semantic expansion of first-party bootstrap roles.
-- Advance their optimistic revision so cached/read clients cannot mistake the
-- migrated role membership for the pre-ADR-0046 version.
UPDATE administration.administrative_role role
SET revision = role.revision + 1,
    updated_at = now()
FROM administration.initial_admin_bootstrap b
WHERE b.authentication_anchor_type = 'FIRST_PARTY_PRINCIPAL'
  AND b.tenant_id = role.tenant_id
  AND b.administrative_role_id = role.id;
