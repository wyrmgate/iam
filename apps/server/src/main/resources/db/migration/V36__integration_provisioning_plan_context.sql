-- ADR-0076: pin planned context; historical jobs have no fabricated revision proof.
ALTER TABLE integration.provisioning_job
 ADD COLUMN planned_binding_revision bigint CHECK (planned_binding_revision>0),
 ADD COLUMN planned_connector_revision bigint CHECK (planned_connector_revision>0),
 ADD COLUMN planned_configuration_version bigint CHECK (planned_configuration_version>0),
 ADD CONSTRAINT provisioning_job_context_ck CHECK (
 (planned_binding_revision IS NULL AND planned_connector_revision IS NULL AND planned_configuration_version IS NULL)
 OR (planned_binding_revision IS NOT NULL AND planned_connector_revision IS NOT NULL AND planned_configuration_version IS NOT NULL));
