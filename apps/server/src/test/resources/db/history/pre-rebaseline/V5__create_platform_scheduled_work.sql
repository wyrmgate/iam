CREATE TABLE platform.scheduled_work (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    handler_type varchar(256) NOT NULL,
    work_key varchar(256) NOT NULL,
    subject_type varchar(128),
    subject_id uuid,
    subject_revision bigint,
    available_at timestamptz NOT NULL,
    delivery_state varchar(24) NOT NULL DEFAULT 'READY',
    attempt_count integer NOT NULL DEFAULT 0,
    lease_owner varchar(256),
    lease_until timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT scheduled_work_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT scheduled_work_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT scheduled_work_handler_not_blank CHECK (btrim(handler_type) <> ''),
    CONSTRAINT scheduled_work_key_not_blank CHECK (btrim(work_key) <> ''),
    CONSTRAINT scheduled_work_subject_reference_complete CHECK (
        (subject_type IS NULL AND subject_id IS NULL AND subject_revision IS NULL)
        OR (subject_type IS NOT NULL AND btrim(subject_type) <> '' AND subject_id IS NOT NULL AND subject_revision IS NOT NULL AND subject_revision > 0)
    ),
    CONSTRAINT scheduled_work_delivery_state CHECK (delivery_state IN ('READY', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT scheduled_work_attempt_count_nonnegative CHECK (attempt_count >= 0),
    CONSTRAINT scheduled_work_lease_complete CHECK (
        (lease_owner IS NULL AND lease_until IS NULL)
        OR (lease_owner IS NOT NULL AND btrim(lease_owner) <> '' AND lease_until IS NOT NULL)
    ),
    CONSTRAINT scheduled_work_timestamp_order CHECK (updated_at >= created_at),
    CONSTRAINT scheduled_work_causal_key_unique UNIQUE (tenant_id, handler_type, work_key)
);

CREATE INDEX scheduled_work_due_idx
    ON platform.scheduled_work (available_at, id)
    WHERE delivery_state = 'READY';

COMMENT ON TABLE platform.scheduled_work IS
    'Technical timers/delivery leases only. Referenced domain capabilities retain business/process state ownership.';
