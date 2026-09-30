ALTER TABLE access.lifecycle_access_policy_rule
    ADD COLUMN expected_boolean boolean NULL,
    ADD COLUMN expected_integer bigint NULL,
    ADD COLUMN expected_enum varchar(256) NULL;

ALTER TABLE access.lifecycle_access_policy_rule
    DROP CONSTRAINT lifecycle_access_policy_rule_predicate_ck,
    DROP CONSTRAINT lifecycle_access_policy_rule_predicate_shape_ck,
    ADD CONSTRAINT lifecycle_access_policy_rule_predicate_ck
        CHECK (predicate_kind IN (
            'ALWAYS',
            'CANONICAL_STRING_EQUALS',
            'CANONICAL_BOOLEAN_EQUALS',
            'CANONICAL_INTEGER_EQUALS',
            'CANONICAL_ENUM_EQUALS'
        )),
    ADD CONSTRAINT lifecycle_access_policy_rule_predicate_shape_ck CHECK (
        (predicate_kind = 'ALWAYS'
            AND canonical_key IS NULL
            AND expected_string IS NULL
            AND expected_boolean IS NULL
            AND expected_integer IS NULL
            AND expected_enum IS NULL)
        OR
        (predicate_kind = 'CANONICAL_STRING_EQUALS'
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_string IS NOT NULL
            AND expected_boolean IS NULL AND expected_integer IS NULL AND expected_enum IS NULL)
        OR
        (predicate_kind = 'CANONICAL_BOOLEAN_EQUALS'
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_string IS NULL
            AND expected_boolean IS NOT NULL
            AND expected_integer IS NULL AND expected_enum IS NULL)
        OR
        (predicate_kind = 'CANONICAL_INTEGER_EQUALS'
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_string IS NULL AND expected_boolean IS NULL
            AND expected_integer IS NOT NULL AND expected_enum IS NULL)
        OR
        (predicate_kind = 'CANONICAL_ENUM_EQUALS'
            AND canonical_key IS NOT NULL AND btrim(canonical_key) <> ''
            AND expected_string IS NULL AND expected_boolean IS NULL AND expected_integer IS NULL
            AND expected_enum IS NOT NULL AND btrim(expected_enum) <> '')
    );

COMMENT ON COLUMN access.lifecycle_access_policy_rule.expected_boolean IS
    'Typed BOOLEAN expected value for CANONICAL_BOOLEAN_EQUALS.';
COMMENT ON COLUMN access.lifecycle_access_policy_rule.expected_integer IS
    'Typed INTEGER expected value for CANONICAL_INTEGER_EQUALS.';
COMMENT ON COLUMN access.lifecycle_access_policy_rule.expected_enum IS
    'Typed ENUM key expected value for CANONICAL_ENUM_EQUALS.';
