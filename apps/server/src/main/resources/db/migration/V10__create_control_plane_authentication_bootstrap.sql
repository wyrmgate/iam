CREATE TABLE administration.control_plane_actor_binding (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    external_subject_key char(64) NOT NULL,
    issuer varchar(512) NOT NULL,
    subject varchar(512) NOT NULL,
    actor_identity_id uuid NOT NULL,
    state varchar(24) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT administration_actor_binding_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT administration_actor_binding_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT administration_actor_binding_subject_key_uq UNIQUE (external_subject_key),
    CONSTRAINT administration_actor_binding_subject_key_ck CHECK (
        external_subject_key ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT administration_actor_binding_issuer_ck CHECK (btrim(issuer) <> '' AND issuer = btrim(issuer)),
    CONSTRAINT administration_actor_binding_subject_ck CHECK (btrim(subject) <> '' AND subject = btrim(subject)),
    CONSTRAINT administration_actor_binding_state_ck CHECK (state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT administration_actor_binding_revision_ck CHECK (revision > 0),
    CONSTRAINT administration_actor_binding_timestamp_ck CHECK (updated_at >= created_at)
);

COMMENT ON TABLE administration.control_plane_actor_binding IS
    'Administration-owned mapping from a validated external issuer+subject to one tenant-scoped governed Identity. The binding never grants IAM permission by itself.';
COMMENT ON COLUMN administration.control_plane_actor_binding.actor_identity_id IS
    'Cross-capability stable Identity ID; intentionally no database FK to Identity.';
COMMENT ON COLUMN administration.control_plane_actor_binding.external_subject_key IS
    'SHA-256 lookup key over length-delimited issuer and subject. Exact issuer+subject remain authoritative and are rechecked on lookup.';

CREATE INDEX administration_actor_binding_tenant_actor_idx
    ON administration.control_plane_actor_binding (tenant_id, actor_identity_id, state);

CREATE TABLE administration.initial_admin_bootstrap (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    actor_identity_id uuid NOT NULL,
    actor_binding_id uuid NOT NULL,
    administrative_role_id uuid NOT NULL,
    administrative_grant_id uuid NOT NULL,
    completed_at timestamptz NOT NULL,
    correlation_id uuid NOT NULL,
    CONSTRAINT administration_initial_bootstrap_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT administration_initial_bootstrap_tenant_uq UNIQUE (tenant_id),
    CONSTRAINT administration_initial_bootstrap_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT administration_initial_bootstrap_binding_fk
        FOREIGN KEY (tenant_id, actor_binding_id)
        REFERENCES administration.control_plane_actor_binding (tenant_id, id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT administration_initial_bootstrap_role_fk
        FOREIGN KEY (tenant_id, administrative_role_id)
        REFERENCES administration.administrative_role (tenant_id, id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT administration_initial_bootstrap_grant_fk
        FOREIGN KEY (tenant_id, administrative_grant_id)
        REFERENCES administration.administrative_grant (tenant_id, id)
        DEFERRABLE INITIALLY DEFERRED
);

COMMENT ON TABLE administration.initial_admin_bootstrap IS
    'Immutable burn-once tenant marker for first-administrator provisioning. Its unique tenant row permanently closes the bootstrap path after the successful transaction commits.';
COMMENT ON COLUMN administration.initial_admin_bootstrap.actor_identity_id IS
    'Governed Identity selected as the first administrator; intentionally no cross-capability database FK.';
