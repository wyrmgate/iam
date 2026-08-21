CREATE TABLE identity.source_system (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    code varchar(128) NOT NULL,
    name varchar(512) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT identity_source_system_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT identity_source_system_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_source_system_code_uq UNIQUE (tenant_id, code),
    CONSTRAINT identity_source_system_code_ck CHECK (btrim(code) <> ''),
    CONSTRAINT identity_source_system_name_ck CHECK (btrim(name) <> ''),
    CONSTRAINT identity_source_system_revision_ck CHECK (revision > 0),
    CONSTRAINT identity_source_system_timestamp_order_ck CHECK (updated_at >= created_at)
);

COMMENT ON TABLE identity.source_system IS
    'Identity-owned authoritative source definition. SourceSystem retains governance meaning independently from connector implementation.';

CREATE TABLE identity.source_import_run (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    run_state varchar(24) NOT NULL,
    completeness varchar(16) NOT NULL,
    started_at timestamptz NOT NULL,
    completed_at timestamptz NULL,
    checkpoint_token varchar(1024) NULL,
    partial_reason varchar(1024) NULL,
    CONSTRAINT identity_source_import_run_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_source_import_run_source_fk
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES identity.source_system (tenant_id, id),
    CONSTRAINT identity_source_import_run_state_ck
        CHECK (run_state IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT identity_source_import_run_completeness_ck
        CHECK (completeness IN ('COMPLETE', 'PARTIAL', 'UNKNOWN')),
    CONSTRAINT identity_source_import_run_completion_ck CHECK (
        (run_state = 'RUNNING' AND completed_at IS NULL)
        OR (run_state IN ('COMPLETED', 'FAILED') AND completed_at IS NOT NULL)
    ),
    CONSTRAINT identity_source_import_run_partial_reason_ck CHECK (
        completeness <> 'PARTIAL' OR (partial_reason IS NOT NULL AND btrim(partial_reason) <> '')
    )
);

COMMENT ON TABLE identity.source_import_run IS
    'Identity-owned source-import process provenance. Execution outcome and coverage completeness are independent semantics.';

CREATE TABLE identity.source_record (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    native_key varchar(1024) NOT NULL,
    observed_attributes jsonb NOT NULL,
    source_updated_at timestamptz NULL,
    first_observed_at timestamptz NOT NULL,
    last_observed_at timestamptz NOT NULL,
    last_import_run_id uuid NOT NULL,
    last_complete_import_run_id uuid NULL,
    CONSTRAINT identity_source_record_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_source_record_native_key_uq UNIQUE (tenant_id, source_system_id, native_key),
    CONSTRAINT identity_source_record_source_fk
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES identity.source_system (tenant_id, id),
    CONSTRAINT identity_source_record_last_run_fk
        FOREIGN KEY (tenant_id, last_import_run_id)
        REFERENCES identity.source_import_run (tenant_id, id),
    CONSTRAINT identity_source_record_last_complete_run_fk
        FOREIGN KEY (tenant_id, last_complete_import_run_id)
        REFERENCES identity.source_import_run (tenant_id, id),
    CONSTRAINT identity_source_record_native_key_ck CHECK (btrim(native_key) <> ''),
    CONSTRAINT identity_source_record_attributes_ck CHECK (jsonb_typeof(observed_attributes) = 'object'),
    CONSTRAINT identity_source_record_timestamp_order_ck CHECK (last_observed_at >= first_observed_at)
);

COMMENT ON TABLE identity.source_record IS
    'Current positive normalized source observation. observed_attributes contains bounded/filtered source-native fields, not retained raw connector payload. Partial imports never erase unseen records.';

CREATE TABLE identity.identity_link (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    source_record_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    link_state varchar(24) NOT NULL,
    linked_at timestamptz NOT NULL,
    ended_at timestamptz NULL,
    correlation_reason varchar(512) NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid NULL,
    CONSTRAINT identity_identity_link_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT identity_identity_link_source_record_fk
        FOREIGN KEY (tenant_id, source_record_id)
        REFERENCES identity.source_record (tenant_id, id),
    CONSTRAINT identity_identity_link_identity_fk
        FOREIGN KEY (tenant_id, identity_id)
        REFERENCES identity.identity (tenant_id, id),
    CONSTRAINT identity_identity_link_state_ck
        CHECK (link_state IN ('ACCEPTED', 'SUPERSEDED')),
    CONSTRAINT identity_identity_link_reason_ck CHECK (btrim(correlation_reason) <> ''),
    CONSTRAINT identity_identity_link_end_ck CHECK (
        (link_state = 'ACCEPTED' AND ended_at IS NULL)
        OR (link_state = 'SUPERSEDED' AND ended_at IS NOT NULL AND ended_at >= linked_at)
    )
);

CREATE UNIQUE INDEX identity_identity_link_one_active_accepted_idx
    ON identity.identity_link (tenant_id, source_record_id)
    WHERE link_state = 'ACCEPTED' AND ended_at IS NULL;

CREATE INDEX identity_source_record_source_observed_idx
    ON identity.source_record (tenant_id, source_system_id, last_observed_at, id);

CREATE INDEX identity_identity_link_identity_idx
    ON identity.identity_link (tenant_id, identity_id, linked_at, id);

COMMENT ON TABLE identity.identity_link IS
    'Authoritative correlation history. At most one active accepted canonical Identity link exists for a SourceRecord.';
