CREATE TABLE audit.audit_record (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    actor_id uuid NULL,
    action_type varchar(128) NOT NULL,
    resource_type varchar(128) NOT NULL,
    resource_id uuid NULL,
    outcome varchar(32) NOT NULL,
    correlation_id uuid NULL,
    causation_id uuid NULL,
    material_snapshot jsonb NULL,
    integrity_metadata jsonb NULL,
    CONSTRAINT audit_record_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT audit_record_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT audit_record_action_type_ck CHECK (btrim(action_type) <> ''),
    CONSTRAINT audit_record_resource_type_ck CHECK (btrim(resource_type) <> ''),
    CONSTRAINT audit_record_outcome_ck CHECK (outcome IN ('SUCCESS','DENIED','FAILURE')),
    CONSTRAINT audit_record_material_snapshot_object_ck CHECK (
        material_snapshot IS NULL OR jsonb_typeof(material_snapshot) = 'object'
    ),
    CONSTRAINT audit_record_integrity_metadata_object_ck CHECK (
        integrity_metadata IS NULL OR jsonb_typeof(integrity_metadata) = 'object'
    )
);

CREATE INDEX audit_record_time_idx
    ON audit.audit_record (tenant_id, occurred_at DESC, id DESC);

CREATE INDEX audit_record_actor_time_idx
    ON audit.audit_record (tenant_id, actor_id, occurred_at DESC, id DESC)
    WHERE actor_id IS NOT NULL;

CREATE INDEX audit_record_resource_time_idx
    ON audit.audit_record (tenant_id, resource_type, resource_id, occurred_at DESC, id DESC);

CREATE INDEX audit_record_correlation_time_idx
    ON audit.audit_record (tenant_id, correlation_id, occurred_at DESC, id DESC)
    WHERE correlation_id IS NOT NULL;

CREATE INDEX audit_record_action_time_idx
    ON audit.audit_record (tenant_id, action_type, occurred_at DESC, id DESC);

CREATE INDEX audit_record_outcome_time_idx
    ON audit.audit_record (tenant_id, outcome, occurred_at DESC, id DESC);

CREATE OR REPLACE FUNCTION audit.reject_audit_record_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $audit$
BEGIN
    RAISE EXCEPTION 'AuditRecord is append-only';
END;
$audit$;

CREATE TRIGGER audit_record_append_only_trg
BEFORE UPDATE OR DELETE ON audit.audit_record
FOR EACH ROW EXECUTE FUNCTION audit.reject_audit_record_mutation();

COMMENT ON TABLE audit.audit_record IS
    'Append-only Audit-owned security/governance evidence. Domain facts, evidence snapshots and operational logs remain separate.';
COMMENT ON COLUMN audit.audit_record.material_snapshot IS
    'Reserved for a later data-minimized material snapshot contract; unused by the first AuditRecord runtime slice.';
COMMENT ON COLUMN audit.audit_record.integrity_metadata IS
    'Reserved for later integrity/archive metadata; unused by the first AuditRecord runtime slice.';
