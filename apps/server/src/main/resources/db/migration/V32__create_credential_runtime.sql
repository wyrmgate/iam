CREATE SCHEMA IF NOT EXISTS credential;

CREATE TABLE credential.credential (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    credential_kind varchar(32) NOT NULL,
    secret_provider_type varchar(64) NOT NULL,
    secret_reference_key varchar(512) NOT NULL,
    lifecycle_state varchar(24) NOT NULL,
    valid_from timestamptz NULL,
    valid_until timestamptz NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    compromised_at timestamptz NULL,
    revoked_at timestamptz NULL,
    expired_at timestamptz NULL,
    CONSTRAINT credential_credential_tenant_fk
        FOREIGN KEY (tenant_id)
        REFERENCES platform.tenant (id),
    CONSTRAINT credential_credential_tenant_id_uq
        UNIQUE (tenant_id, id),
    CONSTRAINT credential_credential_kind_ck
        CHECK (credential_kind IN (
            'PASSWORD','API_KEY','SSH_KEY',
            'CERTIFICATE','OAUTH_CLIENT_SECRET')),
    CONSTRAINT credential_credential_provider_ck
        CHECK (btrim(secret_provider_type) <> ''),
    CONSTRAINT credential_credential_reference_ck
        CHECK (btrim(secret_reference_key) <> ''),
    CONSTRAINT credential_credential_state_ck
        CHECK (lifecycle_state IN (
            'SCHEDULED','ACTIVE','REVOKED',
            'EXPIRED','COMPROMISED')),
    CONSTRAINT credential_credential_scheduled_ck
        CHECK (
            lifecycle_state <> 'SCHEDULED'
            OR valid_from IS NOT NULL),
    CONSTRAINT credential_credential_validity_ck
        CHECK (
            valid_from IS NULL
            OR valid_until IS NULL
            OR valid_until > valid_from),
    CONSTRAINT credential_credential_revision_ck
        CHECK (revision > 0),
    CONSTRAINT credential_credential_timestamp_ck
        CHECK (updated_at >= created_at),
    CONSTRAINT credential_credential_terminal_ck CHECK (
        (lifecycle_state IN ('SCHEDULED','ACTIVE')
            AND compromised_at IS NULL
            AND revoked_at IS NULL
            AND expired_at IS NULL)
        OR
        (lifecycle_state = 'COMPROMISED'
            AND compromised_at IS NOT NULL
            AND revoked_at IS NULL
            AND expired_at IS NULL)
        OR
        (lifecycle_state = 'REVOKED'
            AND revoked_at IS NOT NULL
            AND expired_at IS NULL)
        OR
        (lifecycle_state = 'EXPIRED'
            AND expired_at IS NOT NULL
            AND revoked_at IS NULL)
    )
);

CREATE INDEX credential_credential_principal_idx
    ON credential.credential (
        tenant_id, principal_id,
        lifecycle_state, created_at, id);

CREATE TABLE credential.credential_rotation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    old_credential_id uuid NOT NULL,
    replacement_credential_id uuid NULL,
    initiator_identity_id uuid NOT NULL,
    process_state varchar(32) NOT NULL,
    checkpoint varchar(256) NULL,
    failure_code varchar(128) NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz NULL,
    CONSTRAINT credential_rotation_tenant_fk
        FOREIGN KEY (tenant_id)
        REFERENCES platform.tenant (id),
    CONSTRAINT credential_rotation_old_fk
        FOREIGN KEY (tenant_id, old_credential_id)
        REFERENCES credential.credential (tenant_id, id),
    CONSTRAINT credential_rotation_replacement_fk
        FOREIGN KEY (tenant_id, replacement_credential_id)
        REFERENCES credential.credential (tenant_id, id),
    CONSTRAINT credential_rotation_tenant_id_uq
        UNIQUE (tenant_id, id),
    CONSTRAINT credential_rotation_old_replacement_ck
        CHECK (
            replacement_credential_id IS NULL
            OR replacement_credential_id <> old_credential_id),
    CONSTRAINT credential_rotation_state_ck
        CHECK (process_state IN (
            'PLANNED','CREATING_REPLACEMENT',
            'DISTRIBUTING','VERIFYING',
            'CUTOVER_COMPLETE','REVOKING_OLD',
            'COMPLETED','FAILED',
            'MANUAL_REQUIRED','FAILED_REMEDIATION')),
    CONSTRAINT credential_rotation_revision_ck
        CHECK (revision > 0),
    CONSTRAINT credential_rotation_timestamp_ck
        CHECK (updated_at >= created_at),
    CONSTRAINT credential_rotation_completion_ck CHECK (
        (process_state IN (
            'COMPLETED','FAILED',
            'MANUAL_REQUIRED','FAILED_REMEDIATION')
            AND completed_at IS NOT NULL)
        OR
        (process_state NOT IN (
            'COMPLETED','FAILED',
            'MANUAL_REQUIRED','FAILED_REMEDIATION')
            AND completed_at IS NULL)
    )
);

CREATE UNIQUE INDEX credential_rotation_open_old_uq
    ON credential.credential_rotation (
        tenant_id, old_credential_id)
    WHERE process_state NOT IN (
        'COMPLETED','FAILED',
        'MANUAL_REQUIRED','FAILED_REMEDIATION');

CREATE INDEX credential_rotation_old_idx
    ON credential.credential_rotation (
        tenant_id, old_credential_id,
        created_at DESC);

CREATE INDEX credential_rotation_replacement_idx
    ON credential.credential_rotation (
        tenant_id, replacement_credential_id,
        created_at DESC)
    WHERE replacement_credential_id IS NOT NULL;

CREATE OR REPLACE FUNCTION credential.guard_credential_update()
RETURNS trigger
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

CREATE TRIGGER credential_update_guard
BEFORE UPDATE ON credential.credential
FOR EACH ROW EXECUTE FUNCTION credential.guard_credential_update();

CREATE OR REPLACE FUNCTION credential.guard_rotation_update()
RETURNS trigger
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

CREATE TRIGGER credential_rotation_update_guard
BEFORE UPDATE ON credential.credential_rotation
FOR EACH ROW EXECUTE FUNCTION credential.guard_rotation_update();

CREATE OR REPLACE FUNCTION credential.reject_credential_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Credential history is retained; use lifecycle state';
END;
$$;

CREATE TRIGGER credential_delete_guard
BEFORE DELETE ON credential.credential
FOR EACH ROW EXECUTE FUNCTION credential.reject_credential_delete();

CREATE TRIGGER credential_rotation_delete_guard
BEFORE DELETE ON credential.credential_rotation
FOR EACH ROW EXECUTE FUNCTION credential.reject_credential_delete();

COMMENT ON SCHEMA credential IS
    'Credential capability authoritative metadata and durable rotation process state.';
COMMENT ON TABLE credential.credential IS
    'Credential metadata only. secret_provider_type + secret_reference_key are opaque external references; raw secret/private material is structurally absent.';
COMMENT ON COLUMN credential.credential.principal_id IS
    'Stable cross-capability Principal reference; intentionally no Identity database foreign key.';
COMMENT ON TABLE credential.credential_rotation IS
    'Durable Credential-owned rotation orchestration. Process state is separate from Credential lifecycle and provider fulfillment.';
