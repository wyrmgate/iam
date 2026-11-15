# ADR-0065: Credential rotation plan cancellation and terminal safety

Status: Accepted

## Context

ADR-0020, ADR-0063 and ADR-0064 distinguish authoritative Credential lifecycle from durable rotation and provider execution. Administrative rotate currently creates an inert PLANNED rotation; the one-open-rotation invariant can indefinitely block a legitimate local password change. No generic external runner is enabled. A safe explicit way to abandon an unstarted plan is required; calling cancellation a provider failure or deleting history would misrepresent the outcome.

## Decision

- Add CANCELLED as a terminal CredentialRotation state, separate from FAILED, MANUAL_REQUIRED and FAILED_REMEDIATION. Only PLANNED with no replacement attached may be cancelled. Record completion time and revision, retain immutable history; cancellation does not mutate either Credential or assert provider cleanup.
- Expose a semantic cancel operation `POST /api/v1/credential-rotations/{rotationId}:cancel` with strong If-Match and causal Idempotency-Key, no request fields. Use existing credential:rotate authority scoped to the old Credential; do not expand permissions or allow generic status updates. Resolve old Credential from trusted tenant rotation; authorize before mutation and replay. Record minimized Audit outcome for the old Credential. Cancellation is valid even if the old Credential has become ineffective.
- Serialize cancellation and planning on the old Credential row; revision-guard cancellation against concurrent execution. A cancelled plan no longer occupies the one-open-rotation slot. Local password change may then proceed from an effective old Credential. Active or terminal processes cannot be cancelled or reset; post-start recovery remains a separately designed forward operation.
- Routine progression must not begin replacement or attach/distribute a replacement for an old Credential that is no longer effective. Before cutover, recheck both current old and replacement; before beginning old revocation, the replacement must still be effective. A source may become unsafe at any time independently of rotation. No routine transition may reactivate it. Completion still requires an ineffective old and effective replacement; once cutover has been reached, security reduction is never blocked by rotation state. Failed terminal history remains immutable. External remediation and non-local overlap policy stay deferred; local PASSWORD execution remains atomic with zero committed overlap.
- Add an additive migration to extend the rotation state/check constraints, terminal guard, and open-plan uniqueness index; do not rewrite historical rows or V7. Cancellation requires no new aggregate, generic provider execution or UI.

## Consequences

This closes the repository-level planning dead end and adds a bounded cancellation contract. It does not claim provider cancellation, cleanup, live DEV acceptance, or formal specification promotion.
