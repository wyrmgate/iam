CREATE TABLE identity.source_correlation_policy_version (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    match_attribute_definition_version_id uuid NOT NULL,
    match_mapping_version_id uuid NOT NULL,
    version_number bigint NOT NULL,
    create_identity_on_no_match boolean NOT NULL,
    created_identity_type varchar(24) NULL,
    display_name_source_path varchar(512) NULL,
    state varchar(24) NOT NULL,
    created_at timestamptz NOT NULL,
    activated_at timestamptz NOT NULL,
    superseded_at timestamptz NULL,
    CONSTRAINT identity_source_correlation_policy_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_source_correlation_policy_version_uq
        UNIQUE (tenant_id, source_system_id, version_number),
    CONSTRAINT identity_source_correlation_policy_source_fk
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES identity.source_system (tenant_id, id),
    CONSTRAINT identity_source_correlation_policy_definition_fk
        FOREIGN KEY (tenant_id, match_attribute_definition_version_id)
        REFERENCES identity.attribute_definition_version (tenant_id, id),
    CONSTRAINT identity_source_correlation_policy_mapping_tuple_fk
        FOREIGN KEY (
            tenant_id,
            match_mapping_version_id,
            source_system_id,
            match_attribute_definition_version_id)
        REFERENCES identity.attribute_mapping_version (
            tenant_id,
            id,
            source_system_id,
            attribute_definition_version_id),
    CONSTRAINT identity_source_correlation_policy_version_ck CHECK (version_number > 0),
    CONSTRAINT identity_source_correlation_policy_state_ck
        CHECK (state IN ('ACTIVE', 'SUPERSEDED')),
    CONSTRAINT identity_source_correlation_policy_creation_ck CHECK (
        (
            create_identity_on_no_match
            AND created_identity_type IN ('PERSON', 'SERVICE', 'WORKLOAD')
            AND display_name_source_path IS NOT NULL
            AND btrim(display_name_source_path) <> ''
        )
        OR (
            NOT create_identity_on_no_match
            AND created_identity_type IS NULL
            AND display_name_source_path IS NULL
        )
    ),
    CONSTRAINT identity_source_correlation_policy_time_ck CHECK (
        (state = 'ACTIVE' AND superseded_at IS NULL)
        OR (
            state = 'SUPERSEDED'
            AND superseded_at IS NOT NULL
            AND superseded_at >= activated_at
        )
    )
);

CREATE UNIQUE INDEX identity_source_correlation_policy_one_active_idx
    ON identity.source_correlation_policy_version (tenant_id, source_system_id)
    WHERE state = 'ACTIVE';

CREATE INDEX identity_state_single_string_correlation_lookup_idx
    ON identity.canonical_attribute_state_value
        (tenant_id, attribute_definition_version_id, value_string, state_id)
    WHERE data_type = 'STRING'
      AND cardinality = 'SINGLE'
      AND value_ordinal = 0;

COMMENT ON TABLE identity.source_correlation_policy_version IS
    'Immutable activated Identity-owned policy for deterministic source correlation. The first version uses one exact STRING/SINGLE canonical key; optional no-match creation produces a PENDING Identity.';
