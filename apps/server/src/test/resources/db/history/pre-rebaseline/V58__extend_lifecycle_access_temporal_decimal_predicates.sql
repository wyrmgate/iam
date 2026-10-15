ALTER TABLE access.lifecycle_access_policy_rule
    ADD COLUMN expected_decimal numeric(38,12) NULL,
    ADD COLUMN expected_date date NULL,
    ADD COLUMN expected_datetime timestamptz NULL;

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
            'CANONICAL_ENUM_EQUALS'
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
        (predicate_kind = 'CANONICAL_STRING_EQUALS'
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_string IS NOT NULL
            AND expected_boolean IS NULL
            AND expected_integer IS NULL
            AND expected_decimal IS NULL
            AND expected_date IS NULL
            AND expected_datetime IS NULL
            AND expected_enum IS NULL)
        OR
        (predicate_kind = 'CANONICAL_BOOLEAN_EQUALS'
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_string IS NULL
            AND expected_boolean IS NOT NULL
            AND expected_integer IS NULL
            AND expected_decimal IS NULL
            AND expected_date IS NULL
            AND expected_datetime IS NULL
            AND expected_enum IS NULL)
        OR
        (predicate_kind = 'CANONICAL_INTEGER_EQUALS'
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_string IS NULL
            AND expected_boolean IS NULL
            AND expected_integer IS NOT NULL
            AND expected_decimal IS NULL
            AND expected_date IS NULL
            AND expected_datetime IS NULL
            AND expected_enum IS NULL)
        OR
        (predicate_kind = 'CANONICAL_DECIMAL_EQUALS'
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_string IS NULL
            AND expected_boolean IS NULL
            AND expected_integer IS NULL
            AND expected_decimal IS NOT NULL
            AND expected_date IS NULL
            AND expected_datetime IS NULL
            AND expected_enum IS NULL)
        OR
        (predicate_kind = 'CANONICAL_DATE_EQUALS'
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_string IS NULL
            AND expected_boolean IS NULL
            AND expected_integer IS NULL
            AND expected_decimal IS NULL
            AND expected_date IS NOT NULL
            AND expected_datetime IS NULL
            AND expected_enum IS NULL)
        OR
        (predicate_kind = 'CANONICAL_DATETIME_EQUALS'
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_string IS NULL
            AND expected_boolean IS NULL
            AND expected_integer IS NULL
            AND expected_decimal IS NULL
            AND expected_date IS NULL
            AND expected_datetime IS NOT NULL
            AND expected_enum IS NULL)
        OR
        (predicate_kind = 'CANONICAL_ENUM_EQUALS'
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_string IS NULL
            AND expected_boolean IS NULL
            AND expected_integer IS NULL
            AND expected_decimal IS NULL
            AND expected_date IS NULL
            AND expected_datetime IS NULL
            AND expected_enum IS NOT NULL AND btrim(expected_enum) <> '')
    );

COMMENT ON COLUMN access.lifecycle_access_policy_rule.expected_decimal IS
    'Exact normalized DECIMAL expected value for CANONICAL_DECIMAL_EQUALS; application validation prevents numeric(38,12) rounding.';
COMMENT ON COLUMN access.lifecycle_access_policy_rule.expected_date IS
    'Exact DATE expected value for CANONICAL_DATE_EQUALS.';
COMMENT ON COLUMN access.lifecycle_access_policy_rule.expected_datetime IS
    'Exact absolute microsecond-precision timestamp expected value for CANONICAL_DATETIME_EQUALS.';
