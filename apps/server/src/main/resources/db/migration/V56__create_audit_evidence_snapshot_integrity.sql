CREATE OR REPLACE FUNCTION audit.validate_material_snapshot(value jsonb)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $audit$
    SELECT value IS NULL OR (
        jsonb_typeof(value) = 'object'
        AND value ->> 'schemaVersion' = 'audit-material-v1'
        AND (value - ARRAY[
            'schemaVersion',
            'actorDisplayLabel',
            'resourceDisplayLabel',
            'resourceRevision',
            'resourceState'
        ]) = '{}'::jsonb
        AND (
            NOT (value ? 'actorDisplayLabel')
            OR jsonb_typeof(value -> 'actorDisplayLabel') = 'string'
        )
        AND (
            NOT (value ? 'resourceDisplayLabel')
            OR jsonb_typeof(value -> 'resourceDisplayLabel') = 'string'
        )
        AND (
            NOT (value ? 'resourceRevision')
            OR (
                jsonb_typeof(value -> 'resourceRevision') = 'number'
                AND (value ->> 'resourceRevision')::bigint > 0
            )
        )
        AND (
            NOT (value ? 'resourceState')
            OR jsonb_typeof(value -> 'resourceState') = 'string'
        )
    );
$audit$;

CREATE OR REPLACE FUNCTION audit.validate_integrity_metadata(value jsonb)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $audit$
    SELECT value IS NULL OR (
        jsonb_typeof(value) = 'object'
        AND value ->> 'schemaVersion' = 'audit-integrity-v1'
        AND value ->> 'algorithm' = 'SHA-256'
        AND (value - ARRAY[
            'schemaVersion',
            'algorithm',
            'contentSha256',
            'materialSnapshotSha256'
        ]) = '{}'::jsonb
        AND value ->> 'contentSha256' ~ '^[0-9a-f]{64}$'
        AND (
            NOT (value ? 'materialSnapshotSha256')
            OR value ->> 'materialSnapshotSha256' ~ '^[0-9a-f]{64}$'
        )
    );
$audit$;

ALTER TABLE audit.audit_record
    DROP CONSTRAINT audit_record_material_snapshot_object_ck,
    DROP CONSTRAINT audit_record_integrity_metadata_object_ck,
    ADD CONSTRAINT audit_record_material_snapshot_v1_ck
        CHECK (audit.validate_material_snapshot(material_snapshot)),
    ADD CONSTRAINT audit_record_integrity_metadata_v1_ck
        CHECK (audit.validate_integrity_metadata(integrity_metadata));

ALTER TABLE audit.audit_archived_record_index
    ADD COLUMN material_snapshot jsonb NULL,
    ADD COLUMN integrity_metadata jsonb NULL,
    ADD CONSTRAINT audit_archived_record_material_snapshot_v1_ck
        CHECK (audit.validate_material_snapshot(material_snapshot)),
    ADD CONSTRAINT audit_archived_record_integrity_metadata_v1_ck
        CHECK (audit.validate_integrity_metadata(integrity_metadata));

DROP VIEW audit.audit_record_query;

CREATE VIEW audit.audit_record_query AS
SELECT
    r.tenant_id,
    r.id,
    r.occurred_at,
    r.recorded_at,
    r.actor_id,
    r.action_type,
    r.resource_type,
    r.resource_id,
    r.outcome,
    r.correlation_id,
    r.causation_id,
    r.material_snapshot,
    r.integrity_metadata
FROM audit.audit_record r
UNION ALL
SELECT
    archived.tenant_id,
    archived.record_id AS id,
    archived.occurred_at,
    archived.recorded_at,
    archived.actor_id,
    archived.action_type,
    archived.resource_type,
    archived.resource_id,
    archived.outcome,
    archived.correlation_id,
    archived.causation_id,
    archived.material_snapshot,
    archived.integrity_metadata
FROM (
    SELECT DISTINCT ON (i.tenant_id, i.record_id)
        i.tenant_id,
        i.record_id,
        i.occurred_at,
        i.recorded_at,
        i.actor_id,
        i.action_type,
        i.resource_type,
        i.resource_id,
        i.outcome,
        i.correlation_id,
        i.causation_id,
        i.material_snapshot,
        i.integrity_metadata,
        s.completed_at,
        s.id AS segment_id
    FROM audit.audit_archived_record_index i
    JOIN audit.audit_archive_segment s
      ON s.tenant_id = i.tenant_id
     AND s.id = i.archive_segment_id
     AND s.state = 'SUCCEEDED'
    WHERE NOT EXISTS (
        SELECT 1
        FROM audit.audit_record online
        WHERE online.tenant_id = i.tenant_id
          AND online.id = i.record_id
    )
    ORDER BY i.tenant_id, i.record_id, s.completed_at DESC, s.id DESC
) archived;

CREATE TABLE audit.evidence_snapshot (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    actor_id uuid NULL,
    snapshot_type varchar(64) NOT NULL,
    subject_resource_type varchar(128) NOT NULL,
    subject_resource_id uuid NOT NULL,
    subject_revision bigint NULL,
    subject_display_label varchar(256) NULL,
    policy_resource_type varchar(128) NULL,
    policy_resource_id uuid NULL,
    policy_revision bigint NULL,
    policy_display_label varchar(256) NULL,
    related_resource_type varchar(128) NULL,
    related_resource_id uuid NULL,
    related_revision bigint NULL,
    related_display_label varchar(256) NULL,
    decision_label varchar(64) NOT NULL,
    correlation_id uuid NULL,
    causation_id uuid NULL,
    CONSTRAINT evidence_snapshot_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT evidence_snapshot_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT evidence_snapshot_type_ck CHECK (btrim(snapshot_type) <> ''),
    CONSTRAINT evidence_snapshot_subject_type_ck CHECK (btrim(subject_resource_type) <> ''),
    CONSTRAINT evidence_snapshot_subject_revision_ck CHECK (
        subject_revision IS NULL OR subject_revision > 0
    ),
    CONSTRAINT evidence_snapshot_policy_pair_ck CHECK (
        (policy_resource_type IS NULL AND policy_resource_id IS NULL AND policy_revision IS NULL)
        OR
        (policy_resource_type IS NOT NULL AND btrim(policy_resource_type) <> ''
            AND policy_resource_id IS NOT NULL
            AND (policy_revision IS NULL OR policy_revision > 0))
    ),
    CONSTRAINT evidence_snapshot_related_pair_ck CHECK (
        (related_resource_type IS NULL AND related_resource_id IS NULL AND related_revision IS NULL)
        OR
        (related_resource_type IS NOT NULL AND btrim(related_resource_type) <> ''
            AND related_resource_id IS NOT NULL
            AND (related_revision IS NULL OR related_revision > 0))
    ),
    CONSTRAINT evidence_snapshot_decision_ck CHECK (btrim(decision_label) <> '')
);

CREATE INDEX evidence_snapshot_time_idx
    ON audit.evidence_snapshot (tenant_id, occurred_at DESC, id DESC);
CREATE INDEX evidence_snapshot_subject_idx
    ON audit.evidence_snapshot
        (tenant_id, subject_resource_type, subject_resource_id, occurred_at DESC, id DESC);
CREATE INDEX evidence_snapshot_type_idx
    ON audit.evidence_snapshot (tenant_id, snapshot_type, occurred_at DESC, id DESC);
CREATE INDEX evidence_snapshot_correlation_idx
    ON audit.evidence_snapshot (tenant_id, correlation_id, occurred_at DESC, id DESC)
    WHERE correlation_id IS NOT NULL;

COMMENT ON COLUMN audit.audit_record.material_snapshot IS
    'Closed ADR-0036 audit-material-v1 explanatory display snapshot; stable IDs remain authoritative references.';
COMMENT ON COLUMN audit.audit_record.integrity_metadata IS
    'Derived ADR-0036 audit-integrity-v1 SHA-256 tamper-detection metadata; never authorization or ordering authority.';
COMMENT ON TABLE audit.evidence_snapshot IS
    'Audit-owned immutable ADR-0036 decision-time contextual reference snapshot. Capability-owned decision evidence remains with its owner.';
