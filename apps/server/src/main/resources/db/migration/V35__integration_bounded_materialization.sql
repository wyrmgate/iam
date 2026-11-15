-- ADR-0075: durable materialization independent of provider completion.
ALTER TABLE integration.reconciliation_run
 ADD COLUMN materialization_phase varchar(16) CHECK (materialization_phase IN ('POSITIVE','ABSENCE')),
 ADD COLUMN materialization_cursor varchar(512),
 ADD COLUMN materialization_outcome varchar(24) CHECK (materialization_outcome IN ('SUCCEEDED','FAILED','CANCELLED')),
 ADD COLUMN materialization_coverage varchar(16) CHECK (materialization_coverage IN ('UNKNOWN','PARTIAL','COMPLETE')),
 ADD CONSTRAINT reconciliation_materialization_state_ck CHECK (
 (materialization_phase IS NULL AND materialization_cursor IS NULL AND materialization_outcome IS NULL AND materialization_coverage IS NULL)
 OR (materialization_phase IS NOT NULL AND materialization_outcome IS NOT NULL AND materialization_coverage IS NOT NULL
     AND state='RUNNING' AND completion_lease_epoch IS NOT NULL AND effective_completeness='UNKNOWN'));
CREATE INDEX reconciliation_materialization_pending_idx ON integration.reconciliation_run(updated_at,id)
 WHERE materialization_phase IS NOT NULL;
