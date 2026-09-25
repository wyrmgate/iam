CREATE TABLE catalog.role (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    role_type varchar(24) NOT NULL,
    application_id uuid NULL,
    code varchar(128) NOT NULL,
    name varchar(512) NOT NULL,
    lifecycle_state varchar(24) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT catalog_role_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT catalog_role_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT catalog_role_application_fk
        FOREIGN KEY (tenant_id, application_id)
        REFERENCES catalog.application (tenant_id, id),
    CONSTRAINT catalog_role_type_ck CHECK (role_type IN ('BUSINESS','APPLICATION')),
    CONSTRAINT catalog_role_shape_ck CHECK (
        (role_type = 'BUSINESS' AND application_id IS NULL)
        OR (role_type = 'APPLICATION' AND application_id IS NOT NULL)
    ),
    CONSTRAINT catalog_role_code_ck CHECK (btrim(code) <> ''),
    CONSTRAINT catalog_role_name_ck CHECK (btrim(name) <> ''),
    CONSTRAINT catalog_role_lifecycle_ck CHECK (lifecycle_state IN ('ACTIVE','RETIRED')),
    CONSTRAINT catalog_role_revision_ck CHECK (revision > 0),
    CONSTRAINT catalog_role_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX catalog_business_role_code_uq
    ON catalog.role (tenant_id, code)
    WHERE role_type = 'BUSINESS';

CREATE UNIQUE INDEX catalog_application_role_code_uq
    ON catalog.role (tenant_id, application_id, code)
    WHERE role_type = 'APPLICATION';

CREATE TABLE catalog.role_version (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    role_id uuid NOT NULL,
    version_number bigint NOT NULL,
    state varchar(24) NOT NULL,
    content_hash varchar(128) NOT NULL,
    activated_at timestamptz NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT catalog_role_version_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT catalog_role_version_number_uq
        UNIQUE (tenant_id, role_id, version_number),
    CONSTRAINT catalog_role_version_role_fk
        FOREIGN KEY (tenant_id, role_id)
        REFERENCES catalog.role (tenant_id, id),
    CONSTRAINT catalog_role_version_number_ck CHECK (version_number > 0),
    CONSTRAINT catalog_role_version_state_ck CHECK (state IN (
        'DRAFT','VALIDATING','READY','ACTIVE',
        'SUPERSEDED','REJECTED','CANCELLED')),
    CONSTRAINT catalog_role_version_hash_ck CHECK (btrim(content_hash) <> ''),
    CONSTRAINT catalog_role_version_activation_ck CHECK (
        (state IN ('ACTIVE','SUPERSEDED') AND activated_at IS NOT NULL)
        OR (state NOT IN ('ACTIVE','SUPERSEDED') AND activated_at IS NULL)
    ),
    CONSTRAINT catalog_role_version_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX catalog_role_one_active_version_uq
    ON catalog.role_version (tenant_id, role_id)
    WHERE state = 'ACTIVE';

CREATE TABLE catalog.role_version_member (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    role_version_id uuid NOT NULL,
    member_kind varchar(32) NOT NULL,
    member_role_id uuid NULL,
    member_entitlement_id uuid NULL,
    ordinal integer NOT NULL,
    CONSTRAINT catalog_role_version_member_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT catalog_role_version_member_ordinal_uq
        UNIQUE (tenant_id, role_version_id, ordinal),
    CONSTRAINT catalog_role_version_member_role_version_fk
        FOREIGN KEY (tenant_id, role_version_id)
        REFERENCES catalog.role_version (tenant_id, id),
    CONSTRAINT catalog_role_version_member_role_fk
        FOREIGN KEY (tenant_id, member_role_id)
        REFERENCES catalog.role (tenant_id, id),
    CONSTRAINT catalog_role_version_member_entitlement_fk
        FOREIGN KEY (tenant_id, member_entitlement_id)
        REFERENCES catalog.entitlement (tenant_id, id),
    CONSTRAINT catalog_role_version_member_kind_ck
        CHECK (member_kind IN ('APPLICATION_ROLE','ENTITLEMENT')),
    CONSTRAINT catalog_role_version_member_shape_ck CHECK (
        (member_kind = 'APPLICATION_ROLE'
            AND member_role_id IS NOT NULL
            AND member_entitlement_id IS NULL)
        OR
        (member_kind = 'ENTITLEMENT'
            AND member_entitlement_id IS NOT NULL
            AND member_role_id IS NULL)
    ),
    CONSTRAINT catalog_role_version_member_ordinal_ck CHECK (ordinal >= 0)
);

CREATE INDEX catalog_role_version_role_idx
    ON catalog.role_version (tenant_id, role_id, version_number DESC);

CREATE INDEX catalog_role_member_child_role_idx
    ON catalog.role_version_member (tenant_id, member_role_id)
    WHERE member_role_id IS NOT NULL;

CREATE INDEX catalog_role_member_entitlement_idx
    ON catalog.role_version_member (tenant_id, member_entitlement_id)
    WHERE member_entitlement_id IS NOT NULL;

COMMENT ON TABLE catalog.role IS
    'Catalog-owned governed BUSINESS or APPLICATION access package.';
COMMENT ON TABLE catalog.role_version IS
    'Versioned Role composition. Activated and superseded content is immutable.';
COMMENT ON TABLE catalog.role_version_member IS
    'Normalized typed RoleVersion composition; graph/type validity is enforced by Catalog domain logic.';


CREATE OR REPLACE FUNCTION catalog.reject_immutable_activated_role_version_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.role_id <> OLD.role_id
       OR NEW.version_number <> OLD.version_number THEN
        RAISE EXCEPTION 'RoleVersion identity is immutable';
    END IF;
    IF NEW.content_hash <> OLD.content_hash
       AND (
           OLD.state IN ('ACTIVE','SUPERSEDED')
           OR NEW.state IN ('ACTIVE','SUPERSEDED')
       ) THEN
        RAISE EXCEPTION 'activated RoleVersion content is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER catalog_role_version_immutable_trg
BEFORE UPDATE ON catalog.role_version
FOR EACH ROW EXECUTE FUNCTION catalog.reject_immutable_activated_role_version_change();

CREATE OR REPLACE FUNCTION catalog.reject_immutable_activated_role_version_member_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    version_id uuid;
    version_state varchar(24);
BEGIN
    version_id := CASE WHEN TG_OP = 'DELETE'
        THEN OLD.role_version_id ELSE NEW.role_version_id END;
    SELECT state INTO version_state
    FROM catalog.role_version
    WHERE tenant_id = CASE WHEN TG_OP = 'DELETE'
            THEN OLD.tenant_id ELSE NEW.tenant_id END
      AND id = version_id;

    IF version_state IN ('ACTIVE','SUPERSEDED') THEN
        RAISE EXCEPTION 'activated RoleVersion members are immutable';
    END IF;

    IF TG_OP = 'UPDATE'
       AND OLD.role_version_id <> NEW.role_version_id THEN
        RAISE EXCEPTION 'RoleVersion member ownership is immutable';
    END IF;

    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE TRIGGER catalog_role_version_member_immutable_trg
BEFORE INSERT OR UPDATE OR DELETE ON catalog.role_version_member
FOR EACH ROW EXECUTE FUNCTION catalog.reject_immutable_activated_role_version_member_change();
