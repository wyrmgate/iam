CREATE TABLE audit.audit_retention_policy_version (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    policy_version bigint NOT NULL,
    export_artifact_lifetime_ms bigint NOT NULL,
    archive_eligibility_age_ms bigint NOT NULL,
    minimum_online_record_retention_ms bigint NOT NULL,
    minimum_archive_retention_ms bigint NOT NULL,
    effective_from timestamptz NOT NULL,
    correlation_id uuid NULL,
    causation_id uuid NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT audit_retention_policy_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT audit_retention_policy_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT audit_retention_policy_version_uq UNIQUE (tenant_id, policy_version),
    CONSTRAINT audit_retention_policy_version_ck CHECK (policy_version > 0),
    CONSTRAINT audit_retention_policy_durations_ck CHECK (
        export_artifact_lifetime_ms > 0
        AND archive_eligibility_age_ms > 0
        AND minimum_online_record_retention_ms > 0
        AND minimum_archive_retention_ms > 0
    )
);

CREATE INDEX audit_retention_policy_effective_idx
    ON audit.audit_retention_policy_version (tenant_id, effective_from DESC, policy_version DESC);

CREATE OR REPLACE FUNCTION audit.reject_audit_retention_policy_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $audit$
BEGIN
    RAISE EXCEPTION 'AuditRetentionPolicyVersion is immutable';
END;
$audit$;

CREATE TRIGGER audit_retention_policy_immutable_trg
BEFORE UPDATE OR DELETE ON audit.audit_retention_policy_version
FOR EACH ROW EXECUTE FUNCTION audit.reject_audit_retention_policy_mutation();

CREATE TABLE audit.audit_archive_segment (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    retention_policy_version bigint NOT NULL,
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
    failure_code varchar(128) NULL,
    correlation_id uuid NULL,
    causation_id uuid NULL,
    revision bigint NOT NULL DEFAULT 1,
    completed_at timestamptz NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT audit_archive_segment_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT audit_archive_segment_policy_fk
        FOREIGN KEY (tenant_id, retention_policy_version)
        REFERENCES audit.audit_retention_policy_version (tenant_id, policy_version),
    CONSTRAINT audit_archive_segment_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT audit_archive_segment_window_ck CHECK (occurred_until > occurred_from),
    CONSTRAINT audit_archive_segment_schema_ck CHECK (btrim(schema_version) <> ''),
    CONSTRAINT audit_archive_segment_state_ck CHECK (state IN ('REQUESTED','RUNNING','SUCCEEDED','FAILED')),
    CONSTRAINT audit_archive_segment_continuation_ck CHECK (
        (continuation_occurred_at IS NULL AND continuation_id IS NULL)
        OR (continuation_occurred_at IS NOT NULL AND continuation_id IS NOT NULL)
    ),
    CONSTRAINT audit_archive_segment_counts_ck CHECK (record_count >= 0 AND byte_count >= 0),
    CONSTRAINT audit_archive_segment_sha_ck CHECK (
        sha256_hex IS NULL OR sha256_hex ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT audit_archive_segment_terminal_ck CHECK (
        (state = 'SUCCEEDED'
            AND artifact_reference IS NOT NULL
            AND sha256_hex IS NOT NULL
            AND completed_at IS NOT NULL
            AND failure_code IS NULL)
        OR (state = 'FAILED'
            AND failure_code IS NOT NULL
            AND completed_at IS NOT NULL
            AND artifact_reference IS NULL
            AND sha256_hex IS NULL)
        OR (state IN ('REQUESTED','RUNNING')
            AND completed_at IS NULL
            AND failure_code IS NULL
            AND artifact_reference IS NULL
            AND sha256_hex IS NULL)
    )
);

CREATE INDEX audit_archive_segment_state_idx
    ON audit.audit_archive_segment (tenant_id, state, created_at, id);

CREATE INDEX audit_archive_segment_range_idx
    ON audit.audit_archive_segment (tenant_id, occurred_from, occurred_until, snapshot_recorded_at, id);

CREATE OR REPLACE FUNCTION audit.protect_audit_archive_segment()
RETURNS trigger
LANGUAGE plpgsql
AS $audit$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'AuditArchiveSegment cannot be deleted';
    END IF;

    IF OLD.state IN ('SUCCEEDED','FAILED') THEN
        RAISE EXCEPTION 'Terminal AuditArchiveSegment is immutable';
    END IF;

    IF NEW.tenant_id <> OLD.tenant_id
       OR NEW.retention_policy_version <> OLD.retention_policy_version
       OR NEW.occurred_from <> OLD.occurred_from
       OR NEW.occurred_until <> OLD.occurred_until
       OR NEW.snapshot_recorded_at <> OLD.snapshot_recorded_at
       OR NEW.schema_version <> OLD.schema_version
       OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
       OR NEW.causation_id IS DISTINCT FROM OLD.causation_id
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'AuditArchiveSegment frozen membership/evidence fields are immutable';
    END IF;

    RETURN NEW;
END;
$audit$;

CREATE TRIGGER audit_archive_segment_protection_trg
BEFORE UPDATE OR DELETE ON audit.audit_archive_segment
FOR EACH ROW EXECUTE FUNCTION audit.protect_audit_archive_segment();

COMMENT ON TABLE audit.audit_retention_policy_version IS
    'Immutable versioned Audit retention policy inputs. No row grants destructive AuditRecord deletion authority.';
COMMENT ON TABLE audit.audit_archive_segment IS
    'Durable immutable verified archive-segment evidence generated from a frozen AuditRecord membership range.';
COMMENT ON COLUMN audit.audit_archive_segment.artifact_reference IS
    'Opaque deployment adapter reference; never a signed URL, provider credential, or public IAM identifier.';
