-- Platform capability current-state persistence baseline.
-- Generated once from the verified pre-rebaseline schema, then maintained as reviewed source.

CREATE TABLE platform.connector_work_lease (
    tenant_id uuid NOT NULL,
    work_kind character varying(24) NOT NULL,
    work_id uuid NOT NULL,
    execution_owner_id uuid CONSTRAINT connector_work_lease_session_id_not_null NOT NULL,
    lease_id uuid NOT NULL,
    lease_epoch bigint NOT NULL,
    claimed_at timestamp with time zone NOT NULL,
    lease_expires_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT platform_connector_work_lease_epoch_ck CHECK ((lease_epoch > 0)),
    CONSTRAINT platform_connector_work_lease_expiry_ck CHECK ((lease_expires_at > claimed_at)),
    CONSTRAINT platform_connector_work_lease_kind_ck CHECK (((work_kind)::text = ANY ((ARRAY['PROVISION'::character varying, 'RECONCILE'::character varying])::text[])))
);

COMMENT ON COLUMN platform.connector_work_lease.execution_owner_id IS 'Opaque technical execution owner. Remote connector workers use negotiated session IDs; in-process executors use stable runtime owner IDs.';

CREATE TABLE platform.idempotency_record (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    operation_namespace character varying(256) NOT NULL,
    idempotency_key character varying(256) NOT NULL,
    request_fingerprint character varying(160) NOT NULL,
    operation_state character varying(24) DEFAULT 'IN_PROGRESS'::character varying NOT NULL,
    resource_type character varying(128),
    resource_id uuid,
    created_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    expires_at timestamp with time zone,
    CONSTRAINT idempotency_record_completion_consistent CHECK (((((operation_state)::text = 'COMPLETED'::text) AND (completed_at IS NOT NULL)) OR (((operation_state)::text = 'IN_PROGRESS'::text) AND (completed_at IS NULL)))),
    CONSTRAINT idempotency_record_expiry_after_creation CHECK (((expires_at IS NULL) OR (expires_at > created_at))),
    CONSTRAINT idempotency_record_fingerprint_not_blank CHECK ((btrim((request_fingerprint)::text) <> ''::text)),
    CONSTRAINT idempotency_record_key_not_blank CHECK ((btrim((idempotency_key)::text) <> ''::text)),
    CONSTRAINT idempotency_record_namespace_not_blank CHECK ((btrim((operation_namespace)::text) <> ''::text)),
    CONSTRAINT idempotency_record_resource_reference_complete CHECK ((((resource_type IS NULL) AND (resource_id IS NULL)) OR ((resource_type IS NOT NULL) AND (btrim((resource_type)::text) <> ''::text) AND (resource_id IS NOT NULL)))),
    CONSTRAINT idempotency_record_state CHECK (((operation_state)::text = ANY ((ARRAY['IN_PROGRESS'::character varying, 'COMPLETED'::character varying])::text[])))
);

CREATE TABLE platform.idp_authorization_code (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    client_registration_id uuid NOT NULL,
    client_registration_revision bigint NOT NULL,
    application_id uuid NOT NULL,
    client_id character varying(200) NOT NULL,
    browser_session_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    code_hash character varying(64) NOT NULL,
    redirect_uri character varying(2048) NOT NULL,
    scopes character varying(128) NOT NULL,
    pkce_challenge character varying(64) NOT NULL,
    nonce_value character varying(512),
    auth_time timestamp with time zone NOT NULL,
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    CONSTRAINT idp_authorization_code_client_revision_ck CHECK ((client_registration_revision > 0)),
    CONSTRAINT idp_authorization_code_hash_ck CHECK (((code_hash)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT idp_authorization_pkce_ck CHECK (((pkce_challenge)::text ~ '^[A-Za-z0-9_-]{43}$'::text)),
    CONSTRAINT idp_authorization_time_ck CHECK (((auth_time <= created_at) AND (expires_at > created_at) AND ((consumed_at IS NULL) OR (consumed_at >= created_at))))
);

COMMENT ON TABLE platform.idp_authorization_code IS 'Platform-owned short-lived OAuth authorization-code state; not IAM governance or access authority.';

COMMENT ON COLUMN platform.idp_authorization_code.client_registration_id IS 'Stable cross-capability reference to Catalog SSO registration; intentionally no database foreign key.';

COMMENT ON COLUMN platform.idp_authorization_code.code_hash IS 'SHA-256 digest of a high-entropy single-use authorization code. Raw codes are never persisted.';

CREATE TABLE platform.idp_browser_session (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    credential_id uuid NOT NULL,
    credential_revision bigint NOT NULL,
    token_hash character varying(64) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    last_seen_at timestamp with time zone NOT NULL,
    idle_expires_at timestamp with time zone NOT NULL,
    absolute_expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    CONSTRAINT idp_browser_session_credential_revision_ck CHECK ((credential_revision > 0)),
    CONSTRAINT idp_browser_session_time_ck CHECK (((last_seen_at >= created_at) AND (idle_expires_at > created_at) AND (absolute_expires_at > created_at) AND (idle_expires_at <= absolute_expires_at) AND ((revoked_at IS NULL) OR (revoked_at >= created_at)))),
    CONSTRAINT idp_browser_session_token_hash_ck CHECK (((token_hash)::text ~ '^[0-9a-f]{64}$'::text))
);

COMMENT ON TABLE platform.idp_browser_session IS 'Platform-owned first-party IdP browser authentication sessions. This is authentication context, not IAM governance or access authority.';

COMMENT ON COLUMN platform.idp_browser_session.credential_id IS 'Stable cross-capability reference to the Credential that established the session; intentionally no database foreign key.';

COMMENT ON COLUMN platform.idp_browser_session.token_hash IS 'SHA-256 digest of a high-entropy opaque browser token. Raw session tokens are never persisted.';

CREATE TABLE platform.inbox_message (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    consumer_name character varying(256) NOT NULL,
    message_id character varying(256) NOT NULL,
    processing_state character varying(24) DEFAULT 'RECEIVED'::character varying NOT NULL,
    first_seen_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    outcome_code character varying(128),
    CONSTRAINT inbox_message_completion_consistent CHECK (((((processing_state)::text = 'COMPLETED'::text) AND (completed_at IS NOT NULL)) OR (((processing_state)::text = 'RECEIVED'::text) AND (completed_at IS NULL)))),
    CONSTRAINT inbox_message_consumer_not_blank CHECK ((btrim((consumer_name)::text) <> ''::text)),
    CONSTRAINT inbox_message_message_id_not_blank CHECK ((btrim((message_id)::text) <> ''::text)),
    CONSTRAINT inbox_message_processing_state CHECK (((processing_state)::text = ANY ((ARRAY['RECEIVED'::character varying, 'COMPLETED'::character varying])::text[])))
);

CREATE TABLE platform.installation_bootstrap (
    singleton_key boolean DEFAULT true NOT NULL,
    state character varying(32) NOT NULL,
    tenant_id uuid NOT NULL,
    tenant_display_name character varying(512) NOT NULL,
    identity_id uuid NOT NULL,
    identity_display_name character varying(512) NOT NULL,
    console_application_id uuid NOT NULL,
    console_target_id uuid NOT NULL,
    console_sso_registration_id uuid NOT NULL,
    console_client_id character varying(200) NOT NULL,
    console_redirect_uri character varying(2048) NOT NULL,
    principal_id uuid NOT NULL,
    principal_key character varying(512) NOT NULL,
    credential_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    bootstrap_version integer NOT NULL,
    started_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    sealed_at timestamp with time zone,
    CONSTRAINT platform_installation_bootstrap_client_id_ck CHECK ((btrim((console_client_id)::text) <> ''::text)),
    CONSTRAINT platform_installation_bootstrap_identity_name_ck CHECK ((btrim((identity_display_name)::text) <> ''::text)),
    CONSTRAINT platform_installation_bootstrap_principal_key_ck CHECK ((btrim((principal_key)::text) <> ''::text)),
    CONSTRAINT platform_installation_bootstrap_redirect_ck CHECK ((btrim((console_redirect_uri)::text) <> ''::text)),
    CONSTRAINT platform_installation_bootstrap_seal_ck CHECK (((((state)::text = 'SEALED'::text) AND (sealed_at IS NOT NULL)) OR (((state)::text <> 'SEALED'::text) AND (sealed_at IS NULL)))),
    CONSTRAINT platform_installation_bootstrap_singleton_ck CHECK (singleton_key),
    CONSTRAINT platform_installation_bootstrap_state_ck CHECK (((state)::text = ANY ((ARRAY['PLANNED'::character varying, 'TENANT_CREATED'::character varying, 'IDENTITY_CREATED'::character varying, 'CONSOLE_REGISTERED'::character varying, 'PRINCIPAL_CREATED'::character varying, 'CREDENTIAL_ENROLLED'::character varying, 'ADMIN_BOOTSTRAPPED'::character varying, 'VERIFIED'::character varying, 'SEALED'::character varying])::text[]))),
    CONSTRAINT platform_installation_bootstrap_tenant_name_ck CHECK ((btrim((tenant_display_name)::text) <> ''::text)),
    CONSTRAINT platform_installation_bootstrap_time_ck CHECK ((updated_at >= started_at)),
    CONSTRAINT platform_installation_bootstrap_version_ck CHECK ((bootstrap_version > 0))
);

COMMENT ON TABLE platform.installation_bootstrap IS 'Deployment-local ADR-0044 fresh-install plan and irreversible seal. It stores stable identifiers and non-secret inputs only; no password or verifier material is permitted.';

COMMENT ON COLUMN platform.installation_bootstrap.tenant_id IS 'Preallocated first Tenant ID. No FK is intentional because the plan is committed before the Tenant exists.';

COMMENT ON COLUMN platform.installation_bootstrap.sealed_at IS 'Permanent terminal installation seal. Loss of later administrative authority never reopens this process.';

CREATE TABLE platform.outbox_event (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    event_type character varying(256) NOT NULL,
    event_version integer NOT NULL,
    aggregate_type character varying(128),
    aggregate_id uuid,
    aggregate_revision bigint,
    occurred_at timestamp with time zone NOT NULL,
    correlation_id uuid,
    causation_id uuid,
    payload jsonb NOT NULL,
    publication_state character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone,
    last_attempt_at timestamp with time zone,
    published_at timestamp with time zone,
    last_error_code character varying(128),
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT outbox_event_aggregate_reference_complete CHECK ((((aggregate_id IS NULL) AND (aggregate_revision IS NULL) AND (aggregate_type IS NULL)) OR ((aggregate_id IS NOT NULL) AND (aggregate_revision IS NOT NULL) AND (aggregate_type IS NOT NULL) AND (btrim((aggregate_type)::text) <> ''::text)))),
    CONSTRAINT outbox_event_aggregate_revision_positive CHECK (((aggregate_revision IS NULL) OR (aggregate_revision > 0))),
    CONSTRAINT outbox_event_attempt_count_nonnegative CHECK ((attempt_count >= 0)),
    CONSTRAINT outbox_event_failed_state_consistent CHECK ((((publication_state)::text <> 'FAILED'::text) OR ((next_attempt_at IS NULL) AND (last_error_code IS NOT NULL) AND (btrim((last_error_code)::text) <> ''::text)))),
    CONSTRAINT outbox_event_payload_object CHECK ((jsonb_typeof(payload) = 'object'::text)),
    CONSTRAINT outbox_event_publication_state CHECK (((publication_state)::text = ANY ((ARRAY['PENDING'::character varying, 'PUBLISHED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT outbox_event_published_state_consistent CHECK (((((publication_state)::text = 'PUBLISHED'::text) AND (published_at IS NOT NULL)) OR (((publication_state)::text = ANY ((ARRAY['PENDING'::character varying, 'FAILED'::character varying])::text[])) AND (published_at IS NULL)))),
    CONSTRAINT outbox_event_type_not_blank CHECK ((btrim((event_type)::text) <> ''::text)),
    CONSTRAINT outbox_event_version_positive CHECK ((event_version > 0))
);

CREATE TABLE platform.scheduled_work (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    handler_type character varying(256) NOT NULL,
    work_key character varying(256) NOT NULL,
    subject_type character varying(128),
    subject_id uuid,
    subject_revision bigint,
    available_at timestamp with time zone NOT NULL,
    delivery_state character varying(24) DEFAULT 'READY'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    lease_owner character varying(256),
    lease_until timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    last_error_code character varying(128),
    CONSTRAINT scheduled_work_attempt_count_nonnegative CHECK ((attempt_count >= 0)),
    CONSTRAINT scheduled_work_delivery_state CHECK (((delivery_state)::text = ANY ((ARRAY['READY'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT scheduled_work_handler_not_blank CHECK ((btrim((handler_type)::text) <> ''::text)),
    CONSTRAINT scheduled_work_key_not_blank CHECK ((btrim((work_key)::text) <> ''::text)),
    CONSTRAINT scheduled_work_last_error_code_ck CHECK (((last_error_code IS NULL) OR (btrim((last_error_code)::text) <> ''::text))),
    CONSTRAINT scheduled_work_lease_complete CHECK ((((lease_owner IS NULL) AND (lease_until IS NULL)) OR ((lease_owner IS NOT NULL) AND (btrim((lease_owner)::text) <> ''::text) AND (lease_until IS NOT NULL)))),
    CONSTRAINT scheduled_work_subject_reference_complete CHECK ((((subject_type IS NULL) AND (subject_id IS NULL) AND (subject_revision IS NULL)) OR ((subject_type IS NOT NULL) AND (btrim((subject_type)::text) <> ''::text) AND (subject_id IS NOT NULL) AND (subject_revision IS NOT NULL) AND (subject_revision > 0)))),
    CONSTRAINT scheduled_work_timestamp_order CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE platform.scheduled_work IS 'Technical timers/delivery leases only. Referenced domain capabilities retain business/process state ownership.';

COMMENT ON COLUMN platform.scheduled_work.last_error_code IS 'Normalized technical delivery/retry code only. Domain capabilities retain business/process outcome ownership.';

CREATE TABLE platform.tenant (
    id uuid NOT NULL,
    display_name character varying(512) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT tenant_display_name_not_blank CHECK ((btrim((display_name)::text) <> ''::text)),
    CONSTRAINT tenant_revision_positive CHECK ((revision > 0)),
    CONSTRAINT tenant_timestamp_order CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE platform.tenant IS 'Explicit tenant isolation roots. IDs are application-generated opaque UUIDv7 values.';

ALTER TABLE ONLY platform.connector_work_lease
    ADD CONSTRAINT connector_work_lease_pkey PRIMARY KEY (tenant_id, work_kind, work_id);

ALTER TABLE ONLY platform.idempotency_record
    ADD CONSTRAINT idempotency_record_causal_key_unique UNIQUE (tenant_id, operation_namespace, idempotency_key);

ALTER TABLE ONLY platform.idempotency_record
    ADD CONSTRAINT idempotency_record_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform.idempotency_record
    ADD CONSTRAINT idempotency_record_tenant_id_unique UNIQUE (tenant_id, id);

ALTER TABLE ONLY platform.idp_authorization_code
    ADD CONSTRAINT idp_authorization_code_hash_uq UNIQUE (tenant_id, code_hash);

ALTER TABLE ONLY platform.idp_authorization_code
    ADD CONSTRAINT idp_authorization_code_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform.idp_browser_session
    ADD CONSTRAINT idp_browser_session_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform.idp_browser_session
    ADD CONSTRAINT idp_browser_session_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY platform.idp_browser_session
    ADD CONSTRAINT idp_browser_session_token_hash_uq UNIQUE (tenant_id, token_hash);

ALTER TABLE ONLY platform.inbox_message
    ADD CONSTRAINT inbox_message_dedup_unique UNIQUE (tenant_id, consumer_name, message_id);

ALTER TABLE ONLY platform.inbox_message
    ADD CONSTRAINT inbox_message_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform.inbox_message
    ADD CONSTRAINT inbox_message_tenant_id_unique UNIQUE (tenant_id, id);

ALTER TABLE ONLY platform.installation_bootstrap
    ADD CONSTRAINT installation_bootstrap_pkey PRIMARY KEY (singleton_key);

ALTER TABLE ONLY platform.outbox_event
    ADD CONSTRAINT outbox_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform.outbox_event
    ADD CONSTRAINT outbox_event_tenant_id_unique UNIQUE (tenant_id, id);

ALTER TABLE ONLY platform.connector_work_lease
    ADD CONSTRAINT platform_connector_work_lease_id_uq UNIQUE (lease_id);

ALTER TABLE ONLY platform.scheduled_work
    ADD CONSTRAINT scheduled_work_causal_key_unique UNIQUE (tenant_id, handler_type, work_key);

ALTER TABLE ONLY platform.scheduled_work
    ADD CONSTRAINT scheduled_work_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform.scheduled_work
    ADD CONSTRAINT scheduled_work_tenant_id_unique UNIQUE (tenant_id, id);

ALTER TABLE ONLY platform.tenant
    ADD CONSTRAINT tenant_pkey PRIMARY KEY (id);

CREATE INDEX idempotency_record_expiry_idx ON platform.idempotency_record USING btree (expires_at) WHERE (expires_at IS NOT NULL);

CREATE INDEX idp_authorization_code_expiry_idx ON platform.idp_authorization_code USING btree (tenant_id, expires_at) WHERE (consumed_at IS NULL);

CREATE INDEX idp_browser_session_expiry_idx ON platform.idp_browser_session USING btree (tenant_id, idle_expires_at, absolute_expires_at) WHERE (revoked_at IS NULL);

CREATE UNIQUE INDEX idp_browser_session_token_hash_global_uq ON platform.idp_browser_session USING btree (token_hash);

COMMENT ON INDEX platform.idp_browser_session_token_hash_global_uq IS 'Globally unique opaque-session digest used to derive Tenant server-side from possession of a valid first-party browser session token.';

CREATE INDEX outbox_event_pending_idx ON platform.outbox_event USING btree (next_attempt_at, occurred_at, id) WHERE ((publication_state)::text = 'PENDING'::text);

CREATE INDEX outbox_event_tenant_aggregate_idx ON platform.outbox_event USING btree (tenant_id, aggregate_type, aggregate_id, aggregate_revision) WHERE (aggregate_id IS NOT NULL);

CREATE INDEX platform_connector_work_lease_expiry_idx ON platform.connector_work_lease USING btree (lease_expires_at);

CREATE INDEX scheduled_work_due_idx ON platform.scheduled_work USING btree (available_at, id) WHERE ((delivery_state)::text = 'READY'::text);

ALTER TABLE ONLY platform.idempotency_record
    ADD CONSTRAINT idempotency_record_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY platform.idp_authorization_code
    ADD CONSTRAINT idp_authorization_code_session_fk FOREIGN KEY (tenant_id, browser_session_id) REFERENCES platform.idp_browser_session(tenant_id, id);

ALTER TABLE ONLY platform.idp_authorization_code
    ADD CONSTRAINT idp_authorization_code_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY platform.idp_browser_session
    ADD CONSTRAINT idp_browser_session_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY platform.inbox_message
    ADD CONSTRAINT inbox_message_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY platform.outbox_event
    ADD CONSTRAINT outbox_event_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY platform.connector_work_lease
    ADD CONSTRAINT platform_connector_work_lease_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY platform.scheduled_work
    ADD CONSTRAINT scheduled_work_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);
