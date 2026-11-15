-- ADR-0074: no fabricated generation or configuration proof for existing historical runs.
CREATE SEQUENCE integration.reconciliation_scan_generation_seq AS bigint START WITH 1;
ALTER TABLE integration.reconciliation_run
    ADD COLUMN scan_generation bigint CHECK (scan_generation > 0),
    ADD COLUMN binding_revision bigint CHECK (binding_revision > 0),
    ADD COLUMN connector_revision bigint CHECK (connector_revision > 0),
    ADD CONSTRAINT reconciliation_scan_context_ck CHECK (
        (scan_generation IS NULL AND binding_revision IS NULL AND connector_revision IS NULL)
        OR (scan_generation IS NOT NULL AND binding_revision IS NOT NULL AND connector_revision IS NOT NULL));
CREATE UNIQUE INDEX reconciliation_scan_generation_uq
    ON integration.reconciliation_run(scan_generation) WHERE scan_generation IS NOT NULL;
CREATE INDEX reconciliation_finalized_generation_idx
    ON integration.reconciliation_run(tenant_id, connector_binding_id, scope_object_class, scan_generation)
    WHERE completed_at IS NOT NULL AND scan_generation IS NOT NULL;
