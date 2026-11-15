-- A provider success is evidence; never re-open an uncertain mutation.
CREATE TABLE integration.provisioning_effect_recovery (
    tenant_id uuid NOT NULL,
    attempt_id uuid NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('PENDING','RESOLVED','PLANNED','MANUAL')),
    reason varchar(128),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY(tenant_id,attempt_id),
    FOREIGN KEY(tenant_id,attempt_id) REFERENCES integration.provisioning_attempt(tenant_id,id)
);
CREATE INDEX provisioning_effect_recovery_pending_idx
    ON integration.provisioning_effect_recovery(next_attempt_at,attempt_id) WHERE state='PENDING';
