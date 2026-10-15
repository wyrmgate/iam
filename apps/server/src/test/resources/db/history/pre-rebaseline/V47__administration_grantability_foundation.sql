ALTER TABLE administration.administrative_grant
    ADD COLUMN grantable boolean NOT NULL DEFAULT false,
    ADD COLUMN delegable boolean NOT NULL DEFAULT false,
    ADD COLUMN authority_basis_grant_id uuid NULL;

ALTER TABLE administration.administrative_grant
    ADD CONSTRAINT administration_grant_authority_basis_fk
        FOREIGN KEY (tenant_id, authority_basis_grant_id)
        REFERENCES administration.administrative_grant (tenant_id, id),
    ADD CONSTRAINT administration_grant_authority_basis_not_self_ck
        CHECK (authority_basis_grant_id IS NULL OR authority_basis_grant_id <> id);

UPDATE administration.administrative_grant g
SET grantable = true,
    delegable = true
FROM administration.initial_admin_bootstrap b
WHERE b.tenant_id = g.tenant_id
  AND b.administrative_grant_id = g.id;

CREATE INDEX administration_grant_authority_basis_idx
    ON administration.administrative_grant (tenant_id, authority_basis_grant_id)
    WHERE authority_basis_grant_id IS NOT NULL;

COMMENT ON COLUMN administration.administrative_grant.grantable IS
    'Whether this direct grant may be the explicit authority basis for bounded direct-grant creation/elevation. Does not imply manage-authorization permission.';
COMMENT ON COLUMN administration.administrative_grant.delegable IS
    'Whether this direct grant may be the explicit authority basis for bounded delegation. Delegation remains a separate authority object.';
COMMENT ON COLUMN administration.administrative_grant.authority_basis_grant_id IS
    'Creation-time provenance for a direct grant. The created direct grant remains independent if the basis is later revoked/expired.';
