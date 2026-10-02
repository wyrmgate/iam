ALTER TABLE access.lifecycle_access_policy_rule
    DROP CONSTRAINT lifecycle_access_policy_rule_predicate_ck,
    DROP CONSTRAINT lifecycle_access_policy_rule_predicate_shape_ck,
    ADD CONSTRAINT lifecycle_access_policy_rule_predicate_ck
        CHECK (predicate_kind IN (
            'ALWAYS',
            'CANONICAL_STRING_EQUALS',
            'CANONICAL_BOOLEAN_EQUALS',
            'CANONICAL_INTEGER_EQUALS',
            'CANONICAL_DECIMAL_EQUALS',
            'CANONICAL_DATE_EQUALS',
            'CANONICAL_DATETIME_EQUALS',
            'CANONICAL_ENUM_EQUALS',
            'CANONICAL_STRING_CONTAINS',
            'CANONICAL_BOOLEAN_CONTAINS',
            'CANONICAL_INTEGER_CONTAINS',
            'CANONICAL_DECIMAL_CONTAINS',
            'CANONICAL_DATE_CONTAINS',
            'CANONICAL_DATETIME_CONTAINS',
            'CANONICAL_ENUM_CONTAINS'
        )),
    ADD CONSTRAINT lifecycle_access_policy_rule_predicate_shape_ck CHECK (
        (predicate_kind = 'ALWAYS'
            AND canonical_key IS NULL
            AND expected_string IS NULL
            AND expected_boolean IS NULL
            AND expected_integer IS NULL
            AND expected_decimal IS NULL
            AND expected_date IS NULL
            AND expected_datetime IS NULL
            AND expected_enum IS NULL)
        OR
        (predicate_kind IN ('CANONICAL_STRING_EQUALS','CANONICAL_STRING_CONTAINS')
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_string IS NOT NULL
            AND expected_boolean IS NULL
            AND expected_integer IS NULL
            AND expected_decimal IS NULL
            AND expected_date IS NULL
            AND expected_datetime IS NULL
            AND expected_enum IS NULL)
        OR
        (predicate_kind IN ('CANONICAL_BOOLEAN_EQUALS','CANONICAL_BOOLEAN_CONTAINS')
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_string IS NULL
            AND expected_boolean IS NOT NULL
            AND expected_integer IS NULL
            AND expected_decimal IS NULL
            AND expected_date IS NULL
            AND expected_datetime IS NULL
            AND expected_enum IS NULL)
        OR
        (predicate_kind IN ('CANONICAL_INTEGER_EQUALS','CANONICAL_INTEGER_CONTAINS')
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_string IS NULL
            AND expected_boolean IS NULL
            AND expected_integer IS NOT NULL
            AND expected_decimal IS NULL
            AND expected_date IS NULL
            AND expected_datetime IS NULL
            AND expected_enum IS NULL)
        OR
        (predicate_kind IN ('CANONICAL_DECIMAL_EQUALS','CANONICAL_DECIMAL_CONTAINS')
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_string IS NULL
            AND expected_boolean IS NULL
            AND expected_integer IS NULL
            AND expected_decimal IS NOT NULL
            AND expected_date IS NULL
            AND expected_datetime IS NULL
            AND expected_enum IS NULL)
        OR
        (predicate_kind IN ('CANONICAL_DATE_EQUALS','CANONICAL_DATE_CONTAINS')
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_string IS NULL
            AND expected_boolean IS NULL
            AND expected_integer IS NULL
            AND expected_decimal IS NULL
            AND expected_date IS NOT NULL
            AND expected_datetime IS NULL
            AND expected_enum IS NULL)
        OR
        (predicate_kind IN ('CANONICAL_DATETIME_EQUALS','CANONICAL_DATETIME_CONTAINS')
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_string IS NULL
            AND expected_boolean IS NULL
            AND expected_integer IS NULL
            AND expected_decimal IS NULL
            AND expected_date IS NULL
            AND expected_datetime IS NOT NULL
            AND expected_enum IS NULL)
        OR
        (predicate_kind IN ('CANONICAL_ENUM_EQUALS','CANONICAL_ENUM_CONTAINS')
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_string IS NULL
            AND expected_boolean IS NULL
            AND expected_integer IS NULL
            AND expected_decimal IS NULL
            AND expected_date IS NULL
            AND expected_datetime IS NULL
            AND expected_enum IS NOT NULL AND btrim(expected_enum) <> '')
    );

COMMENT ON CONSTRAINT lifecycle_access_policy_rule_predicate_ck
    ON access.lifecycle_access_policy_rule IS
    'Closed lifecycle-access predicate vocabulary: exact SINGLE equality plus typed MULTI membership.';
COMMENT ON CONSTRAINT lifecycle_access_policy_rule_predicate_shape_ck
    ON access.lifecycle_access_policy_rule IS
    'Each lifecycle predicate carries exactly one typed expected value; cardinality compatibility is validated through the Identity semantic query at activation.';
