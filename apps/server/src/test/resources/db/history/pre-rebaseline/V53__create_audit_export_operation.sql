CREATE TABLE audit.audit_export_operation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    requested_by_identity_id uuid NOT NULL,
    actor_filter_id uuid NULL,
    action_type_filter varchar(128) NULL,
    resource_type_filter varchar(128) NULL,
    resource_id_filter uuid NULL,
    outcome_filter varchar(32) NULL,
    correlation_id_filter uuid NULL,
    occurred_from timestamptz NOT NULL,
    occurred_until timestamptz NOT NULL,
    snapshot_recorded_at timestamptz NOT NULL,
    schema_version varchar(64) NOT NULL,
    state varchar(32) NOT NULL,
    continuation_occurred_at timestamptz NULL,
    continuation_id uuid NULL,
    record_count bigint NOT NULL DEFAULT 0,
    byte_count bigint NOT NULL DEFAULT 0,
    sha256_hex varchar(64) NULL,
    artifact_reference varchar(512) NULL,
    artifact_expires_at timestamptz NULL,
    failure_code varchar(128) NULL,
    revision bigint NOT NULL DEFAULT 1,
    completed_at timestamptz NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT audit_export_operation_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT audit_export_operation_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT audit_export_operation_window_ck CHECK (occurred_until > occurred_from),
    CONSTRAINT audit_export_operation_schema_ck CHECK (btrim(schema_version) <> ''),
    CONSTRAINT audit_export_operation_state_ck CHECK (state IN ('REQUESTED','RUNNING','SUCCEEDED','FAILED')),
    CONSTRAINT audit_export_operation_outcome_ck CHECK (
        outcome_filter IS NULL OR outcome_filter IN ('SUCCESS','DENIED','FAILURE')
    ),
    CONSTRAINT audit_export_operation_continuation_ck CHECK (
        (continuation_occurred_at IS NULL AND continuation_id IS NULL)
        OR (continuation_occurred_at IS NOT NULL AND continuation_id IS NOT NULL)
    ),
    CONSTRAINT audit_export_operation_counts_ck CHECK (record_count >= 0 AND byte_count >= 0),
    CONSTRAINT audit_export_operation_sha_ck CHECK (
        sha256_hex IS NULL OR sha256_hex ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT audit_export_operation_terminal_ck CHECK (
        (state = 'SUCCEEDED'
            AND artifact_reference IS NOT NULL
            AND sha256_hex IS NOT NULL
            AND completed_at IS NOT NULL
            AND failure_code IS NULL)
        OR (state = 'FAILED'
            AND failure_code IS NOT NULL
            AND completed_at IS NOT NULL
            AND artifact_reference IS NULL)
        OR (state IN ('REQUESTED','RUNNING')
            AND completed_at IS NULL
            AND failure_code IS NULL)
    )
);

CREATE INDEX audit_export_operation_state_idx
    ON audit.audit_export_operation (tenant_id, state, created_at, id);

CREATE INDEX audit_export_operation_requester_idx
    ON audit.audit_export_operation (tenant_id, requested_by_identity_id, created_at DESC, id DESC);

COMMENT ON TABLE audit.audit_export_operation IS
    'Audit-owned durable ADR-0034 export process. Artifact bytes remain outside the authoritative database.';
COMMENT ON COLUMN audit.audit_export_operation.artifact_reference IS
    'Opaque deployment adapter reference; never a credential or public provider-native object identifier.';
