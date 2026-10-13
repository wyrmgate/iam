import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSource } from './helpers/source-contract.mjs';

const governanceWorkspace = await readConsoleSource('workspaces/GovernanceWorkspace.tsx');

test('Governance generic workspace now owns reviews and policies only', () => {
  assertSourceContains(
    governanceWorkspace,
    /export function ReviewsWorkspace/,
    /export function PoliciesWorkspace/,
  );
  assertSourceExcludes(
    governanceWorkspace,
    /export function RequestsWorkspace/,
    /Approval Inbox/,
    /Request Item Status/,
    /AccessRequestPage/,
  );
});

test('Review campaign, immutable decision, and remediation process are presented as separate concepts', () => {
  assertSourceContains(
    governanceWorkspace,
    /Campaign lifecycle and generation state/,
    /KEEP — retain current access/,
    /REVOKE — request access reduction/,
    /Remediation status/,
    /REVOKE creates durable remediation that re-reads current Access state/,
    /APPLIED means Access accepted the authoritative reduction; it does not mean provider removal is complete/,
  );
});

test('Governance policy and exception presentation preserves typed versioned and scoped semantics', () => {
  assertSourceContains(
    governanceWorkspace,
    /typed immutable-content PolicyVersion/,
    /DRAFT → READY → ACTIVE → SUPERSEDED/,
    /Activated\/superseded content is not edited in place/,
    /explicit IDENTITY_SOD_RULE exception/,
    /scoped to one Identity and exact SoDRule/,
    /never removes the underlying SoD\/risk evidence/,
  );
});

test('remaining Governance workspace uses only accepted review, policy, exception and Identity source-policy routes', () => {
  assertSourceContains(
    governanceWorkspace,
    /\/governance\/review-campaigns/,
    /\/governance\/review-inbox/,
    /\/governance\/review-remediations/,
    /\/governance\/policies\/access-request\/versions/,
    /\/governance\/exceptions/,
    /\/source-systems\/\$\{encodeURIComponent\(id\)\}\/correlation-policy/,
  );
  assertSourceExcludes(
    governanceWorkspace,
    /\/governance\/access-requests|\/governance\/approval-inbox|\/governance\/request-items/,
    /GovernanceFinding|dynamic reviewer|manager rule|generic status|expressionJson|jsonPath|\/provisioning-|\/connector-/,
  );
});
