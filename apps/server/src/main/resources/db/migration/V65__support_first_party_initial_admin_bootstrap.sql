ALTER TABLE administration.initial_admin_bootstrap
    ADD COLUMN authentication_anchor_type varchar(32),
    ADD COLUMN actor_principal_id uuid;

UPDATE administration.initial_admin_bootstrap
SET authentication_anchor_type = 'EXTERNAL_SUBJECT'
WHERE authentication_anchor_type IS NULL;

ALTER TABLE administration.initial_admin_bootstrap
    ALTER COLUMN authentication_anchor_type SET NOT NULL,
    ALTER COLUMN actor_binding_id DROP NOT NULL;

ALTER TABLE administration.initial_admin_bootstrap
    ADD CONSTRAINT administration_initial_bootstrap_anchor_type_ck CHECK (
        authentication_anchor_type IN ('EXTERNAL_SUBJECT', 'FIRST_PARTY_PRINCIPAL')
    ),
    ADD CONSTRAINT administration_initial_bootstrap_anchor_shape_ck CHECK (
        (authentication_anchor_type = 'EXTERNAL_SUBJECT'
            AND actor_binding_id IS NOT NULL
            AND actor_principal_id IS NULL)
        OR
        (authentication_anchor_type = 'FIRST_PARTY_PRINCIPAL'
            AND actor_binding_id IS NULL
            AND actor_principal_id IS NOT NULL)
    );

COMMENT ON COLUMN administration.initial_admin_bootstrap.authentication_anchor_type IS
    'Typed authentication source for the immutable first-admin marker: external subject binding or first-party governed Principal.';
COMMENT ON COLUMN administration.initial_admin_bootstrap.actor_principal_id IS
    'Cross-capability stable Principal ID used only for FIRST_PARTY_PRINCIPAL bootstrap; intentionally no database FK to Identity-owned state.';
