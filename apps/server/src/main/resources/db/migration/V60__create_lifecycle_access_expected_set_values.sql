ALTER TABLE access.lifecycle_access_policy_rule
    DROP CONSTRAINT lifecycle_access_policy_rule_predicate_ck,
    DROP CONSTRAINT lifecycle_access_policy_rule_predicate_shape_ck,
    ADD CONSTRAINT lifecycle_access_policy_rule_predicate_ck
        CHECK (predicate_kind IN (
            'ALWAYS',
            'CANONICAL_STRING_EQUALS','CANONICAL_BOOLEAN_EQUALS','CANONICAL_INTEGER_EQUALS',
            'CANONICAL_DECIMAL_EQUALS','CANONICAL_DATE_EQUALS','CANONICAL_DATETIME_EQUALS','CANONICAL_ENUM_EQUALS',
            'CANONICAL_STRING_CONTAINS','CANONICAL_BOOLEAN_CONTAINS','CANONICAL_INTEGER_CONTAINS',
            'CANONICAL_DECIMAL_CONTAINS','CANONICAL_DATE_CONTAINS','CANONICAL_DATETIME_CONTAINS','CANONICAL_ENUM_CONTAINS',
            'CANONICAL_STRING_CONTAINS_ANY','CANONICAL_BOOLEAN_CONTAINS_ANY','CANONICAL_INTEGER_CONTAINS_ANY',
            'CANONICAL_DECIMAL_CONTAINS_ANY','CANONICAL_DATE_CONTAINS_ANY','CANONICAL_DATETIME_CONTAINS_ANY','CANONICAL_ENUM_CONTAINS_ANY',
            'CANONICAL_STRING_CONTAINS_ALL','CANONICAL_BOOLEAN_CONTAINS_ALL','CANONICAL_INTEGER_CONTAINS_ALL',
            'CANONICAL_DECIMAL_CONTAINS_ALL','CANONICAL_DATE_CONTAINS_ALL','CANONICAL_DATETIME_CONTAINS_ALL','CANONICAL_ENUM_CONTAINS_ALL'
        )),
    ADD CONSTRAINT lifecycle_access_policy_rule_predicate_shape_ck CHECK (
        (predicate_kind = 'ALWAYS'
            AND canonical_key IS NULL
            AND num_nonnulls(expected_string,expected_boolean,expected_integer,expected_decimal,expected_date,expected_datetime,expected_enum)=0)
        OR
        (predicate_kind IN ('CANONICAL_STRING_EQUALS','CANONICAL_STRING_CONTAINS')
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_string IS NOT NULL
            AND num_nonnulls(expected_boolean,expected_integer,expected_decimal,expected_date,expected_datetime,expected_enum)=0)
        OR
        (predicate_kind IN ('CANONICAL_BOOLEAN_EQUALS','CANONICAL_BOOLEAN_CONTAINS')
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_boolean IS NOT NULL
            AND num_nonnulls(expected_string,expected_integer,expected_decimal,expected_date,expected_datetime,expected_enum)=0)
        OR
        (predicate_kind IN ('CANONICAL_INTEGER_EQUALS','CANONICAL_INTEGER_CONTAINS')
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_integer IS NOT NULL
            AND num_nonnulls(expected_string,expected_boolean,expected_decimal,expected_date,expected_datetime,expected_enum)=0)
        OR
        (predicate_kind IN ('CANONICAL_DECIMAL_EQUALS','CANONICAL_DECIMAL_CONTAINS')
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_decimal IS NOT NULL
            AND num_nonnulls(expected_string,expected_boolean,expected_integer,expected_date,expected_datetime,expected_enum)=0)
        OR
        (predicate_kind IN ('CANONICAL_DATE_EQUALS','CANONICAL_DATE_CONTAINS')
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_date IS NOT NULL
            AND num_nonnulls(expected_string,expected_boolean,expected_integer,expected_decimal,expected_datetime,expected_enum)=0)
        OR
        (predicate_kind IN ('CANONICAL_DATETIME_EQUALS','CANONICAL_DATETIME_CONTAINS')
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_datetime IS NOT NULL
            AND num_nonnulls(expected_string,expected_boolean,expected_integer,expected_decimal,expected_date,expected_enum)=0)
        OR
        (predicate_kind IN ('CANONICAL_ENUM_EQUALS','CANONICAL_ENUM_CONTAINS')
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_enum IS NOT NULL AND btrim(expected_enum) <> ''
            AND num_nonnulls(expected_string,expected_boolean,expected_integer,expected_decimal,expected_date,expected_datetime)=0)
        OR
        (predicate_kind LIKE 'CANONICAL_%_CONTAINS_ANY'
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND num_nonnulls(expected_string,expected_boolean,expected_integer,expected_decimal,expected_date,expected_datetime,expected_enum)=0)
        OR
        (predicate_kind LIKE 'CANONICAL_%_CONTAINS_ALL'
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND num_nonnulls(expected_string,expected_boolean,expected_integer,expected_decimal,expected_date,expected_datetime,expected_enum)=0)
    );

ALTER TABLE access.lifecycle_access_policy_rule
    ADD CONSTRAINT lifecycle_access_policy_rule_predicate_ref_uq
        UNIQUE (tenant_id, policy_version_id, rule_id, predicate_kind);

CREATE TABLE access.lifecycle_access_policy_rule_expected_value (
    tenant_id uuid NOT NULL,
    policy_version_id uuid NOT NULL,
    rule_id uuid NOT NULL,
    predicate_kind varchar(32) NOT NULL,
    value_ordinal integer NOT NULL,
    value_type varchar(24) NOT NULL,
    value_string varchar(1024) NULL,
    value_boolean boolean NULL,
    value_integer bigint NULL,
    value_decimal numeric(38,12) NULL,
    value_date date NULL,
    value_datetime timestamptz NULL,
    value_enum varchar(256) NULL,
    PRIMARY KEY (tenant_id, policy_version_id, rule_id, value_ordinal),
    CONSTRAINT lifecycle_access_policy_rule_expected_value_rule_fk
        FOREIGN KEY (tenant_id, policy_version_id, rule_id, predicate_kind)
        REFERENCES access.lifecycle_access_policy_rule
            (tenant_id, policy_version_id, rule_id, predicate_kind),
    CONSTRAINT lifecycle_access_policy_rule_expected_value_predicate_type_ck CHECK (
        (predicate_kind IN ('CANONICAL_STRING_CONTAINS_ANY','CANONICAL_STRING_CONTAINS_ALL') AND value_type='STRING')
        OR (predicate_kind IN ('CANONICAL_BOOLEAN_CONTAINS_ANY','CANONICAL_BOOLEAN_CONTAINS_ALL') AND value_type='BOOLEAN')
        OR (predicate_kind IN ('CANONICAL_INTEGER_CONTAINS_ANY','CANONICAL_INTEGER_CONTAINS_ALL') AND value_type='INTEGER')
        OR (predicate_kind IN ('CANONICAL_DECIMAL_CONTAINS_ANY','CANONICAL_DECIMAL_CONTAINS_ALL') AND value_type='DECIMAL')
        OR (predicate_kind IN ('CANONICAL_DATE_CONTAINS_ANY','CANONICAL_DATE_CONTAINS_ALL') AND value_type='DATE')
        OR (predicate_kind IN ('CANONICAL_DATETIME_CONTAINS_ANY','CANONICAL_DATETIME_CONTAINS_ALL') AND value_type='DATETIME')
        OR (predicate_kind IN ('CANONICAL_ENUM_CONTAINS_ANY','CANONICAL_ENUM_CONTAINS_ALL') AND value_type='ENUM')
    ),
    CONSTRAINT lifecycle_access_policy_rule_expected_value_ordinal_ck
        CHECK (value_ordinal >= 0 AND value_ordinal < 20),
    CONSTRAINT lifecycle_access_policy_rule_expected_value_type_ck
        CHECK (value_type IN ('STRING','BOOLEAN','INTEGER','DECIMAL','DATE','DATETIME','ENUM')),
    CONSTRAINT lifecycle_access_policy_rule_expected_value_shape_ck CHECK (
        (value_type='STRING' AND value_string IS NOT NULL
            AND num_nonnulls(value_boolean,value_integer,value_decimal,value_date,value_datetime,value_enum)=0)
        OR (value_type='BOOLEAN' AND value_boolean IS NOT NULL
            AND num_nonnulls(value_string,value_integer,value_decimal,value_date,value_datetime,value_enum)=0)
        OR (value_type='INTEGER' AND value_integer IS NOT NULL
            AND num_nonnulls(value_string,value_boolean,value_decimal,value_date,value_datetime,value_enum)=0)
        OR (value_type='DECIMAL' AND value_decimal IS NOT NULL
            AND num_nonnulls(value_string,value_boolean,value_integer,value_date,value_datetime,value_enum)=0)
        OR (value_type='DATE' AND value_date IS NOT NULL
            AND num_nonnulls(value_string,value_boolean,value_integer,value_decimal,value_datetime,value_enum)=0)
        OR (value_type='DATETIME' AND value_datetime IS NOT NULL
            AND num_nonnulls(value_string,value_boolean,value_integer,value_decimal,value_date,value_enum)=0)
        OR (value_type='ENUM' AND value_enum IS NOT NULL AND btrim(value_enum) <> ''
            AND num_nonnulls(value_string,value_boolean,value_integer,value_decimal,value_date,value_datetime)=0)
    )
);

COMMENT ON TABLE access.lifecycle_access_policy_rule_expected_value IS
    'Typed relational expected-set members for lifecycle CONTAINS_ANY/CONTAINS_ALL predicates; application enforces 2-20 distinct homogeneous values.';
