# Console Access golden page

Status: implemented on the Access golden-page feature branch; review before merge.

Route: `/access`

Owning capability: Access

## Operator purpose

The Access workspace answers:

> What access has IAM authorized, is it currently effective, and what action can I safely take next?

The page is investigation-and-action UI, not a database editor or provider provisioning console.

## Semantic views

`/access` is Assignments-first and keeps URL-addressable semantic state:

- `?view=assignments` — Access-owned authoritative `AccessAssignment` intent;
- `?view=effective` — Access-owned derived, rebuildable `EffectiveAccess` projection;
- `?view=policy` — Access-owned immutable lifecycle-access policy administration.

Selected assignment/effective IDs and meaningful filters remain in query state. Opaque continuation cursors remain local to the bounded collection session because signed cursors are tenant/filter/time-bound transport, not durable navigation state.

## Authority, projection, fulfillment and observation

The Console must preserve these independent meanings:

- `AccessAssignment` — authoritative business access intent;
- `EffectiveAccess` — derived read-only entitlement projection;
- desired provider state — derived technical state;
- provisioning task/attempt — Integration-owned fulfillment;
- provider observation — Integration-owned observation;
- Governance approval/evaluation — Governance evidence/authorization context;
- `AuditRecord` — Audit-owned immutable evidence.

`ACTIVE`, `AUTHORIZED`, or `APPLIED` never means provider fulfillment succeeded. Provider failure cannot visually or semantically restore revoked/suspended/cancelled Access authority.

The golden page deliberately does not expose assignment-correlated technical realization because there is no accepted bounded Integration correlation query. This remains a future Integration-owned read model, not an Access state field.

## Assignments collection

The Console uses `/api/v1/console/access/assignments`, a read-only bounded presentation composition over Access-owned authoritative queries. It does not introduce a new aggregate or mutation path.

Supported exact filter context:

- governed Identity;
- AccessAssignment lifecycle;
- target kind plus exact target;
- provenance;
- temporal category `CURRENT`, `FUTURE`, or `ENDED`.

Filtering occurs before keyset pagination. Assignment cursors are signed and bound to the complete filter context.

Temporal category is server-evaluated at the collection/detail read instant. It is presentation/query meaning derived from `validFrom`/`validUntil`, not another lifecycle state.

Human labels are bounded presentation enrichment only. The composition asks Identity/Catalog through their owning query services and independently checks the corresponding foreign read permission before disclosing a label. A missing/unauthorized label falls back to the stable ID; foreign capability persistence is never joined or mutated by Access.

## Bounded selectors

Manual grant uses bounded owner-backed selection:

- Identity prefix search through Identity-owned query semantics;
- active Role/Entitlement prefix search through a Catalog-owned read query;
- Identity-scoped Principal selection through the Identity-owned Principal query.

The browser never fetches all Identities, Roles, Entitlements, Principals, or AccessAssignments to manufacture a selector.

## Lifecycle operations

The UI exposes only semantic operations accepted by Access:

- ACTIVE -> Suspend current access;
- SUSPENDED + current temporal validity -> Resume suspended access;
- future SCHEDULED -> Cancel scheduled access;
- ACTIVE/SUSPENDED -> Revoke access.

There is no generic status control.

Every lifecycle mutation uses the selected resource ETag and one causal Idempotency-Key. The same key is retained for retry of the same confirmed operation. A 412 never auto-retries against a newly fetched revision; the operator must refresh and review current authoritative state.

Cancel/Revoke are terminal. Suspend is privilege-reducing but non-terminal. Resume is privilege-increasing. Downstream provider work remains asynchronous and cannot rewrite the valid Access business decision.

## Manual grant

`Grant access` is a deliberate side-panel operation, not a permanent form.

The operator supplies:

- governed Identity;
- exactly one ROLE or ENTITLEMENT target;
- ANY or SPECIFIC Principal constraint;
- a specific Principal only for SPECIFIC;
- optional validity bounds.

The browser cannot select provenance or arbitrary lifecycle. `MANUAL` provenance and initial lifecycle semantics remain server-owned. A successful response is described as access authorization being recorded, not as provider provisioning success.

## EffectiveAccess explanation

EffectiveAccess is labelled derived/read-only and has no edit/revoke action.

The collection supports the accepted Identity filter and signed continuation semantics. Selected explanation detail uses:

- a bounded current semantic summary;
- a separately cursor-paged support query ordered by `(accessAssignmentId, pathHash)`;
- direct-vs-Role-derived path depth;
- ordered RoleVersion reference evidence;
- deterministic path hash as technical evidence.

Changing business intent always navigates to a supporting authoritative AccessAssignment.

## Lifecycle policy

Lifecycle policy remains an expert surface. The Console exposes only the accepted closed predicate kinds through ADR-0041:

- ALWAYS;
- typed SINGLE exact equality;
- typed MULTI CONTAINS;
- typed bounded CONTAINS_ANY / CONTAINS_ALL.

No arbitrary JSON, expression script, generic AND/OR/NOT, relationship predicate, set equality, count, or order semantics are introduced.

Activation creates an immutable successor version. The confirmation explicitly states that activation does not perform an immediate tenant-wide retroactive sweep and that privilege-increasing effects remain subject to current Governance evaluation/approval. Governance evidence never creates AccessAssignment authority directly.

The current policy editor retains a stable target ID field because the accepted policy activation contract itself is ID-based. Catalog bounded target search exists for Access selectors; a future shared searchable-field primitive may reuse it in the policy editor without changing the contract.

## Independent authorization and failure states

Assignments, selected detail, EffectiveAccess, lifecycle policy, Audit preview, foreign labels, and selectors are independently authorized.

A 403 renders `Not authorized`, never zero/empty. Audit or label lookup failure does not erase an AccessAssignment. If the Console cannot load the current administrative-authority projection, mutation controls fail conservative by remaining hidden; the server still re-authorizes every operation.

Known conflict/stale states are rendered semantically, including:

- 412 resource changed;
- `access_assignment_not_active`;
- `access_assignment_not_suspended`;
- `access_assignment_expired`;
- non-cancellable/non-revocable current state.

## Scale

The implementation assumes enterprise cardinality:

- no fetch-all;
- no client-wide filtering;
- no cursor walking to manufacture counts;
- no tenant-wide metrics without a dedicated server semantic;
- bounded option searches;
- bounded current-page label enrichment;
- keyset/cursor assignment and EffectiveAccess pagination;
- separately cursor-paged EffectiveAccess support evidence.

`V70__index_access_console_workbench.sql` adds query indexes for the accepted Access filter/search paths. These indexes add no authoritative state.

## Responsive and accessibility contract

Desktop keeps the established dark shell and presents a dense assignment/effective table plus selected-detail workbench. Mobile replaces dense tables with semantic cards/rows; it does not shrink the desktop table.

The page preserves:

- one h1 and meaningful h2/h3 hierarchy;
- semantic tables on desktop;
- explicit mobile field labels;
- native/shared form controls;
- accessible tabs and confirmations;
- visible focus and keyboard operation;
- no color-only state;
- polite loading/mutation announcements;
- textual equivalents for EffectiveAccess paths;
- reduced-motion behavior.

## Deferred by capability boundary

The following remain intentionally outside this golden-page implementation:

- assignment-correlated Integration fulfillment/provider-observation status until an accepted Integration-owned bounded query exists;
- tenant-wide Access attention metrics until their semantics and server computation exist;
- bulk Access mutation;
- tenant-wide retroactive lifecycle-policy reevaluation operation;
- generic rules-engine semantics;
- provider payload disclosure.

These are deferred rather than fabricated by the Console.
