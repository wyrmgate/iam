ALTER TABLE integration.connector_binding
    ADD COLUMN supports_complete_entitlement_discovery boolean NOT NULL DEFAULT false,
    ADD COLUMN supports_complete_grant_discovery boolean NOT NULL DEFAULT false;

ALTER TABLE integration.reconciliation_run
    DROP CONSTRAINT integration_reconciliation_run_scope_ck;

ALTER TABLE integration.reconciliation_run
    ADD CONSTRAINT integration_reconciliation_run_scope_ck
        CHECK (scope_object_class IN ('PRINCIPAL', 'ENTITLEMENT', 'GRANT'));

CREATE TABLE integration.reconciliation_entitlement_staging (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    reconciliation_run_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    provider_stable_id varchar(512) NOT NULL,
    provider_version varchar(512) NULL,
    observed_state jsonb NOT NULL,
    observed_at timestamptz NOT NULL,
    CONSTRAINT integration_reconciliation_entitlement_stage_tenant_id_uq
        UNIQUE (tenant_id, id),
    CONSTRAINT integration_reconciliation_entitlement_stage_provider_uq
        UNIQUE (tenant_id, reconciliation_run_id, provider_stable_id),
    CONSTRAINT integration_reconciliation_entitlement_stage_batch_fk
        FOREIGN KEY (tenant_id, reconciliation_run_id, batch_id)
        REFERENCES integration.reconciliation_observation_batch
            (tenant_id, reconciliation_run_id, batch_id),
    CONSTRAINT integration_reconciliation_entitlement_stage_provider_ck
        CHECK (btrim(provider_stable_id) <> '')
);

CREATE TABLE integration.reconciliation_grant_staging (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    reconciliation_run_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    provider_stable_id varchar(512) NOT NULL,
    provider_version varchar(512) NULL,
    principal_provider_id varchar(512) NOT NULL,
    entitlement_provider_id varchar(512) NOT NULL,
    observed_state jsonb NOT NULL,
    observed_at timestamptz NOT NULL,
    CONSTRAINT integration_reconciliation_grant_stage_tenant_id_uq
        UNIQUE (tenant_id, id),
    CONSTRAINT integration_reconciliation_grant_stage_provider_uq
        UNIQUE (tenant_id, reconciliation_run_id, provider_stable_id),
    CONSTRAINT integration_reconciliation_grant_stage_pair_uq
        UNIQUE (tenant_id, reconciliation_run_id, principal_provider_id, entitlement_provider_id),
    CONSTRAINT integration_reconciliation_grant_stage_batch_fk
        FOREIGN KEY (tenant_id, reconciliation_run_id, batch_id)
        REFERENCES integration.reconciliation_observation_batch
            (tenant_id, reconciliation_run_id, batch_id),
    CONSTRAINT integration_reconciliation_grant_stage_provider_ck
        CHECK (
            btrim(provider_stable_id) <> ''
            AND btrim(principal_provider_id) <> ''
            AND btrim(entitlement_provider_id) <> ''
        )
);

CREATE TABLE integration.observed_entitlement (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    provider_stable_id varchar(512) NOT NULL,
    provider_version varchar(512) NULL,
    observed_state jsonb NOT NULL,
    present boolean NOT NULL,
    last_observed_run_id uuid NOT NULL,
    observed_at timestamptz NOT NULL,
    absent_at timestamptz NULL,
    CONSTRAINT integration_observed_entitlement_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT integration_observed_entitlement_provider_uq
        UNIQUE (tenant_id, connector_binding_id, provider_stable_id),
    CONSTRAINT integration_observed_entitlement_binding_fk
        FOREIGN KEY (tenant_id, connector_binding_id)
        REFERENCES integration.connector_binding (tenant_id, id),
    CONSTRAINT integration_observed_entitlement_run_fk
        FOREIGN KEY (tenant_id, last_observed_run_id)
        REFERENCES integration.reconciliation_run (tenant_id, id),
    CONSTRAINT integration_observed_entitlement_presence_ck CHECK (
        (present AND absent_at IS NULL) OR (NOT present AND absent_at IS NOT NULL)
    )
);

CREATE TABLE integration.observed_grant (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    provider_stable_id varchar(512) NOT NULL,
    provider_version varchar(512) NULL,
    principal_provider_id varchar(512) NOT NULL,
    entitlement_provider_id varchar(512) NOT NULL,
    observed_state jsonb NOT NULL,
    present boolean NOT NULL,
    last_observed_run_id uuid NOT NULL,
    observed_at timestamptz NOT NULL,
    absent_at timestamptz NULL,
    CONSTRAINT integration_observed_grant_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT integration_observed_grant_provider_uq
        UNIQUE (tenant_id, connector_binding_id, provider_stable_id),
    CONSTRAINT integration_observed_grant_pair_uq
        UNIQUE (tenant_id, connector_binding_id, principal_provider_id, entitlement_provider_id),
    CONSTRAINT integration_observed_grant_binding_fk
        FOREIGN KEY (tenant_id, connector_binding_id)
        REFERENCES integration.connector_binding (tenant_id, id),
    CONSTRAINT integration_observed_grant_run_fk
        FOREIGN KEY (tenant_id, last_observed_run_id)
        REFERENCES integration.reconciliation_run (tenant_id, id),
    CONSTRAINT integration_observed_grant_presence_ck CHECK (
        (present AND absent_at IS NULL) OR (NOT present AND absent_at IS NOT NULL)
    )
);

CREATE INDEX integration_reconciliation_entitlement_stage_run_idx
    ON integration.reconciliation_entitlement_staging
        (tenant_id, reconciliation_run_id, provider_stable_id);

CREATE INDEX integration_reconciliation_grant_stage_run_idx
    ON integration.reconciliation_grant_staging
        (tenant_id, reconciliation_run_id, provider_stable_id);

CREATE INDEX integration_observed_entitlement_binding_present_idx
    ON integration.observed_entitlement
        (tenant_id, connector_binding_id, present, provider_stable_id);

CREATE INDEX integration_observed_grant_binding_present_idx
    ON integration.observed_grant
        (tenant_id, connector_binding_id, present, provider_stable_id);

CREATE INDEX integration_observed_grant_pair_idx
    ON integration.observed_grant
        (tenant_id, principal_provider_id, entitlement_provider_id);

COMMENT ON TABLE integration.observed_entitlement IS
    'Integration-owned provider observation of technical access units. Provider observation never creates Catalog authority.';
COMMENT ON TABLE integration.observed_grant IS
    'Integration-owned provider observation of principal-to-entitlement membership. Observation never creates AccessAssignment authority.';
