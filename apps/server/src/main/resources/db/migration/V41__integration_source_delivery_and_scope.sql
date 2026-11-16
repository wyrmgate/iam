ALTER TABLE integration.reconciliation_run ADD COLUMN scope_mode varchar(16) NOT NULL DEFAULT 'FULL'
    CHECK(scope_mode IN ('FULL','INCREMENTAL'));
UPDATE integration.reconciliation_run SET scope_mode='INCREMENTAL' WHERE checkpoint_start IS NOT NULL;
CREATE TABLE integration.source_import_delivery (
    tenant_id uuid NOT NULL,
    reconciliation_run_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    identity_import_run_id uuid,
    cursor_provider_id varchar(512),
    state varchar(16) NOT NULL CHECK(state IN ('PENDING','COMPLETED','SUPERSEDED')),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY(tenant_id,reconciliation_run_id),
    FOREIGN KEY(tenant_id,reconciliation_run_id) REFERENCES integration.reconciliation_run(tenant_id,id)
);
CREATE INDEX source_import_delivery_pending_idx ON integration.source_import_delivery(updated_at,reconciliation_run_id) WHERE state='PENDING';
