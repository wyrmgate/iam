# Console `/requests` Golden Page — Requests & Approvals

Status: **Approved implementation target**  
Scope: **Wyrmgate Console `/requests` only**  
Baseline inspected: `main` at `13421b13ddc6d03ef1c159ae23d8eb234ea0f1c5`  

## 1. Purpose

`/requests` is the Governance workbench for self-service AccessRequest work and reusable ApprovalCase decisions assigned to the current governed actor.

It answers one primary question:

> **What access request or approval work needs my attention, and what can I safely do next?**

The page is not a generic Governance object browser, a provider-fulfillment dashboard, a tenant risk score, or an Administration permission surface.

The page preserves the accepted flow:

`request intent -> eligibility/policy/risk/SoD -> approval evidence -> Governance authorization -> Access application -> technical fulfillment`

Those dimensions are never collapsed into one generic status.

## 2. Canonical semantics

Governance owns AccessRequest, RequestItem, ApprovalCase, immutable approval plan/stage/approver snapshots, and append-only ApprovalDecision evidence.

The first public request slice is self-service only. The authenticated governed Identity is the requester and beneficiary. The browser must not infer or manufacture on-behalf authority.

Approval authority is assignment-based. Only an immutable resolved approver for the current ApprovalStage may decide. Administrative permission does not substitute for approver assignment, and self-approval remains denied by the accepted Governance rule.

RequestItem state remains authoritative Governance workflow state:

`DRAFT -> SUBMITTED -> EVALUATING -> PENDING_APPROVAL -> AUTHORIZED -> APPLIED`

with `DENIED`, `REJECTED`, `CANCELLED`, and `EXPIRED` exits where applicable.

`AUTHORIZED` means Governance requirements succeeded. `APPLIED` means Access accepted the authoritative AccessAssignment. Neither means provider provisioning succeeded.

The existing AccessRequest envelope state is not treated as a continuously materialized summary for this page. Where the page needs a request-level status, it uses a **read-only presentation status derived from the request's current RequestItems**. This projection does not mutate Governance state and does not become a new aggregate.

## 3. Page hierarchy

At approximately 1440 px the content order is:

1. page header, current Tenant context, bounded time window, Refresh;
2. attention summary;
3. work switcher and New Request action;
4. My Approval Work or My Requests primary workspace;
5. bounded request activity/status context.

At narrow/mobile widths the same semantic order is preserved. Tables become compact cards or horizontally bounded table containers; decision context stacks beneath the selected work item.

## 4. Header

**Title:** `Requests & Approvals`  
**Description:** `Request access for yourself and manage approval work assigned to you.`

Context:

- current server-derived Tenant;
- `Data as of <timestamp>`;
- selected activity window: `24h`, `7d`, or `30d`, default `7d`;
- Refresh.

The selected time window applies only to bounded historical/activity values. Current assigned approvals, current drafts, and current in-flight request state are not hidden by the window.

## 5. Attention summary

The page exposes four independently interpretable actor-scoped metrics:

| Metric | Meaning |
| --- | --- |
| My pending approvals | Current `PENDING` ApprovalCases for which the actor is a resolved approver on the current stage. |
| Draft requests | Current self-service AccessRequests still in DRAFT. |
| In-flight request items | Current actor-owned RequestItems in `SUBMITTED`, `EVALUATING`, `PENDING_APPROVAL`, or `AUTHORIZED`. |
| Applied request items | Actor-owned RequestItems that reached `APPLIED` inside the selected activity window. |

The page does not invent overdue counts, SLA due dates, risk bands, or provider-success counts.

Zero means an evaluated empty result. Data-access failure is shown as unavailable rather than zero.

## 6. My Approval Work

This is the default primary view when pending approval work exists or when entered from IAM Operations with `view=approvals`.

Default ordering is oldest first. The bounded server query returns actor-assigned current-stage work only.

Each row/card contains:

- ApprovalCase ID;
- semantic subject kind;
- related RequestItem and AccessRequest identifiers when the subject is access-request work;
- target kind and stable target ID for access-request work;
- current stage ordinal;
- created timestamp and relative age;
- one `Review` action.

The list does **not** contain inline Approve/Reject buttons. Selecting work opens a decision context surface.

### 6.1 Decision context

The decision surface re-reads the current ApprovalCase detail and current ETag before mutation. It exposes the immutable plan/stage/approver/decision evidence required to understand the decision, plus safe access-request subject context when available.

Actions:

- `Approve current stage`;
- `Reject current stage`;
- optional decision reason.

Mutations retain strong `If-Match` and causal `Idempotency-Key`. `412` is shown as stale state and requires refresh. A server `403` remains final. Rejection is consequence-styled because it terminates the current approval path, but approval and rejection remain Governance evidence/decision operations rather than direct Access mutations.

## 7. My Requests

This view lists only self-service requests belonging to the authenticated governed Identity. It is a bounded actor-scoped presentation read, not tenant-wide enumeration.

Each request summary contains:

- AccessRequest ID;
- created/updated timestamps;
- item count;
- derived presentation status;
- item-state composition;
- target kind/ID only when a concise unambiguous single-item summary is available.

Selecting a request opens request detail and RequestItem states. The UI labels the lifecycle boundary explicitly:

- evaluation/approval is Governance;
- `AUTHORIZED` is Governance authorization;
- `APPLIED` means Access accepted authoritative intent;
- provider fulfillment is separate and is not inferred here.

## 8. New Request

`New Request` opens a deliberate panel rather than a permanent top-of-page form.

The current accepted self-service contract does not require the user to type their own beneficiary Identity ID. The page uses the server-derived governed actor Identity as beneficiary.

Inputs:

- target kind: `ROLE` or `ENTITLEMENT`;
- stable target ID;
- principal constraint: `ANY` or `SPECIFIC`;
- specific Principal ID only when `SPECIFIC`;
- optional `validFrom`;
- optional `validUntil`.

Creation produces a DRAFT. Submission is a separate explicit action: `Submit for Governance evaluation`.

The UI may offer local form presets such as Role request, Entitlement request, or Specific-principal request. These presets only populate the accepted typed request form. They are not persisted RequestTemplate resources and do not create a new IAM concept.

## 9. Activity visualization

The page may show one bounded activity chart using RequestItem state changes/updates inside the selected window.

Its purpose is operational orientation only: show whether the actor's request work is accumulating in evaluation/approval/application states over time.

The chart never becomes a risk score or provider health score. Missing/unavailable data is not converted to zero. An accessible data representation is required for any rendered chart.

## 10. Read-model/interface boundary

The page requires a bounded, read-only, actor-scoped Governance presentation query. Conceptually:

`RequestsAndApprovalsPageQuery`

It may read Governance-owned request/approval persistence because Governance owns that state. It must not join foreign capability tables to manufacture display names or provider status.

The query returns bounded counts, approval work, recent self-service request summaries, and bounded activity buckets. Human-friendly foreign-capability labels may be added only through explicit semantic queries from the owning capability; the browser must not perform N+1 cursor walks to manufacture them.

The Console transport is a presentation endpoint under `/api/v1/console`. It is not new authoritative IAM state and does not redefine the public Governance mutation API.

## 11. URL state

The page honors IAM Operations drill-down state:

- `/requests?view=approvals&approvalCaseId=<id>` selects approval work and opens the current decision context;
- `/requests?view=approvals&sort=oldest|newest` changes bounded approval ordering;
- `/requests?view=requests&requestId=<id>` selects My Requests and opens that request.

Query state is navigation state only; it never carries authorization or secret material.

## 12. Failure and authorization states

The page deliberately handles:

- loading;
- empty current work;
- unavailable presentation data;
- authentication failure;
- not authorized;
- not found;
- stale revision;
- semantic conflict;
- mandatory evaluator unavailable;
- idempotency conflict/in-progress where returned.

Actor-owned approval/request work is never inferred from browser-side administrative permission claims.

## 13. Explicit exclusions

This golden page does not introduce:

- ApprovalTask or RequestTemplate aggregates;
- arbitrary beneficiary/on-behalf requests;
- generic status mutation;
- per-approval `Low/Medium/High` risk badges without an accepted exact semantic contract;
- invented approval due dates or overdue SLAs;
- provider provisioning state as RequestItem state;
- browser-side authorization;
- cross-capability repository/table joins;
- unbounded collection aggregation;
- raw credential/private secret material.

## 14. Definition of done

The implementation is complete when:

1. `/requests` renders the dedicated Requests & Approvals workbench rather than generic resource tabs;
2. IAM Operations approval drill-downs open the intended approval decision context;
3. current actor approval work and self-service requests are bounded and server-derived;
4. decision mutations preserve ETag/If-Match and causal idempotency;
5. New Request uses the authenticated actor as self-service beneficiary and preserves explicit DRAFT -> submit behavior;
6. request, item, approval, Access application, and provider fulfillment meanings stay distinct;
7. desktop/mobile/reduced-motion/accessibility behavior matches the established IAM Operations visual standard;
8. loading/empty/unavailable/forbidden/stale/conflict states are explicit;
9. focused Console/server tests pass and the exact PR head passes required repository CI;
10. no new authoritative IAM aggregate, capability, or cross-capability mutation path is introduced.
