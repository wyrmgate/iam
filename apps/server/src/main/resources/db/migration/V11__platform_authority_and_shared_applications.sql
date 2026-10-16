-- ADR-0049 deployment authority and platform application-tenancy delta.
-- Schema only: this migration deliberately grants no authority and infers no owner.

CREATE TABLE administration.platform_owner_authority (
    id uuid NOT NULL,
    actor_tenant_id uuid NOT NULL,
    actor_identity_id uuid NOT NULL,
    actor_principal_id uuid,
    state character varying(24) NOT NULL,
    grantable boolean DEFAULT true NOT NULL,
    delegable boolean DEFAULT true NOT NULL,
    authority_basis_id uuid,
    valid_from timestamp with time zone,
    valid_until timestamp with time zone,
    revoked_by_identity_id uuid,
    revoked_at timestamp with time zone,
    correlation_id uuid,
    causation_id uuid,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT platform_owner_authority_pkey PRIMARY KEY (id),
    CONSTRAINT platform_owner_authority_state_ck CHECK (state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT platform_owner_authority_revision_ck CHECK (revision > 0),
    CONSTRAINT platform_owner_authority_timestamp_ck CHECK (updated_at >= created_at),
    CONSTRAINT platform_owner_authority_validity_ck CHECK (
        valid_until IS NULL OR valid_from IS NULL OR valid_until > valid_from),
    CONSTRAINT platform_owner_authority_revocation_ck CHECK (
        (state = 'ACTIVE' AND revoked_by_identity_id IS NULL AND revoked_at IS NULL)
        OR (state = 'REVOKED' AND revoked_at IS NOT NULL)),
    CONSTRAINT platform_owner_authority_basis_not_self_ck CHECK (
        authority_basis_id IS NULL OR authority_basis_id <> id),
    CONSTRAINT platform_owner_authority_actor_tenant_fk
        FOREIGN KEY (actor_tenant_id) REFERENCES platform.tenant(id)
);

CREATE UNIQUE INDEX platform_owner_authority_active_actor_uq
    ON administration.platform_owner_authority (actor_tenant_id, actor_identity_id)
    WHERE state = 'ACTIVE';

CREATE INDEX platform_owner_authority_effective_idx
    ON administration.platform_owner_authority (state, actor_tenant_id, actor_identity_id, valid_until);

COMMENT ON TABLE administration.platform_owner_authority IS
    'Deployment-scoped PLATFORM_OWNER authority. actor_tenant_id locates the governed Identity and is not an authority scope.';
COMMENT ON COLUMN administration.platform_owner_authority.actor_identity_id IS
    'Stable governed Identity ID. Administration never mutates Identity state directly.';
COMMENT ON COLUMN administration.platform_owner_authority.actor_principal_id IS
    'Optional first-party authentication anchor. Fresh ADR-0049 bootstrap records the installation Principal.';

CREATE TABLE administration.tenant_owner_authority (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    actor_identity_id uuid NOT NULL,
    administrative_grant_id uuid NOT NULL,
    state character varying(24) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    revision bigint DEFAULT 1 NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT tenant_owner_authority_pkey PRIMARY KEY (id),
    CONSTRAINT tenant_owner_authority_state_ck CHECK (state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT tenant_owner_authority_revision_ck CHECK (revision > 0),
    CONSTRAINT tenant_owner_authority_timestamp_ck CHECK (updated_at >= created_at),
    CONSTRAINT tenant_owner_authority_revocation_ck CHECK (
        (state = 'ACTIVE' AND revoked_at IS NULL)
        OR (state = 'REVOKED' AND revoked_at IS NOT NULL)),
    CONSTRAINT tenant_owner_authority_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id)
);

CREATE UNIQUE INDEX tenant_owner_authority_active_actor_uq
    ON administration.tenant_owner_authority (tenant_id, actor_identity_id)
    WHERE state = 'ACTIVE';
CREATE UNIQUE INDEX tenant_owner_authority_grant_uq
    ON administration.tenant_owner_authority (tenant_id, administrative_grant_id);

COMMENT ON TABLE administration.tenant_owner_authority IS
    'Explicit TENANT_OWNER designation bound to one tenant-scoped AdministrativeGrant; compatibility names do not imply deployment authority.';

-- Prevent direct removal/revocation of the last currently ACTIVE authority row.
-- Cross-capability governed-Identity viability remains an application-level semantic query.
CREATE FUNCTION administration.reject_last_platform_owner_authority_loss() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    other_count bigint;
BEGIN
    IF (TG_OP = 'DELETE' AND OLD.state = 'ACTIVE')
       OR (TG_OP = 'UPDATE' AND OLD.state = 'ACTIVE' AND NEW.state <> 'ACTIVE') THEN
        SELECT count(*) INTO other_count
        FROM administration.platform_owner_authority p
        WHERE p.state = 'ACTIVE'
          AND p.id <> OLD.id
          AND (p.valid_from IS NULL OR p.valid_from <= CURRENT_TIMESTAMP)
          AND (p.valid_until IS NULL OR p.valid_until > CURRENT_TIMESTAMP);
        IF other_count = 0 THEN
            RAISE EXCEPTION 'cannot remove the final active PLATFORM_OWNER authority';
        END IF;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE TRIGGER platform_owner_authority_last_owner_guard
    BEFORE UPDATE OR DELETE ON administration.platform_owner_authority
    FOR EACH ROW EXECUTE FUNCTION administration.reject_last_platform_owner_authority_loss();

CREATE FUNCTION administration.reject_last_tenant_owner_authority_loss() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    other_count bigint;
BEGIN
    IF (TG_OP = 'DELETE' AND OLD.state = 'ACTIVE')
       OR (TG_OP = 'UPDATE' AND OLD.state = 'ACTIVE' AND NEW.state <> 'ACTIVE') THEN
        SELECT count(*) INTO other_count
        FROM administration.tenant_owner_authority t
        JOIN administration.administrative_grant g
          ON g.tenant_id = t.tenant_id AND g.id = t.administrative_grant_id
        WHERE t.tenant_id = OLD.tenant_id
          AND t.id <> OLD.id
          AND t.state = 'ACTIVE'
          AND g.state = 'ACTIVE'
          AND (g.valid_from IS NULL OR g.valid_from <= CURRENT_TIMESTAMP)
          AND (g.valid_until IS NULL OR g.valid_until > CURRENT_TIMESTAMP);
        IF other_count = 0 THEN
            RAISE EXCEPTION 'cannot remove the final active TENANT_OWNER authority';
        END IF;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE TRIGGER tenant_owner_authority_last_owner_guard
    BEFORE UPDATE OR DELETE ON administration.tenant_owner_authority
    FOR EACH ROW EXECUTE FUNCTION administration.reject_last_tenant_owner_authority_loss();

CREATE FUNCTION administration.reject_last_tenant_owner_grant_loss() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    owner_row administration.tenant_owner_authority%ROWTYPE;
    other_count bigint;
BEGIN
    IF OLD.state = 'ACTIVE' AND NEW.state <> 'ACTIVE' THEN
        SELECT * INTO owner_row
        FROM administration.tenant_owner_authority
        WHERE tenant_id = OLD.tenant_id
          AND administrative_grant_id = OLD.id
          AND state = 'ACTIVE';
        IF FOUND THEN
            SELECT count(*) INTO other_count
            FROM administration.tenant_owner_authority t
            JOIN administration.administrative_grant g
              ON g.tenant_id = t.tenant_id AND g.id = t.administrative_grant_id
            WHERE t.tenant_id = OLD.tenant_id
              AND t.id <> owner_row.id
              AND t.state = 'ACTIVE'
              AND g.state = 'ACTIVE'
              AND (g.valid_from IS NULL OR g.valid_from <= CURRENT_TIMESTAMP)
              AND (g.valid_until IS NULL OR g.valid_until > CURRENT_TIMESTAMP);
            IF other_count = 0 THEN
                RAISE EXCEPTION 'cannot revoke the final active TENANT_OWNER grant';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER administrative_grant_last_tenant_owner_guard
    BEFORE UPDATE OF state ON administration.administrative_grant
    FOR EACH ROW EXECUTE FUNCTION administration.reject_last_tenant_owner_grant_loss();

CREATE TABLE catalog.platform_application_offering (
    id uuid NOT NULL,
    code character varying(128) NOT NULL,
    name character varying(512) NOT NULL,
    tenancy_mode character varying(32) NOT NULL,
    admission_mode character varying(32) NOT NULL,
    governed_policy_ref character varying(256),
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT platform_application_offering_pkey PRIMARY KEY (id),
    CONSTRAINT platform_application_offering_code_uq UNIQUE (code),
    CONSTRAINT platform_application_offering_code_ck CHECK (btrim(code) <> ''),
    CONSTRAINT platform_application_offering_name_ck CHECK (btrim(name) <> ''),
    CONSTRAINT platform_application_offering_tenancy_ck CHECK (
        tenancy_mode IN ('PLATFORM_SHARED', 'PLATFORM_INTERNAL')),
    CONSTRAINT platform_application_offering_admission_ck CHECK (
        admission_mode IN ('ALL_TENANTS', 'SELECTED_TENANTS', 'GOVERNED_POLICY')),
    CONSTRAINT platform_application_offering_policy_shape_ck CHECK (
        (admission_mode = 'GOVERNED_POLICY' AND governed_policy_ref IS NOT NULL AND btrim(governed_policy_ref) <> '')
        OR (admission_mode <> 'GOVERNED_POLICY' AND governed_policy_ref IS NULL)),
    CONSTRAINT platform_application_offering_lifecycle_ck CHECK (
        lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT platform_application_offering_revision_ck CHECK (revision > 0),
    CONSTRAINT platform_application_offering_timestamp_ck CHECK (updated_at >= created_at)
);

COMMENT ON TABLE catalog.platform_application_offering IS
    'Catalog-owned platform application definition. It is explicitly platform-owned rather than a tenant-owned Application with nullable tenant_id.';

CREATE TABLE catalog.platform_application_tenant_admission (
    platform_application_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT platform_application_tenant_admission_pkey PRIMARY KEY (platform_application_id, tenant_id),
    CONSTRAINT platform_application_tenant_admission_offering_fk
        FOREIGN KEY (platform_application_id) REFERENCES catalog.platform_application_offering(id),
    CONSTRAINT platform_application_tenant_admission_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id)
);

COMMENT ON TABLE catalog.platform_application_tenant_admission IS
    'Explicit SELECTED_TENANTS admission membership for one platform application offering.';

CREATE TABLE catalog.platform_application_binding (
    id uuid NOT NULL,
    platform_application_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    application_id uuid NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT platform_application_binding_pkey PRIMARY KEY (id),
    CONSTRAINT platform_application_binding_uq UNIQUE (platform_application_id, tenant_id),
    CONSTRAINT platform_application_binding_app_uq UNIQUE (tenant_id, application_id),
    CONSTRAINT platform_application_binding_lifecycle_ck CHECK (
        lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT platform_application_binding_revision_ck CHECK (revision > 0),
    CONSTRAINT platform_application_binding_timestamp_ck CHECK (updated_at >= created_at),
    CONSTRAINT platform_application_binding_offering_fk
        FOREIGN KEY (platform_application_id) REFERENCES catalog.platform_application_offering(id),
    CONSTRAINT platform_application_binding_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id),
    CONSTRAINT platform_application_binding_application_fk
        FOREIGN KEY (tenant_id, application_id) REFERENCES catalog.application(tenant_id, id)
);

CREATE INDEX platform_application_binding_tenant_idx
    ON catalog.platform_application_binding (tenant_id, lifecycle_state, platform_application_id);

COMMENT ON TABLE catalog.platform_application_binding IS
    'Catalog-owned binding from one platform offering to one ordinary tenant-local Application. Tenant access/governance/SSO state remains isolated.';
