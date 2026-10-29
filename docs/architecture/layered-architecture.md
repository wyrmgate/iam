# Wyrmgate IAM Layered Architecture

## Status

This document is the living repository counterpart to the reconciled Wyrmgate IAM v3 architecture image set. The detailed agreed diagrams refine older/base diagrams. Where an older base image conflicts with a later detailed image, the detailed model governs.

The architecture remains framework-neutral. A layer is not automatically a package, Maven module, process, microservice, database schema or deployment unit.

## Dependency direction

```text
Layer 1  Experience surfaces
   ↓
Layer 2  Delivery / protocol edge
   ↓
Layer 3  Application / use-case orchestration
   ↓
Layer 4  Business capabilities and authoritative state
   ↓
Layer 5  External provider adapters / anti-corruption edges
   ↓
Layer 6  Platform / infrastructure mechanics
```

Cross-layer calls may use explicit semantic ports in either request/response or asynchronous directions, but ownership never flows upward or downward implicitly. Only an owning Layer-4 capability mutates its authoritative business state.

## Layer 1 - Experience surfaces

Layer 1 is human/machine-facing experience composition, not domain authority.

Primary human-facing experiences are:

- Authentication and SSO experience;
- My Access / self-service experience;
- IAM administration Console experience;
- Platform administration experience.

Machine clients do not become Layer-1 business owners; they enter through Layer 2 contracts.

Layer 1 owns presentation state and interaction flow only. It may not infer TenantContext, business access, administrative authority or provider truth from UI state.

## Layer 2 - Delivery and protocol edge

Current baseline:

- public semantic REST API edge;
- Web Console delivery edge;
- OIDC/OAuth 2.0 protocol edge;
- curated public integration-event delivery edge.

SAML protocol support and inbound SCIM APIs are high-priority next-phase protocol surfaces, not part of the current baseline merely because outbound/provider adapters may already speak SAML or SCIM.

Layer 2 performs transport/protocol concerns such as authentication envelope handling, request validation, protocol error mapping, HTTP semantics, OIDC discovery/JWKS, authorization/token endpoints, webhook delivery and protocol data minimization. It does not own Layer-4 business state.

## Layer 3 - Application and use-case orchestration

Layer 3 coordinates semantic use cases. It may:

- establish trusted server-derived operation/Tenant context;
- perform operation-time Administration authorization;
- orchestrate typed commands and queries across capability ports;
- apply causal idempotency and optimistic-concurrency preconditions at application boundaries;
- coordinate durable domain-owned processes through semantic commands/facts;
- map domain/application outcomes into Layer-2 responses.

Layer 3 does not become a second domain authority. It must not use direct cross-capability repository/table mutation, generic orchestration state in place of domain process state, or UI/request state as authority.

Authentication/IdP/SSO is a composed product/application service spanning Layers 1-3 and consuming Layer-4 owners plus Layer-6 protocol/session mechanics. It is not a tenth Layer-4 capability.

## Layer 4 - Canonical business capabilities

The canonical v3 Layer-4 capabilities are:

1. **Tenancy** - deployment-owned Tenant identity and lifecycle; hard isolation/customer realm.
2. **Identity** - governed subjects, typed profiles, Organizations/relationships, source/canonical semantics, Principals and Identity lifecycle.
3. **Catalog** - Applications, ApplicationTargets, Entitlements, Roles/RoleVersions, SSO relying-party registration and deployment-owned platform application offerings/bindings.
4. **Access** - AccessAssignment business intent, EffectiveAccess and desired technical-state projections, lifecycle reduction and terminal-target reduction.
5. **Governance** - requests, approvals, reviews, policies, risk/SoD, exceptions and findings.
6. **Credential** - Principal-bound credential/authenticator metadata, lifecycle and rotation process state.
7. **Integration** - ConnectorInstance/Binding, provisioning/reconciliation process semantics, provider observations and typed observation mappings.
8. **Administration** - IAM control-plane roles/permissions/grants/scopes, owner authority, delegation, elevation and break-glass.
9. **Audit** - immutable Audit evidence and evidence lifecycle, with explicit tenant/deployment evidence scope.

Only the owning capability mutates its authoritative state. Cross-capability collaboration uses typed semantic commands, queries and facts with stable IDs.

### Critical Layer-4 reconciliations

- Tenancy is a first-class capability under ADR-0051; Platform no longer owns Tenant business lifecycle.
- Authentication/IdP/SSO remains composed rather than becoming a Layer-4 capability.
- Catalog exposes semantic target availability; terminal target retirement drives Access-owned `AccessTargetReduction` under ADR-0052.
- Integration owns `PrincipalObservationMapping` technical interpretation under ADR-0050 while Identity remains sole owner of `Principal` authority.
- Audit uses explicit `AuditEvidenceScope = TENANT(ownerTenantId) | DEPLOYMENT` under ADR-0053.

## Layer 5 - External adapters

Layer 5 is provider-specific anti-corruption/execution infrastructure. The agreed adapter families are:

- **5A Identity source / HR / directory adapters**;
- **5B SaaS / directory provisioning adapters**;
- **5C Cloud / infrastructure adapters**;
- **5D External IdP / federation adapters**;
- **5E Connector runtime / remote-worker adapters**;
- **5F Secret-provider adapters**;
- **5G Notification adapters**;
- **5H Evidence / SIEM / artifact-store adapters**.

Layer 5 translates typed internal contracts into provider-specific protocols, schemas, errors and normalized results. It never owns business authority. Provider-native data remains observation or technical provenance until an owning capability explicitly promotes/maps it.

External/provider calls never execute inside the authoritative Layer-4 transaction that commits business state.

## Layer 6 - Platform and infrastructure

Layer 6 provides replaceable technical mechanics. It must never regain business ownership simply because it hosts persistence, runtime or delivery.

### 6A Persistence and transaction infrastructure

Provides capability-owned persistence mechanics, local authoritative transactions, optimistic revision support, transactional outbox/idempotency storage and provider-neutral transaction boundaries.

Tenancy may have Tenancy-owned persistence like any other capability; physical schemas/modules remain implementation mappings rather than capability definitions.

Cross-capability business mutation never occurs through shared repositories/tables or cascade delete.

### 6B Messaging, outbox and reliable delivery

Provides atomic state-plus-outbox capture, at-least-once publication/consumption, optional inbox/deduplication, correlation/causation propagation and retry-safe delivery.

Internal facts are triggers to re-read current owner state. Public integration events remain separately curated/versioned; transport does not own domain meaning.

### 6C Scheduler, durable work, leases and retry

Provides clocks/timers, due-work discovery, leases/claims, fencing, retry/backoff and worker-delivery mechanics for durable owner-defined processes.

Business state machines remain in Tenancy, Identity, Access, Governance, Credential, Integration, Administration or Audit. Scheduled work may carry only bounded versioned technical command metadata/references; it is not a generic JSON domain model and must not contain secret/private material.

### 6D Cache, projection and read infrastructure

Provides rebuildable projections, bounded read models, search/index/cache support and invalidation/checkpoint mechanics for convenience and scale.

Projection lag never redefines authoritative lifecycle/access/security semantics. Rebuild may use authoritative state and/or sufficient retained facts/checkpoints; Wyrmgate does not require event sourcing.

### 6E Session, token and protocol technical state

Provides browser-session, authorization-code and token-protocol technical state for the composed Authentication/IdP service.

Session/code/token records are security-sensitive technical state, not a parallel Identity/Principal/Access/Admin authority model. Security-sensitive operations revalidate current owner state.

### 6F Cryptography, signing and key infrastructure

Provides cryptographic primitives, signing/verification, JWKS/public-key publication and key rotation through replaceable provider ports.

Private signing material remains behind the provider boundary and never enters ordinary APIs/events/audit/logs/tasks/errors. File-backed, KMS or HSM realizations are implementation choices.

### 6G Secret and configuration runtime

Provides typed configuration resolution and secret-reference/provider plumbing for runtime and adapters.

Secret values are resolved only at approved execution edges and remain excluded from ordinary domain persistence, events, Audit, logs, tasks and errors. Configuration metadata must not become an arbitrary ungoverned domain settings bag.

### 6H Observability and operational telemetry

Provides metrics, traces, health/status, operational logging and alerting/SLO instrumentation with correlation/causation and redaction.

Operational telemetry is not Audit evidence and `AuditRecord` is not an observability log. Retention/export vendor choices remain implementation/operations decisions unless separately accepted.

### 6I Runtime, deployment, availability and recovery

Provides replaceable runtime roles/topology, health/readiness, scaling, availability, backup/recovery and disaster-recovery mechanics.

API/WORKER/SCHEDULER/CONNECTOR_WORKER/ALL are deployment roles, not capabilities. Deployment topology must preserve capability ownership, at-least-once/replay-safe behavior and the accepted HA/DR objectives.

The detailed image numbered 86 contains an internal heading typo `Layer 6L`; its filename/content and this reconciled model correctly identify the area as Layer **6I**.

## Cross-layer invariants

- Trusted scope is explicit: `TENANT(TenantId)` or `DEPLOYMENT`; transport/UI selectors are not authority.
- Authoritative State, Observation, Evidence and Projection remain distinct.
- Governance/business state, temporal validity, technical fulfillment and provider observation remain separate dimensions.
- Privilege increase fails closed when mandatory governance/admission evaluation is unavailable.
- Authoritative privilege reduction/revocation is not blocked by unrelated evaluator failure.
- Partial import/reconciliation never implies destructive absence.
- Commands request actions; facts describe committed semantic changes.
- Internal domain facts are not automatically public integration events.
- Async delivery is at least once and consumers tolerate retry, duplication, replay and out-of-order arrival.
- Secrets/private credential material never enters ordinary APIs, events, Audit, logs, work payloads or errors.
- No framework, ORM, database, broker, cloud or deployment convenience may redefine capability ownership.
