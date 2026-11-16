# VF-FND-02 — Ecosystem identity and authorization contract proposal v0.1

Status: strategic direction approved in #540 (2026-11-16); detailed contract PROPOSED, review only. [ADR-0086](../../adr/0086-ecosystem-resource-audience-and-service-identity.md) and [experiment plan](vf-fnd-engineering-experiments.md) specify decision gates. Date 2026-11-16. Parent #538, work #540; product/architecture [VF-FND-01](vf-fnd-01-product-foundation.md). Not a production wire contract or accepted ADR. No deployed configuration or implementation authorized. Version 0.1 here is proposal version, not Kernel wire v0.2 or IAM public API version. Actual issuer/audiences/client IDs and customer data remain unconfirmed; every example uses synthetic values.

## 1. Evidence and problem

IAM acaf766: [IdpJwtIssuer](https://github.com/wyrmgate/iam/blob/acaf766984a2c7041ea370c593354240b1d2cdb4/apps/server/src/main/java/io/wyrmgate/iam/idp/protocol/IdpJwtIssuer.java) issues 5-minute default access token with typ at+jwt, iss/sub/aud/iat/exp/client_id/scope; aud = code.clientId; no jti or access auth_time. ID token typ JWT, aud client ID, auth_time, optional nonce. Subject is deterministic opaque hash of version prefix + Tenant ID + Identity ID. Current [protocol contract](../../api/idp-sso-contracts.md), [security boundary](../../security/identity-provider-sso.md), ADR-0042/0066–0071/0077–0079 govern. Public code+S256 PKCE, exact registration/redirect, one-use code, current state at redemption, local and explicitly bound upstream OIDC. No supported runtime client credentials, refresh or delegation. ADR-0042 describes confidential clients where configured; runtime does not yet prove this.

Storage 8ea998f: [FS-110B verifier](https://github.com/vyrnforge/file-storage/blob/8ea998faf20d0f94ac92f6a5ddb083e994cae592/docs/security/FS110B_JWT_VERIFIER.md) expects exact issuer, singleton aud and client_id equal configured client, RSA signature, bounded trusted JWKS snapshot; no live JWKS transport in that slice. [FS-110A boundary](https://github.com/vyrnforge/file-storage/blob/8ea998faf20d0f94ac92f6a5ddb083e994cae592/docs/security/FS110_IDENTITY_AUTHORIZATION.md) resolves exact server-side binding, membership, module entitlement, purpose/consumer/file grant; USER only, no implicit tenant admin or delegation. #17 requires agreed resource audience/service semantics; #18 transport and actual IAM tokens; #19 authority sources. Kernel e107dbd [identity boundary](https://github.com/vyrnforge/platform-kernel/blob/e107dbd700bda33daf6483d41651b50724f8fc22/docs/integration/identity-boundary.md) consumes trusted projection and mandatory domain authorization; not JWT verifier or membership authority. Do not imply live acceptance from synthetic CI.

Standards references: [OIDC Core](https://openid.net/specs/openid-connect-core-1_0.html), [RFC 6749](https://www.rfc-editor.org/rfc/rfc6749), [RFC 7636](https://www.rfc-editor.org/rfc/rfc7636), [RFC 8707](https://www.rfc-editor.org/rfc/rfc8707), [RFC 9068](https://www.rfc-editor.org/rfc/rfc9068), [RFC 9700](https://www.rfc-editor.org/rfc/rfc9700), [RFC 8693](https://www.rfc-editor.org/rfc/rfc8693) (future delegation option). Use standards-based profile; not claim certification. RFC 9068 includes jti; current IAM token is not claimed fully conformant. Proposed new profile adds required jti, resource audience, separate client_id.

## 2. Proposed P06 decision and alternatives

| Option | Consequence |
|---|---|
| Keep aud=client_id for every client | Minimal existing compatibility; forces dedicated resource client and prevents correct multi-client resource access without unsafe token forwarding |
| Single resource audience per access token, separate OAuth client identity | Recommended. Resource and client registered/bound independently, exact allowed pairs/scopes; standards-aligned; requires issuer/consumer changes and migration |
| Generic multi-audience token or shared ecosystem token | Reject for initial release: confused deputy/cross-service reuse and broad privileges; no proven need |
| Opaque introspected token only | Can support tighter revocation but introduces availability/latency dependency on IAM; evaluate for high-risk operations if bounded JWT plus current authority not sufficient |

Decision proposed: resource-specific JWT access profile, one audience per token, explicitly requested resource (RFC 8707) or exact unambiguous registered default; no wildcard. ID-token audience remains relying-party client_id. Keep legacy client-audience profile only for explicitly registered old interactive clients until tested cutover, not as generic fallback. No ID token sent to API. Existing public subject stable across migration if issuer/Tenant/Identity stable; preserve IDs or explicit binding map. No automatic linking on email/name. Actual profile version, issuer and audience registration remain owner-approved configuration. Security/Architecture must accept ADR amendment before implementation; proposed ADR-0086 is not accepted.

## 3. Ownership and proposed model (logical, not persistence schema)

- Catalog owns Application/ApplicationTarget and typed ClientRegistration plus proposed ResourceRegistration (stable resource ID, exact audience URI, owner Tenant or explicitly approved shared/deployment topology, status/revision, allowed scopes). ClientResourceBinding references client, resource, exact tenant/target route, allowed grant types/scopes and revision. These are proposed extensions, not existing entities; evaluate Catalog ownership against ADR-0049 shared application tenancy and ADR-0052 retirement; no generic nullable tenant. Client ID globally unique; each first tranche client bound to one Tenant and exact application target. Shared consumer can be installed once with tenant-specific client/binding; no implicit cross-tenant token.
- Identity owns PERSON/SERVICE/WORKLOAD and Principal; Credential owns authenticator metadata and verifier/secret references. Service client registration is not a secret or second user store: explicit client -> exact non-human Identity/Principal/credential binding with accountable owner. For initial service profile one stable client_id maps to one service subject within issuer and Tenant; service subject is an opaque stable client identity (distinct namespace from human subjects), not raw internal ID or human impersonation. Identity/Principal revocation and client retirement deny new issuance. Credential owns client-authenticator lifecycle; Catalog owns registration metadata, not secret values. Confirm exact binding shape with #541.
- Access/Governance own governed application admission and access assignments; token scope is a projection of allowed protocol scopes, not business role or administrative grant. New privilege/admission fails closed on missing mandatory evaluation. Authoritative reduction still proceeds.
- Integration owns upstream federation trust, exact subject links and proof; external issuer assertion does not create Identity/Administration automatically.
- Administration authorizes configuration/secret-reference/rotation operations, separate from application authorization. Audit owns data-minimized evidence; Platform protocol mechanics owns codes/sessions/signing and replay controls. Kernel accepts a trusted context only after product verifier/binding; no shared state or IAM dependency.
- File Storage owns local actor reference/binding, tenant/consumer mapping, resource ownership, purpose and file grants. Membership/entitlement sources must be authorized and current (FS-110E); no implicit cross-app grants. Future ITSM/Asset own business resources and policies. IAM is preferred IdP; alternative issuer supported through explicit trust/binding, not relaxed validation.

## 4. Proposed wire profile and trust

Synthetic examples (decoded claims only, not real tokens):
 
Human access:
```json
{"iss":"https://iam.example.invalid","sub":"human-opaque-01","aud":"https://storage.example.invalid/api","client_id":"storage-browser-t1","iat":1794780000,"exp":1794780300,"jti":"synthetic-human-001","scope":"files.read"}
```

Service access:
```json
{"iss":"https://iam.example.invalid","sub":"service-opaque-01","aud":"https://storage.example.invalid/api","client_id":"asset-backend-t1","iat":1794780000,"exp":1794780300,"jti":"synthetic-service-001","scope":"files.write"}
```

Both proposed JWT header typ=at+jwt, alg=RS256 (allowlist per configured issuer, no untrusted algorithm choice), kid=<registered verification key>. Claims are illustrative; exact syntax/limits to approve. jti unique per issuance, not an authorization grant; never use for automatic exactly-once. Scope string space-delimited exact registered values; examples do not define Storage's authoritative permission names. No tenant claim required; resolve trusted binding on server. No internal UUIDs, raw roles, entitlement dumps or secret material. Optional auth_time/acr/amr only when evidence/semantics agreed; iat is never auth_time. No service token asserts human auth_time or assurance. ID token has OIDC semantics, separate validation and audience; not API bearer. Optional token grant-kind claim only if separately standardized/approved; initial actor type derives from explicit client/subject binding (not guessed from string or token scope). No custom tenant/actor claims assumed for external IdPs.

| Check | Proposed required behavior |
|---|---|
| Trust source | Server-pinned exact HTTPS issuer, discovery/JWKS endpoint and TLS/egress/redirect policy; never token-controlled jku/x5u or issuer discovery from arbitrary request |
| Signature/type/time | Verify permitted alg, signature, exact issuer, type at+jwt or application/at+jwt, expected single resource aud, registered client_id and allowed pair, required sub/iat/exp/jti, optional nbf; reject malformed/oversized, duplicate keys/claims, unsupported critical headers, private/weak keys, unknown kid after bounded refresh. ID tokens denied. |
| Key rotation | Publish current + retained public keys; bounded cache freshness, refresh and size; no stale/untrusted fallback. Unknown key/degraded trust denies. Test old/new overlap and emergency key removal; review propagation and max stale bound. |
| Time | Existing default 300s; propose max access lifetime 300s and max clock leeway 30s in foundation, measured clocks synchronized; accepted security policy needed. For self-contained token without fresh subject check residual <=330s after revocation in worst case, excluding already authorized in-flight operation; no immediate universal JWT revocation claim. |
| Auth state | Fresh Tenant/Identity/Principal/Credential/client/resource/Access admission at issuance and code redemption; new issuance fails closed on revocation/outage. Browser sessions retain current revision fences; logout revokes session, not all previously minted JWTs. |
| Consumer authorization | Verify token before trusted binding and tenant selection. Require current local membership, module entitlement, client/consumer action/purpose grant and exact resource grant when applicable; missing/stale/unavailable mandatory authority denies. Reauthorize replay, background work and each sensitive operation. Explicit local revocation immediately blocks new decisions; cached remote authority needs approved freshness bound; no unbounded stale allow. |
| Errors/evidence | Neutral auth failure; forbidden for valid caller without grant; dependency failure visible without disclosing sensitive context. Audit operation IDs, actor refs, scope/resource/decision, correlation/causation, not tokens, keys, passwords, codes, raw assertions or signed URLs. |

## 5. Normalized trusted execution context

Conceptual internal projection (not a new public wire schema): TrustedActor {actorRef, actorType USER|SERVICE|SYSTEM, issuer, subject, clientId, assurance?, authenticatedAt?}; ExecutionScope = TENANT(localTenantId) | DEPLOYMENT | TENANTLESS. TENANTLESS means explicitly non-tenant operation that cannot read tenant-owned resources, not deployment authority. DEPLOYMENT is explicit IAM authorized control-plane operation; never inferred from missing tenant, token, module entitlement or kernel's tenantless flag. Internal SYSTEM job has explicit scope and constrained command authority/causation, not a public service token or wildcard. WORKLOAD maps to service-type trusted actor where policy allows; Identity type preserved in IAM, not inferred by consumer.

Binding: (trusted issuer, subject, resource audience, client_id) -> approved stable local actor, local tenant, consumer, allowed protocol/grant type; verify active binding and authoritative membership. Multiple client associations may refer to same canonical actor but each route is explicit; ambiguous match deny. Tenant selector in route/header is untrusted until checked against binding/resource owner. Distinct issuers with identical sub cannot collide; no cross-tenant fallback. Deployment operations use IAM server-derived scope + Administration, not file/storage pipeline. Map into Kernel ActorReference/ValidatedIdentityCredential/RequestContextPipeline only after verification; product-owned DomainAuthorizationHook always enforced. Kernel #41 may add generic scope contract only if it preserves current consumers and does not transfer authority. A local actor ID is a product reference, not a global user-table key.

## 6. Three flows and failure paths

1. Human -> Storage: browser/public client PKCE, exact redirect, state/nonce; registered resource binding; current governed checks at issue; Storage verifies exact issuer/aud/client, binds actor+tenant+consumer, checks current local grant and file owner. An Asset browser token for Asset API fails at Storage even same tenant. Confidential web client may authenticate at token endpoint in addition to PKCE; compare asymmetric private_key_jwt vs secret-based option in Security spike, prefer asymmetric where feasible; no public client secret.
2. Service -> Storage: confidential registered Asset/ITSM backend with owned service identity, client auth (prefer private_key_jwt with replay/time/audience checks; mTLS alternative; choose in ADR), client_credentials grant, approved resource+scope+governed service eligibility; no user session/ID token, no actor spoofing; consumer explicit service binding and purpose/resource grant. Store no raw private key/client secret in normal APIs/events. Credential rotation and revocation stop new issuance; old JWT bounded as above. No arbitrary forwarding of user's login token.
3. Acting for user: deferred implementation. If actual workflow needs it, evaluate RFC 8693 token exchange with authenticated caller, explicit subject/actor relationship, audience, bounded delegation grant, downscoping and no broader authority than both parties; require current subject and actor eligibility and local resource grant. Until accepted and implemented, reject delegation/act claims and impersonation. Initial service call acts as service only; if action is user-specific it must not be represented as service-only to bypass user checks.

Failure: provider/authority unavailable -> no new privilege increase, consumer denies stale/missing authority, existing governance decision preserved, authoritative reduction still commits and durable deprovisioning continues. Token validity and current authorization separate; already authorized in-flight operations/transfer handles need Storage's own lease/drain/revalidation (FS-310A etc.), not assumption that JWT expiry cancels bytes. Security/Storage agree maximum residual transfer window and remote freshness before production. No automatic grant from token scope.

## 7. Compatibility, migration, conformance

Proposed staged migration:
1. Accept P06 and actual issuer/client/resource/tenant binding in #540 + Storage #17. Agree exact profile contract and fixtures, client auth, scope/credential ownership, consumer freshness and residual risk.
2. Implement new issuer profile behind explicit configuration after #541 ADR approval; preserve old client-specific contract, no broad fallback. Add jti and separate audience, distinct service subject, register exact binding, test current-state checks; compare mature authorization-server library vs existing. No default behavior switch.
3. Storage #18/#19 implements new exact resource verifier + allowed client pairs, trusted HTTPS JWKS/cache, current authority and explicit service grant. Separate old/new named verifier configurations/routes or client registration; do not accept both audiences indiscriminately on one route. Pin revision/fixture profile; do not use unverified claims to select trust.
4. Staging #542 tests actual issuer tokens and old client compatibility, key rotation, authority outage, negative cases, consumer/tenant binding; test Kernel current release and candidate with current IAM + Storage. No public activation before acceptance.
5. #543 migrate registrations/IDs/subject bindings explicitly; freeze issuance for old registration, drain old access token + skew and applicable transfer leases, revoke old sessions/credentials as approved, verify new consumer, disable legacy acceptance; rollback only with explicit risk/compatibility and no duplicate authority. Formal #438 checkpoint tracks changed BRD/FRD/SRS/DDD/SAD/security/integration/RTM and public interface version. No customer reset.

| ID | Fixture / action | Expected observable |
|---|---|---|
| C01 | Active human T1, registered Storage client/resource, valid signed access + grant | Exactly one permitted file operation; correct actor/tenant/consumer; correlation evidence |
| C02 | Wrong issuer, wrong aud, wrong client pair, ID token, malformed/expired | Denied before domain effect; no writes/secret logs |
| C03 | Owned service T1, allowed client_credentials/resource/purpose | Service-only operation; no ID token/human claim; wrong client/credential/scope denied |
| C04 | Revoke subject/client/credential/Access or local grant | New issue/consumer decision denied as applicable; prior self-contained JWT tested through max approved window; local grant revocation immediate, transfer drain tested separately |
| C05 | Stale/unavailable trust, unknown kid, authority outage | Fail closed, bounded refresh, explicit degraded evidence, no unbounded fallback |
| C06 | Two tenants, same sub across two issuers, header tenant spoof | Distinct bindings, cross-tenant denied; no auto-link |
| C07 | Allowed application scope but missing purpose/file grant | Deny; same tenant/role/module not sufficient; no tenant admin bypass |
| C08 | Replay idempotent operation after revoke, duplicate/reordered delivery | Reauthorize, no new unauthorized effect, stable causal outcome |
| C09 | Key rotation overlap then remove old key; issuer unavailable | Valid current key works; retained key per window; removed/stale key denied after approved cache bound |
| C10 | Delegation/act supplied before approved flow | Deny; no user token forwarding or service impersonation |
| C11 | Legacy dedicated client vs new profile, wrong profile on route | Only explicitly configured matching profile; no fallback; drain/cutover/rollback fixture |
| C12 | Deployment operation vs tenantless route vs Tenant T1 | Administration explicitly checks DEPLOYMENT; tenantless cannot access tenant-owned resources; no null-tenant escalation |

QA evidence: synthetic signed fixtures first, then real IAM staging token with protected logs and exact IAM/Kernel/Storage revisions; capture test outcomes and config IDs only, no credentials. Existing FS-110A/B synthetic CI is evidence of old behavior only. C-series proposed, not executed. #17 owns agreement, #18 trust, #19 grant sources, #542 integrated results. Owners: Solution Architecture and Security & IAM, Product Owner approval of scope/revocation/service tier, File Storage owner local grant policy, Kernel engineer context/conformance. No implementation authorized by this proposal.

## 8. Decision gates still open

D1 actual issuer(s), audience URIs, client registrations and beta/Storage tenant mapping; Product/Platform/IAM operator.
D2 choose auth method and protocol implementation after bounded comparison (confidential clients, current eligibility, replay, signing, audit, discovery/PKCE); Architecture/Security.
D3 approve 300s/30s candidate bound, remote authority freshness, incident revocation/transfer drain; Product/Security/Storage; consider introspection or online authorization where insufficient.
D4 approve service principal/credential binding and grant issuance/revocation owner, exact scopes and consumer purpose; IAM/Storage owner.
D5 actual delegated user workflow; defer until required, explicit contract approval.
D6 formal controlled folder 404; #438 must recover/designate successor. No silent formal promotion.

Once decisions are approved, update accepted ADR(s) and canonical living contracts in reviewed PRs; this proposal is superseded by those accepted documents rather than becoming parallel authority. No merge before approval.
