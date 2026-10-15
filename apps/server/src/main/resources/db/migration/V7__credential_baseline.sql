-- Credential capability current-state persistence baseline.
-- Generated once from the verified pre-rebaseline schema, then maintained as reviewed source.
-- Some function bodies reference tables created later in this same capability baseline.
SET check_function_bodies = false;

CREATE FUNCTION credential.guard_credential_update() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.principal_id IS DISTINCT FROM OLD.principal_id
       OR NEW.credential_kind IS DISTINCT FROM OLD.credential_kind
       OR NEW.secret_provider_type IS DISTINCT FROM OLD.secret_provider_type
       OR NEW.secret_reference_key IS DISTINCT FROM OLD.secret_reference_key
       OR NEW.valid_from IS DISTINCT FROM OLD.valid_from
       OR NEW.valid_until IS DISTINCT FROM OLD.valid_until
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Credential ownership, kind, secret reference and validity are immutable in v1';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION credential.guard_rotation_update() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.old_credential_id IS DISTINCT FROM OLD.old_credential_id
       OR NEW.initiator_identity_id IS DISTINCT FROM OLD.initiator_identity_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'CredentialRotation subject/initiator is immutable';
    END IF;

    IF OLD.replacement_credential_id IS NOT NULL
       AND NEW.replacement_credential_id IS DISTINCT FROM OLD.replacement_credential_id THEN
        RAISE EXCEPTION 'CredentialRotation replacement Credential is immutable once set';
    END IF;

    IF OLD.process_state IN (
        'COMPLETED','FAILED',
        'MANUAL_REQUIRED','FAILED_REMEDIATION') THEN
        RAISE EXCEPTION 'terminal CredentialRotation is immutable';
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION credential.reject_credential_delete() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'Credential history is retained; use lifecycle state';
END;
$$;

CREATE TABLE credential.credential (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    credential_kind character varying(32) NOT NULL,
    secret_provider_type character varying(64) NOT NULL,
    secret_reference_key character varying(512) NOT NULL,
    lifecycle_state character varying(24) NOT NULL,
    valid_from timestamp with time zone,
    valid_until timestamp with time zone,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    compromised_at timestamp with time zone,
    revoked_at timestamp with time zone,
    expired_at timestamp with time zone,
    CONSTRAINT credential_credential_kind_ck CHECK (((credential_kind)::text = ANY ((ARRAY['PASSWORD'::character varying, 'API_KEY'::character varying, 'SSH_KEY'::character varying, 'CERTIFICATE'::character varying, 'OAUTH_CLIENT_SECRET'::character varying])::text[]))),
    CONSTRAINT credential_credential_provider_ck CHECK ((btrim((secret_provider_type)::text) <> ''::text)),
    CONSTRAINT credential_credential_reference_ck CHECK ((btrim((secret_reference_key)::text) <> ''::text)),
    CONSTRAINT credential_credential_revision_ck CHECK ((revision > 0)),
    CONSTRAINT credential_credential_scheduled_ck CHECK ((((lifecycle_state)::text <> 'SCHEDULED'::text) OR (valid_from IS NOT NULL))),
    CONSTRAINT credential_credential_state_ck CHECK (((lifecycle_state)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'ACTIVE'::character varying, 'REVOKED'::character varying, 'EXPIRED'::character varying, 'COMPROMISED'::character varying])::text[]))),
    CONSTRAINT credential_credential_terminal_ck CHECK (((((lifecycle_state)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'ACTIVE'::character varying])::text[])) AND (compromised_at IS NULL) AND (revoked_at IS NULL) AND (expired_at IS NULL)) OR (((lifecycle_state)::text = 'COMPROMISED'::text) AND (compromised_at IS NOT NULL) AND (revoked_at IS NULL) AND (expired_at IS NULL)) OR (((lifecycle_state)::text = 'REVOKED'::text) AND (revoked_at IS NOT NULL) AND (expired_at IS NULL)) OR (((lifecycle_state)::text = 'EXPIRED'::text) AND (expired_at IS NOT NULL) AND (revoked_at IS NULL)))),
    CONSTRAINT credential_credential_timestamp_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT credential_credential_validity_ck CHECK (((valid_from IS NULL) OR (valid_until IS NULL) OR (valid_until > valid_from)))
);

COMMENT ON TABLE credential.credential IS 'Credential metadata only. secret_provider_type + secret_reference_key are opaque external references; raw secret/private material is structurally absent.';

COMMENT ON COLUMN credential.credential.principal_id IS 'Stable cross-capability Principal reference; intentionally no Identity database foreign key.';

CREATE TABLE credential.credential_rotation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    old_credential_id uuid NOT NULL,
    replacement_credential_id uuid,
    initiator_identity_id uuid NOT NULL,
    process_state character varying(32) NOT NULL,
    checkpoint character varying(256),
    failure_code character varying(128),
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT credential_rotation_completion_ck CHECK (((((process_state)::text = ANY ((ARRAY['COMPLETED'::character varying, 'FAILED'::character varying, 'MANUAL_REQUIRED'::character varying, 'FAILED_REMEDIATION'::character varying])::text[])) AND (completed_at IS NOT NULL)) OR (((process_state)::text <> ALL ((ARRAY['COMPLETED'::character varying, 'FAILED'::character varying, 'MANUAL_REQUIRED'::character varying, 'FAILED_REMEDIATION'::character varying])::text[])) AND (completed_at IS NULL)))),
    CONSTRAINT credential_rotation_old_replacement_ck CHECK (((replacement_credential_id IS NULL) OR (replacement_credential_id <> old_credential_id))),
    CONSTRAINT credential_rotation_revision_ck CHECK ((revision > 0)),
    CONSTRAINT credential_rotation_state_ck CHECK (((process_state)::text = ANY ((ARRAY['PLANNED'::character varying, 'CREATING_REPLACEMENT'::character varying, 'DISTRIBUTING'::character varying, 'VERIFYING'::character varying, 'CUTOVER_COMPLETE'::character varying, 'REVOKING_OLD'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'MANUAL_REQUIRED'::character varying, 'FAILED_REMEDIATION'::character varying])::text[]))),
    CONSTRAINT credential_rotation_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE credential.credential_rotation IS 'Durable Credential-owned rotation orchestration. Process state is separate from Credential lifecycle and provider fulfillment.';

CREATE TABLE credential.local_password_verifier (
    reference_key character varying(64) NOT NULL,
    tenant_id uuid NOT NULL,
    credential_id uuid NOT NULL,
    algorithm character varying(64) NOT NULL,
    iterations integer NOT NULL,
    salt bytea NOT NULL,
    verifier bytea NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT credential_local_password_iterations_ck CHECK ((iterations > 0)),
    CONSTRAINT credential_local_password_salt_ck CHECK ((octet_length(salt) >= 16)),
    CONSTRAINT credential_local_password_verifier_ck CHECK ((octet_length(verifier) >= 32))
);

COMMENT ON TABLE credential.local_password_verifier IS 'Credential-private one-way verifier material for Wyrmgate first-party PASSWORD authentication. This table is not ordinary IAM state and is never exposed through ordinary APIs/events/audit.';

COMMENT ON COLUMN credential.local_password_verifier.reference_key IS 'Opaque system-managed SecretReference key. Ordinary Credential creation cannot select this provider type.';

COMMENT ON COLUMN credential.local_password_verifier.verifier IS 'Salted one-way verifier material; never plaintext password or reversible secret material.';

ALTER TABLE ONLY credential.credential
    ADD CONSTRAINT credential_credential_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY credential.local_password_verifier
    ADD CONSTRAINT credential_local_password_credential_uq UNIQUE (credential_id);

ALTER TABLE ONLY credential.credential
    ADD CONSTRAINT credential_pkey PRIMARY KEY (id);

ALTER TABLE ONLY credential.credential_rotation
    ADD CONSTRAINT credential_rotation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY credential.credential_rotation
    ADD CONSTRAINT credential_rotation_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY credential.local_password_verifier
    ADD CONSTRAINT local_password_verifier_pkey PRIMARY KEY (reference_key);

CREATE INDEX credential_credential_principal_created_idx ON credential.credential USING btree (tenant_id, principal_id, created_at, id);

COMMENT ON INDEX credential.credential_credential_principal_created_idx IS 'Supports deterministic bounded public Credential listing by Principal using immutable createdAt + id continuation.';

CREATE INDEX credential_credential_principal_idx ON credential.credential USING btree (tenant_id, principal_id, lifecycle_state, created_at, id);

CREATE INDEX credential_rotation_old_idx ON credential.credential_rotation USING btree (tenant_id, old_credential_id, created_at DESC);

CREATE UNIQUE INDEX credential_rotation_open_old_uq ON credential.credential_rotation USING btree (tenant_id, old_credential_id) WHERE ((process_state)::text <> ALL ((ARRAY['COMPLETED'::character varying, 'FAILED'::character varying, 'MANUAL_REQUIRED'::character varying, 'FAILED_REMEDIATION'::character varying])::text[]));

CREATE INDEX credential_rotation_replacement_idx ON credential.credential_rotation USING btree (tenant_id, replacement_credential_id, created_at DESC) WHERE (replacement_credential_id IS NOT NULL);

CREATE TRIGGER credential_delete_guard BEFORE DELETE ON credential.credential FOR EACH ROW EXECUTE FUNCTION credential.reject_credential_delete();

CREATE TRIGGER credential_rotation_delete_guard BEFORE DELETE ON credential.credential_rotation FOR EACH ROW EXECUTE FUNCTION credential.reject_credential_delete();

CREATE TRIGGER credential_rotation_update_guard BEFORE UPDATE ON credential.credential_rotation FOR EACH ROW EXECUTE FUNCTION credential.guard_rotation_update();

CREATE TRIGGER credential_update_guard BEFORE UPDATE ON credential.credential FOR EACH ROW EXECUTE FUNCTION credential.guard_credential_update();

ALTER TABLE ONLY credential.credential
    ADD CONSTRAINT credential_credential_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY credential.local_password_verifier
    ADD CONSTRAINT credential_local_password_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY credential.credential_rotation
    ADD CONSTRAINT credential_rotation_old_fk FOREIGN KEY (tenant_id, old_credential_id) REFERENCES credential.credential(tenant_id, id);

ALTER TABLE ONLY credential.credential_rotation
    ADD CONSTRAINT credential_rotation_replacement_fk FOREIGN KEY (tenant_id, replacement_credential_id) REFERENCES credential.credential(tenant_id, id);

ALTER TABLE ONLY credential.credential_rotation
    ADD CONSTRAINT credential_rotation_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);
