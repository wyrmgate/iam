CREATE UNIQUE INDEX idp_browser_session_token_hash_global_uq
    ON platform.idp_browser_session (token_hash);

COMMENT ON INDEX platform.idp_browser_session_token_hash_global_uq IS
    'Globally unique opaque-session digest used to derive Tenant server-side from possession of a valid first-party browser session token.';
