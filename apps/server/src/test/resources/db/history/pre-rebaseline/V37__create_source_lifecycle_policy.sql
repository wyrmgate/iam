CREATE TABLE identity.source_lifecycle_policy_version (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    source_path varchar(512) NOT NULL,
    version_number bigint NOT NULL,
    state varchar(24) NOT NULL,
    created_at timestamptz NOT NULL,
    activated_at timestamptz NOT NULL,
    superseded_at timestamptz NULL,
    CONSTRAINT identity_source_lifecycle_policy_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_source_lifecycle_policy_version_uq
        UNIQUE (tenant_id, source_system_id, version_number),
    CONSTRAINT identity_source_lifecycle_policy_source_fk
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES identity.source_system (tenant_id, id),
    CONSTRAINT identity_source_lifecycle_policy_version_ck CHECK (version_number > 0),
    CONSTRAINT identity_source_lifecycle_policy_path_ck CHECK (
        source_path LIKE '$.%' AND length(source_path) >= 3),
    CONSTRAINT identity_source_lifecycle_policy_state_ck
        CHECK (state IN ('ACTIVE', 'SUPERSEDED')),
    CONSTRAINT identity_source_lifecycle_policy_time_ck CHECK (
        (state = 'ACTIVE' AND superseded_at IS NULL)
        OR (
            state = 'SUPERSEDED'
            AND superseded_at IS NOT NULL
            AND superseded_at >= activated_at
        )
    )
);

CREATE UNIQUE INDEX identity_source_lifecycle_policy_one_active_idx
    ON identity.source_lifecycle_policy_version (tenant_id, source_system_id)
    WHERE state = 'ACTIVE';

CREATE TABLE identity.source_lifecycle_policy_rule (
    policy_version_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_value varchar(512) NOT NULL,
    target_lifecycle_state varchar(24) NOT NULL,
    PRIMARY KEY (tenant_id, policy_version_id, source_value),
    CONSTRAINT identity_source_lifecycle_policy_rule_policy_fk
        FOREIGN KEY (tenant_id, policy_version_id)
        REFERENCES identity.source_lifecycle_policy_version (tenant_id, id),
    CONSTRAINT identity_source_lifecycle_policy_rule_value_ck
        CHECK (btrim(source_value) <> ''),
    CONSTRAINT identity_source_lifecycle_policy_rule_target_ck
        CHECK (target_lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'INACTIVE', 'DECOMMISSIONED'))
);

COMMENT ON TABLE identity.source_lifecycle_policy_version IS
    'Immutable activated Identity-owned policy mapping one bounded explicit source field to lifecycle intent. Destructive absence inference is not represented here.';

COMMENT ON TABLE identity.source_lifecycle_policy_rule IS
    'Typed exact text-value lifecycle rules. ACTIVE may only auto-advance PENDING in the first implementation; source-driven reactivation is deferred.';
