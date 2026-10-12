CREATE TABLE platform.installation_bootstrap (
    singleton_key boolean PRIMARY KEY DEFAULT true,
    state varchar(32) NOT NULL,
    tenant_id uuid NOT NULL,
    tenant_display_name varchar(512) NOT NULL,
    identity_id uuid NOT NULL,
    identity_display_name varchar(512) NOT NULL,
    console_application_id uuid NOT NULL,
    console_target_id uuid NOT NULL,
    console_sso_registration_id uuid NOT NULL,
    console_client_id varchar(200) NOT NULL,
    console_redirect_uri varchar(2048) NOT NULL,
    principal_id uuid NOT NULL,
    principal_key varchar(512) NOT NULL,
    credential_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    bootstrap_version integer NOT NULL,
    started_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    sealed_at timestamptz,
    CONSTRAINT platform_installation_bootstrap_singleton_ck CHECK (singleton_key),
    CONSTRAINT platform_installation_bootstrap_state_ck CHECK (
        state IN (
            'PLANNED',
            'TENANT_CREATED',
            'IDENTITY_CREATED',
            'CONSOLE_REGISTERED',
            'PRINCIPAL_CREATED',
            'CREDENTIAL_ENROLLED',
            'ADMIN_BOOTSTRAPPED',
            'VERIFIED',
            'SEALED'
        )
    ),
    CONSTRAINT platform_installation_bootstrap_version_ck CHECK (bootstrap_version > 0),
    CONSTRAINT platform_installation_bootstrap_time_ck CHECK (updated_at >= started_at),
    CONSTRAINT platform_installation_bootstrap_seal_ck CHECK (
        (state = 'SEALED' AND sealed_at IS NOT NULL)
        OR (state <> 'SEALED' AND sealed_at IS NULL)
    ),
    CONSTRAINT platform_installation_bootstrap_tenant_name_ck CHECK (btrim(tenant_display_name) <> ''),
    CONSTRAINT platform_installation_bootstrap_identity_name_ck CHECK (btrim(identity_display_name) <> ''),
    CONSTRAINT platform_installation_bootstrap_client_id_ck CHECK (btrim(console_client_id) <> ''),
    CONSTRAINT platform_installation_bootstrap_redirect_ck CHECK (btrim(console_redirect_uri) <> ''),
    CONSTRAINT platform_installation_bootstrap_principal_key_ck CHECK (btrim(principal_key) <> '')
);

COMMENT ON TABLE platform.installation_bootstrap IS
    'Deployment-local ADR-0044 fresh-install plan and irreversible seal. It stores stable identifiers and non-secret inputs only; no password or verifier material is permitted.';
COMMENT ON COLUMN platform.installation_bootstrap.tenant_id IS
    'Preallocated first Tenant ID. No FK is intentional because the plan is committed before the Tenant exists.';
COMMENT ON COLUMN platform.installation_bootstrap.sealed_at IS
    'Permanent terminal installation seal. Loss of later administrative authority never reopens this process.';
