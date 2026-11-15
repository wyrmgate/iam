-- ADR-0070: shared bounded technical admission and retention.
CREATE TABLE platform.idp_attempt_budget (
    tenant_id uuid NOT NULL REFERENCES tenancy.tenant(id),
    kind varchar(32) NOT NULL CHECK (kind IN ('TENANT_LOGIN','PRINCIPAL_LOGIN','INTERACTION')),
    subject_id uuid NOT NULL,
    window_start timestamptz NOT NULL,
    attempt_count integer NOT NULL CHECK (attempt_count > 0),
    PRIMARY KEY (tenant_id, kind, subject_id),
    CHECK (kind = 'PRINCIPAL_LOGIN' OR subject_id = tenant_id)
);
CREATE INDEX idp_attempt_budget_window_idx ON platform.idp_attempt_budget(window_start);
CREATE INDEX idp_code_cleanup_expiry_idx ON platform.idp_authorization_code(expires_at, id);
CREATE INDEX idp_code_cleanup_consumed_idx ON platform.idp_authorization_code(consumed_at, id) WHERE consumed_at IS NOT NULL;
CREATE INDEX idp_code_session_idx ON platform.idp_authorization_code(tenant_id, browser_session_id);
CREATE INDEX idp_session_cleanup_absolute_idx ON platform.idp_browser_session(absolute_expires_at, id);
CREATE INDEX idp_session_cleanup_idle_idx ON platform.idp_browser_session(idle_expires_at, id);
CREATE INDEX idp_session_cleanup_revoked_idx ON platform.idp_browser_session(revoked_at, id) WHERE revoked_at IS NOT NULL;
