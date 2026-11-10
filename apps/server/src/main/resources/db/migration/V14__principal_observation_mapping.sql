-- ADR-0050 Integration-owned provider Principal observation mapping.
-- Stable provider account identity remains observation provenance and never becomes Identity authority.

CREATE TABLE integration.principal_observation_mapping (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    provider_stable_id character varying(512) NOT NULL,
    principal_id uuid NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    retired_at timestamp with time zone,
    CONSTRAINT integration_principal_mapping_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT integration_principal_mapping_provider_ck
        CHECK (btrim(provider_stable_id) <> ''),
    CONSTRAINT integration_principal_mapping_state_ck
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT integration_principal_mapping_retired_ck CHECK (
        (lifecycle_state = 'ACTIVE' AND retired_at IS NULL)
        OR (lifecycle_state = 'RETIRED' AND retired_at IS NOT NULL)),
    CONSTRAINT integration_principal_mapping_revision_ck CHECK (revision > 0),
    CONSTRAINT integration_principal_mapping_timestamp_ck CHECK (updated_at >= created_at),
    CONSTRAINT integration_principal_mapping_binding_fk
        FOREIGN KEY (tenant_id, connector_binding_id)
        REFERENCES integration.connector_binding(tenant_id, id)
);

CREATE UNIQUE INDEX integration_principal_mapping_active_provider_uq
    ON integration.principal_observation_mapping
        (tenant_id, connector_binding_id, provider_stable_id)
    WHERE lifecycle_state = 'ACTIVE';

CREATE INDEX integration_principal_mapping_principal_idx
    ON integration.principal_observation_mapping
        (tenant_id, principal_id, lifecycle_state);

COMMENT ON TABLE integration.principal_observation_mapping IS
    'Integration-owned explicit interpretation from one stable provider account observation identity to one existing governed Identity-owned Principal stable ID. No cross-capability FK transfers Principal authority.';
