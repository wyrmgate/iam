-- Integration owns the input generation; Governance owns only its evaluation checkpoint.
CREATE TABLE integration.observed_access_generation (
    tenant_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    generation bigint NOT NULL CHECK (generation > 0),
    PRIMARY KEY (tenant_id, connector_binding_id),
    FOREIGN KEY (tenant_id, connector_binding_id)
        REFERENCES integration.connector_binding(tenant_id, id)
);

CREATE TABLE governance.observation_evaluation (
    tenant_id uuid NOT NULL REFERENCES tenancy.tenant(id),
    connector_binding_id uuid NOT NULL,
    source_generation bigint NOT NULL CHECK (source_generation >= 0),
    phase varchar(24) NOT NULL CHECK (phase IN ('ENTITLEMENTS','GRANTS','RESOLUTION','COMPLETED')),
    after_key varchar(512),
    revision bigint NOT NULL CHECK (revision > 0),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, connector_binding_id),
    CHECK (phase <> 'COMPLETED' OR after_key IS NULL)
);

CREATE INDEX governance_finding_open_page_idx
    ON governance.finding (tenant_id, connector_binding_id, finding_key)
    WHERE lifecycle_state = 'OPEN';
