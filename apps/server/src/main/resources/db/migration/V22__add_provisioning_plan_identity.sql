ALTER TABLE integration.provisioning_job
    ADD COLUMN plan_key varchar(200) NULL;

ALTER TABLE integration.provisioning_job
    ADD CONSTRAINT integration_provisioning_job_plan_key_ck
        CHECK (plan_key IS NULL OR btrim(plan_key) <> '');

CREATE UNIQUE INDEX integration_provisioning_job_plan_key_uq
    ON integration.provisioning_job (tenant_id, plan_key)
    WHERE plan_key IS NOT NULL;

COMMENT ON COLUMN integration.provisioning_job.plan_key IS
    'Optional deterministic Integration planner identity. Manual/test-created jobs may leave it null.';
