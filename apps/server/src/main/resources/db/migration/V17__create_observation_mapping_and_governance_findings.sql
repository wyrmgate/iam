CREATE TABLE integration.entitlement_observation_mapping (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    provider_stable_id varchar(512) NOT NULL,
    entitlement_id uuid NOT NULL,
    lifecycle_state varchar(24) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    retired_at timestamptz NULL,
    CONSTRAINT integration_entitlement_mapping_tenant_id_uq
        UNIQUE (tenant_id, id),
    CONSTRAINT integration_entitlement_mapping_binding_fk
        FOREIGN KEY (tenant_id, connector_binding_id)
        REFERENCES integration.connector_binding (tenant_id, id),
    CONSTRAINT integration_entitlement_mapping_provider_ck
        CHECK (btrim(provider_stable_id) <> ''),
    CONSTRAINT integration_entitlement_mapping_state_ck
        CHECK (lifecycle_state IN ('ACTIVE','RETIRED')),
    CONSTRAINT integration_entitlement_mapping_revision_ck
        CHECK (revision > 0),
    CONSTRAINT integration_entitlement_mapping_retired_ck
        CHECK (
            (lifecycle_state = 'ACTIVE' AND retired_at IS NULL)
            OR (lifecycle_state = 'RETIRED' AND retired_at IS NOT NULL)
        ),
    CONSTRAINT integration_entitlement_mapping_timestamp_ck
        CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX integration_entitlement_mapping_active_provider_uq
    ON integration.entitlement_observation_mapping
        (tenant_id, connector_binding_id, provider_stable_id)
    WHERE lifecycle_state = 'ACTIVE';

CREATE UNIQUE INDEX integration_entitlement_mapping_active_entitlement_uq
    ON integration.entitlement_observation_mapping
        (tenant_id, connector_binding_id, entitlement_id)
    WHERE lifecycle_state = 'ACTIVE';

CREATE INDEX integration_entitlement_mapping_entitlement_idx
    ON integration.entitlement_observation_mapping
        (tenant_id, entitlement_id, lifecycle_state);

CREATE TABLE governance.finding (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    finding_key varchar(128) NOT NULL,
    finding_type varchar(64) NOT NULL,
    subject_kind varchar(64) NOT NULL,
    connector_binding_id uuid NOT NULL,
    provider_stable_id varchar(512) NOT NULL,
    related_provider_stable_id varchar(512) NULL,
    lifecycle_state varchar(24) NOT NULL,
    first_observed_at timestamptz NOT NULL,
    last_observed_at timestamptz NOT NULL,
    resolved_at timestamptz NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT governance_finding_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT governance_finding_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT governance_finding_key_uq UNIQUE (tenant_id, finding_key),
    CONSTRAINT governance_finding_type_ck CHECK (
        finding_type IN (
            'UNMAPPED_PROVIDER_ENTITLEMENT',
            'UNMAPPED_PROVIDER_GRANT_ENTITLEMENT',
            'UNRESOLVED_PROVIDER_GRANT_PRINCIPAL'
        )
    ),
    CONSTRAINT governance_finding_subject_kind_ck
        CHECK (subject_kind IN ('OBSERVED_ENTITLEMENT','OBSERVED_GRANT')),
    CONSTRAINT governance_finding_provider_ck
        CHECK (btrim(provider_stable_id) <> ''),
    CONSTRAINT governance_finding_state_ck
        CHECK (lifecycle_state IN ('OPEN','RESOLVED')),
    CONSTRAINT governance_finding_state_time_ck CHECK (
        (lifecycle_state = 'OPEN' AND resolved_at IS NULL)
        OR (lifecycle_state = 'RESOLVED' AND resolved_at IS NOT NULL)
    ),
    CONSTRAINT governance_finding_revision_ck CHECK (revision > 0),
    CONSTRAINT governance_finding_observed_time_ck
        CHECK (last_observed_at >= first_observed_at),
    CONSTRAINT governance_finding_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE INDEX governance_finding_open_binding_idx
    ON governance.finding
        (tenant_id, connector_binding_id, finding_type, provider_stable_id)
    WHERE lifecycle_state = 'OPEN';

COMMENT ON TABLE integration.entitlement_observation_mapping IS
    'Integration-owned explicit resolution from provider entitlement observation identity to existing Catalog Entitlement stable ID. Mapping is not Catalog authority.';
COMMENT ON TABLE governance.finding IS
    'Governance-owned actionable issue state. Observation-derived findings contain minimized normalized identifiers, never provider payloads or secrets.';
