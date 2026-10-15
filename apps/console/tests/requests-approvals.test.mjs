import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSources } from './helpers/source-contract.mjs';

const { app, wrapper, page, api, styles, governance } = await readConsoleSources({
  app: 'App.tsx',
  wrapper: 'RequestsApprovalsWithAccessLinks.tsx',
  page: 'RequestsApprovalsPage.tsx',
  api: 'requestsApprovalsApi.ts',
  styles: 'requests-approvals.css',
  governance: 'workspaces/GovernanceWorkspace.tsx',
});

test('requests route uses the dedicated Requests & Approvals golden page', () => {
  assertSourceContains(
    app,
    /RequestsApprovalsWithAccessLinks/,
    /route === 'requests'.*<RequestsApprovalsWithAccessLinks/s,
  );
  assertSourceContains(
    wrapper,
    /RequestsApprovalsPage/,
    /<RequestsApprovalsPage \/>/,
  );
  assertSourceExcludes(app, /RequestsWorkspace/);
  assertSourceExcludes(governance, /export function RequestsWorkspace|Approval Inbox|Request Item Status/);
});

test('page leads with actor-scoped approval and self-service request work', () => {
  assertSourceContains(
    page,
    /Requests &amp; Approvals/,
    /Request access for yourself and manage approval work assigned to you/,
    /My pending approvals/,
    /Draft requests/,
    /In-flight request items/,
    /Applied request items/,
    /My Approval Work/,
    /My Requests/,
    /New Request/,
  );
});

test('approval decisions re-read current evidence and preserve concurrency and idempotency', () => {
  assertSourceContains(
    api,
    /\/governance\/approvals\/\$\{encodeURIComponent\(id\)\}/,
    /etag,/,
    /idempotent: true/,
    /reason: reason\.trim\(\) \|\| null/,
  );
  assertSourceContains(
    page,
    /Review immutable Governance evidence before deciding the current stage/,
    /Approve current stage/,
    /Reject current stage/,
    /State changed/,
    /server denied this Governance operation/i,
  );
});

test('new request is self-service and keeps typed request semantics explicit', () => {
  assertSourceContains(
    page,
    /beneficiaryIdentityId: load\.value\.actorIdentityId/,
    /Requested target kind/,
    /ROLE/,
    /ENTITLEMENT/,
    /Principal constraint/,
    /ANY/,
    /SPECIFIC/,
    /Create draft/,
    /Submit for Governance evaluation/,
    /Self-service boundary/,
  );
  assertSourceExcludes(page, /Beneficiary Identity ID/);
});

test('request, approval, Access application and provider fulfillment remain distinct', () => {
  assertSourceContains(
    page,
    /Approval records Governance decision evidence only\. It does not directly mutate Access or prove provider fulfillment/,
    /AUTHORIZED.*Governance authorization/s,
    /APPLIED.*Access accepted authoritative intent/s,
    /Provider fulfillment is separate/,
    /read-only presentation projection/,
  );
  assertSourceExcludes(
    page,
    /risk score|High risk|Medium risk|Low risk|Overdue|SLA due|provisioning success/i,
    /localStorage|sessionStorage/,
    /Authorization:\s*['"`]/,
    /VITE_.*BACKEND|BACKEND_ORIGIN/,
  );
});

test('page consumes one bounded presentation query and supports Overview deep links', () => {
  assertSourceContains(
    api,
    /\/console\/requests-and-approvals/,
    /window: RequestsWindow[\s\S]*approvalSort: ApprovalSort/,
  );
  assertSourceContains(
    page,
    /approvalCaseId/,
    /requestId/,
    /sort/,
    /URLSearchParams/,
    /getRequestsAndApprovals/,
    /Latest \{ready\.requests\.length\} · bounded/,
  );
  assertSourceExcludes(page, /while\s*\([^)]*cursor|nextCursor/);
});

test('page preserves responsive and reduced-motion baseline', () => {
  assertSourceContains(page, /<Page width="wide">/, /aria-live="polite"/, /<caption className="sr-only">/);
  assertSourceContains(styles, /@media \(max-width: 760px\)/, /@media \(prefers-reduced-motion: reduce\)/, /ra-metric-grid/, /ra-table/);
});
