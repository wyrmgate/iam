CREATE TABLE integration.connector_instance (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    connector_type varchar(128) NOT NULL,
    runtime_id varchar(128) NOT NULL,
    runtime_version varchar(64) NOT NULL,
    configuration_version bigint NOT NULL,
    configuration_json jsonb NOT NULL,
    secret_reference varchar(1024) NULL,
    lifecycle_state varchar(24) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT integration_connector_instance_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT integration_connector_instance_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT integration_connector_instance_type_ck CHECK (btrim(connector_type) <> ''),
    CONSTRAINT integration_connector_instance_runtime_id_ck CHECK (btrim(runtime_id) <> ''),
    CONSTRAINT integration_connector_instance_runtime_version_ck CHECK (btrim(runtime_version) <> ''),
    CONSTRAINT integration_connector_instance_configuration_version_ck CHECK (configuration_version > 0),
    CONSTRAINT integration_connector_instance_state_ck
        CHECK (lifecycle_state IN ('ACTIVE', 'DISABLED', 'RETIRED')),
    CONSTRAINT integration_connector_instance_revision_ck CHECK (revision > 0),
    CONSTRAINT integration_connector_instance_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE TABLE integration.connector_binding (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    connector_instance_id uuid NOT NULL,
    target_kind varchar(32) NOT NULL,
    target_id uuid NOT NULL,
    contract_id varchar(128) NOT NULL,
    contract_version integer NOT NULL,
    supports_complete_principal_discovery boolean NOT NULL DEFAULT false,
    lifecycle_state varchar(24) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT integration_connector_binding_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT integration_connector_binding_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT integration_connector_binding_instance_fk
        FOREIGN KEY (tenant_id, connector_instance_id)
        REFERENCES integration.connector_instance (tenant_id, id),
    CONSTRAINT integration_connector_binding_target_kind_ck
        CHECK (target_kind IN ('APPLICATION_TARGET', 'SOURCE_SYSTEM')),
    CONSTRAINT integration_connector_binding_contract_id_ck CHECK (btrim(contract_id) <> ''),
    CONSTRAINT integration_connector_binding_contract_version_ck CHECK (contract_version > 0),
    CONSTRAINT integration_connector_binding_state_ck
        CHECK (lifecycle_state IN ('ACTIVE', 'DISABLED', 'RETIRED')),
    CONSTRAINT integration_connector_binding_revision_ck CHECK (revision > 0),
    CONSTRAINT integration_connector_binding_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE TABLE integration.connector_worker_registration (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    external_subject_key char(64) NOT NULL,
    issuer varchar(512) NOT NULL,
    subject varchar(512) NOT NULL,
    state varchar(16) NOT NULL,
    protocol_major_min integer NOT NULL DEFAULT 1,
    protocol_major_max integer NOT NULL DEFAULT 1,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT integration_worker_registration_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT integration_worker_registration_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT integration_worker_registration_subject_uq UNIQUE (external_subject_key),
    CONSTRAINT integration_worker_registration_state_ck CHECK (state IN ('ENABLED', 'DISABLED')),
    CONSTRAINT integration_worker_registration_protocol_ck
        CHECK (protocol_major_min > 0 AND protocol_major_max >= protocol_major_min),
    CONSTRAINT integration_worker_registration_revision_ck CHECK (revision > 0),
    CONSTRAINT integration_worker_registration_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE TABLE integration.connector_worker_binding_scope (
    tenant_id uuid NOT NULL,
    worker_registration_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, worker_registration_id, connector_binding_id),
    CONSTRAINT integration_worker_scope_registration_fk
        FOREIGN KEY (tenant_id, worker_registration_id)
        REFERENCES integration.connector_worker_registration (tenant_id, id),
    CONSTRAINT integration_worker_scope_binding_fk
        FOREIGN KEY (tenant_id, connector_binding_id)
        REFERENCES integration.connector_binding (tenant_id, id)
);

CREATE TABLE integration.connector_worker_runtime_permission (
    tenant_id uuid NOT NULL,
    worker_registration_id uuid NOT NULL,
    runtime_id varchar(128) NOT NULL,
    runtime_version varchar(64) NOT NULL,
    capability varchar(48) NOT NULL,
    contract_id varchar(128) NOT NULL,
    contract_version integer NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (
        tenant_id, worker_registration_id, runtime_id, runtime_version,
        capability, contract_id, contract_version
    ),
    CONSTRAINT integration_worker_permission_registration_fk
        FOREIGN KEY (tenant_id, worker_registration_id)
        REFERENCES integration.connector_worker_registration (tenant_id, id),
    CONSTRAINT integration_worker_permission_capability_ck
        CHECK (capability IN (
            'PROVISION',
            'RECONCILE',
            'SOURCE_DISCOVER',
            'CREDENTIAL_PROVIDER_OPERATION'
        )),
    CONSTRAINT integration_worker_permission_runtime_id_ck CHECK (btrim(runtime_id) <> ''),
    CONSTRAINT integration_worker_permission_runtime_version_ck CHECK (btrim(runtime_version) <> ''),
    CONSTRAINT integration_worker_permission_contract_id_ck CHECK (btrim(contract_id) <> ''),
    CONSTRAINT integration_worker_permission_contract_version_ck CHECK (contract_version > 0)
);

CREATE TABLE integration.connector_worker_session (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    worker_registration_id uuid NOT NULL,
    worker_instance_id varchar(128) NOT NULL,
    selected_protocol_major integer NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CONSTRAINT integration_worker_session_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT integration_worker_session_registration_fk
        FOREIGN KEY (tenant_id, worker_registration_id)
        REFERENCES integration.connector_worker_registration (tenant_id, id),
    CONSTRAINT integration_worker_session_protocol_ck CHECK (selected_protocol_major = 1),
    CONSTRAINT integration_worker_session_worker_instance_ck CHECK (btrim(worker_instance_id) <> ''),
    CONSTRAINT integration_worker_session_expiry_ck CHECK (expires_at > created_at)
);

CREATE TABLE integration.connector_worker_session_capability (
    tenant_id uuid NOT NULL,
    session_id uuid NOT NULL,
    runtime_id varchar(128) NOT NULL,
    runtime_version varchar(64) NOT NULL,
    capability varchar(48) NOT NULL,
    PRIMARY KEY (tenant_id, session_id, runtime_id, runtime_version, capability),
    CONSTRAINT integration_worker_session_capability_session_fk
        FOREIGN KEY (tenant_id, session_id)
        REFERENCES integration.connector_worker_session (tenant_id, id),
    CONSTRAINT integration_worker_session_capability_ck
        CHECK (capability IN (
            'PROVISION',
            'RECONCILE',
            'SOURCE_DISCOVER',
            'CREDENTIAL_PROVIDER_OPERATION'
        ))
);

CREATE TABLE integration.connector_worker_session_contract (
    tenant_id uuid NOT NULL,
    session_id uuid NOT NULL,
    runtime_id varchar(128) NOT NULL,
    runtime_version varchar(64) NOT NULL,
    contract_id varchar(128) NOT NULL,
    contract_version integer NOT NULL,
    PRIMARY KEY (
        tenant_id, session_id, runtime_id, runtime_version, contract_id, contract_version
    ),
    CONSTRAINT integration_worker_session_contract_session_fk
        FOREIGN KEY (tenant_id, session_id)
        REFERENCES integration.connector_worker_session (tenant_id, id),
    CONSTRAINT integration_worker_session_contract_version_ck CHECK (contract_version > 0)
);

CREATE TABLE integration.provisioning_job (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    state varchar(24) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    correlation_id uuid NOT NULL,
    causation_id uuid NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT integration_provisioning_job_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT integration_provisioning_job_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT integration_provisioning_job_binding_fk
        FOREIGN KEY (tenant_id, connector_binding_id)
        REFERENCES integration.connector_binding (tenant_id, id),
    CONSTRAINT integration_provisioning_job_state_ck
        CHECK (state IN ('PLANNED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT integration_provisioning_job_revision_ck CHECK (revision > 0),
    CONSTRAINT integration_provisioning_job_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE TABLE integration.provisioning_task (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    provisioning_job_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    operation_type varchar(64) NOT NULL,
    subject_kind varchar(32) NOT NULL,
    subject_id uuid NOT NULL,
    desired_revision bigint NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    contract_id varchar(128) NOT NULL,
    contract_version integer NOT NULL,
    payload jsonb NOT NULL,
    state varchar(32) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NULL,
    failure_code varchar(128) NULL,
    revision bigint NOT NULL DEFAULT 1,
    correlation_id uuid NOT NULL,
    causation_id uuid NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT integration_provisioning_task_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT integration_provisioning_task_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT integration_provisioning_task_job_id_uq
        UNIQUE (tenant_id, provisioning_job_id, id),
    CONSTRAINT integration_provisioning_task_job_fk
        FOREIGN KEY (tenant_id, provisioning_job_id)
        REFERENCES integration.provisioning_job (tenant_id, id),
    CONSTRAINT integration_provisioning_task_idempotency_uq UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT integration_provisioning_task_operation_type_ck CHECK (btrim(operation_type) <> ''),
    CONSTRAINT integration_provisioning_task_subject_kind_ck CHECK (btrim(subject_kind) <> ''),
    CONSTRAINT integration_provisioning_task_desired_revision_ck CHECK (desired_revision > 0),
    CONSTRAINT integration_provisioning_task_contract_id_ck CHECK (btrim(contract_id) <> ''),
    CONSTRAINT integration_provisioning_task_contract_version_ck CHECK (contract_version > 0),
    CONSTRAINT integration_provisioning_task_state_ck
        CHECK (state IN (
            'READY',
            'RUNNING',
            'SUCCEEDED',
            'FAILED_RETRYABLE',
            'FAILED_FINAL',
            'SUPERSEDED',
            'SKIPPED',
            'BLOCKED'
        )),
    CONSTRAINT integration_provisioning_task_attempt_count_ck CHECK (attempt_count >= 0),
    CONSTRAINT integration_provisioning_task_revision_ck CHECK (revision > 0),
    CONSTRAINT integration_provisioning_task_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE TABLE integration.provisioning_task_dependency (
    tenant_id uuid NOT NULL,
    provisioning_job_id uuid NOT NULL,
    task_id uuid NOT NULL,
    depends_on_task_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, provisioning_job_id, task_id, depends_on_task_id),
    CONSTRAINT integration_provisioning_dependency_task_fk
        FOREIGN KEY (tenant_id, provisioning_job_id, task_id)
        REFERENCES integration.provisioning_task (tenant_id, provisioning_job_id, id),
    CONSTRAINT integration_provisioning_dependency_parent_fk
        FOREIGN KEY (tenant_id, provisioning_job_id, depends_on_task_id)
        REFERENCES integration.provisioning_task (tenant_id, provisioning_job_id, id),
    CONSTRAINT integration_provisioning_dependency_not_self_ck CHECK (task_id <> depends_on_task_id)
);

CREATE TABLE integration.provisioning_attempt (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    task_id uuid NOT NULL,
    attempt_number integer NOT NULL,
    lease_id uuid NOT NULL,
    lease_epoch bigint NOT NULL,
    started_at timestamptz NOT NULL,
    completed_at timestamptz NOT NULL,
    outcome varchar(32) NOT NULL,
    failure_category varchar(32) NULL,
    provider_error_code varchar(128) NULL,
    provider_request_id varchar(256) NULL,
    provider_object_id varchar(512) NULL,
    provider_version varchar(512) NULL,
    metadata jsonb NOT NULL,
    completion_fingerprint varchar(71) NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid NULL,
    CONSTRAINT integration_provisioning_attempt_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT integration_provisioning_attempt_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT integration_provisioning_attempt_task_fk
        FOREIGN KEY (tenant_id, task_id)
        REFERENCES integration.provisioning_task (tenant_id, id),
    CONSTRAINT integration_provisioning_attempt_number_uq
        UNIQUE (tenant_id, task_id, attempt_number),
    CONSTRAINT integration_provisioning_attempt_epoch_uq
        UNIQUE (tenant_id, task_id, lease_epoch),
    CONSTRAINT integration_provisioning_attempt_number_ck CHECK (attempt_number > 0),
    CONSTRAINT integration_provisioning_attempt_epoch_ck CHECK (lease_epoch > 0),
    CONSTRAINT integration_provisioning_attempt_time_ck CHECK (completed_at >= started_at),
    CONSTRAINT integration_provisioning_attempt_outcome_ck
        CHECK (outcome IN (
            'SUCCEEDED',
            'FAILED_RETRYABLE',
            'FAILED_FINAL',
            'SUPERSEDED',
            'SKIPPED'
        )),
    CONSTRAINT integration_provisioning_attempt_failure_category_ck
        CHECK (failure_category IS NULL OR failure_category IN (
            'TRANSIENT',
            'RATE_LIMITED',
            'AUTHENTICATION',
            'AUTHORIZATION',
            'VALIDATION',
            'UNSUPPORTED',
            'PROVIDER'
        ))
);

CREATE TABLE integration.reconciliation_run (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    scope_object_class varchar(32) NOT NULL,
    state varchar(24) NOT NULL,
    reported_coverage varchar(16) NOT NULL DEFAULT 'UNKNOWN',
    effective_completeness varchar(16) NOT NULL DEFAULT 'UNKNOWN',
    configuration_version bigint NOT NULL,
    runtime_id varchar(128) NOT NULL,
    runtime_version varchar(64) NOT NULL,
    contract_id varchar(128) NOT NULL,
    contract_version integer NOT NULL,
    checkpoint_start varchar(2048) NULL,
    checkpoint_end varchar(2048) NULL,
    next_attempt_at timestamptz NULL,
    failure_code varchar(128) NULL,
    completion_lease_epoch bigint NULL,
    completion_fingerprint varchar(71) NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid NULL,
    revision bigint NOT NULL DEFAULT 1,
    started_at timestamptz NOT NULL,
    completed_at timestamptz NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT integration_reconciliation_run_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT integration_reconciliation_run_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT integration_reconciliation_run_binding_fk
        FOREIGN KEY (tenant_id, connector_binding_id)
        REFERENCES integration.connector_binding (tenant_id, id),
    CONSTRAINT integration_reconciliation_run_scope_ck CHECK (scope_object_class = 'PRINCIPAL'),
    CONSTRAINT integration_reconciliation_run_state_ck
        CHECK (state IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT integration_reconciliation_run_reported_coverage_ck
        CHECK (reported_coverage IN ('COMPLETE', 'PARTIAL', 'UNKNOWN')),
    CONSTRAINT integration_reconciliation_run_effective_completeness_ck
        CHECK (effective_completeness IN ('COMPLETE', 'PARTIAL', 'UNKNOWN')),
    CONSTRAINT integration_reconciliation_run_complete_ck CHECK (
        effective_completeness <> 'COMPLETE'
        OR (state = 'SUCCEEDED' AND reported_coverage = 'COMPLETE')
    ),
    CONSTRAINT integration_reconciliation_run_configuration_version_ck CHECK (configuration_version > 0),
    CONSTRAINT integration_reconciliation_run_contract_version_ck CHECK (contract_version > 0),
    CONSTRAINT integration_reconciliation_run_revision_ck CHECK (revision > 0),
    CONSTRAINT integration_reconciliation_run_completed_ck CHECK (
        (state = 'RUNNING' AND completed_at IS NULL)
        OR (state <> 'RUNNING' AND completed_at IS NOT NULL)
    ),
    CONSTRAINT integration_reconciliation_run_timestamp_ck CHECK (
        updated_at >= created_at AND (completed_at IS NULL OR completed_at >= started_at)
    )
);

CREATE TABLE integration.reconciliation_observation_batch (
    tenant_id uuid NOT NULL,
    reconciliation_run_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    sequence integer NOT NULL,
    request_fingerprint varchar(71) NOT NULL,
    received_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, reconciliation_run_id, batch_id),
    CONSTRAINT integration_reconciliation_batch_sequence_uq
        UNIQUE (tenant_id, reconciliation_run_id, sequence),
    CONSTRAINT integration_reconciliation_batch_run_fk
        FOREIGN KEY (tenant_id, reconciliation_run_id)
        REFERENCES integration.reconciliation_run (tenant_id, id),
    CONSTRAINT integration_reconciliation_batch_sequence_ck CHECK (sequence >= 0)
);

CREATE TABLE integration.reconciliation_principal_staging (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    reconciliation_run_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    provider_stable_id varchar(512) NOT NULL,
    provider_version varchar(512) NULL,
    observed_state jsonb NOT NULL,
    observed_at timestamptz NOT NULL,
    CONSTRAINT integration_reconciliation_staging_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT integration_reconciliation_staging_provider_uq
        UNIQUE (tenant_id, reconciliation_run_id, provider_stable_id),
    CONSTRAINT integration_reconciliation_staging_batch_fk
        FOREIGN KEY (tenant_id, reconciliation_run_id, batch_id)
        REFERENCES integration.reconciliation_observation_batch (tenant_id, reconciliation_run_id, batch_id),
    CONSTRAINT integration_reconciliation_staging_provider_ck CHECK (btrim(provider_stable_id) <> '')
);

CREATE TABLE integration.observed_principal (
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
    CONSTRAINT integration_observed_principal_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT integration_observed_principal_provider_uq
        UNIQUE (tenant_id, connector_binding_id, provider_stable_id),
    CONSTRAINT integration_observed_principal_binding_fk
        FOREIGN KEY (tenant_id, connector_binding_id)
        REFERENCES integration.connector_binding (tenant_id, id),
    CONSTRAINT integration_observed_principal_run_fk
        FOREIGN KEY (tenant_id, last_observed_run_id)
        REFERENCES integration.reconciliation_run (tenant_id, id),
    CONSTRAINT integration_observed_principal_presence_ck CHECK (
        (present AND absent_at IS NULL) OR (NOT present AND absent_at IS NOT NULL)
    )
);

CREATE TABLE platform.connector_work_lease (
    tenant_id uuid NOT NULL,
    work_kind varchar(24) NOT NULL,
    work_id uuid NOT NULL,
    session_id uuid NOT NULL,
    lease_id uuid NOT NULL,
    lease_epoch bigint NOT NULL,
    claimed_at timestamptz NOT NULL,
    lease_expires_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, work_kind, work_id),
    CONSTRAINT platform_connector_work_lease_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT platform_connector_work_lease_id_uq UNIQUE (lease_id),
    CONSTRAINT platform_connector_work_lease_kind_ck
        CHECK (work_kind IN ('PROVISION', 'RECONCILE')),
    CONSTRAINT platform_connector_work_lease_epoch_ck CHECK (lease_epoch > 0),
    CONSTRAINT platform_connector_work_lease_expiry_ck CHECK (lease_expires_at > claimed_at)
);

CREATE INDEX integration_worker_registration_subject_idx
    ON integration.connector_worker_registration (external_subject_key, state);
CREATE INDEX integration_worker_scope_binding_idx
    ON integration.connector_worker_binding_scope (tenant_id, connector_binding_id);
CREATE INDEX integration_provisioning_task_claim_idx
    ON integration.provisioning_task (tenant_id, state, next_attempt_at, created_at);
CREATE INDEX integration_reconciliation_run_claim_idx
    ON integration.reconciliation_run (tenant_id, state, next_attempt_at, started_at);
CREATE INDEX integration_observed_principal_present_idx
    ON integration.observed_principal (tenant_id, connector_binding_id, present);
CREATE INDEX platform_connector_work_lease_expiry_idx
    ON platform.connector_work_lease (lease_expires_at);

CREATE OR REPLACE FUNCTION integration.reject_immutable_provisioning_attempt_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'provisioning_attempt is immutable';
END;
$$;

CREATE TRIGGER integration_provisioning_attempt_immutable_trg
BEFORE UPDATE OR DELETE ON integration.provisioning_attempt
FOR EACH ROW EXECUTE FUNCTION integration.reject_immutable_provisioning_attempt_change();
