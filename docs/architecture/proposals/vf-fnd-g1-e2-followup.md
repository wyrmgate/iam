# VF-FND G1 follow-up — guarded IdP comparison (E2/E3)

Status: **Executed bounded follow-up; G2 recommendation Proposed**, not an accepted ADR or production implementation decision.
2026-11-16. [G1 follow-up authorization and initial evidence #538](https://github.com/wyrmgate/iam/issues/538#issuecomment-6906631263), #540/#541/#517. [Original experiment plan](vf-fnd-engineering-experiments.md), [G1 evidence](vf-fnd-g1-evidence.md), [ADR-0087](../../adr/0087-idp-protocol-engine-evaluation.md). No E1b, E5, releases, customer access, merges or production implementation.

## Baseline and method

IAM main acaf766984a2c7041ea370c593354240b1d2cdb4 (V42), Kernel main e107dbd700bda33daf6483d41651b50724f8fc22 (0.3.2), Storage main 8ea998faf20d0f94ac92f6a5ddb083e994cae592; unchanged at start. Controlled formal Drive folder still returns 404, not replaced with working drafts. Canonical ADR-0066/0067 and existing security/API contracts remain governing; 0084–0088 Proposed. Test-only [IAM draft #546](https://github.com/wyrmgate/iam/pull/546), branch experiment/vf-fnd-e2-guarded-hybrid. CI copies server to /tmp/vf-fnd-e2/server, adds candidate test dependency only in copied POM, uses actual source and V42 PostgreSQL Testcontainers. No production POM or runtime source modified. Exact command: bash experiments/vf-fnd-e2/run.sh (Maven 3.10.0, Java Temurin 25.0.5, strict checksums, dependency:tree and targeted tests). No real credentials, all synthetic keys generated in process, no provider traffic or public deployment.

- A: actual IdpProtocolService, JdbcIdpAuthorizationCodeRepository, IdpBrowserSessionService, JdbcIdpBrowserSessionRepository, IdpJwtIssuer, current MVC controllers.
- B: same guards/repositories/controllers; isolated Spring JwtGenerator with transient RegisteredClient/OAuth2Authorization projections, explicit legacy claim projection and Nimbus JWSSigner using existing SigningKeyProvider.sign(bytes), public material only. RS256 fixture; no framework registration/state authority, no full authorization-server endpoint replacement.
- E3: actual JwtClientAssertionAuthenticationProvider with pinned synthetic RSA decoder + standard checks + exact token endpoint, bounded timestamps, PostgreSQL unique insert for hash of jti scoped by issuer/client, expiry cleanup and fail-closed database outage.

Actual source hashes: IdpProtocolService 88243d270f4e34880ab9abe453ff1b3c49de675ccd981c51368b3dea92e37d7f; JdbcIdpAuthorizationCodeRepository 908f7f9b701137ddb7f1b40c6b46e857cc05fa56604a7909601217679334261b; IdpJwtIssuer ecb012f96e99202ead86c0ce0e146af0c041e0a9b893e02691f891057642e2d6.

## Observations and decision rationale

The previous #545 default Spring provider diagnostics already showed wrong PKCE leaves code usable, two concurrent in-memory redemptions succeed and default assertions replay. B deliberately **does not** replace those existing IAM guards. Spring JwtGenerator default ID token TTL 30 minutes and additional defaults (jti/azp/nbf) require explicit accepted legacy profile customization. B constructs ephemeral client/authorization state and custom signing SPI; no demonstrated removal of existing authorization protocol maintenance. Current issuer supports RS256/384/512 and signing-provider publication checks; B only RS256. Source size diagnostic: current IdpJwtIssuer 152 lines (132 nonblank), test HybridIssuer 81 lines (80 nonblank); not comparable production maintenance metric, no claim of net reduction. Candidate has no independent dependency/CVE/license review or full KMS/HSM, rotation, federation/filter-chain and live consumer validation.

## Execution history

- [Run 43799392217](https://github.com/wyrmgate/iam/actions/runs/43799392217) e4128d0: 27 existing control tests and two E3 replay tests passed; three A/B tests setup errors (missing originating browser-session row; V42 FK). [Run 43799468766](https://github.com/wyrmgate/iam/actions/runs/43799468766) 671652a: same 29 passes, one controller assertion and two setup errors due same missing persisted session. Corrected with actual JDBC browser session establishment and current revision semantic ports, not weakening FK or changing product code.
- E3 actual observed in both runs: 1/20 successful assertion authentications across two provider instances; recreated provider denied; distinct registered client with same jti accepted; invalid signature/subject/audience/expiry/overlong assertion denied before replay reserve; bounded cleanup; unavailable replay table denied. Test schema only; no production retention, registered key transport, credential revision/current governed service eligibility or end-to-end client_credentials issuance. Actual database outage is simulated by temporarily renaming test table, not a network failure.
- [Run 43799557939](https://github.com/wyrmgate/iam/actions/runs/43799557939) at 77d70e588667653709de1d1afe862acbda6bf20f: corrected A/B fixture with actual JDBC sessions, 20-way redemption, 11 negative paths, persisted idle-expiry check, real MVC authorization/redirect/token/discovery/JWKS, 1000 measured redemptions per alternative at concurrency 10 after 50 warmups. Completed success, job 133076118644, inspected raw logs; [artifact 14195594270](https://github.com/wyrmgate/iam/actions/runs/43799557939/artifacts/14195594270) includes dependency tree and test reports. 32 tests, 0 failures/errors/skipped (27 existing + 5 new).

## Final executed evidence — 77d70e5

| Scenario | A retained IAM | B guarded token-generation hybrid |
|---|---|---|
| 20 simultaneous code redemptions through actual JDBC repository | 1 success, 19 invalid_grant | 1 success, 19 invalid_grant |
| Stored authorization code | SHA-256 digest, consumed_at set | Same |
| Eleven negative paths | PKCE, redirect, registration revision, logout, Identity/Principal/Tenant/Credential revision, access denial, evaluator outage, signing unavailable: all denied and code burned | Same |
| Cross-tenant code redemption | Denied; foreign code not burned; correct tenant may subsequently redeem | Same |
| Session idle expiry | Persisted value unchanged by redemption | Same |
| Existing controller-level HTTP contract | Authorize exact redirect/state/S256; token response and serial replay denial; discovery and public JWKS; exact legacy claims/opaque subject/auth_time/nonce, 300s TTL, signed RS256; no private JWK parameter | Same |
| E3 actual client assertion replay | 1/20 across 2 provider instances; new provider denied, second client isolated, malformed/wrong key/audience/sub/expiry/overlong denied; storage outage fail closed; bounded expiry cleanup | Shared separate candidate fixture |

Actual IAM controls: TenantIsolationSchemaIntegrationTest 6, IdpBrowserSessionServiceTest 8, IdpJwtIssuerTest 1, IdpProtocolServiceTest 6, IdpInteractionPersistenceIntegrationTest 1, IdpRuntimePersistenceIntegrationTest 3, PersistenceArchitectureTest 2. New GuardedHybridExperimentTest 3 and AssertionReplayExperimentTest 2. Hosted Java25.0.5 Maven3.10.0, PG18.4 via Testcontainers, actual V42 migrations, Boot4.1.1. Candidate version previously resolved 7.1.1 on same BOM; exact current tree archived. No direct live HTTP server: standalone MockMvc controllers, synthetic semantic authority ports and mocked evidence. Session service, JDBC session/code repositories and signing mechanics real, same synthetic fixture. No full Spring HTTP filter-chain or authorization-code provider replacement.

### Comparative latency

| Metric (ms) | A | B |
|---|---:|---:|
| p50 | 86.990 | 86.411 |
| p95 | 121.691 | 119.014 |
| p99 | 137.074 | 145.117 |
| Errors | 0 | 0 |

1,000 exchanges per alternative, concurrency 10, warmup 50, pre-created codes, timed service redemption (includes actual JDBC and signing, excludes authorization setup, network ingress, live domain evaluation/Audit, external signing). Sequential A then B on one hosted runner, no repeat runs/confidence intervals or production load evidence. Both below ADR-0043 numerical thresholds in fixture; not SLO acceptance. No compelling performance advantage.

## G2 recommendation — retain existing engine, selective mature components

Recommend A (retain/refactor existing first-party protocol service, current IdpJwtIssuer and durable code/session mechanisms) as the G2 baseline; do not adopt default Spring authorization-server runtime or replace the current signer merely to reuse a framework. B is technically feasible for the tested legacy RS256 slice, but needs explicit claim reconstruction, transient framework objects and a custom signing adapter; no demonstrated maintenance/latency advantage, no RS384/512/rotation/production key custody and full dependency review. Retain B as a reversible evaluated option, not rejected as a library. Prefer supported mature components for new governed service-client assertion verification and service-token issuance behind IAM-owned ports, subject to separate G2 contract/implementation design and G3 authorization. Durable replay fixture supports feasibility but actual registration/credential/current authority, key transport/rotation, bounded replay retention, full service grant and Storage successor verifier remain open. ADR-0087 remains Proposed pending human acceptance of this recommendation; no architecture merge or implementation authorized. E1a/E4 earlier findings unchanged; E1b/E5 not authorized. Same-session implementation verification only; independent QA/security review required.
