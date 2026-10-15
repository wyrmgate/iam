-- Access capability current-state persistence baseline.
-- Generated once from the verified pre-rebaseline schema, then maintained as reviewed source.

CREATE TABLE access.access_assignment (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    target_kind character varying(16) NOT NULL,
    role_id uuid,
    entitlement_id uuid,
    principal_constraint_kind character varying(16) NOT NULL,
    specific_principal_id uuid,
    provenance_kind character varying(32) NOT NULL,
    provenance_ref_id uuid,
    lifecycle_state character varying(24) NOT NULL,
    valid_from timestamp with time zone,
    valid_until timestamp with time zone,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT access_assignment_lifecycle_state_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'ACTIVE'::character varying, 'SUSPENDED'::character varying, 'REVOKED'::character varying, 'EXPIRED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT access_assignment_principal_constraint_kind_ck CHECK (((principal_constraint_kind)::text = ANY ((ARRAY['ANY'::character varying, 'SPECIFIC'::character varying])::text[]))),
    CONSTRAINT access_assignment_principal_constraint_shape_ck CHECK (((((principal_constraint_kind)::text = 'SPECIFIC'::text) AND (specific_principal_id IS NOT NULL)) OR (((principal_constraint_kind)::text = 'ANY'::text) AND (specific_principal_id IS NULL)))),
    CONSTRAINT access_assignment_provenance_kind_ck CHECK (((provenance_kind)::text = ANY ((ARRAY['MANUAL'::character varying, 'REQUEST_ITEM'::character varying, 'LIFECYCLE_POLICY_RULE'::character varying])::text[]))),
    CONSTRAINT access_assignment_provenance_shape_ck CHECK (((((provenance_kind)::text = 'MANUAL'::text) AND (provenance_ref_id IS NULL)) OR (((provenance_kind)::text = ANY ((ARRAY['REQUEST_ITEM'::character varying, 'LIFECYCLE_POLICY_RULE'::character varying])::text[])) AND (provenance_ref_id IS NOT NULL)))),
    CONSTRAINT access_assignment_revision_ck CHECK ((revision > 0)),
    CONSTRAINT access_assignment_scheduled_valid_from_ck CHECK ((((lifecycle_state)::text <> 'SCHEDULED'::text) OR (valid_from IS NOT NULL))),
    CONSTRAINT access_assignment_target_kind_ck CHECK (((target_kind)::text = ANY ((ARRAY['ROLE'::character varying, 'ENTITLEMENT'::character varying])::text[]))),
    CONSTRAINT access_assignment_target_shape_ck CHECK (((((target_kind)::text = 'ROLE'::text) AND (role_id IS NOT NULL) AND (entitlement_id IS NULL)) OR (((target_kind)::text = 'ENTITLEMENT'::text) AND (entitlement_id IS NOT NULL) AND (role_id IS NULL)))),
    CONSTRAINT access_assignment_timestamp_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT access_assignment_validity_ck CHECK (((valid_until IS NULL) OR (valid_from IS NULL) OR (valid_until > valid_from)))
);

COMMENT ON TABLE access.access_assignment IS 'Access-owned authoritative business access intent. Technical fulfillment and provider observation are separate state dimensions.';

COMMENT ON COLUMN access.access_assignment.provenance_ref_id IS 'Stable causal reference within provenance kind. REQUEST_ITEM stores Governance RequestItem ID; LIFECYCLE_POLICY_RULE stores stable Access lifecycle-policy rule ID.';

CREATE TABLE access.desired_grant_state (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    application_target_id uuid NOT NULL,
    entitlement_id uuid NOT NULL,
    principal_id uuid,
    desired_state character varying(24) NOT NULL,
    desired_revision bigint NOT NULL,
    source_generation bigint NOT NULL,
    computed_at timestamp with time zone NOT NULL,
    principal_constraint_key character varying(512) NOT NULL,
    CONSTRAINT access_desired_grant_constraint_key_ck CHECK ((btrim((principal_constraint_key)::text) <> ''::text)),
    CONSTRAINT access_desired_grant_state_generation_ck CHECK ((source_generation > 0)),
    CONSTRAINT access_desired_grant_state_revision_ck CHECK ((desired_revision > 0)),
    CONSTRAINT access_desired_grant_state_state_ck CHECK (((desired_state)::text = ANY ((ARRAY['PRESENT'::character varying, 'ABSENT'::character varying])::text[])))
);

COMMENT ON COLUMN access.desired_grant_state.principal_constraint_key IS 'Stable Access projection tuple key aligned with EffectiveAccess: ANY or SPECIFIC:<principal UUID>.';

CREATE TABLE access.desired_principal_state (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    application_target_id uuid NOT NULL,
    desired_state character varying(24) NOT NULL,
    desired_revision bigint NOT NULL,
    source_generation bigint NOT NULL,
    computed_at timestamp with time zone NOT NULL,
    CONSTRAINT access_desired_principal_state_generation_ck CHECK ((source_generation > 0)),
    CONSTRAINT access_desired_principal_state_revision_ck CHECK ((desired_revision > 0)),
    CONSTRAINT access_desired_principal_state_state_ck CHECK (((desired_state)::text = ANY ((ARRAY['PRESENT'::character varying, 'ABSENT'::character varying])::text[])))
);

CREATE TABLE access.effective_access (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    entitlement_id uuid NOT NULL,
    principal_constraint_key character varying(512) NOT NULL,
    support_count integer NOT NULL,
    computed_at timestamp with time zone NOT NULL,
    projection_generation bigint NOT NULL,
    CONSTRAINT effective_access_constraint_key_ck CHECK ((btrim((principal_constraint_key)::text) <> ''::text)),
    CONSTRAINT effective_access_generation_ck CHECK ((projection_generation > 0)),
    CONSTRAINT effective_access_support_count_ck CHECK ((support_count > 0))
);

COMMENT ON TABLE access.effective_access IS 'Access-owned rebuildable entitlement-level projection derived from authoritative AccessAssignments.';

CREATE TABLE access.effective_access_support (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    effective_access_id uuid NOT NULL,
    access_assignment_id uuid NOT NULL,
    role_version_id uuid,
    path_hash character varying(128) NOT NULL,
    path_depth integer NOT NULL,
    CONSTRAINT effective_access_support_depth_ck CHECK ((path_depth >= 0))
);

COMMENT ON TABLE access.effective_access_support IS 'Explainable support paths for EffectiveAccess; current direct assignments use path_depth 0 and no role_version_id.';

CREATE TABLE access.effective_access_support_role_version (
    tenant_id uuid NOT NULL,
    effective_access_support_id uuid CONSTRAINT effective_access_support_ro_effective_access_support_i_not_null NOT NULL,
    path_ordinal integer NOT NULL,
    role_version_id uuid NOT NULL,
    CONSTRAINT effective_access_support_role_version_ordinal_ck CHECK ((path_ordinal >= 0))
);

COMMENT ON TABLE access.effective_access_support_role_version IS 'Normalized ordered RoleVersion derivation path for one role-derived EffectiveAccess support row.';

CREATE TABLE access.identity_access_reduction (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    source_identity_revision bigint NOT NULL,
    source_lifecycle_state character varying(24) NOT NULL,
    lifecycle_state character varying(16) NOT NULL,
    snapshot_at timestamp with time zone NOT NULL,
    after_created_at timestamp with time zone,
    after_assignment_id uuid,
    processed_assignment_count bigint DEFAULT 0 NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT identity_access_reduction_checkpoint_ck CHECK ((((after_created_at IS NULL) AND (after_assignment_id IS NULL)) OR ((after_created_at IS NOT NULL) AND (after_assignment_id IS NOT NULL)))),
    CONSTRAINT identity_access_reduction_completion_ck CHECK (((((lifecycle_state)::text = 'RUNNING'::text) AND (completed_at IS NULL)) OR (((lifecycle_state)::text = 'COMPLETED'::text) AND (completed_at IS NOT NULL)))),
    CONSTRAINT identity_access_reduction_processed_ck CHECK ((processed_assignment_count >= 0)),
    CONSTRAINT identity_access_reduction_revision_ck CHECK ((revision > 0)),
    CONSTRAINT identity_access_reduction_source_revision_ck CHECK ((source_identity_revision > 0)),
    CONSTRAINT identity_access_reduction_source_state_ck CHECK (((source_lifecycle_state)::text = ANY ((ARRAY['PENDING'::character varying, 'SUSPENDED'::character varying, 'INACTIVE'::character varying, 'DECOMMISSIONED'::character varying])::text[]))),
    CONSTRAINT identity_access_reduction_state_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['RUNNING'::character varying, 'COMPLETED'::character varying])::text[]))),
    CONSTRAINT identity_access_reduction_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE access.identity_access_reduction IS 'Access-owned durable Leaver reduction process causally keyed by Identity ID and source Identity revision. Identity references are stable cross-capability IDs without database foreign keys.';

CREATE TABLE access.lifecycle_access_policy_rule (
    policy_version_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    rule_id uuid NOT NULL,
    predicate_kind character varying(32) NOT NULL,
    canonical_key character varying(256),
    expected_string character varying(1024),
    target_kind character varying(16) NOT NULL,
    target_id uuid NOT NULL,
    expected_boolean boolean,
    expected_integer bigint,
    expected_enum character varying(256),
    expected_decimal numeric(38,12),
    expected_date date,
    expected_datetime timestamp with time zone,
    CONSTRAINT lifecycle_access_policy_rule_predicate_ck CHECK (((predicate_kind)::text = ANY ((ARRAY['ALWAYS'::character varying, 'CANONICAL_STRING_EQUALS'::character varying, 'CANONICAL_BOOLEAN_EQUALS'::character varying, 'CANONICAL_INTEGER_EQUALS'::character varying, 'CANONICAL_DECIMAL_EQUALS'::character varying, 'CANONICAL_DATE_EQUALS'::character varying, 'CANONICAL_DATETIME_EQUALS'::character varying, 'CANONICAL_ENUM_EQUALS'::character varying, 'CANONICAL_STRING_CONTAINS'::character varying, 'CANONICAL_BOOLEAN_CONTAINS'::character varying, 'CANONICAL_INTEGER_CONTAINS'::character varying, 'CANONICAL_DECIMAL_CONTAINS'::character varying, 'CANONICAL_DATE_CONTAINS'::character varying, 'CANONICAL_DATETIME_CONTAINS'::character varying, 'CANONICAL_ENUM_CONTAINS'::character varying, 'CANONICAL_STRING_CONTAINS_ANY'::character varying, 'CANONICAL_BOOLEAN_CONTAINS_ANY'::character varying, 'CANONICAL_INTEGER_CONTAINS_ANY'::character varying, 'CANONICAL_DECIMAL_CONTAINS_ANY'::character varying, 'CANONICAL_DATE_CONTAINS_ANY'::character varying, 'CANONICAL_DATETIME_CONTAINS_ANY'::character varying, 'CANONICAL_ENUM_CONTAINS_ANY'::character varying, 'CANONICAL_STRING_CONTAINS_ALL'::character varying, 'CANONICAL_BOOLEAN_CONTAINS_ALL'::character varying, 'CANONICAL_INTEGER_CONTAINS_ALL'::character varying, 'CANONICAL_DECIMAL_CONTAINS_ALL'::character varying, 'CANONICAL_DATE_CONTAINS_ALL'::character varying, 'CANONICAL_DATETIME_CONTAINS_ALL'::character varying, 'CANONICAL_ENUM_CONTAINS_ALL'::character varying])::text[]))),
    CONSTRAINT lifecycle_access_policy_rule_predicate_shape_ck CHECK (((((predicate_kind)::text = 'ALWAYS'::text) AND (canonical_key IS NULL) AND (num_nonnulls(expected_string, expected_boolean, expected_integer, expected_decimal, expected_date, expected_datetime, expected_enum) = 0)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_STRING_EQUALS'::character varying, 'CANONICAL_STRING_CONTAINS'::character varying])::text[])) AND (canonical_key IS NOT NULL) AND (btrim((canonical_key)::text) <> ''::text) AND (expected_string IS NOT NULL) AND (num_nonnulls(expected_boolean, expected_integer, expected_decimal, expected_date, expected_datetime, expected_enum) = 0)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_BOOLEAN_EQUALS'::character varying, 'CANONICAL_BOOLEAN_CONTAINS'::character varying])::text[])) AND (canonical_key IS NOT NULL) AND (btrim((canonical_key)::text) <> ''::text) AND (expected_boolean IS NOT NULL) AND (num_nonnulls(expected_string, expected_integer, expected_decimal, expected_date, expected_datetime, expected_enum) = 0)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_INTEGER_EQUALS'::character varying, 'CANONICAL_INTEGER_CONTAINS'::character varying])::text[])) AND (canonical_key IS NOT NULL) AND (btrim((canonical_key)::text) <> ''::text) AND (expected_integer IS NOT NULL) AND (num_nonnulls(expected_string, expected_boolean, expected_decimal, expected_date, expected_datetime, expected_enum) = 0)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_DECIMAL_EQUALS'::character varying, 'CANONICAL_DECIMAL_CONTAINS'::character varying])::text[])) AND (canonical_key IS NOT NULL) AND (btrim((canonical_key)::text) <> ''::text) AND (expected_decimal IS NOT NULL) AND (num_nonnulls(expected_string, expected_boolean, expected_integer, expected_date, expected_datetime, expected_enum) = 0)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_DATE_EQUALS'::character varying, 'CANONICAL_DATE_CONTAINS'::character varying])::text[])) AND (canonical_key IS NOT NULL) AND (btrim((canonical_key)::text) <> ''::text) AND (expected_date IS NOT NULL) AND (num_nonnulls(expected_string, expected_boolean, expected_integer, expected_decimal, expected_datetime, expected_enum) = 0)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_DATETIME_EQUALS'::character varying, 'CANONICAL_DATETIME_CONTAINS'::character varying])::text[])) AND (canonical_key IS NOT NULL) AND (btrim((canonical_key)::text) <> ''::text) AND (expected_datetime IS NOT NULL) AND (num_nonnulls(expected_string, expected_boolean, expected_integer, expected_decimal, expected_date, expected_enum) = 0)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_ENUM_EQUALS'::character varying, 'CANONICAL_ENUM_CONTAINS'::character varying])::text[])) AND (canonical_key IS NOT NULL) AND (btrim((canonical_key)::text) <> ''::text) AND (expected_enum IS NOT NULL) AND (btrim((expected_enum)::text) <> ''::text) AND (num_nonnulls(expected_string, expected_boolean, expected_integer, expected_decimal, expected_date, expected_datetime) = 0)) OR (((predicate_kind)::text ~~ 'CANONICAL_%_CONTAINS_ANY'::text) AND (canonical_key IS NOT NULL) AND (btrim((canonical_key)::text) <> ''::text) AND (num_nonnulls(expected_string, expected_boolean, expected_integer, expected_decimal, expected_date, expected_datetime, expected_enum) = 0)) OR (((predicate_kind)::text ~~ 'CANONICAL_%_CONTAINS_ALL'::text) AND (canonical_key IS NOT NULL) AND (btrim((canonical_key)::text) <> ''::text) AND (num_nonnulls(expected_string, expected_boolean, expected_integer, expected_decimal, expected_date, expected_datetime, expected_enum) = 0)))),
    CONSTRAINT lifecycle_access_policy_rule_target_kind_ck CHECK (((target_kind)::text = ANY ((ARRAY['ROLE'::character varying, 'ENTITLEMENT'::character varying])::text[])))
);

COMMENT ON TABLE access.lifecycle_access_policy_rule IS 'Typed bounded lifecycle access rule. rule_id is stable logical provenance reusable across policy versions.';

COMMENT ON COLUMN access.lifecycle_access_policy_rule.expected_boolean IS 'Typed BOOLEAN expected value for CANONICAL_BOOLEAN_EQUALS.';

COMMENT ON COLUMN access.lifecycle_access_policy_rule.expected_integer IS 'Typed INTEGER expected value for CANONICAL_INTEGER_EQUALS.';

COMMENT ON COLUMN access.lifecycle_access_policy_rule.expected_enum IS 'Typed ENUM key expected value for CANONICAL_ENUM_EQUALS.';

COMMENT ON COLUMN access.lifecycle_access_policy_rule.expected_decimal IS 'Exact normalized DECIMAL expected value for CANONICAL_DECIMAL_EQUALS; application validation prevents numeric(38,12) rounding.';

COMMENT ON COLUMN access.lifecycle_access_policy_rule.expected_date IS 'Exact DATE expected value for CANONICAL_DATE_EQUALS.';

COMMENT ON COLUMN access.lifecycle_access_policy_rule.expected_datetime IS 'Exact absolute microsecond-precision timestamp expected value for CANONICAL_DATETIME_EQUALS.';

CREATE TABLE access.lifecycle_access_policy_rule_expected_value (
    tenant_id uuid NOT NULL,
    policy_version_id uuid CONSTRAINT lifecycle_access_policy_rule_expecte_policy_version_id_not_null NOT NULL,
    rule_id uuid NOT NULL,
    predicate_kind character varying(32) CONSTRAINT lifecycle_access_policy_rule_expected_v_predicate_kind_not_null NOT NULL,
    value_ordinal integer CONSTRAINT lifecycle_access_policy_rule_expected_va_value_ordinal_not_null NOT NULL,
    value_type character varying(24) NOT NULL,
    value_string character varying(1024),
    value_boolean boolean,
    value_integer bigint,
    value_decimal numeric(38,12),
    value_date date,
    value_datetime timestamp with time zone,
    value_enum character varying(256),
    CONSTRAINT lifecycle_access_policy_rule_expected_value_ordinal_ck CHECK (((value_ordinal >= 0) AND (value_ordinal < 20))),
    CONSTRAINT lifecycle_access_policy_rule_expected_value_predicate_type_ck CHECK (((((predicate_kind)::text = ANY ((ARRAY['CANONICAL_STRING_CONTAINS_ANY'::character varying, 'CANONICAL_STRING_CONTAINS_ALL'::character varying])::text[])) AND ((value_type)::text = 'STRING'::text)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_BOOLEAN_CONTAINS_ANY'::character varying, 'CANONICAL_BOOLEAN_CONTAINS_ALL'::character varying])::text[])) AND ((value_type)::text = 'BOOLEAN'::text)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_INTEGER_CONTAINS_ANY'::character varying, 'CANONICAL_INTEGER_CONTAINS_ALL'::character varying])::text[])) AND ((value_type)::text = 'INTEGER'::text)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_DECIMAL_CONTAINS_ANY'::character varying, 'CANONICAL_DECIMAL_CONTAINS_ALL'::character varying])::text[])) AND ((value_type)::text = 'DECIMAL'::text)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_DATE_CONTAINS_ANY'::character varying, 'CANONICAL_DATE_CONTAINS_ALL'::character varying])::text[])) AND ((value_type)::text = 'DATE'::text)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_DATETIME_CONTAINS_ANY'::character varying, 'CANONICAL_DATETIME_CONTAINS_ALL'::character varying])::text[])) AND ((value_type)::text = 'DATETIME'::text)) OR (((predicate_kind)::text = ANY ((ARRAY['CANONICAL_ENUM_CONTAINS_ANY'::character varying, 'CANONICAL_ENUM_CONTAINS_ALL'::character varying])::text[])) AND ((value_type)::text = 'ENUM'::text)))),
    CONSTRAINT lifecycle_access_policy_rule_expected_value_shape_ck CHECK (((((value_type)::text = 'STRING'::text) AND (value_string IS NOT NULL) AND (num_nonnulls(value_boolean, value_integer, value_decimal, value_date, value_datetime, value_enum) = 0)) OR (((value_type)::text = 'BOOLEAN'::text) AND (value_boolean IS NOT NULL) AND (num_nonnulls(value_string, value_integer, value_decimal, value_date, value_datetime, value_enum) = 0)) OR (((value_type)::text = 'INTEGER'::text) AND (value_integer IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_decimal, value_date, value_datetime, value_enum) = 0)) OR (((value_type)::text = 'DECIMAL'::text) AND (value_decimal IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_date, value_datetime, value_enum) = 0)) OR (((value_type)::text = 'DATE'::text) AND (value_date IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_datetime, value_enum) = 0)) OR (((value_type)::text = 'DATETIME'::text) AND (value_datetime IS NOT NULL) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_date, value_enum) = 0)) OR (((value_type)::text = 'ENUM'::text) AND (value_enum IS NOT NULL) AND (btrim((value_enum)::text) <> ''::text) AND (num_nonnulls(value_string, value_boolean, value_integer, value_decimal, value_date, value_datetime) = 0)))),
    CONSTRAINT lifecycle_access_policy_rule_expected_value_type_ck CHECK (((value_type)::text = ANY ((ARRAY['STRING'::character varying, 'BOOLEAN'::character varying, 'INTEGER'::character varying, 'DECIMAL'::character varying, 'DATE'::character varying, 'DATETIME'::character varying, 'ENUM'::character varying])::text[])))
);

COMMENT ON TABLE access.lifecycle_access_policy_rule_expected_value IS 'Typed relational expected-set members for lifecycle CONTAINS_ANY/CONTAINS_ALL predicates; application enforces 2-20 distinct homogeneous values.';

CREATE TABLE access.lifecycle_access_policy_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    version_number bigint NOT NULL,
    state character varying(24) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    activated_at timestamp with time zone NOT NULL,
    superseded_at timestamp with time zone,
    CONSTRAINT lifecycle_access_policy_version_number_ck CHECK ((version_number > 0)),
    CONSTRAINT lifecycle_access_policy_version_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUPERSEDED'::character varying])::text[]))),
    CONSTRAINT lifecycle_access_policy_version_time_ck CHECK (((((state)::text = 'ACTIVE'::text) AND (superseded_at IS NULL)) OR (((state)::text = 'SUPERSEDED'::text) AND (superseded_at IS NOT NULL) AND (superseded_at >= activated_at))))
);

COMMENT ON TABLE access.lifecycle_access_policy_version IS 'Immutable Access-owned Joiner/Mover lifecycle access policy version. At most one is ACTIVE per tenant.';

CREATE TABLE access.review_remediation_application (
    tenant_id uuid NOT NULL,
    review_remediation_id uuid NOT NULL,
    access_assignment_id uuid NOT NULL,
    outcome character varying(32) NOT NULL,
    resulting_lifecycle_state character varying(32) CONSTRAINT review_remediation_applicati_resulting_lifecycle_state_not_null NOT NULL,
    applied_at timestamp with time zone NOT NULL,
    CONSTRAINT access_review_remediation_application_outcome_ck CHECK (((outcome)::text = ANY ((ARRAY['APPLIED'::character varying, 'NO_ACTION_REQUIRED'::character varying])::text[]))),
    CONSTRAINT access_review_remediation_application_state_ck CHECK ((btrim((resulting_lifecycle_state)::text) <> ''::text))
);

COMMENT ON TABLE access.review_remediation_application IS 'Access-owned causal result for Governance review remediation. review_remediation_id is a stable cross-capability idempotency reference with no database foreign key.';

ALTER TABLE ONLY access.access_assignment
    ADD CONSTRAINT access_assignment_pkey PRIMARY KEY (id);

ALTER TABLE ONLY access.access_assignment
    ADD CONSTRAINT access_assignment_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY access.desired_grant_state
    ADD CONSTRAINT access_desired_grant_state_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY access.desired_grant_state
    ADD CONSTRAINT access_desired_grant_tuple_uq UNIQUE (tenant_id, identity_id, application_target_id, entitlement_id, principal_constraint_key);

ALTER TABLE ONLY access.desired_principal_state
    ADD CONSTRAINT access_desired_principal_state_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY access.desired_principal_state
    ADD CONSTRAINT access_desired_principal_tuple_uq UNIQUE (tenant_id, identity_id, application_target_id);

ALTER TABLE ONLY access.desired_grant_state
    ADD CONSTRAINT desired_grant_state_pkey PRIMARY KEY (id);

ALTER TABLE ONLY access.desired_principal_state
    ADD CONSTRAINT desired_principal_state_pkey PRIMARY KEY (id);

ALTER TABLE ONLY access.effective_access
    ADD CONSTRAINT effective_access_pkey PRIMARY KEY (id);

ALTER TABLE ONLY access.effective_access_support
    ADD CONSTRAINT effective_access_support_path_uq UNIQUE (tenant_id, effective_access_id, access_assignment_id, path_hash);

ALTER TABLE ONLY access.effective_access_support
    ADD CONSTRAINT effective_access_support_pkey PRIMARY KEY (id);

ALTER TABLE ONLY access.effective_access_support_role_version
    ADD CONSTRAINT effective_access_support_role_version_pkey PRIMARY KEY (tenant_id, effective_access_support_id, path_ordinal);

ALTER TABLE ONLY access.effective_access_support
    ADD CONSTRAINT effective_access_support_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY access.effective_access
    ADD CONSTRAINT effective_access_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY access.effective_access
    ADD CONSTRAINT effective_access_tuple_uq UNIQUE (tenant_id, identity_id, entitlement_id, principal_constraint_key);

ALTER TABLE ONLY access.identity_access_reduction
    ADD CONSTRAINT identity_access_reduction_pkey PRIMARY KEY (id);

ALTER TABLE ONLY access.identity_access_reduction
    ADD CONSTRAINT identity_access_reduction_source_uq UNIQUE (tenant_id, identity_id, source_identity_revision);

ALTER TABLE ONLY access.identity_access_reduction
    ADD CONSTRAINT identity_access_reduction_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY access.lifecycle_access_policy_rule_expected_value
    ADD CONSTRAINT lifecycle_access_policy_rule_expected_value_pkey PRIMARY KEY (tenant_id, policy_version_id, rule_id, value_ordinal);

ALTER TABLE ONLY access.lifecycle_access_policy_rule
    ADD CONSTRAINT lifecycle_access_policy_rule_pkey PRIMARY KEY (tenant_id, policy_version_id, rule_id);

ALTER TABLE ONLY access.lifecycle_access_policy_rule
    ADD CONSTRAINT lifecycle_access_policy_rule_predicate_ref_uq UNIQUE (tenant_id, policy_version_id, rule_id, predicate_kind);

ALTER TABLE ONLY access.lifecycle_access_policy_version
    ADD CONSTRAINT lifecycle_access_policy_version_number_uq UNIQUE (tenant_id, version_number);

ALTER TABLE ONLY access.lifecycle_access_policy_version
    ADD CONSTRAINT lifecycle_access_policy_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY access.lifecycle_access_policy_version
    ADD CONSTRAINT lifecycle_access_policy_version_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY access.review_remediation_application
    ADD CONSTRAINT review_remediation_application_pkey PRIMARY KEY (tenant_id, review_remediation_id);

CREATE INDEX access_assignment_entitlement_idx ON access.access_assignment USING btree (tenant_id, entitlement_id, identity_id) WHERE ((target_kind)::text = 'ENTITLEMENT'::text);

CREATE INDEX access_assignment_identity_reduction_page_idx ON access.access_assignment USING btree (tenant_id, identity_id, created_at, id) WHERE ((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUSPENDED'::character varying, 'SCHEDULED'::character varying])::text[]));

CREATE INDEX access_assignment_identity_state_idx ON access.access_assignment USING btree (tenant_id, identity_id, lifecycle_state, valid_until) WHERE ((lifecycle_state)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'ACTIVE'::character varying, 'SUSPENDED'::character varying])::text[]));

CREATE UNIQUE INDEX access_assignment_lifecycle_policy_rule_active_uq ON access.access_assignment USING btree (tenant_id, identity_id, provenance_ref_id) WHERE (((provenance_kind)::text = 'LIFECYCLE_POLICY_RULE'::text) AND ((lifecycle_state)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'ACTIVE'::character varying, 'SUSPENDED'::character varying])::text[])));

CREATE UNIQUE INDEX access_assignment_request_item_provenance_uq ON access.access_assignment USING btree (tenant_id, provenance_ref_id) WHERE ((provenance_kind)::text = 'REQUEST_ITEM'::text);

CREATE INDEX access_assignment_specific_principal_idx ON access.access_assignment USING btree (tenant_id, specific_principal_id, lifecycle_state) WHERE (specific_principal_id IS NOT NULL);

CREATE INDEX access_desired_grant_present_target_idx ON access.desired_grant_state USING btree (tenant_id, identity_id, application_target_id, desired_state);

CREATE INDEX access_desired_grant_state_target_idx ON access.desired_grant_state USING btree (tenant_id, application_target_id, identity_id, entitlement_id);

CREATE INDEX access_desired_principal_state_target_idx ON access.desired_principal_state USING btree (tenant_id, application_target_id, identity_id);

CREATE INDEX access_review_remediation_application_assignment_idx ON access.review_remediation_application USING btree (tenant_id, access_assignment_id, applied_at DESC);

CREATE INDEX effective_access_entitlement_identity_idx ON access.effective_access USING btree (tenant_id, entitlement_id, identity_id);

CREATE INDEX effective_access_identity_entitlement_idx ON access.effective_access USING btree (tenant_id, identity_id, entitlement_id);

CREATE INDEX effective_access_support_assignment_idx ON access.effective_access_support USING btree (tenant_id, access_assignment_id);

CREATE INDEX effective_access_support_role_version_lookup_idx ON access.effective_access_support_role_version USING btree (tenant_id, role_version_id, effective_access_support_id);

CREATE INDEX identity_access_reduction_state_idx ON access.identity_access_reduction USING btree (tenant_id, lifecycle_state, created_at, id);

CREATE UNIQUE INDEX lifecycle_access_policy_one_active_idx ON access.lifecycle_access_policy_version USING btree (tenant_id) WHERE ((state)::text = 'ACTIVE'::text);

ALTER TABLE ONLY access.access_assignment
    ADD CONSTRAINT access_assignment_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY access.desired_grant_state
    ADD CONSTRAINT access_desired_grant_state_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY access.desired_principal_state
    ADD CONSTRAINT access_desired_principal_state_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY access.review_remediation_application
    ADD CONSTRAINT access_review_remediation_application_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY access.effective_access_support
    ADD CONSTRAINT effective_access_support_effective_fk FOREIGN KEY (tenant_id, effective_access_id) REFERENCES access.effective_access(tenant_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY access.effective_access_support_role_version
    ADD CONSTRAINT effective_access_support_role_version_support_fk FOREIGN KEY (tenant_id, effective_access_support_id) REFERENCES access.effective_access_support(tenant_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY access.effective_access_support
    ADD CONSTRAINT effective_access_support_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY access.effective_access
    ADD CONSTRAINT effective_access_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY access.identity_access_reduction
    ADD CONSTRAINT identity_access_reduction_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY access.lifecycle_access_policy_rule_expected_value
    ADD CONSTRAINT lifecycle_access_policy_rule_expected_value_rule_fk FOREIGN KEY (tenant_id, policy_version_id, rule_id, predicate_kind) REFERENCES access.lifecycle_access_policy_rule(tenant_id, policy_version_id, rule_id, predicate_kind);

ALTER TABLE ONLY access.lifecycle_access_policy_rule
    ADD CONSTRAINT lifecycle_access_policy_rule_version_fk FOREIGN KEY (tenant_id, policy_version_id) REFERENCES access.lifecycle_access_policy_version(tenant_id, id);

ALTER TABLE ONLY access.lifecycle_access_policy_version
    ADD CONSTRAINT lifecycle_access_policy_version_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);
