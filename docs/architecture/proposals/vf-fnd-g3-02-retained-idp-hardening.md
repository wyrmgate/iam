# G3-02 — retained IdP hardening

Status: implementation draft; independent acceptance and separate merge authorization required. #517/#541, ADR-0087; baseline 1dc55509868c89ebb6fd6016c3659d0ae5a0d302. Retains existing engine, signing provider, sessions and legacy wire profile. No new grant, key custody, token type, dependency, schema, deployment or release.

## Bounded production corrections

- Client lookup and authorization-code store failures translate to protocol temporarily_unavailable. Resolve trusted client/tenant before code consumption; never restore or sign after an uncertain consume. Existing digest-only one-use/revision/PKCE/session/authority checks remain unchanged.
- Share full verification-set validation across signing, discovery and JWKS: supported RSA algorithms, unique key IDs, current material match and retained-key publication. Preserve signing port and rotation/overlap semantics; no private material in publication.
- Browser-session filter catches only session resolution failure; downstream denial remains handled by the security chain. Revoked/missing session must not be translated to an availability failure.
- InstallationBootstrapService normalizes its reused checkpoint Instant to PostgreSQL microsecond precision, avoiding a persisted creation time later than the next lifecycle transition. No lifecycle/schema change.

## Verification and test fixture lifecycle

Targeted exact prior head 4f09d49e3dc163c0d09014c1cb77cd2df35eb9b2: run 43804062849 passed 59 tests, zero errors/failures/skips; full Core 43804062824 passed 540, zero errors/failures/skips (server job 133090777342). Includes actual V42 fresh install and seal, embedded HTTP login/CSRF/PKCE exchange/replay/logout/post-logout 401, JDBC 20-way one-winner and 11 negative burn paths, RSA256/384/512 publication, retained-key overlap/duplicate rejection, timestamp regression. Service grant still rejected. Actual HTTP uses loopback, generated signing keys and disposable database; not live IAM/Storage or production key custody.

QA-G3-02-01: full Core log showed scheduler/pool connection failures immediately after IdpRetainedHttpIntegrationTest stopped its DB; ApplicationFlywayStartupIntegrationTest has the same static container/cached Spring context pattern. First correction 48a8bcb used explicit context close in JUnit AfterAll; targeted run 43805550510 failed two after-class lifecycle errors because Spring Test still needed its cached context. Superseded by Spring-managed DirtiesContext(AFTER_CLASS) plus ordered test execution listeners (925342d, 1c4b400) that stop only their own container after context eviction. Default listeners are preserved; no production scheduler disabling or log suppression. Final exact-head targeted/full Core logs must show tests passing and no scheduler connections to the stopped fixture databases. Earlier green runs do not certify the new head.

Independent QA and Security/Architecture review, actual retained/federated session and signing invariants, and explicit owner merge authorization remain gated; no claim of live provider, external trust or integrated service acceptance. Review state tracked in #538/#541 and PR #548.
