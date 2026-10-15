-- Catalog capability current-state persistence baseline.
-- Generated once from the verified pre-rebaseline schema, then maintained as reviewed source.
-- Some function bodies reference tables created later in this same capability baseline.
SET check_function_bodies = false;

CREATE FUNCTION catalog.reject_immutable_activated_role_version_change() RETURNS trigger
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

CREATE FUNCTION catalog.reject_immutable_activated_role_version_member_change() RETURNS trigger
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

CREATE TABLE catalog.application (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    code character varying(128) NOT NULL,
    name character varying(512) NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT catalog_application_code_ck CHECK ((btrim((code)::text) <> ''::text)),
    CONSTRAINT catalog_application_lifecycle_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'RETIRED'::character varying])::text[]))),
    CONSTRAINT catalog_application_name_ck CHECK ((btrim((name)::text) <> ''::text)),
    CONSTRAINT catalog_application_revision_ck CHECK ((revision > 0)),
    CONSTRAINT catalog_application_timestamp_order_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE catalog.application IS 'Catalog-owned authoritative governable business application/capability.';

CREATE TABLE catalog.application_target (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    application_id uuid NOT NULL,
    code character varying(128) NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT catalog_application_target_code_ck CHECK ((btrim((code)::text) <> ''::text)),
    CONSTRAINT catalog_application_target_lifecycle_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'RETIRED'::character varying])::text[]))),
    CONSTRAINT catalog_application_target_revision_ck CHECK ((revision > 0)),
    CONSTRAINT catalog_application_target_timestamp_order_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE catalog.application_target IS 'Catalog-owned authoritative technical target/environment belonging to one Application.';

CREATE TABLE catalog.entitlement (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    application_id uuid NOT NULL,
    application_target_id uuid,
    code character varying(256) NOT NULL,
    native_key character varying(1024),
    entitlement_type character varying(64) NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT catalog_entitlement_code_ck CHECK ((btrim((code)::text) <> ''::text)),
    CONSTRAINT catalog_entitlement_lifecycle_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'RETIRED'::character varying])::text[]))),
    CONSTRAINT catalog_entitlement_native_key_ck CHECK (((native_key IS NULL) OR (btrim((native_key)::text) <> ''::text))),
    CONSTRAINT catalog_entitlement_revision_ck CHECK ((revision > 0)),
    CONSTRAINT catalog_entitlement_timestamp_order_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT catalog_entitlement_type_ck CHECK ((btrim((entitlement_type)::text) <> ''::text))
);

COMMENT ON TABLE catalog.entitlement IS 'Catalog-owned authoritative smallest governable technical access unit; provider observations never mutate this table directly.';

CREATE TABLE catalog.role (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    role_type character varying(24) NOT NULL,
    application_id uuid,
    code character varying(128) NOT NULL,
    name character varying(512) NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT catalog_role_code_ck CHECK ((btrim((code)::text) <> ''::text)),
    CONSTRAINT catalog_role_lifecycle_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'RETIRED'::character varying])::text[]))),
    CONSTRAINT catalog_role_name_ck CHECK ((btrim((name)::text) <> ''::text)),
    CONSTRAINT catalog_role_revision_ck CHECK ((revision > 0)),
    CONSTRAINT catalog_role_shape_ck CHECK (((((role_type)::text = 'BUSINESS'::text) AND (application_id IS NULL)) OR (((role_type)::text = 'APPLICATION'::text) AND (application_id IS NOT NULL)))),
    CONSTRAINT catalog_role_timestamp_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT catalog_role_type_ck CHECK (((role_type)::text = ANY ((ARRAY['BUSINESS'::character varying, 'APPLICATION'::character varying])::text[])))
);

COMMENT ON TABLE catalog.role IS 'Catalog-owned governed BUSINESS or APPLICATION access package.';

CREATE TABLE catalog.role_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    role_id uuid NOT NULL,
    version_number bigint NOT NULL,
    state character varying(24) NOT NULL,
    content_hash character varying(128) NOT NULL,
    activated_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    CONSTRAINT catalog_role_version_activation_ck CHECK (((((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUPERSEDED'::character varying])::text[])) AND (activated_at IS NOT NULL)) OR (((state)::text <> ALL ((ARRAY['ACTIVE'::character varying, 'SUPERSEDED'::character varying])::text[])) AND (activated_at IS NULL)))),
    CONSTRAINT catalog_role_version_hash_ck CHECK ((btrim((content_hash)::text) <> ''::text)),
    CONSTRAINT catalog_role_version_number_ck CHECK ((version_number > 0)),
    CONSTRAINT catalog_role_version_revision_ck CHECK ((revision > 0)),
    CONSTRAINT catalog_role_version_state_ck CHECK (((state)::text = ANY ((ARRAY['DRAFT'::character varying, 'VALIDATING'::character varying, 'READY'::character varying, 'ACTIVE'::character varying, 'SUPERSEDED'::character varying, 'REJECTED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT catalog_role_version_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE catalog.role_version IS 'Versioned Role composition. Activated and superseded content is immutable.';

COMMENT ON COLUMN catalog.role_version.revision IS 'Optimistic authoritative revision for RoleVersion lifecycle transitions.';

CREATE TABLE catalog.role_version_member (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    role_version_id uuid NOT NULL,
    member_kind character varying(32) NOT NULL,
    member_role_id uuid,
    member_entitlement_id uuid,
    ordinal integer NOT NULL,
    CONSTRAINT catalog_role_version_member_kind_ck CHECK (((member_kind)::text = ANY ((ARRAY['APPLICATION_ROLE'::character varying, 'ENTITLEMENT'::character varying])::text[]))),
    CONSTRAINT catalog_role_version_member_ordinal_ck CHECK ((ordinal >= 0)),
    CONSTRAINT catalog_role_version_member_shape_ck CHECK (((((member_kind)::text = 'APPLICATION_ROLE'::text) AND (member_role_id IS NOT NULL) AND (member_entitlement_id IS NULL)) OR (((member_kind)::text = 'ENTITLEMENT'::text) AND (member_entitlement_id IS NOT NULL) AND (member_role_id IS NULL))))
);

COMMENT ON TABLE catalog.role_version_member IS 'Normalized typed RoleVersion composition; graph/type validity is enforced by Catalog domain logic.';

CREATE TABLE catalog.sso_client_redirect_uri (
    tenant_id uuid NOT NULL,
    sso_client_registration_id uuid NOT NULL,
    redirect_uri character varying(2048) NOT NULL,
    CONSTRAINT catalog_sso_client_redirect_uri_ck CHECK ((btrim((redirect_uri)::text) <> ''::text))
);

COMMENT ON TABLE catalog.sso_client_redirect_uri IS 'Exact registered redirect URIs for one governed SSO client; wildcard redirect matching is prohibited.';

CREATE TABLE catalog.sso_client_registration (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    application_id uuid NOT NULL,
    client_id character varying(200) NOT NULL,
    requires_governed_access boolean DEFAULT true NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT catalog_sso_client_client_id_ck CHECK ((btrim((client_id)::text) <> ''::text)),
    CONSTRAINT catalog_sso_client_lifecycle_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'RETIRED'::character varying])::text[]))),
    CONSTRAINT catalog_sso_client_revision_ck CHECK ((revision > 0)),
    CONSTRAINT catalog_sso_client_timestamp_order_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE catalog.sso_client_registration IS 'Catalog-owned governed public OIDC relying-party registration; framework client records are projections only.';

CREATE TABLE catalog.sso_client_scope (
    tenant_id uuid NOT NULL,
    sso_client_registration_id uuid NOT NULL,
    scope character varying(32) NOT NULL,
    CONSTRAINT catalog_sso_client_scope_value_ck CHECK (((scope)::text = ANY ((ARRAY['openid'::character varying, 'profile'::character varying, 'email'::character varying])::text[])))
);

COMMENT ON TABLE catalog.sso_client_scope IS 'Curated protocol scopes for one governed SSO client; these values are not Wyrmgate AdministrativePermission.';

ALTER TABLE ONLY catalog.application
    ADD CONSTRAINT application_pkey PRIMARY KEY (id);

ALTER TABLE ONLY catalog.application_target
    ADD CONSTRAINT application_target_pkey PRIMARY KEY (id);

ALTER TABLE ONLY catalog.application
    ADD CONSTRAINT catalog_application_code_uq UNIQUE (tenant_id, code);

ALTER TABLE ONLY catalog.application_target
    ADD CONSTRAINT catalog_application_target_app_id_uq UNIQUE (tenant_id, id, application_id);

ALTER TABLE ONLY catalog.application_target
    ADD CONSTRAINT catalog_application_target_code_uq UNIQUE (tenant_id, application_id, code);

ALTER TABLE ONLY catalog.application_target
    ADD CONSTRAINT catalog_application_target_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY catalog.application
    ADD CONSTRAINT catalog_application_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY catalog.entitlement
    ADD CONSTRAINT catalog_entitlement_code_uq UNIQUE (tenant_id, application_id, code);

ALTER TABLE ONLY catalog.entitlement
    ADD CONSTRAINT catalog_entitlement_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY catalog.role
    ADD CONSTRAINT catalog_role_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY catalog.role_version_member
    ADD CONSTRAINT catalog_role_version_member_ordinal_uq UNIQUE (tenant_id, role_version_id, ordinal);

ALTER TABLE ONLY catalog.role_version_member
    ADD CONSTRAINT catalog_role_version_member_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY catalog.role_version
    ADD CONSTRAINT catalog_role_version_number_uq UNIQUE (tenant_id, role_id, version_number);

ALTER TABLE ONLY catalog.role_version
    ADD CONSTRAINT catalog_role_version_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY catalog.sso_client_registration
    ADD CONSTRAINT catalog_sso_client_client_id_uq UNIQUE (tenant_id, client_id);

ALTER TABLE ONLY catalog.sso_client_registration
    ADD CONSTRAINT catalog_sso_client_public_client_id_uq UNIQUE (client_id);

ALTER TABLE ONLY catalog.sso_client_registration
    ADD CONSTRAINT catalog_sso_client_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY catalog.entitlement
    ADD CONSTRAINT entitlement_pkey PRIMARY KEY (id);

ALTER TABLE ONLY catalog.role
    ADD CONSTRAINT role_pkey PRIMARY KEY (id);

ALTER TABLE ONLY catalog.role_version_member
    ADD CONSTRAINT role_version_member_pkey PRIMARY KEY (id);

ALTER TABLE ONLY catalog.role_version
    ADD CONSTRAINT role_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY catalog.sso_client_redirect_uri
    ADD CONSTRAINT sso_client_redirect_uri_pkey PRIMARY KEY (tenant_id, sso_client_registration_id, redirect_uri);

ALTER TABLE ONLY catalog.sso_client_registration
    ADD CONSTRAINT sso_client_registration_pkey PRIMARY KEY (id);

ALTER TABLE ONLY catalog.sso_client_scope
    ADD CONSTRAINT sso_client_scope_pkey PRIMARY KEY (tenant_id, sso_client_registration_id, scope);

CREATE UNIQUE INDEX catalog_application_role_code_uq ON catalog.role USING btree (tenant_id, application_id, code) WHERE ((role_type)::text = 'APPLICATION'::text);

CREATE INDEX catalog_application_target_tenant_app_idx ON catalog.application_target USING btree (tenant_id, application_id, lifecycle_state, created_at, id);

CREATE INDEX catalog_application_tenant_lifecycle_idx ON catalog.application USING btree (tenant_id, lifecycle_state, created_at, id);

CREATE UNIQUE INDEX catalog_business_role_code_uq ON catalog.role USING btree (tenant_id, code) WHERE ((role_type)::text = 'BUSINESS'::text);

CREATE INDEX catalog_entitlement_target_idx ON catalog.entitlement USING btree (tenant_id, application_target_id, lifecycle_state, created_at, id) WHERE (application_target_id IS NOT NULL);

CREATE INDEX catalog_entitlement_tenant_app_idx ON catalog.entitlement USING btree (tenant_id, application_id, lifecycle_state, created_at, id);

CREATE INDEX catalog_role_member_child_role_idx ON catalog.role_version_member USING btree (tenant_id, member_role_id) WHERE (member_role_id IS NOT NULL);

CREATE INDEX catalog_role_member_entitlement_idx ON catalog.role_version_member USING btree (tenant_id, member_entitlement_id) WHERE (member_entitlement_id IS NOT NULL);

CREATE UNIQUE INDEX catalog_role_one_active_version_uq ON catalog.role_version USING btree (tenant_id, role_id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE INDEX catalog_role_version_role_idx ON catalog.role_version USING btree (tenant_id, role_id, version_number DESC);

CREATE INDEX catalog_sso_client_active_client_id_idx ON catalog.sso_client_registration USING btree (tenant_id, client_id) WHERE ((lifecycle_state)::text = 'ACTIVE'::text);

CREATE INDEX catalog_sso_client_application_page_idx ON catalog.sso_client_registration USING btree (tenant_id, application_id, created_at, id);

CREATE TRIGGER catalog_role_version_immutable_trg BEFORE UPDATE ON catalog.role_version FOR EACH ROW EXECUTE FUNCTION catalog.reject_immutable_activated_role_version_change();

CREATE TRIGGER catalog_role_version_member_immutable_trg BEFORE INSERT OR DELETE OR UPDATE ON catalog.role_version_member FOR EACH ROW EXECUTE FUNCTION catalog.reject_immutable_activated_role_version_member_change();

ALTER TABLE ONLY catalog.application_target
    ADD CONSTRAINT catalog_application_target_application_fk FOREIGN KEY (tenant_id, application_id) REFERENCES catalog.application(tenant_id, id);

ALTER TABLE ONLY catalog.application_target
    ADD CONSTRAINT catalog_application_target_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY catalog.application
    ADD CONSTRAINT catalog_application_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY catalog.entitlement
    ADD CONSTRAINT catalog_entitlement_application_fk FOREIGN KEY (tenant_id, application_id) REFERENCES catalog.application(tenant_id, id);

ALTER TABLE ONLY catalog.entitlement
    ADD CONSTRAINT catalog_entitlement_target_fk FOREIGN KEY (tenant_id, application_target_id, application_id) REFERENCES catalog.application_target(tenant_id, id, application_id);

ALTER TABLE ONLY catalog.entitlement
    ADD CONSTRAINT catalog_entitlement_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY catalog.role
    ADD CONSTRAINT catalog_role_application_fk FOREIGN KEY (tenant_id, application_id) REFERENCES catalog.application(tenant_id, id);

ALTER TABLE ONLY catalog.role
    ADD CONSTRAINT catalog_role_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY catalog.role_version_member
    ADD CONSTRAINT catalog_role_version_member_entitlement_fk FOREIGN KEY (tenant_id, member_entitlement_id) REFERENCES catalog.entitlement(tenant_id, id);

ALTER TABLE ONLY catalog.role_version_member
    ADD CONSTRAINT catalog_role_version_member_role_fk FOREIGN KEY (tenant_id, member_role_id) REFERENCES catalog.role(tenant_id, id);

ALTER TABLE ONLY catalog.role_version_member
    ADD CONSTRAINT catalog_role_version_member_role_version_fk FOREIGN KEY (tenant_id, role_version_id) REFERENCES catalog.role_version(tenant_id, id);

ALTER TABLE ONLY catalog.role_version
    ADD CONSTRAINT catalog_role_version_role_fk FOREIGN KEY (tenant_id, role_id) REFERENCES catalog.role(tenant_id, id);

ALTER TABLE ONLY catalog.sso_client_registration
    ADD CONSTRAINT catalog_sso_client_application_fk FOREIGN KEY (tenant_id, application_id) REFERENCES catalog.application(tenant_id, id);

ALTER TABLE ONLY catalog.sso_client_redirect_uri
    ADD CONSTRAINT catalog_sso_client_redirect_parent_fk FOREIGN KEY (tenant_id, sso_client_registration_id) REFERENCES catalog.sso_client_registration(tenant_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY catalog.sso_client_scope
    ADD CONSTRAINT catalog_sso_client_scope_parent_fk FOREIGN KEY (tenant_id, sso_client_registration_id) REFERENCES catalog.sso_client_registration(tenant_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY catalog.sso_client_registration
    ADD CONSTRAINT catalog_sso_client_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);
