# Console `/` Golden Page — IAM Operations

Status: **Review draft — UX and read-model proposal only**  
Scope: **Wyrmgate Console `/` Overview / IAM Operations page only**  
Baseline inspected: `main` at `ae5c09bc3f7591d5356e07023f9b45c176f38b2c`  
Implementation status: **No Console, server, OpenAPI, persistence, migration, or test change is authorized by this document.**

## 1. Purpose

The `/` page is the operator home for Wyrmgate IAM. Its product name is **IAM Operations**.

It exists to answer, in this order:

1. **Attention — what requires my action now?**
2. **Health — are governance work and technical realization progressing normally?**
3. **Context — what environment am I operating in?**

The page must answer the operator question:

> **What needs my attention right now, and is IAM operating correctly?**

It is not a navigation-card directory, generic infrastructure dashboard, runtime status page, reporting warehouse, risk-scoring surface, or browser-side authorization engine.

The current `OverviewPage.tsx` intentionally avoids fabricated counts and begins with navigation cards because no accepted bounded summary contract exists. This specification defines the first reviewable target that can replace that interim hierarchy once the required read contracts are accepted and implemented.

### 1.1 Scope classification

This phase is primarily:

- product UX design;
- information architecture;
- read-model/interface design proposal.

It does **not** change:

- IAM capability ownership;
- tenant meaning or tenant isolation;
- Administrative Authorization semantics;
- approver/reviewer assignment authority;
- Governance workflow state;
- `AccessAssignment` authority;
- `EffectiveAccess` projection meaning;
- provisioning or reconciliation ownership;
- provider-observation semantics;
- Audit evidence meaning;
- Credential secret/private-material boundaries.

Any new read surface described below is explicitly a **proposed projection/query interface**, not accepted authoritative state.

## 2. Canonical grounding

This draft was prepared from current `main`, not prior chat history.

Repository sources inspected:

- `AGENTS.md`
- `docs/PROJECT_INSTRUCTIONS.md`
- `docs/README.md`
- `docs/adr/README.md`
- `docs/engineering/console-operator-workbench.md`
- `docs/architecture/overview.md`
- `docs/architecture/repository-boundaries.md`
- `docs/domain/canonical-model.md`
- `docs/domain/state-and-invariants.md`
- `docs/security/administrative-authorization.md`
- `docs/api/api-conventions.md`
- `docs/api/governance-contracts.md`
- `docs/api/access-contracts.md`
- `docs/api/identity-contracts.md`
- `docs/api/catalog-contracts.md`
- `docs/api/integration-administration.md`
- `docs/api/audit-contracts.md`
- `apps/server/src/main/resources/contracts/openapi/governance-v1.json`
- `apps/server/src/main/resources/contracts/openapi/integration-operations-v1.json`
- `apps/console/src/OverviewPage.tsx`
- `apps/console/src/consoleNavigation.ts`
- `apps/console/src/IntegrationOperationsPage.tsx`

Controlled v0.8 formal package material inspected where relevant:

- IAM-BRD-001 — Wyrmgate IAM Business Requirements Document v0.8;
- IAM-FRD-001 — Functional Requirements & Use Cases v0.8;
- IAM-SRS-001 — Software Requirements Specification v0.8;
- IAM-SEC-001 — Security & Governance Specification v0.8;
- IAM-INT-001 — Integration & Interface Specification v0.8.

The design relies on the accepted architectural distinctions that authoritative state, observation, evidence, and projection are different categories; cross-capability reporting must not create aggregate ownership; projections may lag and must expose freshness; high-cardinality collections must remain bounded; provider failure never rewrites a valid governance/access decision; and incomplete reconciliation does not imply destructive absence.

## 3. Operator personas

The page is shared across operator roles. It does not infer permission from role labels and does not hide a server denial behind a browser guess.

| Persona | Primary value on this page | Most relevant sections | Authorization note |
| --- | --- | --- | --- |
| IAM administrator | Orient to assigned work and broad IAM operating state | Attention, My Work, Provisioning, Reconciliation, Environment | Tenant-wide operational/inventory sections require the corresponding server-side collection read authority. |
| Approver | See approvals assigned to the governed actor and their age | Attention, My Work | Approval authority comes from immutable resolved approver assignment, not generic administration permission. |
| Reviewer | See pending review items and access snapshot context | Attention, My Work | Review authority comes from immutable reviewer assignment, not generic administration permission. |
| Integration operator | Diagnose fulfillment and reconciliation failures | Provisioning Health, Reconciliation Health, Environment | Requires the accepted Integration operational read permissions; absence of permission is not zero. |
| Security operator | See denied/failed control-plane evidence without mistaking it for current state | Security & Administration Evidence | Requires `audit:read` collection authority. Audit remains evidence, not current authorization or logging. |
| Auditor | Orient to evidence volume/signals and drill into immutable records | Security & Administration Evidence, Context | Same Audit boundary; no privileged data is reconstructed by the overview. |

The browser may use the existing current-authority projection to improve navigation, but each overview section is independently authorized by the server and operation-time authorization remains final.

## 4. Operator questions and page hierarchy

### 4.1 Attention

- Do I have approvals waiting?
- Do I have reviews waiting?
- Have final provisioning attempts failed recently?
- Have reconciliation runs failed recently?

### 4.2 Health

- Are provider execution attempts succeeding or repeatedly failing?
- Is technical fulfillment currently running normally?
- Are reconciliation runs completing, and when they succeed are they actually `COMPLETE`, `PARTIAL`, or `UNKNOWN`?
- When was the last successful reconciliation with effective completeness `COMPLETE`?

### 4.3 Context

- Which tenant am I operating in?
- How fresh is the data?
- What is the broad governed inventory size that is safe and useful to expose?
- Are there denied/failed Audit outcomes that merit evidence review?

## 5. Exact desktop layout

At approximately 1440 px, the content order is fixed:

1. page header and global time window;
2. Attention metric strip;
3. My Work;
4. Operational Health:
   - Provisioning Health;
   - Reconciliation Health;
5. Activity & Evidence:
   - My Governance Activity;
   - Security & Administration Evidence;
6. Environment Snapshot.

The visual hierarchy must make Attention and My Work visually dominant. Health follows. Inventory/context is deliberately last.

No navigation-card directory appears on this page. Primary navigation remains in the Console shell.

## 6. Page header

### 6.1 Exact content

**Title:** `IAM Operations`  
**Description:** `What needs my attention right now, and is IAM operating correctly?`

Secondary context line:

- `Tenant: <server-derived tenant identifier>`;
- `Data as of <localized timestamp>`;
- freshness status when any returned section is stale or partial.

Controls:

- global time window segmented control: `24h`, `7d`, `30d`;
- default: `7d`;
- `Refresh` button.

### 6.2 Tenant behavior

There is **no Tenant selector** in this specification.

Tenant is the hard isolation context resolved by the server. The current canonical material does not establish same-session multi-Tenant switching as a Console interaction. The page displays the server-derived current Tenant only.

### 6.3 Time-window behavior

The selected window applies only to time-bounded activity/health values:

- final provisioning failures;
- failed reconciliation runs;
- provisioning attempt trend and failure categories;
- reconciliation period totals/recent period runs;
- My Governance Activity counters;
- Security & Administration Evidence.

It does **not** filter current assigned work or current inventory:

- pending approvals;
- pending reviews;
- My Work items;
- running-now technical state;
- Environment Snapshot.

Cards and section labels must make this distinction explicit, for example `Current` versus `Last 7 days`.

### 6.4 Refresh

Refresh re-runs the bounded overview query with the current global window and section-level settings. It does not clear filters/sort. Focus remains on the Refresh control after completion and an unobtrusive polite live-region announcement reports success or failure.

## 7. Attention summary

The first content row contains exactly four attention metrics.

| Metric | UI label | Semantic meaning | Owner | Authorization | Window | Drill-down | `0` | Unavailable | Not authorized |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `pendingApprovals` | Pending approvals | Exact count of currently pending current-stage ApprovalCase work assigned to the authenticated governed Identity. | Governance | Assigned-approver authority; no generic admin permission substitutes. | **Current**, not window-filtered. | `/requests?view=approvals&status=pending&sort=oldest` | Actor has no pending assigned approvals. | Governance summary query could not be evaluated. | Actor-scoped work query was not authorized/resolvable; never render `0`. |
| `pendingReviews` | Pending reviews | Exact count of `PENDING` ReviewItems assigned to the authenticated governed Identity. | Governance | Assigned-reviewer authority. | **Current**, not window-filtered. | `/reviews?status=pending&sort=oldest` | Actor has no pending assigned review items. | Governance summary query could not be evaluated. | Actor-scoped work query was not authorized/resolvable; never render `0`. |
| `finalProvisioningFailures` | Final provisioning failures | Count of immutable ProvisioningAttempts with outcome `FAILED_FINAL` completed inside `[from, until)`. This is recent technical execution failure evidence; it is **not** an unresolved-business-access count. | Integration | Collection-authorizing `provisioning-attempt:read`. | Selected 24h/7d/30d. | `/integrations?operations=attempts&outcome=FAILED_FINAL&from=...&until=...` | No final provisioning attempt failure occurred in the period. | Integration operational summary is unavailable. | Required Integration collection read authority is absent. |
| `failedReconciliations` | Failed reconciliations | Count of ReconciliationRuns with `state=FAILED` and failure completion inside `[from, until)`. | Integration | Collection-authorizing `reconciliation-run:read`. | Selected 24h/7d/30d. | `/integrations?operations=reconciliation&state=FAILED&from=...&until=...` | No reconciliation run failed in the period. | Reconciliation summary is unavailable. | Required Integration collection read authority is absent. |

### 7.1 Why Audit signals are not an Attention card

Audit `DENIED` and `FAILURE` outcomes are evidence that an operation was denied or failed. They are not, by themselves, a current incident, current authorization defect, or unresolved IAM work item. They belong in the evidence section rather than being promoted into a generic red “security issue” count.

### 7.2 Metric presentation

Each metric card contains:

- concise label;
- numeric value when `AVAILABLE`;
- `Current` or selected-window qualifier;
- explicit status treatment for `NOT_AUTHORIZED`, `UNAVAILABLE`, or `UNSUPPORTED`;
- link affordance only when a meaningful destination is available.

A forbidden/unavailable card never substitutes `—` in a way that can be mistaken for zero.

## 8. My Work

### 8.1 Purpose

My Work is the primary action surface. It answers: **what governed decision work is assigned to me, and what is oldest?**

It combines only actor-assigned Governance work:

- approval cases from the approval inbox;
- pending review items from the review inbox.

It does not create a new authoritative “task” aggregate.

### 8.2 Default behavior

- maximum rendered items: **10**;
- default filter: `All`;
- default sort: `Oldest first`;
- alternate sort: `Newest first`;
- if more work exists, show `View all` with exact counts where available.

Section filters:

- `All`;
- `Approvals`;
- `Reviews`.

Sort:

- `Oldest first`;
- `Newest first`.

The selected global time window does not hide current assigned work.

### 8.3 Row content

Every row/card contains:

- type: `Approval` or `Review`;
- safe subject/resource summary;
- absolute `createdAt` available to assistive technology and detail views;
- visible relative age calculated against response `asOf`;
- current state/stage;
- one primary action: `Review` / `Open`.

There are **no inline Approve/Reject/KEEP/REVOKE buttons on the Overview**. Decisions require the owning Governance detail context, evidence, current revision, and consequence framing.

### 8.4 Existing safe fields versus projection gap

Existing Governance contracts safely expose enough data for technical fallback summaries:

**Approval inbox:**

- ApprovalCase ID;
- `subjectKind`;
- `subjectId`;
- state;
- `currentStageOrdinal`;
- revision;
- `createdAt` / `updatedAt`.

**Review inbox:**

- ReviewItem ID;
- campaign ID;
- AccessAssignment ID/revision;
- target kind and Role/Entitlement ID;
- principal constraint;
- provenance kind/ref;
- snapshot lifecycle state;
- validity;
- assignment/snapshot timestamps;
- item state and revision.

What the current inbox contracts do **not** provide is a bounded, human-friendly cross-resource presentation summary such as beneficiary display name, Role/Entitlement display label, Application context, or a unified global oldest/newest merge across the two inboxes.

Therefore:

- technical fallback may show semantic type plus stable short ID, such as `Access request · …7f2a` or `Review · Role …19c4`;
- richer labels require a **new bounded presentation projection** that resolves only authorized semantic display data through owning capability queries;
- the browser must not issue unbounded/N+1 resource walks to manufacture display labels.

### 8.5 Drill-down

- Approval row → `/requests?view=approvals&approvalCaseId=<id>`.
- Review row → `/reviews?reviewItemId=<id>`.
- `View all approvals` → `/requests?view=approvals&status=pending&sort=<sort>`.
- `View all reviews` → `/reviews?status=pending&sort=<sort>`.

Current Console routing does not yet define these typed query-state contracts. See §17.

## 9. Provisioning Health

### 9.1 Question answered

> **Are provider execution attempts succeeding, retrying, or failing finally over the selected period, and is fulfillment currently running?**

This is technical Integration process health. It does not state whether Governance authorized access, whether an AccessAssignment is valid, or whether observed provider access is authoritative.

### 9.2 Measurements

Summary values:

- `runningTasksNow` — current ProvisioningTasks with `state=RUNNING`;
- `succeededAttempts` — attempts completed in the window with outcome `SUCCEEDED`;
- `retryableFailedAttempts` — attempts completed in the window with outcome `FAILED_RETRYABLE`;
- `finalFailedAttempts` — attempts completed in the window with outcome `FAILED_FINAL`.

The trend deliberately measures **immutable attempt outcomes**, not an invented “success percentage”. A single task may have retryable failures before later success; the chart correctly represents those provider interactions rather than pretending each attempt is a unique AccessAssignment.

### 9.3 Chart

Chart type: **stacked vertical bars by completed attempt time bucket**.

Question: `Are provider execution outcomes changing over time, and where are failure spikes?`

Why chart rather than table/number: temporal clustering and retry/final-failure spikes are difficult to see from individual attempt rows.

Axes and series:

- x-axis: time bucket;
- y-axis: count of completed ProvisioningAttempts;
- series:
  - `SUCCEEDED`;
  - `FAILED_RETRYABLE`;
  - `FAILED_FINAL`.

`RUNNING` is **not** drawn as a historical series because the system does not currently maintain a sampled historical running-task time series. It appears as the `runningTasksNow` current-state summary instead.

Time buckets are fixed and bounded:

- `24h` → 24 one-hour buckets;
- `7d` → 7 one-day buckets;
- `30d` → 30 one-day buckets.

Maximum chart points are therefore bounded independently of tenant cardinality.

Sparse/no data:

- missing buckets render as zero only after the section is `AVAILABLE`;
- if all attempt counts are zero, replace the plot canvas with `No provisioning attempts completed in this period` while still showing `runningTasksNow`;
- unavailable/forbidden/unsupported data never renders as an empty chart.

Click behavior:

- clicking a `SUCCEEDED` bar → Integration operations filtered to attempts outcome `SUCCEEDED` and that exact bucket;
- clicking `FAILED_RETRYABLE` or `FAILED_FINAL` → same destination with exact outcome and bucket;
- destination target: `/integrations?operations=attempts&outcome=<outcome>&from=<bucketStart>&until=<bucketEnd>`.

The current Integration operations list API and Console do not support these filters/query-state links. This is an explicit drill-down gap, not permission to filter only a loaded cursor page.

### 9.4 Failure categories

Below the chart, show a compact failure-category breakdown for failed attempts in the selected period using only the existing normalized categories:

- `TRANSIENT`;
- `RATE_LIMITED`;
- `AUTHENTICATION`;
- `AUTHORIZATION`;
- `VALIDATION`;
- `UNSUPPORTED`;
- `PROVIDER`;
- `UNKNOWN` only for existing `null` category, labeled `Uncategorized` rather than inventing a classification.

This is a fixed bounded enum distribution, not a pie chart. Render as a short ranked list with counts and optional proportional bars with numeric labels.

No browser parsing of `failureCode` creates new categories.

### 9.5 Filters

Initial section filters:

- outcome/state display toggle: `All`, `Succeeded`, `Retryable failure`, `Final failure`;
- failure category when a failure series is selected.

A friendly Application/Target filter is **deferred from the first golden page**. Current operational records expose ConnectorBinding IDs but the accepted Integration operations API has no target/application filter, and loading every binding/target merely to populate a dropdown is not acceptable at scale. A future searchable semantic target filter requires an explicit bounded contract.

## 10. Reconciliation Health

### 10.1 Question answered

> **Are reconciliations completing successfully, and when they succeed do they have trustworthy effective completeness?**

Run outcome and completeness are always displayed as separate dimensions.

### 10.2 Summary

Show four values:

- `runningNow` — current ReconciliationRuns in `RUNNING`;
- `failedInWindow` — `FAILED` runs completed in the selected period;
- `completeSucceededInWindow` — `SUCCEEDED` runs in the period whose `effectiveCompleteness=COMPLETE`;
- `lastCompleteAt` — most recent completion time of a `SUCCEEDED` run whose `effectiveCompleteness=COMPLETE`, regardless of selected window.

A successful run with `PARTIAL` or `UNKNOWN` completeness must never be labeled “complete”.

### 10.3 Recent runs table

Show at most **6** rows:

- run ID;
- ConnectorBinding/target context;
- run state;
- `effectiveCompleteness`;
- started time;
- completed time or `Running`;
- normalized `failureCode` when present.

Rows are the most recent runs that are either currently running or completed in the selected window.

If presentation-friendly connector/target labels are resolved, they must come from bounded authorized semantic queries. ConnectorBinding ID remains the stable fallback; provider-native identifiers/configuration are not surfaced.

### 10.4 No chart in the first golden page

A run-status chart would visually collapse the more important distinction between outcome and completeness or duplicate the recent-run table without answering a better operator question. The first golden page therefore uses summary values plus a table/card list rather than a decorative reconciliation chart.

### 10.5 Filters

- state: `All`, `Running`, `Succeeded`, `Failed`, `Cancelled`;
- effective completeness: `All`, `Complete`, `Partial`, `Unknown`;
- ConnectorBinding: exact selected binding only when supplied through a bounded/searchable selector; do not enumerate all bindings in the browser solely to populate this filter.

The current public reconciliation-run list accepts only cursor/limit. These filters require an accepted server-side read/query addition.

### 10.6 Drill-down

- failed count → `/integrations?operations=reconciliation&state=FAILED&from=...&until=...`;
- running count → `/integrations?operations=reconciliation&state=RUNNING`;
- recent row → `/integrations?operations=reconciliation&runId=<id>`;
- last complete → `/integrations?operations=reconciliation&runId=<lastCompleteRunId>` when available.

Current `IntegrationOperationsPage` keeps tab/detail selection only in local component state and supports direct ID lookup, not route/query-state restoration. The target requires typed query-state hydration.

## 11. My Governance Activity

Governance activity is useful only when its scope is precise. The first golden page therefore includes **actor-scoped decision counters**, not tenant-wide activity and not a risk score.

Question answered:

> **What Governance decisions did I make during the selected period?**

Counters:

- `approvals` — immutable ApprovalDecisions by the current actor with decision `APPROVE`;
- `rejections` — immutable ApprovalDecisions by the current actor with decision `REJECT`;
- `keeps` — immutable ReviewDecisions by the current actor with decision `KEEP`;
- `revokes` — immutable ReviewDecisions by the current actor with decision `REVOKE`.

No chart is proposed for the first golden page. Four exact labeled numbers answer the orientation question more efficiently and avoid decorative trend visualization.

The current Governance public contract exposes pending inboxes and individual evidence but does not expose actor/time aggregate decision history. These counters therefore require a new Governance-owned bounded read projection/query. They must not be reconstructed by crawling approval/review pages.

Click targets, once history query-state exists:

- approvals/rejections → `/requests?view=history&decision=<APPROVE|REJECT>&from=...&until=...`;
- keeps/revokes → `/reviews?view=history&decision=<KEEP|REVOKE>&from=...&until=...`.

Until a bounded history destination exists, counters may remain non-link text; they must not navigate to a misleading incomplete first cursor page.

## 12. Security & Administration Evidence

### 12.1 Purpose

This section surfaces **Audit evidence signals**, not current security state, runtime logs, or inferred incidents.

Question answered:

> **Have control-plane operations been denied or failed recently, and which immutable Audit records should I inspect?**

### 12.2 Exact signals

Summary:

- `deniedCount` — AuditRecords with `outcome=DENIED` in the selected occurrence-time window;
- `failureCount` — AuditRecords with `outcome=FAILURE` in the selected occurrence-time window.

Recent evidence: maximum **5** records in the selected window with outcome `DENIED` or `FAILURE`, ordered `occurredAt DESC, id DESC`.

Visible row fields are only accepted Audit representation fields:

- occurred time;
- action type;
- resource type;
- optional resource ID;
- outcome;
- optional correlation ID.

No request payload, reason text, provider-native detail, secret/private material, or cross-capability current-state reconstruction is introduced.

### 12.3 High-risk Administration events

The first golden page does **not** fabricate a “high risk” event class. The accepted Audit contract has exact action/resource/outcome evidence but no canonical risk classification for Audit action types. A curated high-risk Administration taxonomy is deferred until requirements/ADR/API semantics define it.

### 12.4 Filters

- outcome: `All signals`, `Denied`, `Failure`;
- exact `resourceType`;
- exact `actionType`.

The current Audit search supports exact outcome/resource/action filters, but it does not currently expose the occurrence-time range needed to preserve the global window. The overview requires a new Audit-owned bounded time-window query/aggregate; drill-down time filtering is a separate public Audit contract gap.

### 12.5 Authorization

Requires collection-authorizing `audit:read`.

If not authorized, render `Not authorized to view Audit evidence` and no counts. Do not render zero. Specific-resource Audit authority does not silently authorize collection aggregation.

### 12.6 Drill-down

- Denied → `/audit?outcome=DENIED&from=...&until=...`;
- Failure → `/audit?outcome=FAILURE&from=...&until=...`;
- row → `/audit?auditRecordId=<id>`.

The destination must preserve exact filter state after corresponding Audit/query-state support exists. Until time-range search is accepted, the destination may preserve outcome/resource/action but must visibly state that the Overview period could not be retained; the implementation must not pretend otherwise.

## 13. Environment Snapshot

Environment Snapshot is secondary orientation. It never pages through full collections in the browser to derive totals.

| Candidate | Useful? | Existing bounded contract can calculate exact global value? | New projection/query needed? | Golden-page decision |
| --- | --- | --- | --- | --- |
| Active Identities | Yes; establishes governed population scale. | **No.** Identity list is cursor-paginated and has no exact total count contract. | Identity-owned aggregate query. | **Include once projection exists.** |
| Principals (total) | Some orientation, but weaker than correlation status. | **No.** Principal list is cursor-paginated with no total. | Identity-owned aggregate query. | **Defer total Principals.** Avoid vanity inventory. |
| Uncorrelated Principals | Yes; useful operational orientation and follow-up. | **No.** Cannot count safely by walking Principal pages. | Identity-owned aggregate query with exact uncorrelated semantics. | **Include once projection exists.** |
| Active AccessAssignments | Yes; indicates current authoritative access-intent scale. | **No.** AccessAssignment collection has no global count contract. | Access-owned aggregate query using authoritative lifecycle/temporal semantics. | **Include once projection exists.** |
| Applications | Yes; compact business-catalog orientation. | **No.** Application list is cursor-paginated with no total. | Catalog-owned aggregate query. | **Include once projection exists.** |
| Active/Enabled Connectors | Yes; useful technical environment context. | **No exact total** exposed by current management list contract. | Integration-owned aggregate query using the accepted ConnectorInstance lifecycle semantics. | **Include once projection exists.** |

Initial target Environment Snapshot therefore contains five metrics:

- `activeIdentities`;
- `uncorrelatedPrincipals`;
- `activeAccessAssignments`;
- `applications`;
- `activeConnectors`.

Each metric is independently authorized and may be `AVAILABLE`, `NOT_AUTHORIZED`, `UNAVAILABLE`, or `UNSUPPORTED`. One forbidden inventory family does not erase the others.

Environment counts are current point-in-time values, not selected-window values.

## 14. Filtering model

There is no universal dashboard filter bar.

### 14.1 Global filter

Only:

- `Time window`: `24h | 7d | 30d`.

### 14.2 Section filters and existing API support

| Section | Filter | Golden page | Existing API support | Gap |
| --- | --- | --- | --- | --- |
| My Work | Type: All / Approvals / Reviews | Yes | Separate approval/review inbox endpoints exist, but no unified filter. | Overview projection must merge bounded actor work. |
| My Work | Oldest / newest | Yes | Existing inbox contracts do not expose client-selectable sort. | Governance/overview read query addition. |
| Provisioning | Outcome display | Yes | Integration operations list has cursor/limit only. | Server-side bounded aggregate/filter needed. |
| Provisioning | Failure category | Yes | Category exists on attempts, but list filtering does not. | Server-side aggregate/filter needed. |
| Provisioning | Application/target | Deferred | Not available as semantic list filter. | Requires searchable bounded target context/filter contract; do not enumerate all bindings. |
| Reconciliation | State | Yes | Cursor/limit only. | Server-side filter/query needed. |
| Reconciliation | Effective completeness | Yes | Field exists, no list filter. | Server-side filter/query needed. |
| Reconciliation | ConnectorBinding | Conditional | Field exists, no list filter. | Exact/bounded selector and query support needed. |
| Security evidence | Outcome | Yes | Audit supports exact single outcome. | Overview still needs time-window aggregate and union for All signals. |
| Security evidence | Resource type | Yes | Audit supports exact resource type. | Query-state wiring only for existing exact filter; period filter remains missing. |
| Security evidence | Action type | Yes | Audit supports exact action type. | Query-state wiring only for existing exact filter; period filter remains missing. |

No section may emulate an unsupported server filter by filtering only the first cursor page and presenting it as tenant-wide data.

## 15. Visualization rules

The first golden page intentionally proposes **one chart**.

### 15.1 Provisioning attempt trend

- Question: are provider execution outcomes changing over time, and where are retries/final failures clustering?
- Why chart: time clustering matters more than individual rows.
- x-axis: time bucket.
- y-axis: completed attempt count.
- series: `SUCCEEDED`, `FAILED_RETRYABLE`, `FAILED_FINAL`.
- buckets: 24 hourly / 7 daily / 30 daily.
- sparse data: zero bucket only when section is available; all-zero period renders explicit empty message.
- click: filtered Integration attempt destination for exact bucket/outcome when supported.
- textual alternative: exact data table for every bucket/series.

### 15.2 Explicitly rejected dashboard patterns

Do not use:

- pie/donut charts for status composition;
- percentages without a precise denominator;
- arbitrary health scores;
- generic risk scores;
- trendlines computed from the currently loaded cursor page;
- charts that combine Governance authority state with provider outcome as one lifecycle;
- successful reconciliation visuals that hide `PARTIAL`/`UNKNOWN` completeness.

## 16. Authorization behavior

### 16.1 General rule

The browser is not an authorization engine.

The proposed Overview query is server-authorized per section/metric. A server `403` or per-section `NOT_AUTHORIZED` is final even if navigation is visible or the advisory administrative-authority projection suggested access.

### 16.2 Actor-assigned Governance work

Pending approvals and reviews use the existing assigned-approver/assigned-reviewer authority model. They are not gated by an invented `approval:read`/`review:read` administrative permission.

### 16.3 Collection summaries

Tenant-wide operational, Audit, and inventory aggregates require authority that permits the corresponding collection query. A specific-resource grant does not imply permission to calculate a tenant-wide count.

The Overview must not silently aggregate “whatever subset happens to be authorized” and label it as a tenant total unless a future accepted scoped-aggregate contract defines that meaning explicitly.

### 16.4 Section independence

Examples:

- approver with no Integration rights sees real approval work and `Not authorized` for Integration health;
- Integration operator without `audit:read` sees Integration health and `Not authorized` for Audit evidence;
- auditor without Integration operational rights sees Audit evidence and no fabricated provisioning zero.

## 17. Interaction and drill-down model

The target query-state contract is typed and restorable on refresh/deep link.

| Source | Destination target | Required context |
| --- | --- | --- |
| Pending approvals | `/requests` | `view=approvals`, `status=pending`, sort |
| Pending reviews | `/reviews` | `status=pending`, sort |
| Approval row | `/requests` | exact `approvalCaseId` |
| Review row | `/reviews` | exact `reviewItemId` |
| Provisioning failure metric | `/integrations` | `operations=attempts`, outcome, period |
| Provisioning chart bar | `/integrations` | exact attempt outcome + bucket from/until |
| Reconciliation failure metric | `/integrations` | `operations=reconciliation`, state + period |
| Reconciliation row | `/integrations` | exact `runId` |
| Governance activity counter | `/requests` or `/reviews` | history decision + period when bounded history exists |
| Audit signal | `/audit` | exact outcome + period |
| Audit row | `/audit` | exact `auditRecordId` |
| Active identities | `/identities` | lifecycle `ACTIVE` once server-side list filter exists; otherwise destination opens collection without claiming filter fidelity |
| Uncorrelated principals | `/principals` | correlation `UNCORRELATED` once list filter exists |
| Active assignments | `/access` | current active/effective-intent filter once accepted |
| Applications | `/catalog` | Application collection |
| Active connectors | `/integrations` | Connector collection with lifecycle filter once accepted |

### 17.1 Current routing gap

`consoleNavigation.ts` currently routes by pathname and does not define typed query-state semantics for these destinations. `IntegrationOperationsPage` stores tab/detail state locally and offers cursor paging/direct-ID lookup. Therefore the target needs a future routing/query-state improvement that:

- parses allow-listed query parameters per workspace;
- validates enum/time/UUID values;
- hydrates the selected workspace/tab/filter/detail;
- preserves filters on refresh/back/forward;
- never interprets query parameters as authorization;
- never applies a client-side first-page filter while implying a global server result.

This is a Console routing/interface requirement for implementation planning, not a change to semantic resource ownership.

## 18. Data-contract gap analysis

### 18.1 Why existing endpoints are not sufficient

The browser cannot build trustworthy global metrics from existing high-cardinality cursor pages.

Current gaps include:

- Governance approval/review inboxes expose bounded pages, not exact pending global counts, unified merge, or selectable oldest/newest sort;
- Integration operational lists expose cursor/limit only, not state/window/failure-category aggregates;
- Audit search supports exact actor/action/resource/outcome/correlation filters but no occurrence-time range for this dashboard period;
- authoritative Identity/Principal/Access/Catalog/Connector collections expose bounded lists rather than exact tenant inventory counts;
- current Console routes cannot restore the desired typed drill-down filter context.

### 18.2 Proposed framework-neutral interface

Introduce a conceptual read-only application query:

`IamOperationsOverviewQuery`

Inputs:

- server-resolved tenant context;
- server-resolved governed actor context;
- `window = 24h | 7d | 30d`;
- `workType = ALL | APPROVAL | REVIEW`;
- `workSort = OLDEST | NEWEST`.

The query is a **presentation/read projection**, not a ninth capability and not authoritative state.

It belongs at the first-party Console/application composition boundary and obtains data only through narrow capability-owned semantic read contracts. It may not import foreign repositories/tables or mutate any capability.

Source capability ownership remains:

- Governance → assigned work and actor decision activity;
- Integration → provisioning/reconciliation operational summaries;
- Audit → Audit evidence summaries;
- Identity → Identity/Principal inventory summaries;
- Access → AccessAssignment inventory summary;
- Catalog → Application inventory summary;
- Integration → Connector inventory summary.

If durable materialization is later needed, that is a rebuildable projection decision. It must not become business authority and may require a dedicated ADR if it introduces durable cross-capability projection storage or new event contracts.

### 18.3 Transport placement

Do **not** treat `GET /api/v1/operator-overview?window=7d` as already accepted.

A candidate first-party Console mapping is:

`GET /api/v1/console/iam-operations?window=7d&workType=ALL&workSort=OLDEST`

That path is only a transport proposal. Before implementation, API review must decide whether the first-party Console projection should be:

- a dedicated Console HTTP contract;
- another internal delivery adapter over the same application query; or
- a public projection contract with stronger compatibility commitments.

The UX requires the conceptual query semantics, not that exact URI.

### 18.4 Required projection properties

The read model must be:

- read-only;
- tenant-bound from trusted server context;
- actor-bound where work/activity is actor-specific;
- server-authorized per metric/section;
- bounded independently of tenant size;
- explicit about `asOf` and source freshness;
- able to represent `NOT_AUTHORIZED`, `UNAVAILABLE`, and `UNSUPPORTED` distinctly;
- incapable of cross-capability persistence mutation;
- incapable of returning secret/private credential material;
- incapable of reclassifying Audit evidence as current state;
- incapable of reclassifying Integration failure as Governance/Access invalidity.

### 18.5 Conceptual response shape

```yaml
asOf: 2026-10-13T15:30:00Z
partial: false
tenant:
  id: <server-derived-tenant-id>
window:
  preset: 7d
  from: 2026-10-06T15:30:00Z
  until: 2026-10-13T15:30:00Z

attention:
  pendingApprovals: { status, value, asOf }
  pendingReviews: { status, value, asOf }
  finalProvisioningFailures: { status, value, asOf }
  failedReconciliations: { status, value, asOf }

myWork:
  status: AVAILABLE
  asOf: ...
  freshness: FRESH
  appliedType: ALL
  appliedSort: OLDEST
  totalCount: 23
  approvalCount: 8
  reviewCount: 15
  items: [ ... max 10 ... ]

provisioning:
  status: AVAILABLE
  asOf: ...
  freshness: FRESH
  summary:
    runningTasksNow: 4
    succeededAttempts: 120
    retryableFailedAttempts: 7
    finalFailedAttempts: 2
  trend: [ ... max 30 buckets ... ]
  failureCategories: [ ... fixed category set ... ]

reconciliation:
  status: AVAILABLE
  asOf: ...
  freshness: FRESH
  summary:
    runningNow: 1
    failedInWindow: 2
    completeSucceededInWindow: 18
    lastCompleteAt: ...
    lastCompleteRunId: ...
  recentRuns: [ ... max 6 ... ]

myGovernanceActivity:
  status: AVAILABLE
  asOf: ...
  freshness: FRESH
  approvals: 12
  rejections: 1
  keeps: 31
  revokes: 3

securityEvidence:
  status: AVAILABLE
  asOf: ...
  freshness: FRESH
  deniedCount: 5
  failureCount: 2
  recent: [ ... max 5 Audit-shaped summaries ... ]

environment:
  asOf: ...
  activeIdentities: { status, value }
  uncorrelatedPrincipals: { status, value }
  activeAccessAssignments: { status, value }
  applications: { status, value }
  activeConnectors: { status, value }
```

### 18.6 Common section/result envelope

Recommended status vocabulary:

- `AVAILABLE` — server completed the authorized bounded query;
- `NOT_AUTHORIZED` — required authority is absent;
- `UNAVAILABLE` — normally supported/authorized source could not currently be evaluated;
- `UNSUPPORTED` — the deployment/contract does not implement the metric.

Recommended freshness vocabulary:

- `FRESH`;
- `STALE`;
- `UNKNOWN`.

The server, not the browser, decides whether a source is stale based on the source/read-model contract. The browser displays the result and source `asOf`.

### 18.7 Response section field definitions and bounds

#### Attention

| Field | Meaning | Source | Freshness | Authorization | Bound |
| --- | --- | --- | --- | --- | --- |
| `pendingApprovals` | Current actor pending approval count | Governance | Point-in-time | Assigned approver | 1 metric |
| `pendingReviews` | Current actor pending ReviewItem count | Governance | Point-in-time | Assigned reviewer | 1 metric |
| `finalProvisioningFailures` | `FAILED_FINAL` attempts in period | Integration | Window aggregate | `provisioning-attempt:read` collection | 1 metric |
| `failedReconciliations` | Failed runs in period | Integration | Window aggregate | `reconciliation-run:read` collection | 1 metric |

#### My Work

`items[]` is a typed union with maximum 10 entries.

Common fields:

- `kind: APPROVAL | REVIEW`;
- `id`;
- `createdAt`;
- `updatedAt`;
- `state`;
- `summary.primary`;
- optional `summary.secondary`;
- semantic `resourceRef` values used to navigate.

Approval-specific:

- `subjectKind`;
- `subjectId`;
- `currentStageOrdinal`.

Review-specific:

- `reviewCampaignId`;
- `accessAssignmentId`;
- `targetKind`;
- target ID;
- `snapshotLifecycleState`.

`summary.*` is presentation-only, data-minimized display context resolved by bounded authorized queries. It is never authoritative identity/access/catalog state.

Freshness: point-in-time Governance state as of section query.  
Authorization: assignment-based.  
Cardinality: exactly `<=10` items plus three counts.

#### Provisioning

| Field | Meaning | Source | Bound |
| --- | --- | --- | --- |
| `runningTasksNow` | Current `RUNNING` task count | Integration task state | 1 |
| period attempt totals | Immutable attempt outcomes in window | Integration attempts | 3 totals |
| `trend[]` | Outcome counts per fixed bucket | Integration attempt aggregate | max 30 buckets × 3 series |
| `failureCategories[]` | Failed attempts grouped by accepted normalized category | Integration attempts | fixed <=8 categories |

Authorization: collection-authorizing `provisioning-task:read` for running state and `provisioning-attempt:read` for attempt summaries. If one is absent, the section must represent that sub-result explicitly rather than infer zero.

#### Reconciliation

| Field | Meaning | Source | Bound |
| --- | --- | --- | --- |
| `runningNow` | Current running runs | Integration | 1 |
| `failedInWindow` | Failed completions in period | Integration | 1 |
| `completeSucceededInWindow` | Successful + effectively COMPLETE runs in period | Integration | 1 |
| `lastCompleteAt` / run ID | Latest successful effectively COMPLETE run | Integration | 1 pair |
| `recentRuns[]` | Running or recent period runs | Integration | max 6 |

Every recent row preserves `state` and `effectiveCompleteness` independently.

Authorization: collection-authorizing `reconciliation-run:read`.

#### My Governance Activity

Fields: `approvals`, `rejections`, `keeps`, `revokes`.  
Source: Governance immutable decision evidence.  
Scope: authenticated actor only.  
Freshness: selected window aggregate.  
Authorization: the actor’s own immutable approver/reviewer participation; do not invent tenant-wide aggregate permission.  
Bound: four numbers.

#### Security evidence

Fields:

- `deniedCount`;
- `failureCount`;
- `recent[]` max 5 with accepted Audit fields only.

Source: Audit.  
Freshness: occurrence-time window through Audit-owned query.  
Authorization: `audit:read` collection authority.  
Bound: two totals + five records.

#### Environment

Each inventory field is an independent `MetricResult`.

| Field | Source owner | Required collection authority | Bound |
| --- | --- | --- | --- |
| `activeIdentities` | Identity | `identity:read` | 1 |
| `uncorrelatedPrincipals` | Identity | `principal:read` | 1 |
| `activeAccessAssignments` | Access | `access-assignment:read` | 1 |
| `applications` | Catalog | `application:read` | 1 |
| `activeConnectors` | Integration | `connector:read` | 1 |

No response section grows with tenant cardinality beyond the explicit small caps above.

## 19. UI state model

### Loading

- preserve final page geometry with skeletons for metric values, work rows, chart, and tables;
- header/Tenant context may render independently if already available from the shell;
- never display temporary zeroes while loading.

### No work

When Governance work queries are `AVAILABLE` and both counts are zero:

- Attention cards show `0`;
- My Work shows `No approvals or reviews are currently assigned to you.`

### Genuine zero

Render numeric `0` only when an authorized supported query completed successfully and the exact aggregate is zero.

### Not authorized

Render explicit `Not authorized` section/card state. No numeric value, chart zero baseline, or empty-list wording may imply there is no data.

### Section unavailable

Render `Temporarily unavailable` plus section `asOf` of any retained previous data. Other sections remain usable.

### Partial data

If any section/sub-result is not `AVAILABLE`, set response/page `partial=true` and show a compact page-level notice: `Some IAM Operations data is unavailable.` Each section still explains its own condition.

### Stale/freshness concern

Keep last usable data visible when permitted, label it `Stale`, show source `asOf`, and do not animate or silently refresh it into a new value. Staleness is server/read-model defined.

### Server error

If the entire overview request fails and no retained result exists, show a full-width error panel below the header with correlation ID when the normal error contract supplies one. Shell navigation remains usable.

### Unsupported metric

Render `Not available in this deployment`/`Unsupported` rather than `0`. Unsupported chart sections do not render axes or an empty plot.

### Empty chart period

If Provisioning is `AVAILABLE` and all three attempt series are zero, render `No provisioning attempts completed in this period.` Still render `runningTasksNow` because it is current-state context.

## 20. Responsive design

### 20.1 Desktop — approximately 1440 px

- content width uses the existing wide operator-workbench page rhythm;
- Attention is a 4-column metric strip;
- My Work is full-width to preserve task priority;
- Provisioning/Reconciliation use approximately 7/5 columns;
- My Governance Activity/Security Evidence use approximately 5/7 columns;
- Environment Snapshot is full-width with five compact metrics.

Tables use normal desktop columns. Only dense technical tables may scroll inside their own container; the page itself must not horizontally scroll.

### 20.2 Narrow/mobile — approximately 390 px

Ordering never changes semantically:

1. header;
2. Attention;
3. My Work;
4. Provisioning;
5. Reconciliation;
6. My Governance Activity;
7. Security Evidence;
8. Environment.

Behavior:

- header context stacks vertically;
- `24h / 7d / 30d` remains directly accessible as a full-width segmented control;
- Attention becomes a 2×2 compact grid;
- My Work table becomes stacked cards;
- section filters move behind a `Filters` disclosure when they do not fit, while active filters remain visible as removable chips/text;
- provisioning chart keeps the same semantic series but reduces x-axis labels; it never hides a failure series solely for width;
- reconciliation and Audit tables become stacked record cards showing the most important state/time first;
- technical IDs move into secondary lines/details but remain available;
- Environment becomes a 2-column metric grid with the final item spanning naturally;
- no primary attention metric, My Work, freshness marker, or health section disappears on mobile;
- no horizontal page overflow. A deliberately dense data-table alternative may scroll inside its own bounded container.

## 21. Accessibility

### Heading hierarchy

- one `h1`: `IAM Operations`;
- `h2`: `Attention`, `My Work`, `Operational Health`, `Activity & Evidence`, `Environment Snapshot`;
- `h3`: `Provisioning Health`, `Reconciliation Health`, `My Governance Activity`, `Security & Administration Evidence`.

### Keyboard and focus

- all metric cards that navigate are ordinary keyboard-focusable links/buttons;
- visual order equals DOM/tab order;
- no hover-only information;
- Refresh retains focus after completion;
- workspace navigation moves focus to the destination page heading according to the shared Console routing pattern;
- filter disclosures restore focus to their trigger when closed.

### Status meaning

Color is never the only carrier of state. Every status uses visible text and, where useful, an icon/symbol. `FAILED_FINAL`, `FAILED`, `PARTIAL`, `UNKNOWN`, `NOT_AUTHORIZED`, and `STALE` must be readable without color perception.

### Chart alternative

Provisioning Health includes:

- accessible chart name/description;
- concise text summary of totals;
- a `View data table` disclosure containing semantic column/row headers for bucket and every series;
- equivalent keyboard-accessible drill-down links from the data table when chart bars are clickable.

### Tables/cards

Desktop tables use real table semantics, captions/accessible names, column headers, and row actions. Mobile card transformation preserves field labels rather than relying on visual column position.

### Motion

Respect `prefers-reduced-motion`. No animated counter rollups, auto-scrolling chart transitions, pulsing failure cards, or motion that is required to understand freshness/state.

## 22. Desktop wireframe

```text
┌────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ IAM Operations                                                        [24h] [7d] [30d]     [Refresh]      │
│ What needs my attention right now, and is IAM operating correctly?                                             │
│ Tenant: tnt-…                                  Data as of 13 Oct 2026 22:30 WIB   Fresh / Partial if needed │
└────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

ATTENTION
┌──────────────────────┐ ┌──────────────────────┐ ┌────────────────────────┐ ┌────────────────────────┐
│ Pending approvals    │ │ Pending reviews      │ │ Final provisioning     │ │ Failed reconciliations │
│ 8                    │ │ 15                   │ │ failures               │ │ 2                      │
│ Current · View       │ │ Current · View       │ │ 2 · Last 7 days        │ │ Last 7 days · View     │
└──────────────────────┘ └──────────────────────┘ └────────────────────────┘ └────────────────────────┘

MY WORK                                                             [All] [Approvals] [Reviews]  [Oldest ▾]
┌────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ Approval   Access request …7f2a        Stage 2 · Pending       4d 3h old                       [Review →] │
│ Review     Role …19c4                  Pending                 2d 8h old                       [Open →]   │
│ Approval   Elevation …44aa             Stage 1 · Pending       1d 1h old                       [Review →] │
│ ... max 10                                                                                   [View all]  │
└────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

OPERATIONAL HEALTH
┌───────────────────────────────────────────────────────────────┐  ┌──────────────────────────────────────────┐
│ Provisioning Health                            [filters]      │  │ Reconciliation Health          [filters]│
│ Running now 4 · Success attempts 120 · Retry 7 · Final 2    │  │ Running 1 · Failed 2 · Complete success 18│
│                                                               │  │ Last complete: 13 Oct 21:04              │
│ attempts                                                     │  │                                           │
│  30 ┤                █                                       │  │ Recent runs                               │
│  20 ┤       █        █      █                                │  │ binding …a1  SUCCEEDED  COMPLETE  21:04 →│
│  10 ┤ █     █  ▒     █  ▓   █                               │  │ binding …b7  SUCCEEDED  PARTIAL   20:55 →│
│   0 └────────────────────────────────── time                   │  │ binding …c2  FAILED     UNKNOWN   20:31 →│
│      Succeeded / Retryable failure / Final failure            │  │ ... max 6                                │
│ Failure categories: TRANSIENT 4 · RATE_LIMITED 2 · ...        │  │                                           │
└───────────────────────────────────────────────────────────────┘  └──────────────────────────────────────────┘

ACTIVITY & EVIDENCE
┌───────────────────────────────────────────────┐  ┌──────────────────────────────────────────────────────────┐
│ My Governance Activity · Last 7 days         │  │ Security & Administration Evidence · Last 7 days        │
│ Approvals 12  Rejections 1                    │  │ Denied 5 · Failure 2                       [filters]      │
│ Keeps 31      Revokes 3                       │  │ DENIED  admin…  role/update       13 Oct 21:20   [Open]│
│ actor-scoped; not tenant-wide                 │  │ FAILURE connector… update         13 Oct 18:03   [Open]│
└───────────────────────────────────────────────┘  └──────────────────────────────────────────────────────────┘

ENVIRONMENT SNAPSHOT · CURRENT
┌───────────────────┬──────────────────────┬────────────────────────┬─────────────────┬──────────────────────┐
│ Active identities │ Uncorrelated         │ Active access          │ Applications    │ Active connectors    │
│ 1,240,331         │ principals 1,842     │ assignments 8,442,109  │ 426             │ 39                   │
└───────────────────┴──────────────────────┴────────────────────────┴─────────────────┴──────────────────────┘
```

Wireframe numbers are illustrative only and are not sample product data or acceptance fixtures.

## 23. Mobile wireframe — approximately 390 px

```text
┌──────────────────────────────────────┐
│ IAM Operations                       │
│ What needs my attention right now,   │
│ and is IAM operating correctly?      │
│ Tenant: tnt-…                        │
│ Data as of 22:30 · Fresh             │
│ [ 24h ][ 7d ][ 30d ]   [ Refresh ]   │
└──────────────────────────────────────┘

ATTENTION
┌─────────────────┐ ┌─────────────────┐
│ Approvals       │ │ Reviews         │
│ 8 · Current     │ │ 15 · Current    │
└─────────────────┘ └─────────────────┘
┌─────────────────┐ ┌─────────────────┐
│ Provisioning    │ │ Reconciliation  │
│ final fail 2    │ │ failed 2        │
│ · 7d            │ │ · 7d            │
└─────────────────┘ └─────────────────┘

MY WORK
[All] [Approvals] [Reviews]
Sort: [Oldest ▾]
┌──────────────────────────────────────┐
│ Approval · 4d 3h old                │
│ Access request …7f2a                │
│ Stage 2 · Pending                    │
│ [Review →]                           │
└──────────────────────────────────────┘
┌──────────────────────────────────────┐
│ Review · 2d 8h old                  │
│ Role …19c4                           │
│ Pending                              │
│ [Open →]                             │
└──────────────────────────────────────┘
[View all]

PROVISIONING HEALTH          [Filters]
Running 4 · Success 120 · Retry 7 · Final 2
┌──────────────────────────────────────┐
│ stacked attempt bars; fewer x labels │
│ same 3 semantic series               │
└──────────────────────────────────────┘
Failure categories …
[View data table]

RECONCILIATION HEALTH        [Filters]
Running 1 · Failed 2
Complete success 18
Last complete 21:04
┌──────────────────────────────────────┐
│ SUCCEEDED · COMPLETE                 │
│ binding …a1 · 21:04                  │
│ [Open →]                             │
└──────────────────────────────────────┘
┌──────────────────────────────────────┐
│ SUCCEEDED · PARTIAL                  │
│ binding …b7 · 20:55                  │
│ [Open →]                             │
└──────────────────────────────────────┘

MY GOVERNANCE ACTIVITY · 7d
Approvals 12  Rejections 1
Keeps 31      Revokes 3

SECURITY & ADMIN EVIDENCE    [Filters]
Denied 5 · Failure 2
┌──────────────────────────────────────┐
│ DENIED · role/update                 │
│ 21:20 · resource …                   │
│ [Open →]                             │
└──────────────────────────────────────┘

ENVIRONMENT · CURRENT
┌─────────────────┐ ┌─────────────────┐
│ Active IDs      │ │ Uncorrelated    │
│ 1,240,331       │ │ principals 1,842│
└─────────────────┘ └─────────────────┘
┌─────────────────┐ ┌─────────────────┐
│ Access assigns  │ │ Applications    │
│ 8,442,109       │ │ 426             │
└─────────────────┘ └─────────────────┘
┌──────────────────────────────────────┐
│ Active connectors 39                 │
└──────────────────────────────────────┘
```

## 24. Open implementation questions

These must be resolved before implementation; none is silently decided by this UX draft.

1. **Transport contract placement:** should `IamOperationsOverviewQuery` receive a dedicated first-party Console OpenAPI contract, an internal HTTP adapter, or a public projection API?
2. **Projection execution/materialization:** are the required capability-owned aggregate queries fast enough on current indexes, or does any capability need its own rebuildable summary projection? Cross-capability direct repository joins are not an option.
3. **Failure occurrence timestamp:** confirm the invariant used for failed ReconciliationRun period bucketing (`completedAt` for terminal failure) and reject ambiguous fallback to arbitrary `updatedAt` if that invariant is not guaranteed.
4. **Provisioning aggregate authorization:** define whether the Provisioning section requires both `provisioning-task:read` and `provisioning-attempt:read` as a whole or returns independently authorized sub-results.
5. **Presentation labels:** identify the minimum bounded semantic display queries needed to replace stable-ID fallback labels in My Work and Reconciliation without N+1 browser requests or sensitive disclosure.
6. **Governance decision history:** decide whether actor-scoped historical decision list endpoints are warranted for counter drill-down. Until then the counters must not link to an incomplete substitute.
7. **Audit time-range search:** decide whether the public Audit contract should accept exact bounded occurrence-time range filters so Overview drill-down can preserve period context.
8. **Workspace query-state:** define allow-listed typed query parameter schemas for Requests, Reviews, Integrations, Audit, and inventory workspaces.
9. **Inventory lifecycle labels:** map `activeConnectors` to the exact accepted ConnectorInstance lifecycle term used by the authoritative contract before UI implementation; do not invent lifecycle vocabulary.
10. **Freshness policy:** each contributing query/projection must define when it reports `FRESH`, `STALE`, or `UNKNOWN`; the Console should not invent a global stale timeout.
11. **Scope-aware aggregates:** future richer Administration scopes may permit legitimate scoped collection aggregates. The initial implementation must not infer such semantics from specific-resource grants.

A new ADR is required only if implementation chooses a durable architecture change such as cross-capability persisted presentation projection/event feed or materially new authorization semantics. Straightforward bounded read interfaces that preserve existing ownership may be documented as API/application contracts under the normal interface process.

## 25. Explicit non-goals

This golden page does not:

- create or mutate IAM authoritative state;
- approve/reject or KEEP/REVOKE inline;
- define a new Tenant switcher;
- define a universal dashboard/reporting engine;
- define generic BI/export behavior;
- define a risk score, security score, IAM health score, or compliance percentage;
- treat Audit as operational logging/current authorization state;
- treat provider observation as Access/Catalog authority;
- treat provider failure as invalidating Governance or Access intent;
- treat `PARTIAL`/`UNKNOWN` reconciliation as destructive absence;
- retrieve/display Credential secrets, private keys, tokens, connector secrets, or provider-native sensitive payloads;
- calculate global metrics by cursor-walking in the browser;
- make browser route visibility authoritative;
- change accepted public APIs in this documentation-only phase.

## 26. Acceptance criteria for the design phase

The draft is ready for review when reviewers can answer all of the following from this document without reading implementation code:

- [ ] The page exists to answer `What needs my attention right now, and is IAM operating correctly?`.
- [ ] Attention is first, Health second, Context third.
- [ ] Header content is exact: title, server-derived Tenant, `asOf`/freshness, Refresh, 24h/7d/30d.
- [ ] There is no Tenant selector without an accepted same-session switching requirement.
- [ ] Pending approval/review work is actor-assigned and not hidden by the global time window.
- [ ] Provisioning/Reconciliation failure metrics have exact technical semantics and never rewrite Governance/Access meaning.
- [ ] `0`, `NOT_AUTHORIZED`, `UNAVAILABLE`, `UNSUPPORTED`, partial, and stale data are visually/semantically distinct.
- [ ] My Work has exact filter/sort behavior and no inline decision mutation.
- [ ] The only first-page chart is the bounded provisioning attempt trend with exact axes/series/buckets/empty/click semantics.
- [ ] Reconciliation displays run outcome and effective completeness separately.
- [ ] Governance activity is actor-scoped exact decision evidence, not tenant-wide inferred risk.
- [ ] Audit signals are immutable evidence and use only accepted Audit fields/outcomes.
- [ ] Every environment candidate has an explicit include/defer decision and no browser page-counting shortcut.
- [ ] Global and section filters are separated; unsupported filters are identified as contract gaps.
- [ ] Every metric/row/action has a target drill-down and route/query-state gaps are explicit.
- [ ] The bounded `IamOperationsOverviewQuery` proposal preserves capability ownership and server authorization.
- [ ] Response sections define source capability, freshness, authorization, and fixed cardinality bounds.
- [ ] Desktop and 390 px mobile hierarchy are reviewable from wireframes.
- [ ] Accessibility behavior covers headings, keyboard/focus, chart alternatives, color independence, semantic tables, and reduced motion.
- [ ] No Console/server/OpenAPI/persistence/test implementation change is included in this phase.

## 27. Review decision requested

Reviewers should approve or amend:

1. the Attention metric semantics;
2. the decision to measure provisioning attempt outcomes rather than fabricate a task-level success rate;
3. the reconciliation summary/table treatment without a decorative chart;
4. actor-scoped Governance activity rather than tenant-wide Governance statistics;
5. the Environment Snapshot include/defer set;
6. the `IamOperationsOverviewQuery` composition boundary and section authorization behavior;
7. the proposed route/query-state destinations and explicit API gaps.

Implementation must not begin until this golden-page draft is reviewed and the required read-contract work is separately accepted.