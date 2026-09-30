ALTER TABLE administration.administrative_grant
    ADD COLUMN scope_key varchar(128) NULL;

ALTER TABLE administration.administrative_grant
    DROP CONSTRAINT administration_grant_scope_type_ck,
    DROP CONSTRAINT administration_grant_scope_shape_ck,
    ADD CONSTRAINT administration_grant_scope_type_ck CHECK (
        scope_type IN (
            'GLOBAL', 'ORGANIZATION', 'APPLICATION', 'APPLICATION_TARGET',
            'SOURCE_SYSTEM', 'CONNECTOR_INSTANCE', 'IDENTITY_POPULATION',
            'CANONICAL_ATTRIBUTE_CLASSIFICATION', 'SPECIFIC_RESOURCE'
        )
    ),
    ADD CONSTRAINT administration_grant_scope_shape_ck CHECK (
        (scope_type = 'GLOBAL'
            AND scope_resource_type IS NULL
            AND scope_ref_id IS NULL
            AND scope_key IS NULL)
        OR
        (scope_type = 'SPECIFIC_RESOURCE'
            AND scope_resource_type IS NOT NULL
            AND btrim(scope_resource_type) <> ''
            AND scope_ref_id IS NOT NULL
            AND scope_key IS NULL)
        OR
        (scope_type = 'CANONICAL_ATTRIBUTE_CLASSIFICATION'
            AND scope_resource_type IS NULL
            AND scope_ref_id IS NULL
            AND scope_key IS NOT NULL
            AND btrim(scope_key) <> '')
        OR
        (scope_type NOT IN ('GLOBAL', 'SPECIFIC_RESOURCE', 'CANONICAL_ATTRIBUTE_CLASSIFICATION')
            AND scope_resource_type IS NULL
            AND scope_ref_id IS NOT NULL
            AND scope_key IS NULL)
    );

COMMENT ON COLUMN administration.administrative_grant.scope_key IS
    'Exact tenant-local semantic key for string-keyed scopes such as CANONICAL_ATTRIBUTE_CLASSIFICATION.';
