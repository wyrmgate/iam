# VF-FND-01 — Product scope and ecosystem foundation proposal

Status: G2 architectural directions approved 2026-11-16 ([record](https://github.com/wyrmgate/iam/issues/538#issuecomment-6906844883)); draft ADRs pending canonical merge. Actual beta cohort, detailed scope, service tier and concrete contract remain gated; [G3 package](vf-fnd-g3-authorization-package.md). See [ADR-0084](../../adr/0084-staged-replatforming-and-release-gates.md), [ADR-0085](../../adr/0085-platform-kernel-adoption-boundary.md), [ADR-0087](../../adr/0087-idp-protocol-engine-evaluation.md), [ADR-0088](../../adr/0088-conditional-clean-schema-and-customer-migration.md) and [experiments](vf-fnd-engineering-experiments.md).
Date: 2026-11-16. Parent #538; work #539; companion [VF-FND-02](vf-fnd-02-identity-contract.md). This is a review artifact, not a second source of authority. Accepted ADRs and controlled specifications govern until an approved amendment. No production code, schema, customer environment or release is authorized by this document.

## 1. Evidence and decision context

| Source (pinned where possible) | Observed evidence / limitation |
|---|---|
| [IAM main acaf766](https://github.com/wyrmgate/iam/tree/acaf766984a2c7041ea370c593354240b1d2cdb4) | Nine capabilities, ADR-0083, Flyway V42; #537 merged; #524 closed for repository implementation, not live providers. Read AGENTS, project instructions, README, ADR index, layered architecture, canonical model, tenancy, IdP/security/API, ADR-0042/0043/0048, Maven POM and source tree. |
| [Kernel e107dbd](https://github.com/vyrnforge/platform-kernel/tree/e107dbd700bda33daf6483d41651b50724f8fc22) | Published 0.3.2; Java 21, Maven; core, optional JDBC, testkit; trusted identity adapter consumes verified identity, not a verifier/IdP. Current consumer gate is pinned historical File Storage FS-110A, not live integration or current storage full suite. |
| [Storage 8ea998f](https://github.com/vyrnforge/file-storage/tree/8ea998faf20d0f94ac92f6a5ddb083e994cae592) | FS-310A5 #85 merged; FS-110B cryptographic verifier exists; #17/#18/#19 are unresolved identity/audience, trusted JWKS, authority gates. Existing #13 master; #42–44/#49 environment/release. #47/#48 future adopters. |
| [Controlled formal folder](https://drive.google.com/drive/folders/1ShF6_OZm5-iCX9MFQBFRp-SzhPm3dvnM) | Rechecked metadata 404. Cannot verify exact approved v0.8 contents; restore access or designate successor through #438. |
| [Working v3 folder](https://drive.google.com/drive/folders/1r5-JpUCCAy6N-thhuwwo6k0qoYljJAOl) | Re-read BRD, FRD, SRS, register and RTM targeted sections; package retains v0.8 baseline text and working amendments. BR-IDM-001, BR-ACC-001, BR-GOV-001, BR-LCM-001, BR-INT-001, BR-SEC-001, BR-AUD-001 trace to current intent. FRD has older RoleVersion VALIDATING text; ADR-0054 governs. SRS scale assumptions not pilot volumes. No formal edits. |

Product-owner input: beta, one company migration expected manageable, willing to rebuild and evolve shared Kernel; clean long-term product preferred. This is authorization to plan, not evidence of database version/volume, specific customer workflow or signed release requirements. Exact customer inventory, regulatory obligations, integrations, workload, budget, operational capacity and RTO/SLO applicability unknown. Product owns confirmation; Platform/Data obtains sanitized inventory in #543. No ITSM/Asset implementation assessed.

## 2. Product outcome and scope

Outcome: a governed person or service can use an authorized application, an operator can explain and remove access, and another product can consume the identity/reliability contract without adopting IAM internals. Initial reference: Wyrmgate + File Storage; representative SCIM target and upstream OIDC provider for governance/realization tests, using approved staging configuration. One beta organization plus a synthetic second Tenant for isolation (Organization != Tenant). These are proposed pilot assumptions, not customer commitments.

| Actor/job | Problem hypothesis | Foundation outcome | Evidence / measurement |
|---|---|---|---|
| Tenant operator onboards employee | Repeated/manual account and grant work | Source/correlation -> governed Identity/Principal -> policy/request -> fulfillment | Time from accepted source fact to assignment and provider disposition, manual steps; baseline from beta workflow inventory |
| Employee requests and uses access | Approval does not guarantee working login | Request, approval, assigned access, SSO and resource-level grant; denied unauthorized operation | Successful golden journeys / eligible attempts in agreed staging run; denied attempts count separately |
| Manager/reviewer/security operator offboards/reviews | Residual access and unclear evidence | Immediate semantic reduction, current admission denied, bounded token window, external removal or explicit remediation | Time from committed reduction to denied new token and provider disposition, residual access window, review evidence |
| Application developer/service operator | Independent products duplicate auth and retry plumbing | Standard identity/audience + local grants, client credentials, idempotent durable effect | Two consumer conformance suites; number of replaced duplicate mechanisms and measured regressions |
| Auditor | Approval/effective/provider state confused | Trace decision, assignment, projection, effect and evidence with correlation | All sampled agreed critical actions traceable; no secret-bearing evidence |

Initial acceptance window: controlled staging rehearsal at recorded revisions and configuration. Denominator = agreed fixture scenarios/eligible transactions, not inferred customer population. Proposed pass: all mandatory golden and negative scenarios pass; zero unauthorized cross-tenant successes; all sampled security-sensitive actions traceable; healthy internal work disposition <=5 min security-sensitive, normal 95% <=15 min per ADR-0043. Production SLO/RPO/RTO not waived: 99.95% monthly, RPO <=5 min, RTO <=60 min and associated HA/DR/alerts per ADR-0043. A non-production pilot is not proof. Product Owner must approve actual cohort, migration acceptance, service tier, target load and metrics.

| Release scope | Decision proposal |
|---|---|
| Foundation staging | Secure sealed setup, tenant/owner lifecycle, person and non-human identity ownership, canonical source/relationship model, catalog, access intent/derived effective access, request/approval/basic SoD, JML, review and remediation, SCIM provisioning and independent reconciliation, audit/evidence, usable operator and self-service flows, first-party OIDC code+PKCE, upstream OIDC, public/confidential clients, resource audience, client credentials, key lifecycle, actual File Storage human/service flow. |
| Before customer production | Privileged MFA/passkey or explicitly approved equivalent strong assurance, recovery and owner continuity, signing/secret incident recovery, live provider tests, migration rehearsal and cutover approval, ADR-0043 operational gates or formally accepted successor tier. |
| Later unless actual consumer requires | General delegation/token exchange (contract boundary now; deny until implemented), SAML, refresh/introspection extensions beyond agreed session/revocation policy, adaptive risk, more connector types, broad scale tests for future volumes, ITSM/Asset feature builds. |
| Not in scope | Universal entity/EAV/User model, generic BPM engine, shared cross-product database, compulsory broker or microservices, billing/licensing core, mandatory Wyrmgate server for Kernel, removal of existing accepted capabilities without explicit product and ADR decision. |

## 3. Ownership and architecture boundary

One IAM modular-monolith-compatible product (Layer 1 experience, Layer 2 protocol/delivery, Layer 3 orchestration, Layer 4 owners, Layer 5 adapters, Layer 6 technical mechanisms). IGA and IdP are composed surfaces; not two user stores. Independently deployed File Storage and future products own their data, resource authorization and lifecycle. Kernel is a library with optional adapters, not a central service. VyrnForge UI may be evaluated separately; no dependency mandated. A package/module/process/schema is not the domain capability.

| Authoritative record/decision | Sole owner; collaboration |
|---|---|
| Tenant identity/lifecycle (deployment-owned) | Tenancy; Administration viability query, typed decommission commands to owners |
| Identity, Principal, Organization/manager, source/link and canonical provenance | Identity; Integration delivers observations; no automatic authority |
| Application, ApplicationTarget, target-scoped Entitlement, RoleVersion, client registration | Catalog; proposed resource audience/client binding extension in #540; retirement suppresses Access and drives Access-owned reduction |
| AccessAssignment, desired causes/reduction | Access; EffectiveAccess and desired technical state are projections; Governance decisions consumed via semantic commands |
| Requests/approval/review/policy/exception/finding | Governance; immutable decision/evidence, no foreign writes |
| Credential metadata/lifecycle, authenticator ownership | Credential; secret provider and protocol signing/session material separate |
| Connector/trust/link/mapping/observations, provisioning/reconciliation work | Integration; explicit exact route and current admission, provider effect outside transactions |
| Administrative roles/grants/elevation/break-glass | Administration; no automatic permission from OAuth scope, tenant ownership or normal access |
| Tenant/deployment Audit evidence | Audit; technical audit intent/event envelope not competing evidence authority |
| Protocol codes/sessions/signing, technical idempotency/outbox/lease/context | Platform implementation; replaceable Kernel mechanics where compatible, per-product database; no domain authority in shared library |
| File object, backend, tenant/consumer binding, purpose/local grants, retention | File Storage; IAM governed application admission is necessary where required, never sufficient for file resource access. ITSM/Asset own their analogous domain state. |
| Generic actor/scope/context, IDs, technical event/reliability contracts | Kernel code contract only; no authoritative business rows or network trust source |

Representative success: Identity-owned active employee -> Governance approval -> Access assignment (transaction + fact) -> Integration maps/provisions async -> Catalog/Identity/Credential/Access current checks at IdP issuance -> consumer verifies token -> resolves local actor/tenant/consumer -> membership/module entitlement and local grant -> authorized file action (local transaction + durable intent). No external call inside authoritative commit. Failure: provider times out after mutation; Integration records uncertainty, quiescence/current complete scan required before verified recovery (ADR-0083), no invalidation of approval. Leaver still reduces Access; new issuance denied; old JWT residual window and current consumer authority described in #540. Audit evidence remains in owning capability, no secrets.

## 4. G2 decision records (approved directions; detailed design gated)

| ID / decision | Alternatives and trade-offs | Recommendation / discriminating evidence / owner |
|---|---|---|
| P01 replatform | A in-place patches (low migration, continued duplication); B staged replacement with reference behavior (moderate migration, clean boundaries); C full rewrite (maximum freedom, highest semantic/security regression risk) | B. Preserve semantic tests/data; replace components only where clean target improves maintainability, correctness or verified reuse. If one bounded component cannot be isolated, compare C for that component, not whole product. Architecture + Backend; Product approves scope. |
| P02 Kernel | A independent copies; B selective common infrastructure with mandatory conformance for adopters; C all mechanisms wholesale including IAM authority | B as preferred shared technical foundation. Start 0.3.2 core, add only generic gaps proven in two consumers; consider JDBC after transactional experiment. Kernel #41 + IAM #541; no domain dependency direction reversal. Revisit if adapter duplicates more than it eliminates. |
| P03 protocol | A retain custom protocol; B mature OAuth/OIDC authorization-server adapter (e.g. Spring Security authorization-server stack, candidate only); C separate external IdP as sole authority | A selected: retain/refactor existing protocol and signer; B guarded hybrid passed bounded tests but no compelling maintenance/performance benefit; selectively use mature components for new service authentication, dependency/method still gated. C incompatible with first-party requirement; federation remains supported. See ADR-0087 and G1 follow-up. |
| P04 data/migration | A keep V42 and incremental deltas; B clean target baseline + explicit export/import or compatibility migration; C destructive reset customer | B permitted if target changes materially simplify; A otherwise; reject C. Prove schema equivalence for unchanged semantics, data invariants and restore/rollback for changes. Amend ADR-0048 before rebaseline; reserve V70; do not forge Flyway history. Data/Platform #543. |
| P05 product/service tier | A beta non-production reference, then accepted production objectives; B new explicitly approved smaller production tier; C silently relax | A now; B only with Product/Architecture/Security accepted amendment to ADR-0043 and customer agreement. C reject. |
| P06 identity | Current aud=client ID vs explicit resource audience plus client identity; see companion proposal | G2 approves resource audience and governed service identities, separate legacy mode; exact settings and binding/grants remain gated #540/Storage #17; approval and tests before emission. |

Classification: P01/P02 implementation structure and architecture dependency; P03 protocol/security/interface; P04 physical data and migration; P05 requirements/quality; P06 protocol/interface and Catalog registration semantics. Accepted ADR-0042 already describes confidential clients where configured but current implementation public-only; service grant/resource audience changes need explicit amendment. ADR-0051/0053 ownership unaffected. ADR-0066–0071/0077–0079 current authentication/federation invariants remain. Prior ADRs not silently superseded.

## 5. IAM keep/refactor/rebuild inventory (source-level discovery, not full code review)

Source base apps/server/src/main/java/io/wyrmgate/iam, tests same namespace; one server Maven POM (Java 25/Spring Boot 4.1.1, JDBC/Flyway); React Console. Counts are observed Java source file counts, not completion estimates. Use #541 to validate file-level changes, tests and migration before implementation.

| Component (files) | Recommendation | Evidence / preserve / required check |
|---|---|---|
| Tenancy (11) | KEEP semantics, refactor scope adapter | ADR-0051/V12, domain/tenancy.md; Tenant lifecycle/owner continuity, explicit scope; isolation and final owner test |
| Identity (112) | KEEP domain, refactor persistence/ports where useful | ADR-0022–0027/0038/0055, V13; IdentityApiIntegrationTest, PrincipalApiIntegrationTest; positive source, incomplete absence, relationships, eligibility epoch, historical IDs |
| Catalog (63) | KEEP, extend typed registration | ADR-0052/0054, CatalogRoleApiIntegrationTest; target scope, versioning/retirement, exact client-resource binding; no token roles as authority |
| Access (85) | KEEP domain, refactor infrastructure | ADR-0055/0056/0059; IdentityAccessReductionIntegrationTest, LifecycleAccessReconciliationIntegrationTest; concurrent SoD admission/reduction even evaluator unavailable; explainable support |
| Governance (91) | KEEP typed workflows, simplify orchestration only after tests | #503, ADR-0058–0062, GovernanceReviewApiIntegrationTest; stale approval, review reduction/failure, observation findings; no generic BPM |
| Credential (18) | KEEP metadata, refactor authenticator ports; extend approved strong auth/service credentials | #513 ADR-0063–0065, CredentialApiIntegrationTest; bound verifier, atomic change, rotation, secret custody |
| Integration (104) | KEEP exact observation/recovery, refactor generic workers | #524/#537 ADR-0072–0083 V42, IntegrationAdminApiPersistenceIntegrationTest; stale routes, complete coverage, uncertain effect recovery, federated trust/link |
| Administration (106) | KEEP authority model, refactor policy adapters | ADR-0032/0049/0051, AdministrativeAuthorizationPersistenceIntegrationTest, BreakGlass tests; default deny, finite privilege, viable owners |
| Audit (59) | KEEP immutable evidence, refactor delivery plumbing | ADR-0031/0034–0037/0053; AuditEvidenceLifecyclePersistenceIntegrationTest; retention/hold/purge/evidence integrity, distinct TENANT/DEPLOYMENT |
| IdP (40) | KEEP/REFACTOR existing protocol and signer, guards/session anchor; selectively extend new service authentication behind IAM-owned ports | IdpJwtIssuer, IdpTokenIssuer, IdpBrowserSessionService, ADR-0066–0071/0077–0079; current token redemption, CSRF, pinned trust, issuer/JWKS, one-use code, no parallel User |
| Platform (27) | REFACTOR to shared Kernel adapters when proven | LifecycleEngine, JdbcIdempotencyRepository, JdbcOutboxRepository; compare scope, causal fingerprint, replay, atomic facts/evidence, leases/revisions; no duplicate store |
| Installation (15) | KEEP secure flow, refactor composition | ADR-0044–0047; sealed setup, one-time proof removal, no permanent root; fresh install plus recovery |
| Console / API / schema | KEEP semantic contracts, refactor UI/job flows, rebuild obsolete adapters/schema only with compatibility/migration | #381/#394, api-conventions, event-model, PersistenceArchitectureTest, V42; ETag/idempotency, closed versioned public events, cursor, no browser authority. Preserve IDs and evidence. |

Kernel gap hypotheses (not proven defects): scope must distinguish IAM DEPLOYMENT from consumer tenantless; generic lifecycle extraction only if second use exists; core context/correlation first; JDBC must share same connection/transaction with domain mutation and evidence, preserve event revision/dedup/lease semantics and authorization on replay. Kernel #41 experiment: current IAM+Storage builds, Java 21 bytecode/Java 25 compatibility, exact published version, PostgreSQL concurrent insert/replay/crash/rollback/stale lease/restore, no unauthorized effects, no duplicate authoritative stores. If fail, retain current adapter until generic improvement passes.

## 6. Delivery and acceptance

| Slice / issue | Prerequisite and owner | Observable exit |
|---|---|---|
| F1 #539 | Product/Architecture; this proposal | Product scope, P01–P05 decisions and actual beta inventory/service tier approved; unknowns recorded; no code |
| F2 #540 / Storage #17 | F1 scope; Architecture/Security | P06 contract + C01–C12 companion examples accepted; exact owner mapping and consumer review |
| F3 Kernel #41 | accepted contracts; Kernel engineering | minimal shared adapter release/conformance with two pinned consumers; no forced JDBC |
| F4 #541 | F1/F2 and applicable F3; IAM engineering | clean boot/seal, tests and protocol spike; incremental domain/adapter changes; schema migration proof |
| F5 #542 | F4, Storage #17–19 and staging readiness; QA/Delivery/Platform | actual IAM tokens/SCIM/provider, human+service access and denied/revoked cases, exact revisions; no production claim |
| F6 #543/#438 | F5, approved customer inventory; Data/Platform/Product | dry-run counts+semantic reconciliation, protected backups, rollback/forward recovery, customer release approval, controlled successor specs/RTM |

Acceptance fixtures: two tenants, active employee and manager, service Identity/Principal+owned credential, one app/target/entitlement, one conflicting role, exact provider account binding, one file and consumer grant. A01 source joiner yields governed identity; A02 approved request yields one assignment, provider failure does not undo it; A03 mover/review changes access, stale approval denied; A04 leaver/revocation suppresses new admission even with evaluator down; A05 partial scan does not mass-disable; A06 cross-tenant and missing resource grant deny; A07 duplicate/reordered messages and uncertain effects safe; A08 Audit trace from request to effect with no secret. C-series in companion covers protocol. QA owns tests; all currently proposed, not executed.

Reconcile #437 (DEV V42 safe fresh setup), #438 (controlled package), #503/#513/#517 (acceptance), #381/#394 (Console), #182 (production), #73 (webhook). Do not reopen #524/#537 merely for live acceptance; #542 records missing proof. No issue closure in planning. Product owner decides priorities; Security signs security criteria; Architecture owns ADR proposal; Technical Delivery Lead tracks exact revisions and gates. No human assignees or dates presumed.
