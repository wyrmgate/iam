# ADR-0020: Credential authority, external secret references and durable rotation

Status: Accepted

## Context

Wyrmgate IAM v0.3 defines Credential as a first-class Credential-capability concept that
belongs to exactly one Identity-owned Principal. IAM governs credential metadata,
lifecycle, risk and rotation, while raw secret/private material remains outside ordinary
IAM domain persistence and interfaces.

The formal specifications require:

- Credential lifecycle/rotation without exposing raw secret/private material;
- external SecretProvider ownership of secret values;
- immediate unsafe semantics for compromised credentials;
- bounded routine-rotation overlap where policy permits;
- durable resumable rotation separate from credential lifecycle;
- no secret/private material in ordinary APIs, events, audit, logs, task payloads or
  errors.

The formal baseline names CredentialBinding but does not yet define its concrete provider
or consumer semantics. Inventing that relationship before a concrete requirement exists
would create an arbitrary cross-capability contract.

## Decision

### Credential authority

Credential owns authoritative authentication-instrument metadata.

A Credential belongs to exactly one Principal by stable Principal ID. Principal remains
Identity-owned and is validated through an Identity semantic query; Credential never reads
Identity persistence.

Initial Credential kinds are:

- PASSWORD;
- API_KEY;
- SSH_KEY;
- CERTIFICATE;
- OAUTH_CLIENT_SECRET.

This enum describes governed credential metadata only. It does not imply IAM stores the
corresponding private material.

### External secret reference

Credential stores one opaque SecretReference:

- providerType;
- referenceKey.

The reference identifies material held by an external secret-provider capability. No raw
password, API key value, private key, certificate private key, client secret, token, or
equivalent material exists in the Credential domain model or Credential persistence.

The first slice does not implement secret retrieval. A later SecretProvider adapter may
resolve references only at an explicitly authorized execution edge.

### Credential lifecycle

Initial authoritative lifecycle:

```text
SCHEDULED -> ACTIVE -> REVOKED
SCHEDULED -> EXPIRED
ACTIVE    -> EXPIRED
SCHEDULED -> COMPROMISED
ACTIVE    -> COMPROMISED
```

Credential is semantically effective only while:

- lifecycle = ACTIVE;
- validFrom is absent or <= now;
- validUntil is absent or > now.

COMPROMISED is immediately unsafe even if provider-side revocation has not completed.
REVOKED is an authoritative Credential decision; any provider residue is observation /
fulfillment state, not Credential lifecycle.

Time validity is semantic. Delayed scheduled work cannot extend a credential beyond
validUntil or make a future credential active before validFrom.

### Technical validity materialization

Platform scheduled-work infrastructure may materialize:

- SCHEDULED -> ACTIVE at validFrom;
- SCHEDULED/ACTIVE -> EXPIRED at/after validUntil.

Scheduled handlers re-read current Credential state and are idempotent/revision-aware.
They do not own Credential business state.

### Durable rotation

CredentialRotation is a Credential-owned durable process and is not a Credential lifecycle
state.

Initial normal progression:

```text
PLANNED
 -> CREATING_REPLACEMENT
 -> DISTRIBUTING
 -> VERIFYING
 -> CUTOVER_COMPLETE
 -> REVOKING_OLD
 -> COMPLETED
```

Failure exits are:

- FAILED;
- MANUAL_REQUIRED;
- FAILED_REMEDIATION.

A rotation records:

- old Credential ID;
- replacement Credential ID once one exists;
- initiator Identity ID;
- process state and optimistic revision;
- checkpoint/failure code;
- timestamps.

No secret/private material is stored in rotation state.

Routine rotation begins only from an effective ACTIVE credential. Replacement Credential
must belong to the same Principal as the old credential. Replacement creation, external
distribution, verification and provider revocation are later typed execution ports; the
first slice establishes durable authoritative/process state and invariant-preserving
transitions without pretending provider work occurred.

The old credential remains effective only according to its own lifecycle/validity, not
because the rotation process is incomplete.

Compromise handling is separate from routine rotation. A known-compromised Credential may
be made unsafe immediately even if that causes service interruption.

### CredentialBinding

CredentialBinding remains a canonical Credential concept, but its concrete first
relationship is deferred until a provider/consumer binding requirement is accepted.
This slice does not create a generic JSON binding table or guess at provider semantics.

## Consequences

- IAM can govern Credential metadata and rotation without becoming a secret vault.
- Secret/private material is structurally excluded from the Credential persistence model.
- Principal ownership remains explicit and cross-capability-safe.
- Credential lifecycle, rotation progress and external/provider fulfillment remain
  separate dimensions.
- Compromise semantics fail safe.
- Routine rotation can later integrate with SecretProvider and Integration without
  redefining Credential authority.
- CredentialBinding remains intentionally unresolved at implementation detail instead of
  becoming a generic EAV relationship.

## Deferred

- concrete CredentialBinding shape;
- public Credential API;
- SecretProvider implementation/retrieval;
- provider-native credential create/revoke execution;
- rotation policy/scheduling;
- observed credential reconciliation;
- CredentialRequirementQuery consumers;
- Integration credential provisioning work classes;
- administrative permission surface.
