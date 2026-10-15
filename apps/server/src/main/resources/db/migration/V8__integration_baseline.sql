-- Integration capability current-state persistence baseline.
-- Generated once from the verified pre-rebaseline schema, then maintained as reviewed source.
-- Some function bodies reference tables created later in this same capability baseline.
SET check_function_bodies = false;

CREATE FUNCTION integration.reject_immutable_provisioning_attempt_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'provisioning_attempt is immutable';
END;
$$;

CREATE TABLE integration.connector_binding (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    connector_instance_id uuid NOT NULL,
    target_kind character varying(32) NOT NULL,
    target_id uuid NOT NULL,
    contract_id character varying(128) NOT NULL,
    contract_version integer NOT NULL,
    supports_complete_principal_discovery boolean DEFAULT false CONSTRAINT connector_binding_supports_complete_principal_discover_not_null NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    supports_complete_entitlement_discovery boolean DEFAULT false CONSTRAINT connector_binding_supports_complete_entitlement_discov_not_null NOT NULL,
    supports_complete_grant_discovery boolean DEFAULT false NOT NULL,
    CONSTRAINT integration_connector_binding_contract_id_ck CHECK ((btrim((contract_id)::text) <> ''::text)),
    CONSTRAINT integration_connector_binding_contract_version_ck CHECK ((contract_version > 0)),
    CONSTRAINT integration_connector_binding_revision_ck CHECK ((revision > 0)),
    CONSTRAINT integration_connector_binding_state_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying, 'RETIRED'::character varying])::text[]))),
    CONSTRAINT integration_connector_binding_target_kind_ck CHECK (((target_kind)::text = ANY ((ARRAY['APPLICATION_TARGET'::character varying, 'SOURCE_SYSTEM'::character varying])::text[]))),
    CONSTRAINT integration_connector_binding_timestamp_ck CHECK ((updated_at >= created_at))
);

CREATE TABLE integration.connector_instance (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    connector_type character varying(128) NOT NULL,
    runtime_id character varying(128) NOT NULL,
    runtime_version character varying(64) NOT NULL,
    configuration_version bigint NOT NULL,
    configuration_json jsonb NOT NULL,
    secret_reference character varying(1024),
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_connector_instance_configuration_version_ck CHECK ((configuration_version > 0)),
    CONSTRAINT integration_connector_instance_revision_ck CHECK ((revision > 0)),
    CONSTRAINT integration_connector_instance_runtime_id_ck CHECK ((btrim((runtime_id)::text) <> ''::text)),
    CONSTRAINT integration_connector_instance_runtime_version_ck CHECK ((btrim((runtime_version)::text) <> ''::text)),
    CONSTRAINT integration_connector_instance_state_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying, 'RETIRED'::character varying])::text[]))),
    CONSTRAINT integration_connector_instance_timestamp_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT integration_connector_instance_type_ck CHECK ((btrim((connector_type)::text) <> ''::text))
);

CREATE TABLE integration.connector_worker_binding_scope (
    tenant_id uuid NOT NULL,
    worker_registration_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL
);

CREATE TABLE integration.connector_worker_registration (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    external_subject_key character(64) NOT NULL,
    issuer character varying(512) NOT NULL,
    subject character varying(512) NOT NULL,
    state character varying(16) NOT NULL,
    protocol_major_min integer DEFAULT 1 NOT NULL,
    protocol_major_max integer DEFAULT 1 NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_worker_registration_protocol_ck CHECK (((protocol_major_min > 0) AND (protocol_major_max >= protocol_major_min))),
    CONSTRAINT integration_worker_registration_revision_ck CHECK ((revision > 0)),
    CONSTRAINT integration_worker_registration_state_ck CHECK (((state)::text = ANY ((ARRAY['ENABLED'::character varying, 'DISABLED'::character varying])::text[]))),
    CONSTRAINT integration_worker_registration_timestamp_ck CHECK ((updated_at >= created_at))
);

CREATE TABLE integration.connector_worker_runtime_permission (
    tenant_id uuid NOT NULL,
    worker_registration_id uuid CONSTRAINT connector_worker_runtime_permis_worker_registration_id_not_null NOT NULL,
    runtime_id character varying(128) NOT NULL,
    runtime_version character varying(64) NOT NULL,
    capability character varying(48) NOT NULL,
    contract_id character varying(128) NOT NULL,
    contract_version integer NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_worker_permission_capability_ck CHECK (((capability)::text = ANY ((ARRAY['PROVISION'::character varying, 'RECONCILE'::character varying, 'SOURCE_DISCOVER'::character varying, 'CREDENTIAL_PROVIDER_OPERATION'::character varying])::text[]))),
    CONSTRAINT integration_worker_permission_contract_id_ck CHECK ((btrim((contract_id)::text) <> ''::text)),
    CONSTRAINT integration_worker_permission_contract_version_ck CHECK ((contract_version > 0)),
    CONSTRAINT integration_worker_permission_runtime_id_ck CHECK ((btrim((runtime_id)::text) <> ''::text)),
    CONSTRAINT integration_worker_permission_runtime_version_ck CHECK ((btrim((runtime_version)::text) <> ''::text))
);

CREATE TABLE integration.connector_worker_session (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    worker_registration_id uuid NOT NULL,
    worker_instance_id character varying(128) NOT NULL,
    selected_protocol_major integer NOT NULL,
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_worker_session_expiry_ck CHECK ((expires_at > created_at)),
    CONSTRAINT integration_worker_session_protocol_ck CHECK ((selected_protocol_major = 1)),
    CONSTRAINT integration_worker_session_worker_instance_ck CHECK ((btrim((worker_instance_id)::text) <> ''::text))
);

CREATE TABLE integration.connector_worker_session_capability (
    tenant_id uuid NOT NULL,
    session_id uuid NOT NULL,
    runtime_id character varying(128) NOT NULL,
    runtime_version character varying(64) NOT NULL,
    capability character varying(48) NOT NULL,
    CONSTRAINT integration_worker_session_capability_ck CHECK (((capability)::text = ANY ((ARRAY['PROVISION'::character varying, 'RECONCILE'::character varying, 'SOURCE_DISCOVER'::character varying, 'CREDENTIAL_PROVIDER_OPERATION'::character varying])::text[])))
);

CREATE TABLE integration.connector_worker_session_contract (
    tenant_id uuid NOT NULL,
    session_id uuid NOT NULL,
    runtime_id character varying(128) NOT NULL,
    runtime_version character varying(64) NOT NULL,
    contract_id character varying(128) NOT NULL,
    contract_version integer NOT NULL,
    CONSTRAINT integration_worker_session_contract_version_ck CHECK ((contract_version > 0))
);

CREATE TABLE integration.entitlement_observation_mapping (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    provider_stable_id character varying(512) NOT NULL,
    entitlement_id uuid NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    retired_at timestamp with time zone,
    CONSTRAINT integration_entitlement_mapping_provider_ck CHECK ((btrim((provider_stable_id)::text) <> ''::text)),
    CONSTRAINT integration_entitlement_mapping_retired_ck CHECK (((((lifecycle_state)::text = 'ACTIVE'::text) AND (retired_at IS NULL)) OR (((lifecycle_state)::text = 'RETIRED'::text) AND (retired_at IS NOT NULL)))),
    CONSTRAINT integration_entitlement_mapping_revision_ck CHECK ((revision > 0)),
    CONSTRAINT integration_entitlement_mapping_state_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'RETIRED'::character varying])::text[]))),
    CONSTRAINT integration_entitlement_mapping_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE integration.entitlement_observation_mapping IS 'Integration-owned explicit resolution from provider entitlement observation identity to existing Catalog Entitlement stable ID. Mapping is not Catalog authority.';

CREATE TABLE integration.observed_entitlement (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    provider_stable_id character varying(512) NOT NULL,
    provider_version character varying(512),
    observed_state jsonb NOT NULL,
    present boolean NOT NULL,
    last_observed_run_id uuid NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    absent_at timestamp with time zone,
    CONSTRAINT integration_observed_entitlement_presence_ck CHECK (((present AND (absent_at IS NULL)) OR ((NOT present) AND (absent_at IS NOT NULL))))
);

COMMENT ON TABLE integration.observed_entitlement IS 'Integration-owned provider observation of technical access units. Provider observation never creates Catalog authority.';

CREATE TABLE integration.observed_grant (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    provider_stable_id character varying(512) NOT NULL,
    provider_version character varying(512),
    principal_provider_id character varying(512) NOT NULL,
    entitlement_provider_id character varying(512) NOT NULL,
    observed_state jsonb NOT NULL,
    present boolean NOT NULL,
    last_observed_run_id uuid NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    absent_at timestamp with time zone,
    CONSTRAINT integration_observed_grant_presence_ck CHECK (((present AND (absent_at IS NULL)) OR ((NOT present) AND (absent_at IS NOT NULL))))
);

COMMENT ON TABLE integration.observed_grant IS 'Integration-owned provider observation of principal-to-entitlement membership. Observation never creates AccessAssignment authority.';

CREATE TABLE integration.observed_principal (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    provider_stable_id character varying(512) NOT NULL,
    provider_version character varying(512),
    observed_state jsonb NOT NULL,
    present boolean NOT NULL,
    last_observed_run_id uuid NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    absent_at timestamp with time zone,
    CONSTRAINT integration_observed_principal_presence_ck CHECK (((present AND (absent_at IS NULL)) OR ((NOT present) AND (absent_at IS NOT NULL))))
);

CREATE TABLE integration.provisioning_attempt (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    task_id uuid NOT NULL,
    attempt_number integer NOT NULL,
    lease_id uuid NOT NULL,
    lease_epoch bigint NOT NULL,
    started_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone NOT NULL,
    outcome character varying(32) NOT NULL,
    failure_category character varying(32),
    provider_error_code character varying(128),
    provider_request_id character varying(256),
    provider_object_id character varying(512),
    provider_version character varying(512),
    metadata jsonb NOT NULL,
    completion_fingerprint character varying(71) NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    CONSTRAINT integration_provisioning_attempt_epoch_ck CHECK ((lease_epoch > 0)),
    CONSTRAINT integration_provisioning_attempt_failure_category_ck CHECK (((failure_category IS NULL) OR ((failure_category)::text = ANY ((ARRAY['TRANSIENT'::character varying, 'RATE_LIMITED'::character varying, 'AUTHENTICATION'::character varying, 'AUTHORIZATION'::character varying, 'VALIDATION'::character varying, 'UNSUPPORTED'::character varying, 'PROVIDER'::character varying])::text[])))),
    CONSTRAINT integration_provisioning_attempt_number_ck CHECK ((attempt_number > 0)),
    CONSTRAINT integration_provisioning_attempt_outcome_ck CHECK (((outcome)::text = ANY ((ARRAY['SUCCEEDED'::character varying, 'FAILED_RETRYABLE'::character varying, 'FAILED_FINAL'::character varying, 'SUPERSEDED'::character varying, 'SKIPPED'::character varying])::text[]))),
    CONSTRAINT integration_provisioning_attempt_time_ck CHECK ((completed_at >= started_at))
);

CREATE TABLE integration.provisioning_job (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    plan_key character varying(200),
    CONSTRAINT integration_provisioning_job_plan_key_ck CHECK (((plan_key IS NULL) OR (btrim((plan_key)::text) <> ''::text))),
    CONSTRAINT integration_provisioning_job_revision_ck CHECK ((revision > 0)),
    CONSTRAINT integration_provisioning_job_state_ck CHECK (((state)::text = ANY ((ARRAY['PLANNED'::character varying, 'RUNNING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT integration_provisioning_job_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON COLUMN integration.provisioning_job.plan_key IS 'Optional deterministic Integration planner identity. Manual/test-created jobs may leave it null.';

CREATE TABLE integration.provisioning_task (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    provisioning_job_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    operation_type character varying(64) NOT NULL,
    subject_kind character varying(32) NOT NULL,
    subject_id uuid NOT NULL,
    desired_revision bigint NOT NULL,
    idempotency_key character varying(200) NOT NULL,
    contract_id character varying(128) NOT NULL,
    contract_version integer NOT NULL,
    payload jsonb NOT NULL,
    state character varying(32) NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone,
    failure_code character varying(128),
    revision bigint DEFAULT 1 NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_provisioning_task_attempt_count_ck CHECK ((attempt_count >= 0)),
    CONSTRAINT integration_provisioning_task_contract_id_ck CHECK ((btrim((contract_id)::text) <> ''::text)),
    CONSTRAINT integration_provisioning_task_contract_version_ck CHECK ((contract_version > 0)),
    CONSTRAINT integration_provisioning_task_desired_revision_ck CHECK ((desired_revision > 0)),
    CONSTRAINT integration_provisioning_task_operation_type_ck CHECK ((btrim((operation_type)::text) <> ''::text)),
    CONSTRAINT integration_provisioning_task_revision_ck CHECK ((revision > 0)),
    CONSTRAINT integration_provisioning_task_state_ck CHECK (((state)::text = ANY ((ARRAY['READY'::character varying, 'RUNNING'::character varying, 'SUCCEEDED'::character varying, 'FAILED_RETRYABLE'::character varying, 'FAILED_FINAL'::character varying, 'SUPERSEDED'::character varying, 'SKIPPED'::character varying, 'BLOCKED'::character varying])::text[]))),
    CONSTRAINT integration_provisioning_task_subject_kind_ck CHECK ((btrim((subject_kind)::text) <> ''::text)),
    CONSTRAINT integration_provisioning_task_timestamp_ck CHECK ((updated_at >= created_at))
);

CREATE TABLE integration.provisioning_task_dependency (
    tenant_id uuid NOT NULL,
    provisioning_job_id uuid NOT NULL,
    task_id uuid NOT NULL,
    depends_on_task_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_provisioning_dependency_not_self_ck CHECK ((task_id <> depends_on_task_id))
);

CREATE TABLE integration.reconciliation_entitlement_staging (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    reconciliation_run_id uuid CONSTRAINT reconciliation_entitlement_stagi_reconciliation_run_id_not_null NOT NULL,
    batch_id uuid NOT NULL,
    provider_stable_id character varying(512) NOT NULL,
    provider_version character varying(512),
    observed_state jsonb NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_reconciliation_entitlement_stage_provider_ck CHECK ((btrim((provider_stable_id)::text) <> ''::text))
);

CREATE TABLE integration.reconciliation_grant_staging (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    reconciliation_run_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    provider_stable_id character varying(512) NOT NULL,
    provider_version character varying(512),
    principal_provider_id character varying(512) NOT NULL,
    entitlement_provider_id character varying(512) NOT NULL,
    observed_state jsonb NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_reconciliation_grant_stage_provider_ck CHECK (((btrim((provider_stable_id)::text) <> ''::text) AND (btrim((principal_provider_id)::text) <> ''::text) AND (btrim((entitlement_provider_id)::text) <> ''::text)))
);

CREATE TABLE integration.reconciliation_observation_batch (
    tenant_id uuid NOT NULL,
    reconciliation_run_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    sequence integer NOT NULL,
    request_fingerprint character varying(71) NOT NULL,
    received_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_reconciliation_batch_sequence_ck CHECK ((sequence >= 0))
);

CREATE TABLE integration.reconciliation_principal_staging (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    reconciliation_run_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    provider_stable_id character varying(512) NOT NULL,
    provider_version character varying(512),
    observed_state jsonb NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_reconciliation_staging_provider_ck CHECK ((btrim((provider_stable_id)::text) <> ''::text))
);

CREATE TABLE integration.reconciliation_run (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    connector_binding_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    scope_object_class character varying(32) NOT NULL,
    state character varying(24) NOT NULL,
    reported_coverage character varying(16) DEFAULT 'UNKNOWN'::character varying NOT NULL,
    effective_completeness character varying(16) DEFAULT 'UNKNOWN'::character varying NOT NULL,
    configuration_version bigint NOT NULL,
    runtime_id character varying(128) NOT NULL,
    runtime_version character varying(64) NOT NULL,
    contract_id character varying(128) NOT NULL,
    contract_version integer NOT NULL,
    checkpoint_start character varying(2048),
    checkpoint_end character varying(2048),
    next_attempt_at timestamp with time zone,
    failure_code character varying(128),
    completion_lease_epoch bigint,
    completion_fingerprint character varying(71),
    correlation_id uuid NOT NULL,
    causation_id uuid,
    revision bigint DEFAULT 1 NOT NULL,
    started_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT integration_reconciliation_run_complete_ck CHECK ((((effective_completeness)::text <> 'COMPLETE'::text) OR (((state)::text = 'SUCCEEDED'::text) AND ((reported_coverage)::text = 'COMPLETE'::text)))),
    CONSTRAINT integration_reconciliation_run_completed_ck CHECK (((((state)::text = 'RUNNING'::text) AND (completed_at IS NULL)) OR (((state)::text <> 'RUNNING'::text) AND (completed_at IS NOT NULL)))),
    CONSTRAINT integration_reconciliation_run_configuration_version_ck CHECK ((configuration_version > 0)),
    CONSTRAINT integration_reconciliation_run_contract_version_ck CHECK ((contract_version > 0)),
    CONSTRAINT integration_reconciliation_run_effective_completeness_ck CHECK (((effective_completeness)::text = ANY ((ARRAY['COMPLETE'::character varying, 'PARTIAL'::character varying, 'UNKNOWN'::character varying])::text[]))),
    CONSTRAINT integration_reconciliation_run_reported_coverage_ck CHECK (((reported_coverage)::text = ANY ((ARRAY['COMPLETE'::character varying, 'PARTIAL'::character varying, 'UNKNOWN'::character varying])::text[]))),
    CONSTRAINT integration_reconciliation_run_revision_ck CHECK ((revision > 0)),
    CONSTRAINT integration_reconciliation_run_scope_ck CHECK (((scope_object_class)::text = ANY ((ARRAY['PRINCIPAL'::character varying, 'ENTITLEMENT'::character varying, 'GRANT'::character varying])::text[]))),
    CONSTRAINT integration_reconciliation_run_state_ck CHECK (((state)::text = ANY ((ARRAY['RUNNING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT integration_reconciliation_run_timestamp_ck CHECK (((updated_at >= created_at) AND ((completed_at IS NULL) OR (completed_at >= started_at))))
);

ALTER TABLE ONLY integration.connector_binding
    ADD CONSTRAINT connector_binding_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.connector_instance
    ADD CONSTRAINT connector_instance_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.connector_worker_binding_scope
    ADD CONSTRAINT connector_worker_binding_scope_pkey PRIMARY KEY (tenant_id, worker_registration_id, connector_binding_id);

ALTER TABLE ONLY integration.connector_worker_registration
    ADD CONSTRAINT connector_worker_registration_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.connector_worker_runtime_permission
    ADD CONSTRAINT connector_worker_runtime_permission_pkey PRIMARY KEY (tenant_id, worker_registration_id, runtime_id, runtime_version, capability, contract_id, contract_version);

ALTER TABLE ONLY integration.connector_worker_session_capability
    ADD CONSTRAINT connector_worker_session_capability_pkey PRIMARY KEY (tenant_id, session_id, runtime_id, runtime_version, capability);

ALTER TABLE ONLY integration.connector_worker_session_contract
    ADD CONSTRAINT connector_worker_session_contract_pkey PRIMARY KEY (tenant_id, session_id, runtime_id, runtime_version, contract_id, contract_version);

ALTER TABLE ONLY integration.connector_worker_session
    ADD CONSTRAINT connector_worker_session_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.entitlement_observation_mapping
    ADD CONSTRAINT entitlement_observation_mapping_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.connector_binding
    ADD CONSTRAINT integration_connector_binding_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.connector_instance
    ADD CONSTRAINT integration_connector_instance_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.entitlement_observation_mapping
    ADD CONSTRAINT integration_entitlement_mapping_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.observed_entitlement
    ADD CONSTRAINT integration_observed_entitlement_provider_uq UNIQUE (tenant_id, connector_binding_id, provider_stable_id);

ALTER TABLE ONLY integration.observed_entitlement
    ADD CONSTRAINT integration_observed_entitlement_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.observed_grant
    ADD CONSTRAINT integration_observed_grant_pair_uq UNIQUE (tenant_id, connector_binding_id, principal_provider_id, entitlement_provider_id);

ALTER TABLE ONLY integration.observed_grant
    ADD CONSTRAINT integration_observed_grant_provider_uq UNIQUE (tenant_id, connector_binding_id, provider_stable_id);

ALTER TABLE ONLY integration.observed_grant
    ADD CONSTRAINT integration_observed_grant_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.observed_principal
    ADD CONSTRAINT integration_observed_principal_provider_uq UNIQUE (tenant_id, connector_binding_id, provider_stable_id);

ALTER TABLE ONLY integration.observed_principal
    ADD CONSTRAINT integration_observed_principal_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.provisioning_attempt
    ADD CONSTRAINT integration_provisioning_attempt_epoch_uq UNIQUE (tenant_id, task_id, lease_epoch);

ALTER TABLE ONLY integration.provisioning_attempt
    ADD CONSTRAINT integration_provisioning_attempt_number_uq UNIQUE (tenant_id, task_id, attempt_number);

ALTER TABLE ONLY integration.provisioning_attempt
    ADD CONSTRAINT integration_provisioning_attempt_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.provisioning_job
    ADD CONSTRAINT integration_provisioning_job_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.provisioning_task
    ADD CONSTRAINT integration_provisioning_task_idempotency_uq UNIQUE (tenant_id, idempotency_key);

ALTER TABLE ONLY integration.provisioning_task
    ADD CONSTRAINT integration_provisioning_task_job_id_uq UNIQUE (tenant_id, provisioning_job_id, id);

ALTER TABLE ONLY integration.provisioning_task
    ADD CONSTRAINT integration_provisioning_task_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.reconciliation_observation_batch
    ADD CONSTRAINT integration_reconciliation_batch_sequence_uq UNIQUE (tenant_id, reconciliation_run_id, sequence);

ALTER TABLE ONLY integration.reconciliation_entitlement_staging
    ADD CONSTRAINT integration_reconciliation_entitlement_stage_provider_uq UNIQUE (tenant_id, reconciliation_run_id, provider_stable_id);

ALTER TABLE ONLY integration.reconciliation_entitlement_staging
    ADD CONSTRAINT integration_reconciliation_entitlement_stage_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.reconciliation_grant_staging
    ADD CONSTRAINT integration_reconciliation_grant_stage_pair_uq UNIQUE (tenant_id, reconciliation_run_id, principal_provider_id, entitlement_provider_id);

ALTER TABLE ONLY integration.reconciliation_grant_staging
    ADD CONSTRAINT integration_reconciliation_grant_stage_provider_uq UNIQUE (tenant_id, reconciliation_run_id, provider_stable_id);

ALTER TABLE ONLY integration.reconciliation_grant_staging
    ADD CONSTRAINT integration_reconciliation_grant_stage_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.reconciliation_run
    ADD CONSTRAINT integration_reconciliation_run_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.reconciliation_principal_staging
    ADD CONSTRAINT integration_reconciliation_staging_provider_uq UNIQUE (tenant_id, reconciliation_run_id, provider_stable_id);

ALTER TABLE ONLY integration.reconciliation_principal_staging
    ADD CONSTRAINT integration_reconciliation_staging_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.connector_worker_registration
    ADD CONSTRAINT integration_worker_registration_subject_uq UNIQUE (external_subject_key);

ALTER TABLE ONLY integration.connector_worker_registration
    ADD CONSTRAINT integration_worker_registration_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.connector_worker_session
    ADD CONSTRAINT integration_worker_session_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY integration.observed_entitlement
    ADD CONSTRAINT observed_entitlement_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.observed_grant
    ADD CONSTRAINT observed_grant_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.observed_principal
    ADD CONSTRAINT observed_principal_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.provisioning_attempt
    ADD CONSTRAINT provisioning_attempt_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.provisioning_job
    ADD CONSTRAINT provisioning_job_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.provisioning_task_dependency
    ADD CONSTRAINT provisioning_task_dependency_pkey PRIMARY KEY (tenant_id, provisioning_job_id, task_id, depends_on_task_id);

ALTER TABLE ONLY integration.provisioning_task
    ADD CONSTRAINT provisioning_task_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.reconciliation_entitlement_staging
    ADD CONSTRAINT reconciliation_entitlement_staging_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.reconciliation_grant_staging
    ADD CONSTRAINT reconciliation_grant_staging_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.reconciliation_observation_batch
    ADD CONSTRAINT reconciliation_observation_batch_pkey PRIMARY KEY (tenant_id, reconciliation_run_id, batch_id);

ALTER TABLE ONLY integration.reconciliation_principal_staging
    ADD CONSTRAINT reconciliation_principal_staging_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration.reconciliation_run
    ADD CONSTRAINT reconciliation_run_pkey PRIMARY KEY (id);

CREATE INDEX integration_connector_instance_lifecycle_idx ON integration.connector_instance USING btree (tenant_id, lifecycle_state);

CREATE UNIQUE INDEX integration_entitlement_mapping_active_provider_uq ON integration.entitlement_observation_mapping USING btree (tenant_id, connector_binding_id, provider_stable_id) WHERE ((lifecycle_state)::text = 'ACTIVE'::text);

CREATE INDEX integration_entitlement_mapping_entitlement_idx ON integration.entitlement_observation_mapping USING btree (tenant_id, entitlement_id, lifecycle_state);

CREATE INDEX integration_observed_entitlement_binding_present_idx ON integration.observed_entitlement USING btree (tenant_id, connector_binding_id, present, provider_stable_id);

CREATE INDEX integration_observed_grant_binding_present_idx ON integration.observed_grant USING btree (tenant_id, connector_binding_id, present, provider_stable_id);

CREATE INDEX integration_observed_grant_pair_idx ON integration.observed_grant USING btree (tenant_id, principal_provider_id, entitlement_provider_id);

CREATE INDEX integration_observed_principal_present_idx ON integration.observed_principal USING btree (tenant_id, connector_binding_id, present);

CREATE INDEX integration_provisioning_attempt_overview_time_idx ON integration.provisioning_attempt USING btree (tenant_id, completed_at DESC, outcome, failure_category);

COMMENT ON INDEX integration.integration_provisioning_attempt_overview_time_idx IS 'Supports bounded IAM Operations provisioning attempt summaries and trend reads by tenant and completion window.';

CREATE UNIQUE INDEX integration_provisioning_job_plan_key_uq ON integration.provisioning_job USING btree (tenant_id, plan_key) WHERE (plan_key IS NOT NULL);

CREATE INDEX integration_provisioning_task_claim_idx ON integration.provisioning_task USING btree (tenant_id, state, next_attempt_at, created_at);

CREATE INDEX integration_reconciliation_entitlement_stage_run_idx ON integration.reconciliation_entitlement_staging USING btree (tenant_id, reconciliation_run_id, provider_stable_id);

CREATE INDEX integration_reconciliation_grant_stage_run_idx ON integration.reconciliation_grant_staging USING btree (tenant_id, reconciliation_run_id, provider_stable_id);

CREATE INDEX integration_reconciliation_run_claim_idx ON integration.reconciliation_run USING btree (tenant_id, state, next_attempt_at, started_at);

CREATE INDEX integration_reconciliation_run_overview_time_idx ON integration.reconciliation_run USING btree (tenant_id, completed_at DESC, state, effective_completeness);

COMMENT ON INDEX integration.integration_reconciliation_run_overview_time_idx IS 'Supports bounded IAM Operations reconciliation summaries and recent-run reads by tenant and completion window.';

CREATE INDEX integration_worker_registration_subject_idx ON integration.connector_worker_registration USING btree (external_subject_key, state);

CREATE INDEX integration_worker_scope_binding_idx ON integration.connector_worker_binding_scope USING btree (tenant_id, connector_binding_id);

CREATE TRIGGER integration_provisioning_attempt_immutable_trg BEFORE DELETE OR UPDATE ON integration.provisioning_attempt FOR EACH ROW EXECUTE FUNCTION integration.reject_immutable_provisioning_attempt_change();

ALTER TABLE ONLY integration.connector_binding
    ADD CONSTRAINT integration_connector_binding_instance_fk FOREIGN KEY (tenant_id, connector_instance_id) REFERENCES integration.connector_instance(tenant_id, id);

ALTER TABLE ONLY integration.connector_binding
    ADD CONSTRAINT integration_connector_binding_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY integration.connector_instance
    ADD CONSTRAINT integration_connector_instance_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY integration.entitlement_observation_mapping
    ADD CONSTRAINT integration_entitlement_mapping_binding_fk FOREIGN KEY (tenant_id, connector_binding_id) REFERENCES integration.connector_binding(tenant_id, id);

ALTER TABLE ONLY integration.observed_entitlement
    ADD CONSTRAINT integration_observed_entitlement_binding_fk FOREIGN KEY (tenant_id, connector_binding_id) REFERENCES integration.connector_binding(tenant_id, id);

ALTER TABLE ONLY integration.observed_entitlement
    ADD CONSTRAINT integration_observed_entitlement_run_fk FOREIGN KEY (tenant_id, last_observed_run_id) REFERENCES integration.reconciliation_run(tenant_id, id);

ALTER TABLE ONLY integration.observed_grant
    ADD CONSTRAINT integration_observed_grant_binding_fk FOREIGN KEY (tenant_id, connector_binding_id) REFERENCES integration.connector_binding(tenant_id, id);

ALTER TABLE ONLY integration.observed_grant
    ADD CONSTRAINT integration_observed_grant_run_fk FOREIGN KEY (tenant_id, last_observed_run_id) REFERENCES integration.reconciliation_run(tenant_id, id);

ALTER TABLE ONLY integration.observed_principal
    ADD CONSTRAINT integration_observed_principal_binding_fk FOREIGN KEY (tenant_id, connector_binding_id) REFERENCES integration.connector_binding(tenant_id, id);

ALTER TABLE ONLY integration.observed_principal
    ADD CONSTRAINT integration_observed_principal_run_fk FOREIGN KEY (tenant_id, last_observed_run_id) REFERENCES integration.reconciliation_run(tenant_id, id);

ALTER TABLE ONLY integration.provisioning_attempt
    ADD CONSTRAINT integration_provisioning_attempt_task_fk FOREIGN KEY (tenant_id, task_id) REFERENCES integration.provisioning_task(tenant_id, id);

ALTER TABLE ONLY integration.provisioning_attempt
    ADD CONSTRAINT integration_provisioning_attempt_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY integration.provisioning_task_dependency
    ADD CONSTRAINT integration_provisioning_dependency_parent_fk FOREIGN KEY (tenant_id, provisioning_job_id, depends_on_task_id) REFERENCES integration.provisioning_task(tenant_id, provisioning_job_id, id);

ALTER TABLE ONLY integration.provisioning_task_dependency
    ADD CONSTRAINT integration_provisioning_dependency_task_fk FOREIGN KEY (tenant_id, provisioning_job_id, task_id) REFERENCES integration.provisioning_task(tenant_id, provisioning_job_id, id);

ALTER TABLE ONLY integration.provisioning_job
    ADD CONSTRAINT integration_provisioning_job_binding_fk FOREIGN KEY (tenant_id, connector_binding_id) REFERENCES integration.connector_binding(tenant_id, id);

ALTER TABLE ONLY integration.provisioning_job
    ADD CONSTRAINT integration_provisioning_job_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY integration.provisioning_task
    ADD CONSTRAINT integration_provisioning_task_job_fk FOREIGN KEY (tenant_id, provisioning_job_id) REFERENCES integration.provisioning_job(tenant_id, id);

ALTER TABLE ONLY integration.provisioning_task
    ADD CONSTRAINT integration_provisioning_task_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY integration.reconciliation_observation_batch
    ADD CONSTRAINT integration_reconciliation_batch_run_fk FOREIGN KEY (tenant_id, reconciliation_run_id) REFERENCES integration.reconciliation_run(tenant_id, id);

ALTER TABLE ONLY integration.reconciliation_entitlement_staging
    ADD CONSTRAINT integration_reconciliation_entitlement_stage_batch_fk FOREIGN KEY (tenant_id, reconciliation_run_id, batch_id) REFERENCES integration.reconciliation_observation_batch(tenant_id, reconciliation_run_id, batch_id);

ALTER TABLE ONLY integration.reconciliation_grant_staging
    ADD CONSTRAINT integration_reconciliation_grant_stage_batch_fk FOREIGN KEY (tenant_id, reconciliation_run_id, batch_id) REFERENCES integration.reconciliation_observation_batch(tenant_id, reconciliation_run_id, batch_id);

ALTER TABLE ONLY integration.reconciliation_run
    ADD CONSTRAINT integration_reconciliation_run_binding_fk FOREIGN KEY (tenant_id, connector_binding_id) REFERENCES integration.connector_binding(tenant_id, id);

ALTER TABLE ONLY integration.reconciliation_run
    ADD CONSTRAINT integration_reconciliation_run_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY integration.reconciliation_principal_staging
    ADD CONSTRAINT integration_reconciliation_staging_batch_fk FOREIGN KEY (tenant_id, reconciliation_run_id, batch_id) REFERENCES integration.reconciliation_observation_batch(tenant_id, reconciliation_run_id, batch_id);

ALTER TABLE ONLY integration.connector_worker_runtime_permission
    ADD CONSTRAINT integration_worker_permission_registration_fk FOREIGN KEY (tenant_id, worker_registration_id) REFERENCES integration.connector_worker_registration(tenant_id, id);

ALTER TABLE ONLY integration.connector_worker_registration
    ADD CONSTRAINT integration_worker_registration_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY integration.connector_worker_binding_scope
    ADD CONSTRAINT integration_worker_scope_binding_fk FOREIGN KEY (tenant_id, connector_binding_id) REFERENCES integration.connector_binding(tenant_id, id);

ALTER TABLE ONLY integration.connector_worker_binding_scope
    ADD CONSTRAINT integration_worker_scope_registration_fk FOREIGN KEY (tenant_id, worker_registration_id) REFERENCES integration.connector_worker_registration(tenant_id, id);

ALTER TABLE ONLY integration.connector_worker_session_capability
    ADD CONSTRAINT integration_worker_session_capability_session_fk FOREIGN KEY (tenant_id, session_id) REFERENCES integration.connector_worker_session(tenant_id, id);

ALTER TABLE ONLY integration.connector_worker_session_contract
    ADD CONSTRAINT integration_worker_session_contract_session_fk FOREIGN KEY (tenant_id, session_id) REFERENCES integration.connector_worker_session(tenant_id, id);

ALTER TABLE ONLY integration.connector_worker_session
    ADD CONSTRAINT integration_worker_session_registration_fk FOREIGN KEY (tenant_id, worker_registration_id) REFERENCES integration.connector_worker_registration(tenant_id, id);
