import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSource } from './helpers/source-contract.mjs';

const governanceWorkspace = await readConsoleSource('workspaces/GovernanceWorkspace.tsx');

test('Governance requests separate request creation, request-item status, and approval evidence', () => {
  assertSourceContains(
    governanceWorkspace,
    /Create a typed self-service DRAFT request/,
    /Request Item Status/,
    /Approval evidence/,
    /Evaluation, approval, authorization, Access application, and provider fulfillment are intentionally distinct states/,
    /Approval cases and decisions are Governance process\/evidence state; they do not mutate Access directly/,
  );
});

test('Access request form keeps typed target, principal constraint, and optional validity explicit', () => {
  assertSourceContains(
    governanceWorkspace,
    /Requested target kind/,
    /ROLE/,
    /ENTITLEMENT/,
    /Principal constraint/,
    /ANY/,
    /SPECIFIC/,
    /Valid from/,
    /Valid until/,
    /Submit for Governance evaluation/,
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

test('Governance workspace uses only accepted existing Governance and Identity source-policy routes', () => {
  assertSourceContains(
    governanceWorkspace,
    /\/governance\/access-requests/,
    /\/governance\/request-items/,
    /\/governance\/approval-inbox/,
    /\/governance\/review-campaigns/,
    /\/governance\/review-inbox/,
    /\/governance\/review-remediations/,
    /\/governance\/policies\/access-request\/versions/,
    /\/governance\/exceptions/,
    /\/source-systems\/\$\{encodeURIComponent\(id\)\}\/correlation-policy/,
  );
  assertSourceExcludes(
    governanceWorkspace,
    /GovernanceFinding|dynamic reviewer|manager rule|generic status|expressionJson|jsonPath|\/provisioning-|\/connector-/,
  );
});
