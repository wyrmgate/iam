-- ADR-0083: immutable, observation-backed manual recovery decisions, not task outcomes.
CREATE TABLE integration.provisioning_recovery_decision (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    task_id uuid NOT NULL,
    reconciliation_run_id uuid NOT NULL,
    task_revision bigint NOT NULL CHECK (task_revision > 0),
    desired_revision bigint NOT NULL CHECK (desired_revision > 0),
    observation varchar(16) NOT NULL CHECK (observation IN ('PRESENT','ABSENT','ACTIVE','INACTIVE')),
    disposition varchar(32) NOT NULL CHECK (disposition IN ('MATCHES_DESIRED','CORRECTIVE_PLAN_CREATED')),
    corrective_job_id uuid,
    operator_id uuid NOT NULL,
    execution_quiesced_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    correlation_id uuid NOT NULL,
    UNIQUE (tenant_id, task_id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id,task_id) REFERENCES integration.provisioning_task(tenant_id,id),
    FOREIGN KEY (tenant_id,reconciliation_run_id) REFERENCES integration.reconciliation_run(tenant_id,id),
    FOREIGN KEY (tenant_id,corrective_job_id) REFERENCES integration.provisioning_job(tenant_id,id),
    CHECK ((disposition='CORRECTIVE_PLAN_CREATED') = (corrective_job_id IS NOT NULL)),
    CHECK (execution_quiesced_at <= recorded_at)
);
CREATE FUNCTION integration.reject_immutable_recovery_decision_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'provisioning recovery decision is immutable';
END;
$$;
CREATE TRIGGER provisioning_recovery_decision_immutable
    BEFORE UPDATE OR DELETE ON integration.provisioning_recovery_decision
    FOR EACH ROW EXECUTE FUNCTION integration.reject_immutable_recovery_decision_change();
