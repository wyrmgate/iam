# ADR-0012: Integrity-protected API continuation cursors

Status: Accepted

## Context

ADR-0008 and the formal SRS require deterministic cursor pagination for large mutable collections. The first OD-003 Identity runtime slice implements deterministic continuation positions and documents Identity and canonical-attribute cursors as opaque values that clients must not construct.

The current `IdentityCursorCodec` encodes the continuation tuple with URL-safe Base64. That preserves transport opacity but does not provide integrity or authenticity. A client that learns the tuple format can manufacture a syntactically valid cursor.

Issue #62 requires the stronger runtime property that continuation cursors cannot be trivially manufactured or modified without server detection.

Wyrmgate already has a platform `SigningKeyProvider` abstraction that keeps private signing material behind an adapter and exposes the current public key. The current port is intentionally minimal: it can sign with the current key, but it cannot resolve verification keys by key ID or preserve a verification set across key rotation. It is also not yet wired as a general runtime application-signing service.

Adding an unrelated cursor-only HMAC secret would create a second application secret/key lifecycle without an architectural reason. Reusing the current signing port without rotation semantics would make previously issued cursors invalid whenever the signing key changes and would silently broaden an abstraction originally described for authorization/OIDC signing.

## Decision

Continuation cursors that cross the public API boundary will be integrity-protected, versioned, and context-bound.

The next cursor format will use four URL-safe segments:

```text
v2.<keyId>.<payload>.<signature>
```

where:

- `v2` is the cursor envelope version;
- `keyId` identifies the verification key;
- `payload` is a deterministic, URL-safe encoding of the semantic cursor contents;
- `signature` authenticates the exact version, key ID, and payload bytes.

The payload is not encrypted. Cursor confidentiality is not a security boundary; public cursor payloads must therefore contain only data safe to expose in opaque transport form.

The signed payload must include enough context to prevent cross-context replay:

- cursor kind/resource family;
- tenant ID;
- deterministic continuation tuple;
- for canonical-attribute pagination, the Identity resource ID whose collection is being traversed;
- a payload/schema version.

Future filtered collection cursors must additionally bind any query/filter/sort inputs that affect continuation semantics.

Page-size changes do not need to invalidate a cursor unless a future contract makes page size part of query semantics.

## Signing boundary

Do not introduce a cursor-specific HMAC secret.

Generalize the platform signing boundary so runtime code can:

- obtain the active signing key ID and algorithm;
- sign a payload without exposing private key material;
- resolve an allowed public verification key by key ID;
- retain retired public verification keys for a bounded verification period.

Private keys remain non-exportable through the application port. File-backed local/DEV adapters may continue to load private material internally; production-capable adapters may use KMS/HSM/Vault-backed implementations.

The generalized port is a platform implementation boundary. It does not change IAM capability ownership or make cryptographic key storage part of Identity.

## Rotation and compatibility

Key rotation must not invalidate every outstanding cursor immediately.

A cursor signed with a previous key remains verifiable while that key remains in the configured verification set. The operational retention period must be at least as long as the maximum supported cursor lifetime.

The first implementation should define a bounded cursor lifetime and reject cursors outside that lifetime with the normal semantic invalid-cursor response. This prevents indefinite retention of retired verification keys.

If the first implementation cannot establish a bounded lifetime and retired-key verification safely, v2 cursor issuance must not be enabled.

Existing unsigned v1 cursors are an implementation-transition concern, not a permanent compatibility contract. Before enabling v2 in an environment, the implementation must explicitly choose one controlled transition:

1. accept v1 for a short, documented migration window while issuing only v2; or
2. invalidate pre-deployment cursors at deployment time if the API contract explicitly permits cursor invalidation across deployments.

The implementation must not silently accept unsigned v1 indefinitely after v2 protection is enabled.

## Validation and errors

Cursor verification occurs before converting payload fields into application query positions.

Reject as one semantic invalid/malformed cursor condition:

- unknown envelope versions;
- unknown or retired-outside-window key IDs;
- invalid signature;
- malformed payload;
- expired cursor;
- tenant mismatch;
- resource/cursor-kind mismatch;
- canonical collection Identity mismatch;
- unsupported payload version.

Public errors must not reveal which cryptographic check failed, key material, provider/KMS detail, or stack traces.

Verification must use constant-time-safe cryptographic library primitives rather than application string comparison of signatures.

## Determinism and pagination semantics

Signing does not change continuation ordering.

Identity listing remains ordered by immutable `createdAt` then `id`. Canonical-attribute listing remains ordered by stable attribute `key` then `definitionId`.

The cursor is a transport token for an application continuation position, not a new authoritative data model or projection.

## Consequences

- Clients cannot manufacture or alter continuation positions without detection.
- Tenant and resource context cannot be switched by reusing a valid token in another collection.
- Cursor signing reuses one governed application-signing lifecycle rather than introducing a cursor-only secret.
- The platform signing port gains verification/rotation responsibilities and must remain private-key-safe.
- Operational key rotation and cursor lifetime become explicit runtime contracts.
- Cursor payloads remain data-minimized because signing provides integrity, not confidentiality.
- The Identity capability remains unaware of cryptographic implementation details; the HTTP/API adapter owns cursor transport encoding and verification.

## Alternatives considered

### Keep Base64-only opaque cursors

Rejected for the stronger OD-003 verification requirement because format knowledge permits valid-looking cursor construction and modification.

### Cursor-specific HMAC secret

Rejected because it creates an isolated secret/key lifecycle and rotation problem for one transport concern.

### Server-side random cursor handles

Not selected for the first implementation. It would require durable cursor state, expiry/cleanup, multi-instance semantics, and additional persistence/availability behavior for a problem that can be solved statelessly.

### Sign with only the current key

Rejected because rotation would invalidate all outstanding cursors and the current signing port cannot verify older key IDs.

## Implementation checkpoint

After this ADR is accepted, implementation should:

1. evolve the platform signing port to verification-by-key-ID with retained public verification keys;
2. add explicit runtime configuration for cursor lifetime and verification-key retention;
3. implement v2 signed/context-bound Identity and canonical-attribute cursor codecs;
4. add negative tests for tampering, manufactured payloads, cross-tenant reuse, cross-resource reuse, expiry, unknown key ID, and rotation;
5. update the Identity OpenAPI/living contract with transition/lifetime behavior;
6. close issue #62 only after exact-head CI verifies the runtime behavior.

No formal v0.3 checkpoint is required solely to implement this ADR. Fold it into the next controlled Integration/SAD/Security/RTM revision with the other post-v0.2 accepted amendments.
