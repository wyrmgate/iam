CREATE TABLE access.lifecycle_access_policy_version (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    version_number bigint NOT NULL,
    state varchar(24) NOT NULL,
    created_at timestamptz NOT NULL,
    activated_at timestamptz NOT NULL,
    superseded_at timestamptz NULL,
    CONSTRAINT lifecycle_access_policy_version_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT lifecycle_access_policy_version_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT lifecycle_access_policy_version_number_uq UNIQUE (tenant_id, version_number),
    CONSTRAINT lifecycle_access_policy_version_number_ck CHECK (version_number > 0),
    CONSTRAINT lifecycle_access_policy_version_state_ck
        CHECK (state IN ('ACTIVE', 'SUPERSEDED')),
    CONSTRAINT lifecycle_access_policy_version_time_ck CHECK (
        (state = 'ACTIVE' AND superseded_at IS NULL)
        OR
        (state = 'SUPERSEDED'
            AND superseded_at IS NOT NULL
            AND superseded_at >= activated_at)
    )
);

CREATE UNIQUE INDEX lifecycle_access_policy_one_active_idx
    ON access.lifecycle_access_policy_version (tenant_id)
    WHERE state = 'ACTIVE';

CREATE TABLE access.lifecycle_access_policy_rule (
    policy_version_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    rule_id uuid NOT NULL,
    predicate_kind varchar(32) NOT NULL,
    canonical_key varchar(256) NULL,
    expected_string varchar(1024) NULL,
    target_kind varchar(16) NOT NULL,
    target_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, policy_version_id, rule_id),
    CONSTRAINT lifecycle_access_policy_rule_version_fk
        FOREIGN KEY (tenant_id, policy_version_id)
        REFERENCES access.lifecycle_access_policy_version (tenant_id, id),
    CONSTRAINT lifecycle_access_policy_rule_predicate_ck
        CHECK (predicate_kind IN ('ALWAYS', 'CANONICAL_STRING_EQUALS')),
    CONSTRAINT lifecycle_access_policy_rule_predicate_shape_ck CHECK (
        (predicate_kind = 'ALWAYS'
            AND canonical_key IS NULL
            AND expected_string IS NULL)
        OR
        (predicate_kind = 'CANONICAL_STRING_EQUALS'
            AND canonical_key IS NOT NULL
            AND btrim(canonical_key) <> ''
            AND expected_string IS NOT NULL)
    ),
    CONSTRAINT lifecycle_access_policy_rule_target_kind_ck
        CHECK (target_kind IN ('ROLE', 'ENTITLEMENT'))
);

ALTER TABLE access.access_assignment
    DROP CONSTRAINT access_assignment_provenance_kind_ck,
    DROP CONSTRAINT access_assignment_provenance_shape_ck,
    ADD CONSTRAINT access_assignment_provenance_kind_ck
        CHECK (provenance_kind IN ('MANUAL', 'REQUEST_ITEM', 'LIFECYCLE_POLICY_RULE')),
    ADD CONSTRAINT access_assignment_provenance_shape_ck CHECK (
        (provenance_kind = 'MANUAL' AND provenance_ref_id IS NULL)
        OR
        (provenance_kind IN ('REQUEST_ITEM', 'LIFECYCLE_POLICY_RULE')
            AND provenance_ref_id IS NOT NULL)
    );

CREATE UNIQUE INDEX access_assignment_lifecycle_policy_rule_active_uq
    ON access.access_assignment (tenant_id, identity_id, provenance_ref_id)
    WHERE provenance_kind = 'LIFECYCLE_POLICY_RULE'
      AND lifecycle_state IN ('SCHEDULED', 'ACTIVE', 'SUSPENDED');

COMMENT ON TABLE access.lifecycle_access_policy_version IS
    'Immutable Access-owned Joiner/Mover lifecycle access policy version. At most one is ACTIVE per tenant.';

COMMENT ON TABLE access.lifecycle_access_policy_rule IS
    'Typed bounded lifecycle access rule. rule_id is stable logical provenance reusable across policy versions.';

COMMENT ON COLUMN access.access_assignment.provenance_ref_id IS
    'Stable causal reference within provenance kind. REQUEST_ITEM stores Governance RequestItem ID; LIFECYCLE_POLICY_RULE stores stable Access lifecycle-policy rule ID.';
