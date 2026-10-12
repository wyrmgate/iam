# Administration control-plane contracts

The Administration authority API exposes the existing ADR-0032 runtime through semantic control-plane resources. It does not introduce a generic administrative CRUD model and does not make token roles or scopes authoritative IAM permissions.

## Public resources

The checked-in `administration-v1.json` contract exposes:

- `/api/v1/administrative-roles` for role creation, read/list, metadata rename, and explicit permission add/remove operations.
- `/api/v1/administrative-grants` for bounded direct grant creation, read/list, and explicit revocation.
- `/api/v1/administrative-delegations` for single-hop source-dependent delegation, read/list, and explicit revocation.
- `/api/v1/administrative-elevations` for finite elevation request, approval request, approval application, cancellation, and revocation.
- `/api/v1/administrative-break-glass-operations` for assurance-gated emergency activation, read/list, and revocation.

The companion `administration-current-authority-v1.json` contract exposes the authenticated self-read projection at `/api/v1/current-administrative-authority`.

Every surface is tenant-scoped through the authenticated control-plane actor context. Tenant is never accepted from a request body.

## Current administrative authority projection

`GET /api/v1/current-administrative-authority` returns a point-in-time Administration-owned read projection for the current trusted governed actor. It exists so the console can avoid presenting obviously unavailable navigation/actions without inventing authorization from OAuth/OIDC claims, browser state, business Roles, provider groups, or tenant hints.

The response contains only:

- the server-resolved tenant ID and governed actor Identity ID;
- whether the actor is currently administratively eligible;
- the evaluation timestamp;
- currently effective semantic permission + typed scope entries, with authority source, source ID and validity interval.

The projection preserves `GLOBAL`, `SPECIFIC_RESOURCE` and `CANONICAL_ATTRIBUTE_CLASSIFICATION` semantics. Other modeled scope kinds remain omitted until their operation-time hierarchy/population evaluators are concrete; returning them as effective would overstate authority.

Direct grants, delegations, elevations and break-glass are re-evaluated from current Administration state. Delegation remains source-dependent, temporary authority disappears at semantic expiry/revocation, role permission changes are reflected by the current role-permission join, actor suspension/ineligibility returns an empty projection, and break-glass additionally requires current provider-neutral assurance. The endpoint returns `Cache-Control: no-store` and the browser must not persist the result as durable session authority.

This projection is **not an authorization decision**. Every authoritative operation still performs normal operation-time Administration authorization. A visible/enabled console action may still receive `403` after any concurrent state, policy, scope, assurance or validity change, and that server denial is final.

The endpoint requires authenticated control-plane actor resolution but does not require `administration:manage-authorization`: an operator may inspect only their own derived authority without gaining permission to manage authority resources. There is no endpoint to ask for another actor's projection.

Provider-native claims, bearer tokens, session identifiers, authentication assertions, raw reason/evidence fields, private credential material and secrets are structurally absent.

## Authorization

All operations remain default deny. The bounded authority-management surface uses the existing semantic `administration:manage-authorization` operation permission, while ADR-0032 grantability and delegability ceilings constrain what authority may actually be created.

Possessing `administration:manage-authorization` is not a superuser bypass. Direct grants require one current grantable basis; delegation requires one current delegable direct source; elevation revalidates its current grantable basis and approval context; break-glass additionally requires configured policy and current provider-neutral STRONG assurance.

The unchanged `INITIAL_TENANT_ADMIN` permission membership is not expanded by this API.

## Concurrency and retry

Externally retryable mutations require `Idempotency-Key`. Mutable authoritative operations require a strong `If-Match: "rev-N"` ETag.

Ordinary Administration mutations register idempotency in the same Administration transaction as the authoritative change. Elevation approval/application intentionally do not hold an Administration transaction across the Governance semantic call. Governance ApprovalCase creation is subject-idempotent, and the HTTP layer can recover incomplete idempotency completion from current elevation state.

Break-glass activation uses a retry-stable preselected operation ID derived from tenant, operation namespace, and idempotency key. The domain service accepts that ID through an internal overload while preserving the existing policy, assurance, evidence, and audit validation path.

The current-authority projection is a read and uses neither ETag nor idempotency keys. Its no-store response is intentionally point-in-time and should be re-read when the console needs fresh action/navigation optimization.

## Pagination

Lists use deterministic `createdAt + id` continuation and integrity-protected signed cursors. Cursors are tenant- and collection-kind-bound and expire according to the common API cursor lifetime.

The current bounded Administration queries do not return a separate has-more marker. A page whose size exactly equals the requested limit can therefore return a continuation that leads to an empty final page. This does not skip or duplicate resources and can be refined later without changing cursor semantics.

The current-authority self projection is intentionally not paginated. It is bounded to the actor's current effective authority sources and is not a general authority-search API.

## Elevation boundary

Governance owns ApprovalCase and ApprovalDecision only. `:request-approval` invokes the typed Governance boundary outside the Administration authoritative transaction. `:apply` reads immutable approval evidence and revalidates current Administration context before authority becomes ACTIVE.

Approval is never direct Administration mutation authority.

## Break-glass boundary

Break-glass is distinct from GLOBAL grants and ordinary elevation. Activation requires exact governed actor self-use, a finite policy-bounded validity window, explicit reason and incident/reference evidence, configured policy approval, and current STRONG provider-neutral assurance.

The public resource deliberately excludes reason text, provider-native authentication claims, activation assurance timestamps, and internal policy detail. The owning Administration record retains that evidence. The incident/reference identifier is surfaced for operational correlation.

Notification and post-use-review obligations remain durable Administration process evidence and never affect authority validity.

ADR-0033 fixes the completion contract. POST_USE_REVIEW is implemented as immutable Administration-owned evidence and becomes completable only after revocation or semantic expiry. The public API exposes the explicit semantic operation `POST /api/v1/administrative-break-glass-operations/{id}:complete-review`, requiring causal idempotency, a strong current revision ETag, current `administration:manage-authorization`, reviewer != break-glass actor, a typed outcome and bounded review summary. Completion atomically appends review evidence, completes the POST_USE_REVIEW obligation and advances the break-glass process revision; arbitrary obligation-state PATCH remains prohibited. SECURITY_NOTIFICATION concrete signed-HTTPS delivery is implemented as internal durable process work rather than a public mutable resource. Operators configure one HTTPS endpoint and secret; delivery uses the stable obligation ID for receiver deduplication, bounded leased retries, normalized error evidence and MANUAL_REQUIRED terminal state.

## Audit

Every public role, grant, delegation, and elevation mutation appends a data-minimized AuditRecord after the authoritative operation succeeds or fails. Break-glass reuses its domain-level AuditRecord producer so the public transport layer does not duplicate evidence.

Audit contains the actual governed actor, exact semantic action, known target resource, outcome, time, and request correlation. It does not contain reason text, incident details, full permission sets, request payloads, token or assurance claims, exceptions, or provider-native identity data. Audit failure never rewrites an already completed Administration result.

The current-authority read is an ephemeral derived projection for UI optimization rather than new authoritative state; it does not create or mutate Administration authority.
