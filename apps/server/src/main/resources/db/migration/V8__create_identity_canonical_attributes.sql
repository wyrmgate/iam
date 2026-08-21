CREATE TABLE identity.canonical_schema_version (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    version_number bigint NOT NULL,
    state varchar(24) NOT NULL,
    created_at timestamptz NOT NULL,
    activated_at timestamptz NULL,
    superseded_at timestamptz NULL,
    CONSTRAINT identity_canonical_schema_version_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT identity_canonical_schema_version_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_canonical_schema_version_number_uq UNIQUE (tenant_id, version_number),
    CONSTRAINT identity_canonical_schema_version_number_ck CHECK (version_number > 0),
    CONSTRAINT identity_canonical_schema_version_state_ck
        CHECK (state IN ('DRAFT', 'ACTIVE', 'SUPERSEDED')),
    CONSTRAINT identity_canonical_schema_version_time_ck CHECK (
        (state = 'DRAFT' AND activated_at IS NULL AND superseded_at IS NULL)
        OR (state = 'ACTIVE' AND activated_at IS NOT NULL AND superseded_at IS NULL)
        OR (state = 'SUPERSEDED' AND activated_at IS NOT NULL
            AND superseded_at IS NOT NULL AND superseded_at >= activated_at)
    )
);

CREATE UNIQUE INDEX identity_canonical_schema_one_active_idx
    ON identity.canonical_schema_version (tenant_id)
    WHERE state = 'ACTIVE';

CREATE TABLE identity.attribute_definition (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    canonical_key varchar(256) NOT NULL,
    subject_type varchar(32) NOT NULL,
    lifecycle_state varchar(24) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT identity_attribute_definition_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT identity_attribute_definition_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_attribute_definition_key_uq UNIQUE (tenant_id, canonical_key),
    CONSTRAINT identity_attribute_definition_key_ck CHECK (btrim(canonical_key) <> ''),
    CONSTRAINT identity_attribute_definition_subject_ck CHECK (subject_type = 'IDENTITY'),
    CONSTRAINT identity_attribute_definition_lifecycle_ck
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT identity_attribute_definition_revision_ck CHECK (revision > 0),
    CONSTRAINT identity_attribute_definition_time_ck CHECK (updated_at >= created_at)
);

CREATE TABLE identity.attribute_definition_version (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    schema_version_id uuid NOT NULL,
    attribute_definition_id uuid NOT NULL,
    data_type varchar(24) NOT NULL,
    cardinality varchar(16) NOT NULL,
    classification varchar(64) NOT NULL,
    queryable boolean NOT NULL,
    searchable boolean NOT NULL,
    policy_addressable boolean NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT identity_attribute_definition_version_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_attribute_definition_version_type_ref_uq
        UNIQUE (tenant_id, id, data_type, cardinality),
    CONSTRAINT identity_attribute_definition_version_schema_definition_uq
        UNIQUE (tenant_id, schema_version_id, attribute_definition_id),
    CONSTRAINT identity_attribute_definition_version_schema_fk
        FOREIGN KEY (tenant_id, schema_version_id)
        REFERENCES identity.canonical_schema_version (tenant_id, id),
    CONSTRAINT identity_attribute_definition_version_definition_fk
        FOREIGN KEY (tenant_id, attribute_definition_id)
        REFERENCES identity.attribute_definition (tenant_id, id),
    CONSTRAINT identity_attribute_definition_version_type_ck
        CHECK (data_type IN ('STRING','BOOLEAN','INTEGER','DECIMAL','DATE','DATETIME','ENUM')),
    CONSTRAINT identity_attribute_definition_version_cardinality_ck
        CHECK (cardinality IN ('SINGLE','MULTI')),
    CONSTRAINT identity_attribute_definition_version_classification_ck
        CHECK (btrim(classification) <> '')
);

CREATE TABLE identity.attribute_mapping_version (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    attribute_definition_version_id uuid NOT NULL,
    version_number bigint NOT NULL,
    source_path varchar(512) NOT NULL,
    state varchar(24) NOT NULL,
    created_at timestamptz NOT NULL,
    activated_at timestamptz NULL,
    superseded_at timestamptz NULL,
    CONSTRAINT identity_attribute_mapping_version_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_attribute_mapping_version_number_uq
        UNIQUE (tenant_id, source_system_id, attribute_definition_version_id, version_number),
    CONSTRAINT identity_attribute_mapping_version_source_fk
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES identity.source_system (tenant_id, id),
    CONSTRAINT identity_attribute_mapping_version_definition_fk
        FOREIGN KEY (tenant_id, attribute_definition_version_id)
        REFERENCES identity.attribute_definition_version (tenant_id, id),
    CONSTRAINT identity_attribute_mapping_version_number_ck CHECK (version_number > 0),
    CONSTRAINT identity_attribute_mapping_version_source_path_ck CHECK (btrim(source_path) <> ''),
    CONSTRAINT identity_attribute_mapping_version_state_ck
        CHECK (state IN ('ACTIVE','SUPERSEDED')),
    CONSTRAINT identity_attribute_mapping_version_time_ck CHECK (
        (state = 'ACTIVE' AND activated_at IS NOT NULL AND superseded_at IS NULL)
        OR (state = 'SUPERSEDED' AND activated_at IS NOT NULL
            AND superseded_at IS NOT NULL AND superseded_at >= activated_at)
    )
);

CREATE UNIQUE INDEX identity_attribute_mapping_one_active_idx
    ON identity.attribute_mapping_version
        (tenant_id, source_system_id, attribute_definition_version_id)
    WHERE state = 'ACTIVE';

CREATE TABLE identity.attribute_authority_rule_version (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    attribute_definition_version_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    version_number bigint NOT NULL,
    priority integer NOT NULL,
    state varchar(24) NOT NULL,
    created_at timestamptz NOT NULL,
    activated_at timestamptz NULL,
    superseded_at timestamptz NULL,
    CONSTRAINT identity_attribute_authority_rule_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_attribute_authority_rule_number_uq
        UNIQUE (tenant_id, attribute_definition_version_id, source_system_id, version_number),
    CONSTRAINT identity_attribute_authority_rule_definition_fk
        FOREIGN KEY (tenant_id, attribute_definition_version_id)
        REFERENCES identity.attribute_definition_version (tenant_id, id),
    CONSTRAINT identity_attribute_authority_rule_source_fk
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES identity.source_system (tenant_id, id),
    CONSTRAINT identity_attribute_authority_rule_number_ck CHECK (version_number > 0),
    CONSTRAINT identity_attribute_authority_rule_priority_ck CHECK (priority >= 0),
    CONSTRAINT identity_attribute_authority_rule_state_ck
        CHECK (state IN ('ACTIVE','SUPERSEDED')),
    CONSTRAINT identity_attribute_authority_rule_time_ck CHECK (
        (state = 'ACTIVE' AND activated_at IS NOT NULL AND superseded_at IS NULL)
        OR (state = 'SUPERSEDED' AND activated_at IS NOT NULL
            AND superseded_at IS NOT NULL AND superseded_at >= activated_at)
    )
);

CREATE UNIQUE INDEX identity_attribute_authority_one_active_idx
    ON identity.attribute_authority_rule_version
        (tenant_id, attribute_definition_version_id, source_system_id)
    WHERE state = 'ACTIVE';

CREATE TABLE identity.canonical_attribute_candidate (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    attribute_definition_version_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    source_record_id uuid NOT NULL,
    mapping_version_id uuid NOT NULL,
    source_path varchar(512) NOT NULL,
    candidate_revision bigint NOT NULL DEFAULT 1,
    source_updated_at timestamptz NULL,
    observed_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT identity_canonical_attribute_candidate_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_canonical_attribute_candidate_source_uq
        UNIQUE (tenant_id, identity_id, attribute_definition_version_id, source_record_id, mapping_version_id),
    CONSTRAINT identity_canonical_attribute_candidate_identity_fk
        FOREIGN KEY (tenant_id, identity_id)
        REFERENCES identity.identity (tenant_id, id),
    CONSTRAINT identity_canonical_attribute_candidate_definition_fk
        FOREIGN KEY (tenant_id, attribute_definition_version_id)
        REFERENCES identity.attribute_definition_version (tenant_id, id),
    CONSTRAINT identity_canonical_attribute_candidate_source_fk
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES identity.source_system (tenant_id, id),
    CONSTRAINT identity_canonical_attribute_candidate_source_record_fk
        FOREIGN KEY (tenant_id, source_record_id)
        REFERENCES identity.source_record (tenant_id, id),
    CONSTRAINT identity_canonical_attribute_candidate_mapping_fk
        FOREIGN KEY (tenant_id, mapping_version_id)
        REFERENCES identity.attribute_mapping_version (tenant_id, id),
    CONSTRAINT identity_canonical_attribute_candidate_path_ck CHECK (btrim(source_path) <> ''),
    CONSTRAINT identity_canonical_attribute_candidate_revision_ck CHECK (candidate_revision > 0),
    CONSTRAINT identity_canonical_attribute_candidate_time_ck CHECK (updated_at >= observed_at)
);

CREATE TABLE identity.canonical_attribute_candidate_value (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    candidate_id uuid NOT NULL,
    attribute_definition_version_id uuid NOT NULL,
    data_type varchar(24) NOT NULL,
    cardinality varchar(16) NOT NULL,
    value_ordinal integer NOT NULL,
    value_string text NULL,
    value_boolean boolean NULL,
    value_integer bigint NULL,
    value_decimal numeric(38,12) NULL,
    value_date date NULL,
    value_datetime timestamptz NULL,
    value_enum_key varchar(256) NULL,
    CONSTRAINT identity_candidate_value_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_candidate_value_ordinal_uq UNIQUE (tenant_id, candidate_id, value_ordinal),
    CONSTRAINT identity_candidate_value_candidate_fk
        FOREIGN KEY (tenant_id, candidate_id)
        REFERENCES identity.canonical_attribute_candidate (tenant_id, id),
    CONSTRAINT identity_candidate_value_definition_type_fk
        FOREIGN KEY (tenant_id, attribute_definition_version_id, data_type, cardinality)
        REFERENCES identity.attribute_definition_version (tenant_id, id, data_type, cardinality),
    CONSTRAINT identity_candidate_value_ordinal_ck CHECK (value_ordinal >= 0),
    CONSTRAINT identity_candidate_value_shape_ck CHECK (
        (data_type = 'STRING' AND value_string IS NOT NULL AND num_nonnulls(value_boolean,value_integer,value_decimal,value_date,value_datetime,value_enum_key)=0)
        OR (data_type = 'BOOLEAN' AND value_boolean IS NOT NULL AND num_nonnulls(value_string,value_integer,value_decimal,value_date,value_datetime,value_enum_key)=0)
        OR (data_type = 'INTEGER' AND value_integer IS NOT NULL AND num_nonnulls(value_string,value_boolean,value_decimal,value_date,value_datetime,value_enum_key)=0)
        OR (data_type = 'DECIMAL' AND value_decimal IS NOT NULL AND num_nonnulls(value_string,value_boolean,value_integer,value_date,value_datetime,value_enum_key)=0)
        OR (data_type = 'DATE' AND value_date IS NOT NULL AND num_nonnulls(value_string,value_boolean,value_integer,value_decimal,value_datetime,value_enum_key)=0)
        OR (data_type = 'DATETIME' AND value_datetime IS NOT NULL AND num_nonnulls(value_string,value_boolean,value_integer,value_decimal,value_date,value_enum_key)=0)
        OR (data_type = 'ENUM' AND value_enum_key IS NOT NULL AND num_nonnulls(value_string,value_boolean,value_integer,value_decimal,value_date,value_datetime)=0)
    )
);

CREATE TABLE identity.canonical_attribute_state (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    attribute_definition_id uuid NOT NULL,
    attribute_definition_version_id uuid NOT NULL,
    resolution_status varchar(24) NOT NULL,
    selected_candidate_id uuid NULL,
    authority_rule_version_id uuid NULL,
    value_revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT identity_canonical_attribute_state_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_canonical_attribute_state_identity_definition_uq
        UNIQUE (tenant_id, identity_id, attribute_definition_id),
    CONSTRAINT identity_canonical_attribute_state_identity_fk
        FOREIGN KEY (tenant_id, identity_id)
        REFERENCES identity.identity (tenant_id, id),
    CONSTRAINT identity_canonical_attribute_state_definition_fk
        FOREIGN KEY (tenant_id, attribute_definition_id)
        REFERENCES identity.attribute_definition (tenant_id, id),
    CONSTRAINT identity_canonical_attribute_state_definition_version_fk
        FOREIGN KEY (tenant_id, attribute_definition_version_id)
        REFERENCES identity.attribute_definition_version (tenant_id, id),
    CONSTRAINT identity_canonical_attribute_state_candidate_fk
        FOREIGN KEY (tenant_id, selected_candidate_id)
        REFERENCES identity.canonical_attribute_candidate (tenant_id, id),
    CONSTRAINT identity_canonical_attribute_state_authority_fk
        FOREIGN KEY (tenant_id, authority_rule_version_id)
        REFERENCES identity.attribute_authority_rule_version (tenant_id, id),
    CONSTRAINT identity_canonical_attribute_state_status_ck
        CHECK (resolution_status IN ('RESOLVED','OVERRIDDEN','CONFLICT','UNRESOLVED','NO_VALUE')),
    CONSTRAINT identity_canonical_attribute_state_revision_ck CHECK (value_revision > 0),
    CONSTRAINT identity_canonical_attribute_state_time_ck CHECK (updated_at >= created_at)
);

CREATE TABLE identity.canonical_attribute_state_value (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    state_id uuid NOT NULL,
    attribute_definition_version_id uuid NOT NULL,
    data_type varchar(24) NOT NULL,
    cardinality varchar(16) NOT NULL,
    value_ordinal integer NOT NULL,
    value_string text NULL,
    value_boolean boolean NULL,
    value_integer bigint NULL,
    value_decimal numeric(38,12) NULL,
    value_date date NULL,
    value_datetime timestamptz NULL,
    value_enum_key varchar(256) NULL,
    CONSTRAINT identity_state_value_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_state_value_ordinal_uq UNIQUE (tenant_id, state_id, value_ordinal),
    CONSTRAINT identity_state_value_state_fk
        FOREIGN KEY (tenant_id, state_id)
        REFERENCES identity.canonical_attribute_state (tenant_id, id),
    CONSTRAINT identity_state_value_definition_type_fk
        FOREIGN KEY (tenant_id, attribute_definition_version_id, data_type, cardinality)
        REFERENCES identity.attribute_definition_version (tenant_id, id, data_type, cardinality),
    CONSTRAINT identity_state_value_ordinal_ck CHECK (value_ordinal >= 0),
    CONSTRAINT identity_state_value_shape_ck CHECK (
        (data_type = 'STRING' AND value_string IS NOT NULL AND num_nonnulls(value_boolean,value_integer,value_decimal,value_date,value_datetime,value_enum_key)=0)
        OR (data_type = 'BOOLEAN' AND value_boolean IS NOT NULL AND num_nonnulls(value_string,value_integer,value_decimal,value_date,value_datetime,value_enum_key)=0)
        OR (data_type = 'INTEGER' AND value_integer IS NOT NULL AND num_nonnulls(value_string,value_boolean,value_decimal,value_date,value_datetime,value_enum_key)=0)
        OR (data_type = 'DECIMAL' AND value_decimal IS NOT NULL AND num_nonnulls(value_string,value_boolean,value_integer,value_date,value_datetime,value_enum_key)=0)
        OR (data_type = 'DATE' AND value_date IS NOT NULL AND num_nonnulls(value_string,value_boolean,value_integer,value_decimal,value_datetime,value_enum_key)=0)
        OR (data_type = 'DATETIME' AND value_datetime IS NOT NULL AND num_nonnulls(value_string,value_boolean,value_integer,value_decimal,value_date,value_enum_key)=0)
        OR (data_type = 'ENUM' AND value_enum_key IS NOT NULL AND num_nonnulls(value_string,value_boolean,value_integer,value_decimal,value_date,value_datetime)=0)
    )
);

CREATE TABLE identity.canonical_attribute_override (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    attribute_definition_id uuid NOT NULL,
    attribute_definition_version_id uuid NOT NULL,
    state varchar(24) NOT NULL,
    reason varchar(1024) NOT NULL,
    valid_from timestamptz NULL,
    valid_until timestamptz NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    superseded_at timestamptz NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid NULL,
    CONSTRAINT identity_canonical_attribute_override_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_canonical_attribute_override_identity_fk
        FOREIGN KEY (tenant_id, identity_id)
        REFERENCES identity.identity (tenant_id, id),
    CONSTRAINT identity_canonical_attribute_override_definition_fk
        FOREIGN KEY (tenant_id, attribute_definition_id)
        REFERENCES identity.attribute_definition (tenant_id, id),
    CONSTRAINT identity_canonical_attribute_override_definition_version_fk
        FOREIGN KEY (tenant_id, attribute_definition_version_id)
        REFERENCES identity.attribute_definition_version (tenant_id, id),
    CONSTRAINT identity_canonical_attribute_override_state_ck CHECK (state IN ('ACTIVE','SUPERSEDED')),
    CONSTRAINT identity_canonical_attribute_override_reason_ck CHECK (btrim(reason) <> ''),
    CONSTRAINT identity_canonical_attribute_override_validity_ck
        CHECK (valid_until IS NULL OR valid_from IS NULL OR valid_until > valid_from),
    CONSTRAINT identity_canonical_attribute_override_revision_ck CHECK (revision > 0),
    CONSTRAINT identity_canonical_attribute_override_superseded_ck CHECK (
        (state = 'ACTIVE' AND superseded_at IS NULL)
        OR (state = 'SUPERSEDED' AND superseded_at IS NOT NULL AND superseded_at >= created_at)
    )
);

CREATE UNIQUE INDEX identity_canonical_attribute_override_one_active_idx
    ON identity.canonical_attribute_override (tenant_id, identity_id, attribute_definition_id)
    WHERE state = 'ACTIVE';

CREATE TABLE identity.canonical_attribute_override_value (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    override_id uuid NOT NULL,
    attribute_definition_version_id uuid NOT NULL,
    data_type varchar(24) NOT NULL,
    cardinality varchar(16) NOT NULL,
    value_ordinal integer NOT NULL,
    value_string text NULL,
    value_boolean boolean NULL,
    value_integer bigint NULL,
    value_decimal numeric(38,12) NULL,
    value_date date NULL,
    value_datetime timestamptz NULL,
    value_enum_key varchar(256) NULL,
    CONSTRAINT identity_override_value_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_override_value_ordinal_uq UNIQUE (tenant_id, override_id, value_ordinal),
    CONSTRAINT identity_override_value_override_fk
        FOREIGN KEY (tenant_id, override_id)
        REFERENCES identity.canonical_attribute_override (tenant_id, id),
    CONSTRAINT identity_override_value_definition_type_fk
        FOREIGN KEY (tenant_id, attribute_definition_version_id, data_type, cardinality)
        REFERENCES identity.attribute_definition_version (tenant_id, id, data_type, cardinality),
    CONSTRAINT identity_override_value_ordinal_ck CHECK (value_ordinal >= 0),
    CONSTRAINT identity_override_value_shape_ck CHECK (
        (data_type = 'STRING' AND value_string IS NOT NULL AND num_nonnulls(value_boolean,value_integer,value_decimal,value_date,value_datetime,value_enum_key)=0)
        OR (data_type = 'BOOLEAN' AND value_boolean IS NOT NULL AND num_nonnulls(value_string,value_integer,value_decimal,value_date,value_datetime,value_enum_key)=0)
        OR (data_type = 'INTEGER' AND value_integer IS NOT NULL AND num_nonnulls(value_string,value_boolean,value_decimal,value_date,value_datetime,value_enum_key)=0)
        OR (data_type = 'DECIMAL' AND value_decimal IS NOT NULL AND num_nonnulls(value_string,value_boolean,value_integer,value_date,value_datetime,value_enum_key)=0)
        OR (data_type = 'DATE' AND value_date IS NOT NULL AND num_nonnulls(value_string,value_boolean,value_integer,value_decimal,value_datetime,value_enum_key)=0)
        OR (data_type = 'DATETIME' AND value_datetime IS NOT NULL AND num_nonnulls(value_string,value_boolean,value_integer,value_decimal,value_date,value_enum_key)=0)
        OR (data_type = 'ENUM' AND value_enum_key IS NOT NULL AND num_nonnulls(value_string,value_boolean,value_integer,value_decimal,value_date,value_datetime)=0)
    )
);

CREATE INDEX identity_candidate_resolution_idx
    ON identity.canonical_attribute_candidate
        (tenant_id, identity_id, attribute_definition_version_id, source_system_id, id);
CREATE INDEX identity_state_identity_idx
    ON identity.canonical_attribute_state (tenant_id, identity_id, attribute_definition_id);
CREATE INDEX identity_override_resolution_idx
    ON identity.canonical_attribute_override
        (tenant_id, identity_id, attribute_definition_id, state, valid_from, valid_until, id);

COMMENT ON TABLE identity.attribute_definition IS
    'Stable governed canonical attribute identity/key. Versioned semantic shape is stored in attribute_definition_version.';
COMMENT ON TABLE identity.attribute_definition_version IS
    'Typed canonical attribute semantics attached to one canonical schema version; activated schema content is immutable by application contract.';
COMMENT ON TABLE identity.canonical_attribute_candidate IS
    'Normalized candidate derived from source observation with explicit mapping provenance; never canonical state by itself.';
COMMENT ON TABLE identity.canonical_attribute_state IS
    'Current authoritative canonical attribute resolution state. CONFLICT may retain a previously trusted value while exposing degraded resolution status.';
COMMENT ON TABLE identity.canonical_attribute_override IS
    'Explicit governed override history. Overrides supersede resolution while effective but never rewrite source observations or candidates.';
