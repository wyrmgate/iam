-- ADR-0065: explicit terminal cancellation for unstarted Credential rotation plans.
ALTER TABLE credential.credential_rotation
    DROP CONSTRAINT credential_rotation_state_ck,
    DROP CONSTRAINT credential_rotation_completion_ck;
ALTER TABLE credential.credential_rotation
    ADD CONSTRAINT credential_rotation_state_ck CHECK (process_state IN (
        'PLANNED','CREATING_REPLACEMENT','DISTRIBUTING','VERIFYING','CUTOVER_COMPLETE',
        'REVOKING_OLD','COMPLETED','FAILED','MANUAL_REQUIRED','FAILED_REMEDIATION','CANCELLED')),
    ADD CONSTRAINT credential_rotation_completion_ck CHECK (
        (process_state IN ('COMPLETED','FAILED','MANUAL_REQUIRED','FAILED_REMEDIATION','CANCELLED')) = (completed_at IS NOT NULL)),
    ADD CONSTRAINT credential_rotation_cancelled_ck CHECK (
        process_state <> 'CANCELLED' OR (replacement_credential_id IS NULL AND failure_code IS NULL));

DROP INDEX credential.credential_rotation_open_old_uq;
CREATE UNIQUE INDEX credential_rotation_open_old_uq ON credential.credential_rotation (tenant_id, old_credential_id)
    WHERE process_state NOT IN ('COMPLETED','FAILED','MANUAL_REQUIRED','FAILED_REMEDIATION','CANCELLED');

CREATE OR REPLACE FUNCTION credential.guard_rotation_update() RETURNS trigger
    LANGUAGE plpgsql AS $$
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
    IF OLD.process_state IN ('COMPLETED','FAILED','MANUAL_REQUIRED','FAILED_REMEDIATION','CANCELLED') THEN
        RAISE EXCEPTION 'terminal CredentialRotation is immutable';
    END IF;
    IF NEW.process_state = 'CANCELLED' AND (OLD.process_state <> 'PLANNED' OR OLD.replacement_credential_id IS NOT NULL) THEN
        RAISE EXCEPTION 'only an unstarted CredentialRotation plan may be cancelled';
    END IF;
    RETURN NEW;
END;
$$;
