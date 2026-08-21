CREATE TABLE platform.tenant (
    id uuid PRIMARY KEY,
    display_name varchar(512) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT tenant_display_name_not_blank CHECK (btrim(display_name) <> ''),
    CONSTRAINT tenant_revision_positive CHECK (revision > 0),
    CONSTRAINT tenant_timestamp_order CHECK (updated_at >= created_at)
);

COMMENT ON TABLE platform.tenant IS
    'Explicit tenant isolation roots. IDs are application-generated opaque UUIDv7 values.';
