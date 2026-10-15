CREATE TABLE platform.outbox_event (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    event_type varchar(256) NOT NULL,
    event_version integer NOT NULL,
    aggregate_type varchar(128),
    aggregate_id uuid,
    aggregate_revision bigint,
    occurred_at timestamptz NOT NULL,
    correlation_id uuid,
    causation_id uuid,
    payload jsonb NOT NULL,
    publication_state varchar(24) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    last_attempt_at timestamptz,
    published_at timestamptz,
    last_error_code varchar(128),
    created_at timestamptz NOT NULL,
    CONSTRAINT outbox_event_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT outbox_event_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT outbox_event_type_not_blank CHECK (btrim(event_type) <> ''),
    CONSTRAINT outbox_event_version_positive CHECK (event_version > 0),
    CONSTRAINT outbox_event_aggregate_revision_positive CHECK (aggregate_revision IS NULL OR aggregate_revision > 0),
    CONSTRAINT outbox_event_aggregate_reference_complete CHECK (
        (aggregate_id IS NULL AND aggregate_revision IS NULL AND aggregate_type IS NULL)
        OR (aggregate_id IS NOT NULL AND aggregate_revision IS NOT NULL AND aggregate_type IS NOT NULL AND btrim(aggregate_type) <> '')
    ),
    CONSTRAINT outbox_event_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT outbox_event_publication_state CHECK (publication_state IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT outbox_event_attempt_count_nonnegative CHECK (attempt_count >= 0),
    CONSTRAINT outbox_event_published_state_consistent CHECK (
        (publication_state = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (publication_state = 'PENDING' AND published_at IS NULL)
    )
);

CREATE INDEX outbox_event_pending_idx
    ON platform.outbox_event (next_attempt_at, occurred_at, id)
    WHERE publication_state = 'PENDING';

CREATE INDEX outbox_event_tenant_aggregate_idx
    ON platform.outbox_event (tenant_id, aggregate_type, aggregate_id, aggregate_revision)
    WHERE aggregate_id IS NOT NULL;

CREATE TABLE platform.inbox_message (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    consumer_name varchar(256) NOT NULL,
    message_id varchar(256) NOT NULL,
    processing_state varchar(24) NOT NULL DEFAULT 'RECEIVED',
    first_seen_at timestamptz NOT NULL,
    completed_at timestamptz,
    outcome_code varchar(128),
    CONSTRAINT inbox_message_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT inbox_message_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT inbox_message_consumer_not_blank CHECK (btrim(consumer_name) <> ''),
    CONSTRAINT inbox_message_message_id_not_blank CHECK (btrim(message_id) <> ''),
    CONSTRAINT inbox_message_processing_state CHECK (processing_state IN ('RECEIVED', 'COMPLETED')),
    CONSTRAINT inbox_message_completion_consistent CHECK (
        (processing_state = 'COMPLETED' AND completed_at IS NOT NULL)
        OR (processing_state = 'RECEIVED' AND completed_at IS NULL)
    ),
    CONSTRAINT inbox_message_dedup_unique UNIQUE (tenant_id, consumer_name, message_id)
);

CREATE TABLE platform.idempotency_record (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    operation_namespace varchar(256) NOT NULL,
    idempotency_key varchar(256) NOT NULL,
    request_fingerprint varchar(160) NOT NULL,
    operation_state varchar(24) NOT NULL DEFAULT 'IN_PROGRESS',
    resource_type varchar(128),
    resource_id uuid,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    expires_at timestamptz,
    CONSTRAINT idempotency_record_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT idempotency_record_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT idempotency_record_namespace_not_blank CHECK (btrim(operation_namespace) <> ''),
    CONSTRAINT idempotency_record_key_not_blank CHECK (btrim(idempotency_key) <> ''),
    CONSTRAINT idempotency_record_fingerprint_not_blank CHECK (btrim(request_fingerprint) <> ''),
    CONSTRAINT idempotency_record_state CHECK (operation_state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT idempotency_record_resource_reference_complete CHECK (
        (resource_type IS NULL AND resource_id IS NULL)
        OR (resource_type IS NOT NULL AND btrim(resource_type) <> '' AND resource_id IS NOT NULL)
    ),
    CONSTRAINT idempotency_record_completion_consistent CHECK (
        (operation_state = 'COMPLETED' AND completed_at IS NOT NULL)
        OR (operation_state = 'IN_PROGRESS' AND completed_at IS NULL)
    ),
    CONSTRAINT idempotency_record_expiry_after_creation CHECK (expires_at IS NULL OR expires_at > created_at),
    CONSTRAINT idempotency_record_causal_key_unique UNIQUE (tenant_id, operation_namespace, idempotency_key)
);

CREATE INDEX idempotency_record_expiry_idx
    ON platform.idempotency_record (expires_at)
    WHERE expires_at IS NOT NULL;
