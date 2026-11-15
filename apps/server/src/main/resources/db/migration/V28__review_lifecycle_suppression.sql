-- Access-owned reduction evidence; a review never writes Access authority directly.
CREATE TABLE access.lifecycle_review_suppression (
    tenant_id uuid NOT NULL REFERENCES tenancy.tenant(id),
    identity_id uuid NOT NULL,
    identity_access_epoch bigint NOT NULL CHECK (identity_access_epoch > 0),
    lifecycle_rule_id uuid NOT NULL,
    target_kind varchar(24) NOT NULL CHECK (target_kind IN ('ROLE','ENTITLEMENT')),
    target_id uuid NOT NULL,
    reviewed_assignment_id uuid NOT NULL,
    review_remediation_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY(tenant_id,identity_id,identity_access_epoch,lifecycle_rule_id,target_kind,target_id),
    UNIQUE(tenant_id,review_remediation_id)
);
COMMENT ON TABLE access.lifecycle_review_suppression IS 'Access-owned exact lifecycle cause suppression after review revocation; epoch/target scoped.';
