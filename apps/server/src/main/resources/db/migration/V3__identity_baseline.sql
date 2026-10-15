-- Identity capability current-state persistence baseline.
-- Generated once from the verified pre-rebaseline schema, then maintained as reviewed source.

CREATE TABLE identity.attribute_authority_rule_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    attribute_definition_version_id uuid CONSTRAINT attribute_authority_rule_ve_attribute_definition_versi_not_null NOT NULL,
    source_system_id uuid NOT NULL,
    version_number bigint NOT NULL,
    priority integer NOT NULL,
    state character varying(24) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    activated_at timestamp with time zone,
    superseded_at timestamp with time zone,
    CONSTRAINT identity_attribute_authority_rule_number_ck CHECK ((version_number > 0)),
    CONSTRAINT identity_attribute_authority_rule_priority_ck CHECK ((priority >= 0)),
    CONSTRAINT identity_attribute_authority_rule_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUPERSEDED'::character varying])::text[]))),
    CONSTRAINT identity_attribute_authority_rule_time_ck CHECK (((((state)::text = 'ACTIVE'::text) AND (activated_at IS NOT NULL) AND (superseded_at IS NULL)) OR (((state)::text = 'SUPERSEDED'::text) AND (activated_at IS NOT NULL) AND (superseded_at IS NOT NULL) AND (superseded_at >= activated_at))))
);

CREATE TABLE identity.attribute_definition (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    canonical_key character varying(256) NOT NULL,
    subject_type character varying(32) NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_attribute_definition_key_ck CHECK ((btrim((canonical_key)::text) <> ''::text)),
    CONSTRAINT identity_attribute_definition_lifecycle_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'RETIRED'::character varying])::text[]))),
    CONSTRAINT identity_attribute_definition_revision_ck CHECK ((revision > 0)),
    CONSTRAINT identity_attribute_definition_subject_ck CHECK (((subject_type)::text = 'IDENTITY'::text)),
    CONSTRAINT identity_attribute_definition_time_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE identity.attribute_definition IS 'Stable governed canonical attribute identity/key. Versioned semantic shape is stored in attribute_definition_version.';

CREATE TABLE identity.attribute_definition_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    schema_version_id uuid NOT NULL,
    attribute_definition_id uuid NOT NULL,
    data_type character varying(24) NOT NULL,
    cardinality character varying(16) NOT NULL,
    classification character varying(64) NOT NULL,
    queryable boolean NOT NULL,
    searchable boolean NOT NULL,
    policy_addressable boolean NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_attribute_definition_version_cardinality_ck CHECK (((cardinality)::text = ANY ((ARRAY['SINGLE'::character varying, 'MULTI'::character varying])::text[]))),
    CONSTRAINT identity_attribute_definition_version_classification_ck CHECK ((btrim((classification)::text) <> ''::text)),
    CONSTRAINT identity_attribute_definition_version_type_ck CHECK (((data_type)::text = ANY ((ARRAY['STRING'::character varying, 'BOOLEAN'::character varying, 'INTEGER'::character varying, 'DECIMAL'::character varying, 'DATE'::character varying, 'DATETIME'::character varying, 'ENUM'::character varying])::text[])))
);

COMMENT ON TABLE identity.attribute_definition_version IS 'Typed canonical attribute semantics attached to one canonical schema version; activated schema content is immutable by application contract.';

CREATE TABLE identity.attribute_mapping_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    attribute_definition_version_id uuid CONSTRAINT attribute_mapping_version_attribute_definition_version_not_null NOT NULL,
    version_number bigint NOT NULL,
    source_path character varying(512) NOT NULL,
    state character varying(24) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    activated_at timestamp with time zone,
    superseded_at timestamp with time zone,
    CONSTRAINT identity_attribute_mapping_version_number_ck CHECK ((version_number > 0)),
    CONSTRAINT identity_attribute_mapping_version_source_path_ck CHECK ((btrim((source_path)::text) <> ''::text)),
    CONSTRAINT identity_attribute_mapping_version_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUPERSEDED'::character varying])::text[]))),
    CONSTRAINT identity_attribute_mapping_version_time_ck CHECK (((((state)::text = 'ACTIVE'::text) AND (activated_at IS NOT NULL) AND (superseded_at IS NULL)) OR (((state)::text = 'SUPERSEDED'::text) AND (activated_at IS NOT NULL) AND (superseded_at IS NOT NULL) AND (superseded_at >= activated_at))))
);

CREATE TABLE identity.canonical_attribute_candidate (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    attribute_definition_version_id uuid CONSTRAINT canonical_attribute_candida_attribute_definition_versi_not_null NOT NULL,
    source_system_id uuid NOT NULL,
    source_record_id uuid NOT NULL,
    mapping_version_id uuid NOT NULL,
    source_path character varying(512) NOT NULL,
    candidate_revision bigint DEFAULT 1 NOT NULL,
    source_updated_at timestamp with time zone,
    observed_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_canonical_attribute_candidate_path_ck CHECK ((btrim((source_path)::text) <> ''::text)),
    CONSTRAINT identity_canonical_attribute_candidate_revision_ck CHECK ((candidate_revision > 0)),
    CONSTRAINT identity_canonical_attribute_candidate_time_ck CHECK ((updated_at >= observed_at))
);

COMMENT ON TABLE identity.canonical_attribute_candidate IS 'Normalized candidate derived from source observation with explicit mapping provenance; never canonical state by itself.';

CREATE TABLE identity.canonical_attribute_candidate_value (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    candidate_id uuid NOT NULL,
    attribute_definition_version_id uuid CONSTRAINT canonical_attribute_candid_attribute_definition_versi_not_null1 NOT NULL,
    data_type character varying(24) NOT NULL,
    cardinality character varying(16) NOT NULL,
    value_ordinal integer NOT NULL,
    value_string text,
    value_boolean boolean,
    value_integer bigint,
    value_decimal numeric(38,12),
    value_date date,
    value_datetime timestamp with time zone,
    value_enum_key character varying(256),
    CONSTRAINT identity_candidate_value_ordinal_ck CHECK ((value_ordinal >= 0)),
    CONSTRAINT identity_candidate_value_shape_ck CHECK (((((data_type)::text = 'STRING'::text) AND (value_string IS NOT NULL) AND (num_nonnulls(value_boolean, value_integer, value_decimal, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'BOOLEAN'::text) AND (value_boolean IS NOT NULL) AND (num_nonnulls(value_string, value_integer, value_decimal, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'INTEGER'::text) AND (value_integer IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_decimal, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'DECIMAL'::text) AND (value_decimal IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'DATE'::text) AND (value_date IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'DATETIME'::text) AND (value_datetime IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_date, value_enum_key) = 0)) OR (((data_type)::text = 'ENUM'::text) AND (value_enum_key IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_date, value_datetime) = 0))))
);

CREATE TABLE identity.canonical_attribute_override (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    attribute_definition_id uuid NOT NULL,
    attribute_definition_version_id uuid CONSTRAINT canonical_attribute_overrid_attribute_definition_versi_not_null NOT NULL,
    state character varying(24) NOT NULL,
    reason character varying(1024) NOT NULL,
    valid_from timestamp with time zone,
    valid_until timestamp with time zone,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    superseded_at timestamp with time zone,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    CONSTRAINT identity_canonical_attribute_override_reason_ck CHECK ((btrim((reason)::text) <> ''::text)),
    CONSTRAINT identity_canonical_attribute_override_revision_ck CHECK ((revision > 0)),
    CONSTRAINT identity_canonical_attribute_override_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUPERSEDED'::character varying])::text[]))),
    CONSTRAINT identity_canonical_attribute_override_superseded_ck CHECK (((((state)::text = 'ACTIVE'::text) AND (superseded_at IS NULL)) OR (((state)::text = 'SUPERSEDED'::text) AND (superseded_at IS NOT NULL) AND (superseded_at >= created_at)))),
    CONSTRAINT identity_canonical_attribute_override_validity_ck CHECK (((valid_until IS NULL) OR (valid_from IS NULL) OR (valid_until > valid_from)))
);

COMMENT ON TABLE identity.canonical_attribute_override IS 'Explicit governed override history. Overrides supersede resolution while effective but never rewrite source observations or candidates.';

CREATE TABLE identity.canonical_attribute_override_value (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    override_id uuid NOT NULL,
    attribute_definition_version_id uuid CONSTRAINT canonical_attribute_overri_attribute_definition_versi_not_null1 NOT NULL,
    data_type character varying(24) NOT NULL,
    cardinality character varying(16) NOT NULL,
    value_ordinal integer NOT NULL,
    value_string text,
    value_boolean boolean,
    value_integer bigint,
    value_decimal numeric(38,12),
    value_date date,
    value_datetime timestamp with time zone,
    value_enum_key character varying(256),
    CONSTRAINT identity_override_value_ordinal_ck CHECK ((value_ordinal >= 0)),
    CONSTRAINT identity_override_value_shape_ck CHECK (((((data_type)::text = 'STRING'::text) AND (value_string IS NOT NULL) AND (num_nonnulls(value_boolean, value_integer, value_decimal, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'BOOLEAN'::text) AND (value_boolean IS NOT NULL) AND (num_nonnulls(value_string, value_integer, value_decimal, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'INTEGER'::text) AND (value_integer IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_decimal, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'DECIMAL'::text) AND (value_decimal IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'DATE'::text) AND (value_date IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'DATETIME'::text) AND (value_datetime IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_date, value_enum_key) = 0)) OR (((data_type)::text = 'ENUM'::text) AND (value_enum_key IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_date, value_datetime) = 0))))
);

CREATE TABLE identity.canonical_attribute_state (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    attribute_definition_id uuid NOT NULL,
    attribute_definition_version_id uuid CONSTRAINT canonical_attribute_state_attribute_definition_version_not_null NOT NULL,
    resolution_status character varying(24) NOT NULL,
    selected_candidate_id uuid,
    authority_rule_version_id uuid,
    value_revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_canonical_attribute_state_revision_ck CHECK ((value_revision > 0)),
    CONSTRAINT identity_canonical_attribute_state_status_ck CHECK (((resolution_status)::text = ANY ((ARRAY['RESOLVED'::character varying, 'OVERRIDDEN'::character varying, 'CONFLICT'::character varying, 'UNRESOLVED'::character varying, 'NO_VALUE'::character varying])::text[]))),
    CONSTRAINT identity_canonical_attribute_state_time_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE identity.canonical_attribute_state IS 'Current authoritative canonical attribute resolution state. CONFLICT may retain a previously trusted value while exposing degraded resolution status.';

CREATE TABLE identity.canonical_attribute_state_value (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    state_id uuid NOT NULL,
    attribute_definition_version_id uuid CONSTRAINT canonical_attribute_state_v_attribute_definition_versi_not_null NOT NULL,
    data_type character varying(24) NOT NULL,
    cardinality character varying(16) NOT NULL,
    value_ordinal integer NOT NULL,
    value_string text,
    value_boolean boolean,
    value_integer bigint,
    value_decimal numeric(38,12),
    value_date date,
    value_datetime timestamp with time zone,
    value_enum_key character varying(256),
    CONSTRAINT identity_state_value_ordinal_ck CHECK ((value_ordinal >= 0)),
    CONSTRAINT identity_state_value_shape_ck CHECK (((((data_type)::text = 'STRING'::text) AND (value_string IS NOT NULL) AND (num_nonnulls(value_boolean, value_integer, value_decimal, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'BOOLEAN'::text) AND (value_boolean IS NOT NULL) AND (num_nonnulls(value_string, value_integer, value_decimal, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'INTEGER'::text) AND (value_integer IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_decimal, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'DECIMAL'::text) AND (value_decimal IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_date, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'DATE'::text) AND (value_date IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_datetime, value_enum_key) = 0)) OR (((data_type)::text = 'DATETIME'::text) AND (value_datetime IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_date, value_enum_key) = 0)) OR (((data_type)::text = 'ENUM'::text) AND (value_enum_key IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_date, value_datetime) = 0))))
);

CREATE TABLE identity.canonical_schema_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    version_number bigint NOT NULL,
    state character varying(24) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    activated_at timestamp with time zone,
    superseded_at timestamp with time zone,
    CONSTRAINT identity_canonical_schema_version_number_ck CHECK ((version_number > 0)),
    CONSTRAINT identity_canonical_schema_version_state_ck CHECK (((state)::text = ANY ((ARRAY['DRAFT'::character varying, 'ACTIVE'::character varying, 'SUPERSEDED'::character varying])::text[]))),
    CONSTRAINT identity_canonical_schema_version_time_ck CHECK (((((state)::text = 'DRAFT'::text) AND (activated_at IS NULL) AND (superseded_at IS NULL)) OR (((state)::text = 'ACTIVE'::text) AND (activated_at IS NOT NULL) AND (superseded_at IS NULL)) OR (((state)::text = 'SUPERSEDED'::text) AND (activated_at IS NOT NULL) AND (superseded_at IS NOT NULL) AND (superseded_at >= activated_at))))
);

CREATE TABLE identity.identity (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_type character varying(16) NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    display_name character varying(512) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_identity_display_name_ck CHECK ((btrim((display_name)::text) <> ''::text)),
    CONSTRAINT identity_identity_lifecycle_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, 'SUSPENDED'::character varying, 'INACTIVE'::character varying, 'DECOMMISSIONED'::character varying])::text[]))),
    CONSTRAINT identity_identity_revision_ck CHECK ((revision > 0)),
    CONSTRAINT identity_identity_timestamp_order_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT identity_identity_type_ck CHECK (((identity_type)::text = ANY ((ARRAY['PERSON'::character varying, 'SERVICE'::character varying, 'WORKLOAD'::character varying])::text[])))
);

COMMENT ON TABLE identity.identity IS 'Identity-owned authoritative governed subject. Identity type is stable for normal operations and determines exactly one compatible typed profile.';

CREATE TABLE identity.identity_link (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_record_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    link_state character varying(24) NOT NULL,
    linked_at timestamp with time zone NOT NULL,
    ended_at timestamp with time zone,
    correlation_reason character varying(512) NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    CONSTRAINT identity_identity_link_end_ck CHECK (((((link_state)::text = 'ACCEPTED'::text) AND (ended_at IS NULL)) OR (((link_state)::text = 'SUPERSEDED'::text) AND (ended_at IS NOT NULL) AND (ended_at >= linked_at)))),
    CONSTRAINT identity_identity_link_reason_ck CHECK ((btrim((correlation_reason)::text) <> ''::text)),
    CONSTRAINT identity_identity_link_state_ck CHECK (((link_state)::text = ANY ((ARRAY['ACCEPTED'::character varying, 'SUPERSEDED'::character varying])::text[])))
);

COMMENT ON TABLE identity.identity_link IS 'Authoritative correlation history. At most one active accepted canonical Identity link exists for a SourceRecord.';

CREATE TABLE identity.identity_merge_operation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    survivor_identity_id uuid NOT NULL,
    absorbed_identity_id uuid NOT NULL,
    survivor_revision_before bigint NOT NULL,
    absorbed_revision_before bigint NOT NULL,
    moved_link_count integer NOT NULL,
    moved_principal_count integer NOT NULL,
    reason character varying(1024) NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    completed_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_merge_operation_count_ck CHECK (((moved_link_count >= 0) AND (moved_principal_count >= 0))),
    CONSTRAINT identity_merge_operation_distinct_ck CHECK ((survivor_identity_id <> absorbed_identity_id)),
    CONSTRAINT identity_merge_operation_reason_ck CHECK ((btrim((reason)::text) <> ''::text)),
    CONSTRAINT identity_merge_operation_revision_ck CHECK (((survivor_revision_before > 0) AND (absorbed_revision_before > 0)))
);

COMMENT ON TABLE identity.identity_merge_operation IS 'Immutable Identity-owned evidence for explicit merge. Foreign-capability authority is not moved.';

CREATE TABLE identity.identity_split_operation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_identity_id uuid NOT NULL,
    new_identity_id uuid NOT NULL,
    source_revision_before bigint NOT NULL,
    reason character varying(1024) NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    completed_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_split_operation_distinct_ck CHECK ((source_identity_id <> new_identity_id)),
    CONSTRAINT identity_split_operation_reason_ck CHECK ((btrim((reason)::text) <> ''::text)),
    CONSTRAINT identity_split_operation_revision_ck CHECK ((source_revision_before > 0))
);

COMMENT ON TABLE identity.identity_split_operation IS 'Immutable Identity-owned evidence for explicit split into a new PENDING Identity.';

CREATE TABLE identity.identity_split_principal (
    tenant_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    principal_id uuid NOT NULL
);

COMMENT ON TABLE identity.identity_split_principal IS 'Immutable selected Principal relationship evidence for one split.';

CREATE TABLE identity.identity_split_source_record (
    tenant_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    source_record_id uuid NOT NULL
);

COMMENT ON TABLE identity.identity_split_source_record IS 'Immutable selected SourceRecord relationship evidence for one split.';

CREATE TABLE identity.person_profile (
    identity_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_type character varying(16) DEFAULT 'PERSON'::character varying NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_person_profile_type_ck CHECK (((identity_type)::text = 'PERSON'::text))
);

COMMENT ON TABLE identity.person_profile IS 'Typed PERSON profile boundary. Profile-specific fields are added only when governed requirements define them.';

CREATE TABLE identity.principal (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_id uuid,
    application_target_id uuid NOT NULL,
    principal_kind character varying(32) NOT NULL,
    native_principal_key character varying(512) NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_principal_kind_ck CHECK (((principal_kind)::text = 'ACCOUNT'::text)),
    CONSTRAINT identity_principal_native_key_ck CHECK ((btrim((native_principal_key)::text) <> ''::text)),
    CONSTRAINT identity_principal_revision_ck CHECK ((revision > 0)),
    CONSTRAINT identity_principal_state_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying])::text[]))),
    CONSTRAINT identity_principal_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE identity.principal IS 'Identity-owned technical representation/account on one ApplicationTarget. Provider observation does not create this authority automatically.';

CREATE TABLE identity.service_profile (
    identity_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_type character varying(16) DEFAULT 'SERVICE'::character varying NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_service_profile_type_ck CHECK (((identity_type)::text = 'SERVICE'::text))
);

COMMENT ON TABLE identity.service_profile IS 'Typed SERVICE profile boundary. Profile-specific fields are added only when governed requirements define them.';

CREATE TABLE identity.source_absence_inference (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    import_run_id uuid NOT NULL,
    policy_version_id uuid NOT NULL,
    max_inferred_transitions integer NOT NULL,
    process_state character varying(24) NOT NULL,
    after_first_observed_at timestamp with time zone,
    after_source_record_id uuid,
    processed_candidate_count bigint DEFAULT 0 NOT NULL,
    inferred_transition_count bigint DEFAULT 0 NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT identity_source_absence_inference_ceiling_ck CHECK ((max_inferred_transitions > 0)),
    CONSTRAINT identity_source_absence_inference_checkpoint_ck CHECK ((((after_first_observed_at IS NULL) AND (after_source_record_id IS NULL)) OR ((after_first_observed_at IS NOT NULL) AND (after_source_record_id IS NOT NULL)))),
    CONSTRAINT identity_source_absence_inference_counts_ck CHECK (((processed_candidate_count >= 0) AND (inferred_transition_count >= 0) AND (inferred_transition_count <= max_inferred_transitions))),
    CONSTRAINT identity_source_absence_inference_revision_ck CHECK ((revision > 0)),
    CONSTRAINT identity_source_absence_inference_state_ck CHECK (((process_state)::text = ANY ((ARRAY['RUNNING'::character varying, 'COMPLETED'::character varying, 'SUPERSEDED'::character varying, 'MANUAL_REQUIRED'::character varying])::text[]))),
    CONSTRAINT identity_source_absence_inference_time_ck CHECK (((updated_at >= created_at) AND ((((process_state)::text = 'RUNNING'::text) AND (completed_at IS NULL)) OR (((process_state)::text <> 'RUNNING'::text) AND (completed_at IS NOT NULL) AND (completed_at >= created_at)))))
);

COMMENT ON TABLE identity.source_absence_inference IS 'Durable bounded Identity-owned process for trusted COMPLETE source absence inference.';

CREATE TABLE identity.source_absence_policy_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    version_number bigint NOT NULL,
    max_inferred_transitions integer NOT NULL,
    state character varying(24) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    activated_at timestamp with time zone NOT NULL,
    superseded_at timestamp with time zone,
    CONSTRAINT identity_source_absence_policy_ceiling_ck CHECK ((max_inferred_transitions > 0)),
    CONSTRAINT identity_source_absence_policy_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUPERSEDED'::character varying])::text[]))),
    CONSTRAINT identity_source_absence_policy_time_ck CHECK (((((state)::text = 'ACTIVE'::text) AND (superseded_at IS NULL)) OR (((state)::text = 'SUPERSEDED'::text) AND (superseded_at IS NOT NULL) AND (superseded_at >= activated_at)))),
    CONSTRAINT identity_source_absence_policy_version_ck CHECK ((version_number > 0))
);

COMMENT ON TABLE identity.source_absence_policy_version IS 'Versioned Identity-owned policy enabling bounded inferred absence -> INACTIVE handling for one SourceSystem.';

CREATE TABLE identity.source_absence_transition_evidence (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    source_record_id uuid NOT NULL,
    identity_link_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    import_run_id uuid NOT NULL,
    inference_id uuid NOT NULL,
    pre_identity_revision bigint CONSTRAINT source_absence_transition_eviden_pre_identity_revision_not_null NOT NULL,
    post_identity_revision bigint CONSTRAINT source_absence_transition_evide_post_identity_revision_not_null NOT NULL,
    transitioned_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_source_absence_transition_revision_ck CHECK (((pre_identity_revision > 0) AND (post_identity_revision = (pre_identity_revision + 1))))
);

COMMENT ON TABLE identity.source_absence_transition_evidence IS 'Immutable evidence that trusted COMPLETE source absence inference caused a specific Identity revision to become INACTIVE; used only to fence safe source-driven restoration.';

CREATE TABLE identity.source_correlation_policy_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    match_attribute_definition_version_id uuid CONSTRAINT source_correlation_policy_v_match_attribute_definition_not_null NOT NULL,
    match_mapping_version_id uuid CONSTRAINT source_correlation_policy_ver_match_mapping_version_id_not_null NOT NULL,
    version_number bigint NOT NULL,
    create_identity_on_no_match boolean CONSTRAINT source_correlation_policy_v_create_identity_on_no_matc_not_null NOT NULL,
    created_identity_type character varying(24),
    display_name_source_path character varying(512),
    state character varying(24) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    activated_at timestamp with time zone NOT NULL,
    superseded_at timestamp with time zone,
    CONSTRAINT identity_source_correlation_policy_creation_ck CHECK (((create_identity_on_no_match AND ((created_identity_type)::text = ANY ((ARRAY['PERSON'::character varying, 'SERVICE'::character varying, 'WORKLOAD'::character varying])::text[])) AND (display_name_source_path IS NOT NULL) AND (btrim((display_name_source_path)::text) <> ''::text)) OR ((NOT create_identity_on_no_match) AND (created_identity_type IS NULL) AND (display_name_source_path IS NULL)))),
    CONSTRAINT identity_source_correlation_policy_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUPERSEDED'::character varying])::text[]))),
    CONSTRAINT identity_source_correlation_policy_time_ck CHECK (((((state)::text = 'ACTIVE'::text) AND (superseded_at IS NULL)) OR (((state)::text = 'SUPERSEDED'::text) AND (superseded_at IS NOT NULL) AND (superseded_at >= activated_at)))),
    CONSTRAINT identity_source_correlation_policy_version_ck CHECK ((version_number > 0))
);

COMMENT ON TABLE identity.source_correlation_policy_version IS 'Immutable activated Identity-owned policy for deterministic source correlation. The first version uses one exact STRING/SINGLE canonical key; optional no-match creation produces a PENDING Identity.';

CREATE TABLE identity.source_import_run (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    run_state character varying(24) NOT NULL,
    completeness character varying(16) NOT NULL,
    started_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    checkpoint_token character varying(1024),
    partial_reason character varying(1024),
    absence_trust character varying(16) DEFAULT 'UNTRUSTED'::character varying NOT NULL,
    absence_trust_reason character varying(1024),
    CONSTRAINT identity_source_import_run_absence_trust_ck CHECK (((absence_trust)::text = ANY ((ARRAY['UNTRUSTED'::character varying, 'TRUSTED'::character varying])::text[]))),
    CONSTRAINT identity_source_import_run_absence_trust_reason_ck CHECK ((((absence_trust)::text <> 'TRUSTED'::text) OR (((completeness)::text = 'COMPLETE'::text) AND (absence_trust_reason IS NOT NULL) AND (btrim((absence_trust_reason)::text) <> ''::text)))),
    CONSTRAINT identity_source_import_run_completeness_ck CHECK (((completeness)::text = ANY ((ARRAY['COMPLETE'::character varying, 'PARTIAL'::character varying, 'UNKNOWN'::character varying])::text[]))),
    CONSTRAINT identity_source_import_run_completion_ck CHECK (((((run_state)::text = 'RUNNING'::text) AND (completed_at IS NULL)) OR (((run_state)::text = ANY ((ARRAY['COMPLETED'::character varying, 'FAILED'::character varying])::text[])) AND (completed_at IS NOT NULL)))),
    CONSTRAINT identity_source_import_run_partial_reason_ck CHECK ((((completeness)::text <> 'PARTIAL'::text) OR ((partial_reason IS NOT NULL) AND (btrim((partial_reason)::text) <> ''::text)))),
    CONSTRAINT identity_source_import_run_state_ck CHECK (((run_state)::text = ANY ((ARRAY['RUNNING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[])))
);

COMMENT ON TABLE identity.source_import_run IS 'Identity-owned source-import process provenance. Execution outcome and coverage completeness are independent semantics.';

COMMENT ON COLUMN identity.source_import_run.absence_trust IS 'Run-scoped evidence gate for destructive absence inference. COMPLETE alone is insufficient.';

CREATE TABLE identity.source_lifecycle_policy_rule (
    policy_version_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_value character varying(512) NOT NULL,
    target_lifecycle_state character varying(24) NOT NULL,
    CONSTRAINT identity_source_lifecycle_policy_rule_target_ck CHECK (((target_lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUSPENDED'::character varying, 'INACTIVE'::character varying, 'DECOMMISSIONED'::character varying])::text[]))),
    CONSTRAINT identity_source_lifecycle_policy_rule_value_ck CHECK ((btrim((source_value)::text) <> ''::text))
);

COMMENT ON TABLE identity.source_lifecycle_policy_rule IS 'Typed exact text-value lifecycle rules. ACTIVE may only auto-advance PENDING in the first implementation; source-driven reactivation is deferred.';

CREATE TABLE identity.source_lifecycle_policy_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    source_path character varying(512) NOT NULL,
    version_number bigint NOT NULL,
    state character varying(24) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    activated_at timestamp with time zone NOT NULL,
    superseded_at timestamp with time zone,
    CONSTRAINT identity_source_lifecycle_policy_path_ck CHECK ((((source_path)::text ~~ '$.%'::text) AND (length((source_path)::text) >= 3))),
    CONSTRAINT identity_source_lifecycle_policy_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUPERSEDED'::character varying])::text[]))),
    CONSTRAINT identity_source_lifecycle_policy_time_ck CHECK (((((state)::text = 'ACTIVE'::text) AND (superseded_at IS NULL)) OR (((state)::text = 'SUPERSEDED'::text) AND (superseded_at IS NOT NULL) AND (superseded_at >= activated_at)))),
    CONSTRAINT identity_source_lifecycle_policy_version_ck CHECK ((version_number > 0))
);

COMMENT ON TABLE identity.source_lifecycle_policy_version IS 'Immutable activated Identity-owned policy mapping one bounded explicit source field to lifecycle intent. Destructive absence inference is not represented here.';

CREATE TABLE identity.source_record (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    native_key character varying(1024) NOT NULL,
    observed_attributes jsonb NOT NULL,
    source_updated_at timestamp with time zone,
    first_observed_at timestamp with time zone NOT NULL,
    last_observed_at timestamp with time zone NOT NULL,
    last_import_run_id uuid NOT NULL,
    last_complete_import_run_id uuid,
    CONSTRAINT identity_source_record_attributes_ck CHECK ((jsonb_typeof(observed_attributes) = 'object'::text)),
    CONSTRAINT identity_source_record_native_key_ck CHECK ((btrim((native_key)::text) <> ''::text)),
    CONSTRAINT identity_source_record_timestamp_order_ck CHECK ((last_observed_at >= first_observed_at))
);

COMMENT ON TABLE identity.source_record IS 'Current positive normalized source observation. observed_attributes contains bounded/filtered source-native fields, not retained raw connector payload. Partial imports never erase unseen records.';

CREATE TABLE identity.source_suspension_transition_evidence (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    source_record_id uuid NOT NULL,
    identity_link_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    lifecycle_policy_version_id uuid CONSTRAINT source_suspension_transitio_lifecycle_policy_version_i_not_null NOT NULL,
    pre_identity_revision bigint CONSTRAINT source_suspension_transition_evi_pre_identity_revision_not_null NOT NULL,
    post_identity_revision bigint CONSTRAINT source_suspension_transition_ev_post_identity_revision_not_null NOT NULL,
    suspended_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_source_suspension_transition_revision_ck CHECK (((pre_identity_revision > 0) AND (post_identity_revision = (pre_identity_revision + 1))))
);

COMMENT ON TABLE identity.source_suspension_transition_evidence IS 'Immutable evidence that explicit source lifecycle policy caused a specific Identity revision to become SUSPENDED; used only to fence same-source current-revision restoration.';

CREATE TABLE identity.source_system (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    code character varying(128) NOT NULL,
    name character varying(512) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_source_system_code_ck CHECK ((btrim((code)::text) <> ''::text)),
    CONSTRAINT identity_source_system_name_ck CHECK ((btrim((name)::text) <> ''::text)),
    CONSTRAINT identity_source_system_revision_ck CHECK ((revision > 0)),
    CONSTRAINT identity_source_system_timestamp_order_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE identity.source_system IS 'Identity-owned authoritative source definition. SourceSystem retains governance meaning independently from connector implementation.';

CREATE TABLE identity.workload_profile (
    identity_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_type character varying(16) DEFAULT 'WORKLOAD'::character varying NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT identity_workload_profile_type_ck CHECK (((identity_type)::text = 'WORKLOAD'::text))
);

COMMENT ON TABLE identity.workload_profile IS 'Typed WORKLOAD profile boundary. Profile-specific fields are added only when governed requirements define them.';

ALTER TABLE ONLY identity.attribute_authority_rule_version
    ADD CONSTRAINT attribute_authority_rule_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.attribute_definition
    ADD CONSTRAINT attribute_definition_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.attribute_definition_version
    ADD CONSTRAINT attribute_definition_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.attribute_mapping_version
    ADD CONSTRAINT attribute_mapping_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT canonical_attribute_candidate_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.canonical_attribute_candidate_value
    ADD CONSTRAINT canonical_attribute_candidate_value_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.canonical_attribute_override
    ADD CONSTRAINT canonical_attribute_override_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.canonical_attribute_override_value
    ADD CONSTRAINT canonical_attribute_override_value_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT canonical_attribute_state_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.canonical_attribute_state_value
    ADD CONSTRAINT canonical_attribute_state_value_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.canonical_schema_version
    ADD CONSTRAINT canonical_schema_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.attribute_authority_rule_version
    ADD CONSTRAINT identity_attribute_authority_rule_definition_ref_uq UNIQUE (tenant_id, id, attribute_definition_version_id);

ALTER TABLE ONLY identity.attribute_authority_rule_version
    ADD CONSTRAINT identity_attribute_authority_rule_number_uq UNIQUE (tenant_id, attribute_definition_version_id, source_system_id, version_number);

ALTER TABLE ONLY identity.attribute_authority_rule_version
    ADD CONSTRAINT identity_attribute_authority_rule_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.attribute_definition
    ADD CONSTRAINT identity_attribute_definition_key_uq UNIQUE (tenant_id, canonical_key);

ALTER TABLE ONLY identity.attribute_definition
    ADD CONSTRAINT identity_attribute_definition_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.attribute_definition_version
    ADD CONSTRAINT identity_attribute_definition_version_definition_ref_uq UNIQUE (tenant_id, id, attribute_definition_id);

ALTER TABLE ONLY identity.attribute_definition_version
    ADD CONSTRAINT identity_attribute_definition_version_schema_definition_uq UNIQUE (tenant_id, schema_version_id, attribute_definition_id);

ALTER TABLE ONLY identity.attribute_definition_version
    ADD CONSTRAINT identity_attribute_definition_version_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.attribute_definition_version
    ADD CONSTRAINT identity_attribute_definition_version_type_ref_uq UNIQUE (tenant_id, id, data_type, cardinality);

ALTER TABLE ONLY identity.attribute_mapping_version
    ADD CONSTRAINT identity_attribute_mapping_version_number_uq UNIQUE (tenant_id, source_system_id, attribute_definition_version_id, version_number);

ALTER TABLE ONLY identity.attribute_mapping_version
    ADD CONSTRAINT identity_attribute_mapping_version_provenance_ref_uq UNIQUE (tenant_id, id, source_system_id, attribute_definition_version_id);

ALTER TABLE ONLY identity.attribute_mapping_version
    ADD CONSTRAINT identity_attribute_mapping_version_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_candidate_value
    ADD CONSTRAINT identity_candidate_value_ordinal_uq UNIQUE (tenant_id, candidate_id, value_ordinal);

ALTER TABLE ONLY identity.canonical_attribute_candidate_value
    ADD CONSTRAINT identity_candidate_value_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_definition_ref_uq UNIQUE (tenant_id, id, attribute_definition_version_id);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_source_uq UNIQUE (tenant_id, identity_id, attribute_definition_version_id, source_record_id, mapping_version_id);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_state_ref_uq UNIQUE (tenant_id, id, identity_id, attribute_definition_version_id);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_override
    ADD CONSTRAINT identity_canonical_attribute_override_definition_ref_uq UNIQUE (tenant_id, id, attribute_definition_version_id);

ALTER TABLE ONLY identity.canonical_attribute_override
    ADD CONSTRAINT identity_canonical_attribute_override_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_definition_ref_uq UNIQUE (tenant_id, id, attribute_definition_version_id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_identity_definition_uq UNIQUE (tenant_id, identity_id, attribute_definition_id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.canonical_schema_version
    ADD CONSTRAINT identity_canonical_schema_version_number_uq UNIQUE (tenant_id, version_number);

ALTER TABLE ONLY identity.canonical_schema_version
    ADD CONSTRAINT identity_canonical_schema_version_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.identity_link
    ADD CONSTRAINT identity_identity_link_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.identity
    ADD CONSTRAINT identity_identity_profile_ref_uq UNIQUE (tenant_id, id, identity_type);

ALTER TABLE ONLY identity.identity
    ADD CONSTRAINT identity_identity_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.identity_link
    ADD CONSTRAINT identity_link_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.identity_merge_operation
    ADD CONSTRAINT identity_merge_operation_absorbed_uq UNIQUE (tenant_id, absorbed_identity_id);

ALTER TABLE ONLY identity.identity_merge_operation
    ADD CONSTRAINT identity_merge_operation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.identity_merge_operation
    ADD CONSTRAINT identity_merge_operation_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_override_value
    ADD CONSTRAINT identity_override_value_ordinal_uq UNIQUE (tenant_id, override_id, value_ordinal);

ALTER TABLE ONLY identity.canonical_attribute_override_value
    ADD CONSTRAINT identity_override_value_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.person_profile
    ADD CONSTRAINT identity_person_profile_tenant_id_uq UNIQUE (tenant_id, identity_id);

ALTER TABLE ONLY identity.identity
    ADD CONSTRAINT identity_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.principal
    ADD CONSTRAINT identity_principal_target_native_uq UNIQUE (tenant_id, application_target_id, native_principal_key);

ALTER TABLE ONLY identity.principal
    ADD CONSTRAINT identity_principal_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.service_profile
    ADD CONSTRAINT identity_service_profile_tenant_id_uq UNIQUE (tenant_id, identity_id);

ALTER TABLE ONLY identity.source_absence_inference
    ADD CONSTRAINT identity_source_absence_inference_run_uq UNIQUE (tenant_id, import_run_id);

ALTER TABLE ONLY identity.source_absence_inference
    ADD CONSTRAINT identity_source_absence_inference_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.source_absence_policy_version
    ADD CONSTRAINT identity_source_absence_policy_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.source_absence_policy_version
    ADD CONSTRAINT identity_source_absence_policy_version_uq UNIQUE (tenant_id, source_system_id, version_number);

ALTER TABLE ONLY identity.source_absence_transition_evidence
    ADD CONSTRAINT identity_source_absence_transition_context_uq UNIQUE (tenant_id, inference_id, source_record_id, identity_id, post_identity_revision);

ALTER TABLE ONLY identity.source_absence_transition_evidence
    ADD CONSTRAINT identity_source_absence_transition_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.source_correlation_policy_version
    ADD CONSTRAINT identity_source_correlation_policy_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.source_correlation_policy_version
    ADD CONSTRAINT identity_source_correlation_policy_version_uq UNIQUE (tenant_id, source_system_id, version_number);

ALTER TABLE ONLY identity.source_import_run
    ADD CONSTRAINT identity_source_import_run_source_ref_uq UNIQUE (tenant_id, id, source_system_id);

ALTER TABLE ONLY identity.source_import_run
    ADD CONSTRAINT identity_source_import_run_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.source_lifecycle_policy_version
    ADD CONSTRAINT identity_source_lifecycle_policy_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.source_lifecycle_policy_version
    ADD CONSTRAINT identity_source_lifecycle_policy_version_uq UNIQUE (tenant_id, source_system_id, version_number);

ALTER TABLE ONLY identity.source_record
    ADD CONSTRAINT identity_source_record_native_key_uq UNIQUE (tenant_id, source_system_id, native_key);

ALTER TABLE ONLY identity.source_record
    ADD CONSTRAINT identity_source_record_source_ref_uq UNIQUE (tenant_id, id, source_system_id);

ALTER TABLE ONLY identity.source_record
    ADD CONSTRAINT identity_source_record_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.source_suspension_transition_evidence
    ADD CONSTRAINT identity_source_suspension_transition_context_uq UNIQUE (tenant_id, source_record_id, identity_id, post_identity_revision);

ALTER TABLE ONLY identity.source_suspension_transition_evidence
    ADD CONSTRAINT identity_source_suspension_transition_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.source_system
    ADD CONSTRAINT identity_source_system_code_uq UNIQUE (tenant_id, code);

ALTER TABLE ONLY identity.source_system
    ADD CONSTRAINT identity_source_system_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.identity_split_operation
    ADD CONSTRAINT identity_split_operation_new_identity_uq UNIQUE (tenant_id, new_identity_id);

ALTER TABLE ONLY identity.identity_split_operation
    ADD CONSTRAINT identity_split_operation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.identity_split_operation
    ADD CONSTRAINT identity_split_operation_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.identity_split_principal
    ADD CONSTRAINT identity_split_principal_pkey PRIMARY KEY (tenant_id, operation_id, principal_id);

ALTER TABLE ONLY identity.identity_split_source_record
    ADD CONSTRAINT identity_split_source_record_pkey PRIMARY KEY (tenant_id, operation_id, source_record_id);

ALTER TABLE ONLY identity.canonical_attribute_state_value
    ADD CONSTRAINT identity_state_value_ordinal_uq UNIQUE (tenant_id, state_id, value_ordinal);

ALTER TABLE ONLY identity.canonical_attribute_state_value
    ADD CONSTRAINT identity_state_value_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY identity.workload_profile
    ADD CONSTRAINT identity_workload_profile_tenant_id_uq UNIQUE (tenant_id, identity_id);

ALTER TABLE ONLY identity.person_profile
    ADD CONSTRAINT person_profile_pkey PRIMARY KEY (identity_id);

ALTER TABLE ONLY identity.principal
    ADD CONSTRAINT principal_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.service_profile
    ADD CONSTRAINT service_profile_pkey PRIMARY KEY (identity_id);

ALTER TABLE ONLY identity.source_absence_inference
    ADD CONSTRAINT source_absence_inference_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.source_absence_policy_version
    ADD CONSTRAINT source_absence_policy_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.source_absence_transition_evidence
    ADD CONSTRAINT source_absence_transition_evidence_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.source_correlation_policy_version
    ADD CONSTRAINT source_correlation_policy_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.source_import_run
    ADD CONSTRAINT source_import_run_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.source_lifecycle_policy_rule
    ADD CONSTRAINT source_lifecycle_policy_rule_pkey PRIMARY KEY (tenant_id, policy_version_id, source_value);

ALTER TABLE ONLY identity.source_lifecycle_policy_version
    ADD CONSTRAINT source_lifecycle_policy_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.source_record
    ADD CONSTRAINT source_record_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.source_suspension_transition_evidence
    ADD CONSTRAINT source_suspension_transition_evidence_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.source_system
    ADD CONSTRAINT source_system_pkey PRIMARY KEY (id);

ALTER TABLE ONLY identity.workload_profile
    ADD CONSTRAINT workload_profile_pkey PRIMARY KEY (identity_id);

CREATE UNIQUE INDEX identity_attribute_authority_one_active_idx ON identity.attribute_authority_rule_version USING btree (tenant_id, attribute_definition_version_id, source_system_id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE UNIQUE INDEX identity_attribute_mapping_one_active_idx ON identity.attribute_mapping_version USING btree (tenant_id, source_system_id, attribute_definition_version_id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE INDEX identity_candidate_resolution_idx ON identity.canonical_attribute_candidate USING btree (tenant_id, identity_id, attribute_definition_version_id, source_system_id, id);

CREATE UNIQUE INDEX identity_canonical_attribute_override_one_active_idx ON identity.canonical_attribute_override USING btree (tenant_id, identity_id, attribute_definition_id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE UNIQUE INDEX identity_canonical_schema_one_active_idx ON identity.canonical_schema_version USING btree (tenant_id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE INDEX identity_identity_link_identity_idx ON identity.identity_link USING btree (tenant_id, identity_id, linked_at, id);

CREATE UNIQUE INDEX identity_identity_link_one_active_accepted_idx ON identity.identity_link USING btree (tenant_id, source_record_id) WHERE (((link_state)::text = 'ACCEPTED'::text) AND (ended_at IS NULL));

CREATE INDEX identity_identity_tenant_lifecycle_idx ON identity.identity USING btree (tenant_id, lifecycle_state, id);

CREATE INDEX identity_merge_operation_survivor_idx ON identity.identity_merge_operation USING btree (tenant_id, survivor_identity_id, completed_at DESC, id);

CREATE INDEX identity_override_resolution_idx ON identity.canonical_attribute_override USING btree (tenant_id, identity_id, attribute_definition_id, state, valid_from, valid_until, id);

CREATE INDEX identity_principal_identity_idx ON identity.principal USING btree (tenant_id, identity_id, application_target_id) WHERE (identity_id IS NOT NULL);

CREATE INDEX identity_principal_target_idx ON identity.principal USING btree (tenant_id, application_target_id, lifecycle_state, id);

CREATE INDEX identity_source_absence_candidate_idx ON identity.source_record USING btree (tenant_id, source_system_id, first_observed_at, id);

CREATE UNIQUE INDEX identity_source_absence_policy_one_active_idx ON identity.source_absence_policy_version USING btree (tenant_id, source_system_id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE INDEX identity_source_absence_transition_restore_idx ON identity.source_absence_transition_evidence USING btree (tenant_id, source_record_id, identity_link_id, identity_id, post_identity_revision);

CREATE UNIQUE INDEX identity_source_correlation_policy_one_active_idx ON identity.source_correlation_policy_version USING btree (tenant_id, source_system_id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE UNIQUE INDEX identity_source_lifecycle_policy_one_active_idx ON identity.source_lifecycle_policy_version USING btree (tenant_id, source_system_id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE INDEX identity_source_record_source_observed_idx ON identity.source_record USING btree (tenant_id, source_system_id, last_observed_at, id);

CREATE INDEX identity_source_suspension_transition_restore_idx ON identity.source_suspension_transition_evidence USING btree (tenant_id, source_record_id, identity_link_id, identity_id, post_identity_revision);

CREATE INDEX identity_split_operation_source_idx ON identity.identity_split_operation USING btree (tenant_id, source_identity_id, completed_at DESC, id);

CREATE INDEX identity_state_identity_idx ON identity.canonical_attribute_state USING btree (tenant_id, identity_id, attribute_definition_id);

CREATE INDEX identity_state_single_string_correlation_lookup_idx ON identity.canonical_attribute_state_value USING btree (tenant_id, attribute_definition_version_id, value_string, state_id) WHERE (((data_type)::text = 'STRING'::text) AND ((cardinality)::text = 'SINGLE'::text) AND (value_ordinal = 0));

ALTER TABLE ONLY identity.attribute_authority_rule_version
    ADD CONSTRAINT identity_attribute_authority_rule_definition_fk FOREIGN KEY (tenant_id, attribute_definition_version_id) REFERENCES identity.attribute_definition_version(tenant_id, id);

ALTER TABLE ONLY identity.attribute_authority_rule_version
    ADD CONSTRAINT identity_attribute_authority_rule_source_fk FOREIGN KEY (tenant_id, source_system_id) REFERENCES identity.source_system(tenant_id, id);

ALTER TABLE ONLY identity.attribute_definition
    ADD CONSTRAINT identity_attribute_definition_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY identity.attribute_definition_version
    ADD CONSTRAINT identity_attribute_definition_version_definition_fk FOREIGN KEY (tenant_id, attribute_definition_id) REFERENCES identity.attribute_definition(tenant_id, id);

ALTER TABLE ONLY identity.attribute_definition_version
    ADD CONSTRAINT identity_attribute_definition_version_schema_fk FOREIGN KEY (tenant_id, schema_version_id) REFERENCES identity.canonical_schema_version(tenant_id, id);

ALTER TABLE ONLY identity.attribute_mapping_version
    ADD CONSTRAINT identity_attribute_mapping_version_definition_fk FOREIGN KEY (tenant_id, attribute_definition_version_id) REFERENCES identity.attribute_definition_version(tenant_id, id);

ALTER TABLE ONLY identity.attribute_mapping_version
    ADD CONSTRAINT identity_attribute_mapping_version_source_fk FOREIGN KEY (tenant_id, source_system_id) REFERENCES identity.source_system(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_candidate_value
    ADD CONSTRAINT identity_candidate_value_candidate_definition_fk FOREIGN KEY (tenant_id, candidate_id, attribute_definition_version_id) REFERENCES identity.canonical_attribute_candidate(tenant_id, id, attribute_definition_version_id);

ALTER TABLE ONLY identity.canonical_attribute_candidate_value
    ADD CONSTRAINT identity_candidate_value_candidate_fk FOREIGN KEY (tenant_id, candidate_id) REFERENCES identity.canonical_attribute_candidate(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_candidate_value
    ADD CONSTRAINT identity_candidate_value_definition_type_fk FOREIGN KEY (tenant_id, attribute_definition_version_id, data_type, cardinality) REFERENCES identity.attribute_definition_version(tenant_id, id, data_type, cardinality);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_definition_fk FOREIGN KEY (tenant_id, attribute_definition_version_id) REFERENCES identity.attribute_definition_version(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_identity_fk FOREIGN KEY (tenant_id, identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_mapping_fk FOREIGN KEY (tenant_id, mapping_version_id) REFERENCES identity.attribute_mapping_version(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_mapping_tuple_fk FOREIGN KEY (tenant_id, mapping_version_id, source_system_id, attribute_definition_version_id) REFERENCES identity.attribute_mapping_version(tenant_id, id, source_system_id, attribute_definition_version_id);

COMMENT ON CONSTRAINT identity_canonical_attribute_candidate_mapping_tuple_fk ON identity.canonical_attribute_candidate IS 'Candidate provenance must use a mapping for the same SourceSystem and canonical definition version.';

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_source_fk FOREIGN KEY (tenant_id, source_system_id) REFERENCES identity.source_system(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_source_record_fk FOREIGN KEY (tenant_id, source_record_id) REFERENCES identity.source_record(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_source_tuple_fk FOREIGN KEY (tenant_id, source_record_id, source_system_id) REFERENCES identity.source_record(tenant_id, id, source_system_id);

ALTER TABLE ONLY identity.canonical_attribute_override
    ADD CONSTRAINT identity_canonical_attribute_override_definition_fk FOREIGN KEY (tenant_id, attribute_definition_id) REFERENCES identity.attribute_definition(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_override
    ADD CONSTRAINT identity_canonical_attribute_override_definition_tuple_fk FOREIGN KEY (tenant_id, attribute_definition_version_id, attribute_definition_id) REFERENCES identity.attribute_definition_version(tenant_id, id, attribute_definition_id);

ALTER TABLE ONLY identity.canonical_attribute_override
    ADD CONSTRAINT identity_canonical_attribute_override_definition_version_fk FOREIGN KEY (tenant_id, attribute_definition_version_id) REFERENCES identity.attribute_definition_version(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_override
    ADD CONSTRAINT identity_canonical_attribute_override_identity_fk FOREIGN KEY (tenant_id, identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_authority_fk FOREIGN KEY (tenant_id, authority_rule_version_id) REFERENCES identity.attribute_authority_rule_version(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_authority_tuple_fk FOREIGN KEY (tenant_id, authority_rule_version_id, attribute_definition_version_id) REFERENCES identity.attribute_authority_rule_version(tenant_id, id, attribute_definition_version_id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_candidate_fk FOREIGN KEY (tenant_id, selected_candidate_id) REFERENCES identity.canonical_attribute_candidate(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_candidate_tuple_fk FOREIGN KEY (tenant_id, selected_candidate_id, identity_id, attribute_definition_version_id) REFERENCES identity.canonical_attribute_candidate(tenant_id, id, identity_id, attribute_definition_version_id);

COMMENT ON CONSTRAINT identity_canonical_attribute_state_candidate_tuple_fk ON identity.canonical_attribute_state IS 'Selected candidate, when present, must belong to the same Identity and canonical definition version as the resolved state.';

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_definition_fk FOREIGN KEY (tenant_id, attribute_definition_id) REFERENCES identity.attribute_definition(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_definition_tuple_fk FOREIGN KEY (tenant_id, attribute_definition_version_id, attribute_definition_id) REFERENCES identity.attribute_definition_version(tenant_id, id, attribute_definition_id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_definition_version_fk FOREIGN KEY (tenant_id, attribute_definition_version_id) REFERENCES identity.attribute_definition_version(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_identity_fk FOREIGN KEY (tenant_id, identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.canonical_schema_version
    ADD CONSTRAINT identity_canonical_schema_version_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY identity.identity_link
    ADD CONSTRAINT identity_identity_link_identity_fk FOREIGN KEY (tenant_id, identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.identity_link
    ADD CONSTRAINT identity_identity_link_source_record_fk FOREIGN KEY (tenant_id, source_record_id) REFERENCES identity.source_record(tenant_id, id);

ALTER TABLE ONLY identity.identity
    ADD CONSTRAINT identity_identity_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY identity.identity_merge_operation
    ADD CONSTRAINT identity_merge_operation_absorbed_fk FOREIGN KEY (tenant_id, absorbed_identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.identity_merge_operation
    ADD CONSTRAINT identity_merge_operation_survivor_fk FOREIGN KEY (tenant_id, survivor_identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_override_value
    ADD CONSTRAINT identity_override_value_definition_type_fk FOREIGN KEY (tenant_id, attribute_definition_version_id, data_type, cardinality) REFERENCES identity.attribute_definition_version(tenant_id, id, data_type, cardinality);

ALTER TABLE ONLY identity.canonical_attribute_override_value
    ADD CONSTRAINT identity_override_value_override_definition_fk FOREIGN KEY (tenant_id, override_id, attribute_definition_version_id) REFERENCES identity.canonical_attribute_override(tenant_id, id, attribute_definition_version_id);

ALTER TABLE ONLY identity.canonical_attribute_override_value
    ADD CONSTRAINT identity_override_value_override_fk FOREIGN KEY (tenant_id, override_id) REFERENCES identity.canonical_attribute_override(tenant_id, id);

ALTER TABLE ONLY identity.person_profile
    ADD CONSTRAINT identity_person_profile_identity_fk FOREIGN KEY (tenant_id, identity_id, identity_type) REFERENCES identity.identity(tenant_id, id, identity_type);

ALTER TABLE ONLY identity.principal
    ADD CONSTRAINT identity_principal_identity_fk FOREIGN KEY (tenant_id, identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.principal
    ADD CONSTRAINT identity_principal_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY identity.service_profile
    ADD CONSTRAINT identity_service_profile_identity_fk FOREIGN KEY (tenant_id, identity_id, identity_type) REFERENCES identity.identity(tenant_id, id, identity_type);

ALTER TABLE ONLY identity.source_absence_inference
    ADD CONSTRAINT identity_source_absence_inference_policy_fk FOREIGN KEY (tenant_id, policy_version_id) REFERENCES identity.source_absence_policy_version(tenant_id, id);

ALTER TABLE ONLY identity.source_absence_inference
    ADD CONSTRAINT identity_source_absence_inference_run_fk FOREIGN KEY (tenant_id, import_run_id, source_system_id) REFERENCES identity.source_import_run(tenant_id, id, source_system_id);

ALTER TABLE ONLY identity.source_absence_policy_version
    ADD CONSTRAINT identity_source_absence_policy_source_fk FOREIGN KEY (tenant_id, source_system_id) REFERENCES identity.source_system(tenant_id, id);

ALTER TABLE ONLY identity.source_absence_transition_evidence
    ADD CONSTRAINT identity_source_absence_transition_identity_fk FOREIGN KEY (tenant_id, identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.source_absence_transition_evidence
    ADD CONSTRAINT identity_source_absence_transition_inference_fk FOREIGN KEY (tenant_id, inference_id) REFERENCES identity.source_absence_inference(tenant_id, id);

ALTER TABLE ONLY identity.source_absence_transition_evidence
    ADD CONSTRAINT identity_source_absence_transition_link_fk FOREIGN KEY (tenant_id, identity_link_id) REFERENCES identity.identity_link(tenant_id, id);

ALTER TABLE ONLY identity.source_absence_transition_evidence
    ADD CONSTRAINT identity_source_absence_transition_source_record_fk FOREIGN KEY (tenant_id, source_record_id) REFERENCES identity.source_record(tenant_id, id);

ALTER TABLE ONLY identity.source_correlation_policy_version
    ADD CONSTRAINT identity_source_correlation_policy_definition_fk FOREIGN KEY (tenant_id, match_attribute_definition_version_id) REFERENCES identity.attribute_definition_version(tenant_id, id);

ALTER TABLE ONLY identity.source_correlation_policy_version
    ADD CONSTRAINT identity_source_correlation_policy_mapping_tuple_fk FOREIGN KEY (tenant_id, match_mapping_version_id, source_system_id, match_attribute_definition_version_id) REFERENCES identity.attribute_mapping_version(tenant_id, id, source_system_id, attribute_definition_version_id);

ALTER TABLE ONLY identity.source_correlation_policy_version
    ADD CONSTRAINT identity_source_correlation_policy_source_fk FOREIGN KEY (tenant_id, source_system_id) REFERENCES identity.source_system(tenant_id, id);

ALTER TABLE ONLY identity.source_import_run
    ADD CONSTRAINT identity_source_import_run_source_fk FOREIGN KEY (tenant_id, source_system_id) REFERENCES identity.source_system(tenant_id, id);

ALTER TABLE ONLY identity.source_lifecycle_policy_rule
    ADD CONSTRAINT identity_source_lifecycle_policy_rule_policy_fk FOREIGN KEY (tenant_id, policy_version_id) REFERENCES identity.source_lifecycle_policy_version(tenant_id, id);

ALTER TABLE ONLY identity.source_lifecycle_policy_version
    ADD CONSTRAINT identity_source_lifecycle_policy_source_fk FOREIGN KEY (tenant_id, source_system_id) REFERENCES identity.source_system(tenant_id, id);

ALTER TABLE ONLY identity.source_record
    ADD CONSTRAINT identity_source_record_last_complete_run_fk FOREIGN KEY (tenant_id, last_complete_import_run_id, source_system_id) REFERENCES identity.source_import_run(tenant_id, id, source_system_id);

ALTER TABLE ONLY identity.source_record
    ADD CONSTRAINT identity_source_record_last_run_fk FOREIGN KEY (tenant_id, last_import_run_id, source_system_id) REFERENCES identity.source_import_run(tenant_id, id, source_system_id);

ALTER TABLE ONLY identity.source_record
    ADD CONSTRAINT identity_source_record_source_fk FOREIGN KEY (tenant_id, source_system_id) REFERENCES identity.source_system(tenant_id, id);

ALTER TABLE ONLY identity.source_suspension_transition_evidence
    ADD CONSTRAINT identity_source_suspension_transition_identity_fk FOREIGN KEY (tenant_id, identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.source_suspension_transition_evidence
    ADD CONSTRAINT identity_source_suspension_transition_link_fk FOREIGN KEY (tenant_id, identity_link_id) REFERENCES identity.identity_link(tenant_id, id);

ALTER TABLE ONLY identity.source_suspension_transition_evidence
    ADD CONSTRAINT identity_source_suspension_transition_policy_fk FOREIGN KEY (tenant_id, lifecycle_policy_version_id) REFERENCES identity.source_lifecycle_policy_version(tenant_id, id);

ALTER TABLE ONLY identity.source_suspension_transition_evidence
    ADD CONSTRAINT identity_source_suspension_transition_source_fk FOREIGN KEY (tenant_id, source_system_id) REFERENCES identity.source_system(tenant_id, id);

ALTER TABLE ONLY identity.source_suspension_transition_evidence
    ADD CONSTRAINT identity_source_suspension_transition_source_record_fk FOREIGN KEY (tenant_id, source_record_id) REFERENCES identity.source_record(tenant_id, id);

ALTER TABLE ONLY identity.source_system
    ADD CONSTRAINT identity_source_system_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY identity.identity_split_operation
    ADD CONSTRAINT identity_split_operation_new_fk FOREIGN KEY (tenant_id, new_identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.identity_split_operation
    ADD CONSTRAINT identity_split_operation_source_fk FOREIGN KEY (tenant_id, source_identity_id) REFERENCES identity.identity(tenant_id, id);

ALTER TABLE ONLY identity.identity_split_principal
    ADD CONSTRAINT identity_split_principal_operation_fk FOREIGN KEY (tenant_id, operation_id) REFERENCES identity.identity_split_operation(tenant_id, id);

ALTER TABLE ONLY identity.identity_split_principal
    ADD CONSTRAINT identity_split_principal_principal_fk FOREIGN KEY (tenant_id, principal_id) REFERENCES identity.principal(tenant_id, id);

ALTER TABLE ONLY identity.identity_split_source_record
    ADD CONSTRAINT identity_split_source_record_operation_fk FOREIGN KEY (tenant_id, operation_id) REFERENCES identity.identity_split_operation(tenant_id, id);

ALTER TABLE ONLY identity.identity_split_source_record
    ADD CONSTRAINT identity_split_source_record_source_fk FOREIGN KEY (tenant_id, source_record_id) REFERENCES identity.source_record(tenant_id, id);

ALTER TABLE ONLY identity.canonical_attribute_state_value
    ADD CONSTRAINT identity_state_value_definition_type_fk FOREIGN KEY (tenant_id, attribute_definition_version_id, data_type, cardinality) REFERENCES identity.attribute_definition_version(tenant_id, id, data_type, cardinality);

ALTER TABLE ONLY identity.canonical_attribute_state_value
    ADD CONSTRAINT identity_state_value_state_definition_fk FOREIGN KEY (tenant_id, state_id, attribute_definition_version_id) REFERENCES identity.canonical_attribute_state(tenant_id, id, attribute_definition_version_id);

ALTER TABLE ONLY identity.canonical_attribute_state_value
    ADD CONSTRAINT identity_state_value_state_fk FOREIGN KEY (tenant_id, state_id) REFERENCES identity.canonical_attribute_state(tenant_id, id);

ALTER TABLE ONLY identity.workload_profile
    ADD CONSTRAINT identity_workload_profile_identity_fk FOREIGN KEY (tenant_id, identity_id, identity_type) REFERENCES identity.identity(tenant_id, id, identity_type);
