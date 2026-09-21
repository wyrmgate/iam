# ADR-0014: Remote connector-worker protocol and compatibility

Status: Accepted

## Context

ADR-0004 requires connector capabilities to remain semantic and transport-neutral so the same connector contract can execute in-process or through a remote worker. The formal v0.2 SAD already identifies a connector-worker runtime role for provider calls, provisioning, reconciliation and source import. OD-004 remains open specifically because the remote-worker wire protocol, trust boundary, leasing semantics and version compatibility have not yet been defined.

Remote workers may run in networks that cannot accept inbound connections from Wyrmgate. They may also be restarted, duplicated, partitioned or delayed while provider-side work continues. The protocol therefore cannot rely on exactly-once delivery, a long-lived in-memory session, server-to-worker reachability or direct access to Wyrmgate persistence.

The protocol must preserve existing invariants:

- Integration remains authoritative for ConnectorInstance/ConnectorBinding, ProvisioningJob/Task, ReconciliationRun and provider observations;
- workers execute provider-facing technical work but never create governance intent;
- stale desired-state work is revalidated and superseded/no-ops;
- partial reconciliation/import cannot imply destructive absence;
- raw secret/private credential material is excluded from ordinary task/result payloads, logs, audit and errors;
- external provider calls execute outside authoritative Wyrmgate transactions;
- duplicate, replay and out-of-order delivery are expected.

## Decision

### Protocol role and direction

The first remote connector-worker protocol is a **worker-initiated pull protocol over HTTPS with JSON messages**.

A remote worker opens outbound connections to Wyrmgate, establishes a short-lived protocol session, claims bounded leased work, optionally appends bounded observation batches, renews leases when necessary, and completes work with normalized results.

Wyrmgate does not push provider work directly to worker network endpoints in v1. A broker is not required for v1. Remote workers never connect to the Wyrmgate database and never mutate Integration tables directly.

The protocol is an internal Integration execution interface, not a public IAM administration API and not a cross-capability ownership boundary.

### Worker authentication and authorization

Every protocol request uses TLS and an authenticated connector-worker runtime subject.

The initial HTTP binding uses bearer authentication with a dedicated connector-worker audience. A validated external issuer + subject identifies only the external runtime principal. Integration resolves that subject server-side to an enabled worker registration and its allowed execution scope.

Token roles, scopes, tenant claims, connector-binding IDs, worker IDs or similar caller-controlled claims do not become Wyrmgate execution authority.

The worker registration determines, at minimum:

- stable worker ID;
- enabled/disabled state;
- external authentication subject binding;
- allowed tenant / ConnectorBinding execution scope;
- protocol versions the server permits for that registration;
- connector runtime descriptors/capabilities permitted for the worker.

Disabling/revoking the worker registration prevents new claims. Existing leases remain bounded by lease expiry/fencing and do not become permanent authority.

The exact identity-provider product is replaceable deployment configuration. Mutual TLS or another transport authentication adapter may be added later without changing Integration work semantics.

### Protocol session and version negotiation

Protocol v1 is rooted at `/internal/connector-worker/v1`.

A worker establishes a session by advertising:

- supported protocol major versions;
- a worker instance identifier used for diagnostics only;
- supported connector runtime descriptors/versions;
- supported semantic work capabilities.

The authenticated server-side worker registration remains authoritative; the caller cannot widen authorization by advertising more capabilities.

The server selects one common protocol major and returns:

- opaque session ID;
- selected protocol version;
- server limits such as maximum claim size, long-poll duration and observation batch size;
- lease duration / renewal guidance;
- accepted connector runtime compatibility set.

If there is no compatible protocol/connector runtime combination, the server fails the session explicitly rather than assigning incompatible work.

Session IDs are correlation/negotiation state, not bearer credentials. Every subsequent request is still authenticated and the server verifies that the session belongs to the authenticated worker registration.

### Leased work and fencing

Workers claim bounded work from the server. A leased work item carries:

- work ID and causal operation ID;
- lease ID;
- monotonically increasing lease epoch/fencing value;
- lease expiry time;
- tenant ID and ConnectorBinding reference selected by the server;
- semantic work kind;
- connector contract/schema identity and version;
- desired/source/run revision or checkpoint context where applicable;
- stable idempotency identity;
- correlation and causation IDs;
- bounded, typed/provider-edge payload.

A re-claim after lease expiry increments the lease epoch. Lease renewals and completions must present the current lease ID + epoch. Results from a stale epoch are rejected and cannot overwrite a newer attempt/result.

Lease expiry means another worker may receive the same causal work. Therefore provider operations and worker-side execution must tolerate duplicate delivery. Stable operation/idempotency identity is preserved across lease attempts.

The worker does not decide authoritative ProvisioningTask or ReconciliationRun state. It reports execution facts/results; Integration re-reads current state and applies the domain/process transition.

### Work payload model

The v1 work envelope is strongly typed and versioned. It distinguishes semantic work classes such as:

- provisioning/provider mutation;
- reconciliation/provider discovery;
- identity source discovery/import adapter work;
- credential-related provider work only when the secret boundary can be preserved.

Provider-specific request material may appear only inside a schema-governed connector-edge payload identified by connector contract/schema ID and version. That JSON is an adapter boundary, not canonical IAM semantics and is never directly exposed as a governance/policy model.

Ordinary work payloads must not contain raw provider passwords, access/refresh tokens, private keys, generated credential secrets or other private credential material.

Connector/provider credentials are resolved through approved secret infrastructure available to the execution runtime by opaque reference/configuration. If an operation would require raw secret material to transit this ordinary protocol, that operation is unsupported in v1 until a dedicated secret-delivery boundary is designed and accepted.

### Completion and failure reporting

Completion reports are normalized and data-minimized. They may include:

- causal operation/work IDs;
- current lease ID + epoch;
- normalized outcome/failure category;
- normalized provider error code;
- provider request/correlation ID where non-secret;
- retry-after guidance;
- provider object IDs/versions/ETags where non-secret;
- bounded typed result metadata;
- correlation/causation IDs.

Raw provider response bodies, stack traces and secret material are not ordinary result/error fields.

Duplicate identical completion is idempotent. A conflicting completion for the same operation/lease generation is rejected and becomes explicit remediation/evidence rather than last-writer-wins.

Worker-reported retryability is input to Integration handling, not authority to rewrite governance state. Integration owns final process-state transition and may classify stale work as SUPERSEDED/SKIPPED after revalidation.

### Reconciliation and source-observation batching

Discovery work may append bounded observation batches while a lease is current.

Each batch carries a stable batch ID/sequence and is idempotently accepted. Observations are normalized/provider-edge facts with provenance; raw native payload retention is separately bounded and secret-filtered.

The worker may report discovery coverage/checkpoint evidence, but Integration remains authoritative for effective ReconciliationRun/source-import completeness.

`COMPLETE` absence semantics are accepted only after Integration verifies that:

- the claimed scope/object class is the expected run scope;
- connector capabilities support trustworthy absence detection;
- binding/configuration/runtime versions match;
- no checkpoint/lease/run failure invalidates coverage.

A partial/unknown run may contribute positive observations but never authorizes destructive absence inference.

### Compatibility rules

Protocol major version is explicit. v1 message schemas are closed compatibility surfaces.

Within one protocol major:

- existing message field meaning/type/cardinality is not changed;
- an existing required field is not removed/renamed;
- new semantic work kinds are assigned only when the worker explicitly advertised the corresponding capability/schema version;
- connector-specific payload compatibility is controlled by connector contract/schema version, independently from protocol major version;
- a worker is never sent a connector payload version it did not advertise.

An incompatible wire-shape or core lease/session semantic change creates a new protocol major (for example v2). Wyrmgate may serve multiple protocol majors concurrently during a controlled migration.

Retry/replay of an already leased operation remains on the protocol/work schema selected for that causal operation; it is not silently upgraded mid-attempt.

### Initial endpoint model

The v1 machine-readable contract defines the following internal operations:

- create/establish worker session;
- claim bounded leased work, optionally with bounded long-poll;
- renew a current lease;
- append idempotent observation batches for discovery work;
- complete a leased work item with a normalized typed result.

These endpoint definitions are a wire contract. They do not imply that ProvisioningJob/Task/ReconciliationRun runtime persistence is already implemented.

## Consequences

- OD-004 is resolved at the architecture/interface-contract level without introducing a broker, remote database access or server-push connectivity.
- Remote-worker failures/restarts remain compatible with at-least-once delivery and stale-worker fencing.
- Worker authentication does not bypass server-side Integration authorization or tenant/ConnectorBinding scope.
- Provider-specific JSON stays at the connector adapter edge and is governed by explicit schema/version negotiation.
- Secret/private material remains outside ordinary task/result traffic.
- Reconciliation completeness remains an Integration decision, so partial remote discovery cannot cause destructive absence behavior.
- Protocol version, connector runtime version and connector payload schema version are separate compatibility dimensions.
- Runtime endpoint implementation follows later with the first Integration-owned provisioning/reconciliation aggregates and persistence slice; the wire contract must not be changed merely to fit implementation convenience.
- The next formal Integration/SAD/Security/RTM revision must incorporate this decision and mark OD-004 resolved.
