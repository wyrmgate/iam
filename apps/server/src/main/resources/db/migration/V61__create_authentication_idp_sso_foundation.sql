CREATE SCHEMA IF NOT EXISTS authentication;

CREATE TABLE authentication.client (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    protocol_client_id varchar(192) NOT NULL,
    display_name varchar(256) NOT NULL,
    client_type varchar(24) NOT NULL,
    lifecycle_state varchar(24) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT authentication_client_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT authentication_client_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT authentication_client_protocol_id_uq UNIQUE (protocol_client_id),
    CONSTRAINT authentication_client_protocol_id_ck CHECK (btrim(protocol_client_id) <> ''),
    CONSTRAINT authentication_client_display_name_ck CHECK (btrim(display_name) <> ''),
    CONSTRAINT authentication_client_type_ck CHECK (client_type IN ('PUBLIC','CONFIDENTIAL')),
    CONSTRAINT authentication_client_state_ck CHECK (lifecycle_state IN ('ACTIVE','DISABLED')),
    CONSTRAINT authentication_client_revision_ck CHECK (revision > 0),
    CONSTRAINT authentication_client_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE TABLE authentication.client_redirect_uri (
    tenant_id uuid NOT NULL,
    client_id uuid NOT NULL,
    redirect_uri varchar(2048) NOT NULL,
    ordinal integer NOT NULL,
    PRIMARY KEY (tenant_id, client_id, redirect_uri),
    CONSTRAINT authentication_client_redirect_client_fk
        FOREIGN KEY (tenant_id, client_id)
        REFERENCES authentication.client (tenant_id, id)
        ON DELETE CASCADE,
    CONSTRAINT authentication_client_redirect_uri_ck CHECK (btrim(redirect_uri) <> ''),
    CONSTRAINT authentication_client_redirect_ordinal_ck CHECK (ordinal >= 0),
    CONSTRAINT authentication_client_redirect_ordinal_uq UNIQUE (tenant_id, client_id, ordinal)
);

CREATE TABLE authentication.client_post_logout_redirect_uri (
    tenant_id uuid NOT NULL,
    client_id uuid NOT NULL,
    redirect_uri varchar(2048) NOT NULL,
    ordinal integer NOT NULL,
    PRIMARY KEY (tenant_id, client_id, redirect_uri),
    CONSTRAINT authentication_client_logout_redirect_client_fk
        FOREIGN KEY (tenant_id, client_id)
        REFERENCES authentication.client (tenant_id, id)
        ON DELETE CASCADE,
    CONSTRAINT authentication_client_logout_redirect_uri_ck CHECK (btrim(redirect_uri) <> ''),
    CONSTRAINT authentication_client_logout_redirect_ordinal_ck CHECK (ordinal >= 0),
    CONSTRAINT authentication_client_logout_redirect_ordinal_uq UNIQUE (tenant_id, client_id, ordinal)
);

CREATE TABLE authentication.client_scope (
    tenant_id uuid NOT NULL,
    client_id uuid NOT NULL,
    scope_name varchar(128) NOT NULL,
    ordinal integer NOT NULL,
    PRIMARY KEY (tenant_id, client_id, scope_name),
    CONSTRAINT authentication_client_scope_client_fk
        FOREIGN KEY (tenant_id, client_id)
        REFERENCES authentication.client (tenant_id, id)
        ON DELETE CASCADE,
    CONSTRAINT authentication_client_scope_name_ck CHECK (btrim(scope_name) <> ''),
    CONSTRAINT authentication_client_scope_ordinal_ck CHECK (ordinal >= 0),
    CONSTRAINT authentication_client_scope_ordinal_uq UNIQUE (tenant_id, client_id, ordinal)
);

CREATE TABLE authentication.login_binding (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    login_identifier varchar(320) NOT NULL,
    normalized_login_identifier varchar(320) NOT NULL,
    lifecycle_state varchar(24) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT authentication_login_binding_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT authentication_login_binding_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT authentication_login_binding_identifier_uq
        UNIQUE (tenant_id, normalized_login_identifier),
    CONSTRAINT authentication_login_binding_identifier_ck
        CHECK (btrim(login_identifier) <> '' AND btrim(normalized_login_identifier) <> ''),
    CONSTRAINT authentication_login_binding_state_ck CHECK (lifecycle_state IN ('ACTIVE','DISABLED')),
    CONSTRAINT authentication_login_binding_revision_ck CHECK (revision > 0),
    CONSTRAINT authentication_login_binding_timestamp_ck CHECK (updated_at >= created_at)
);

CREATE INDEX authentication_login_binding_principal_idx
    ON authentication.login_binding (tenant_id, principal_id, lifecycle_state, id);
CREATE INDEX authentication_login_binding_identity_idx
    ON authentication.login_binding (tenant_id, identity_id, lifecycle_state, id);

CREATE TABLE authentication.subject_identifier (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    subject_value varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT authentication_subject_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT authentication_subject_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT authentication_subject_identity_uq UNIQUE (tenant_id, identity_id),
    CONSTRAINT authentication_subject_value_uq UNIQUE (tenant_id, subject_value),
    CONSTRAINT authentication_subject_value_ck CHECK (btrim(subject_value) <> '')
);

CREATE TABLE authentication.session (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    session_secret_digest varchar(128) NOT NULL,
    assurance_level varchar(24) NOT NULL,
    lifecycle_state varchar(24) NOT NULL,
    authenticated_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT authentication_session_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES platform.tenant (id),
    CONSTRAINT authentication_session_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT authentication_session_digest_uq UNIQUE (session_secret_digest),
    CONSTRAINT authentication_session_digest_ck CHECK (btrim(session_secret_digest) <> ''),
    CONSTRAINT authentication_session_assurance_ck CHECK (assurance_level IN ('BASELINE','STRONG')),
    CONSTRAINT authentication_session_state_ck CHECK (lifecycle_state IN ('ACTIVE','REVOKED','EXPIRED')),
    CONSTRAINT authentication_session_time_ck CHECK (
        expires_at > authenticated_at
        AND last_seen_at >= authenticated_at
        AND updated_at >= created_at),
    CONSTRAINT authentication_session_revocation_ck CHECK (
        (lifecycle_state = 'REVOKED' AND revoked_at IS NOT NULL)
        OR (lifecycle_state <> 'REVOKED' AND revoked_at IS NULL)),
    CONSTRAINT authentication_session_revision_ck CHECK (revision > 0)
);

CREATE INDEX authentication_session_identity_idx
    ON authentication.session (tenant_id, identity_id, lifecycle_state, expires_at, id);
CREATE INDEX authentication_session_principal_idx
    ON authentication.session (tenant_id, principal_id, lifecycle_state, expires_at, id);

CREATE OR REPLACE FUNCTION authentication.guard_client_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.protocol_client_id IS DISTINCT FROM OLD.protocol_client_id
       OR NEW.client_type IS DISTINCT FROM OLD.client_type
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'AuthenticationClient tenant, protocol client ID, type and creation time are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER authentication_client_update_guard
BEFORE UPDATE ON authentication.client
FOR EACH ROW EXECUTE FUNCTION authentication.guard_client_update();

CREATE OR REPLACE FUNCTION authentication.guard_login_binding_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.principal_id IS DISTINCT FROM OLD.principal_id
       OR NEW.identity_id IS DISTINCT FROM OLD.identity_id
       OR NEW.normalized_login_identifier IS DISTINCT FROM OLD.normalized_login_identifier
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'AuthenticationLoginBinding tenant, subject and normalized identifier are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER authentication_login_binding_update_guard
BEFORE UPDATE ON authentication.login_binding
FOR EACH ROW EXECUTE FUNCTION authentication.guard_login_binding_update();

COMMENT ON SCHEMA authentication IS
    'Authentication-owned first-party/federated login, IdP and SSO authoritative state under ADR-0042.';
COMMENT ON TABLE authentication.client IS
    'Tenant-bound OIDC/OAuth client authority. Protocol client IDs are globally unique so the protocol edge can derive tenant context from server-owned state. Protocol secret material is never stored in this table.';
COMMENT ON TABLE authentication.login_binding IS
    'Tenant-local login alias bound to stable governed Principal/Identity IDs; not a duplicate user aggregate.';
COMMENT ON TABLE authentication.subject_identifier IS
    'Opaque stable Wyrmgate-issued OIDC subject mapping; mutable profile identifiers are not subject authority.';
COMMENT ON TABLE authentication.session IS
    'Authentication-owned SSO session metadata. Only a digest of bearer-equivalent session secret material is persisted.';