import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSource } from './helpers/source-contract.mjs';

const administrationWorkspace = await readConsoleSource('workspaces/AdministrationWorkspace.tsx');

test('Administration workspace keeps authority concepts distinct from business access and token claims', () => {
  assertSourceContains(
    administrationWorkspace,
    /AdministrativeRole is distinct from Catalog Role, AccessAssignment, and OAuth\/OIDC roles, scopes, or claims/,
    /AdministrativeGrant is not AccessAssignment/,
    /direct authority/,
    /delegated authority/,
    /temporary elevation/,
    /emergency authority only/,
  );
});

test('Direct grant presentation exposes typed scope, validity, grantability, delegability, and single-basis context', () => {
  assertSourceContains(
    administrationWorkspace,
    /Typed scope/,
    /Selecting a modeled scope type does not create browser-side hierarchy or containment semantics/,
    /Valid until/,
    /Grantable authority/,
    /Delegable authority/,
    /Authority-basis direct Grant ID/,
    /one current effective grantable direct grant must contain the complete resulting authority/i,
  );
});

test('High-risk authority actions are consequence-aware without moving authorization into the browser', () => {
  assertSourceContains(
    administrationWorkspace,
    /Revoke direct grant now[\s\S]*destructive: true/,
    /Revoke delegation now[\s\S]*destructive: true/,
    /Request Governance approval evidence/,
    /Apply approved elevation after revalidation/,
    /Cancel pending elevation[\s\S]*destructive: true/,
    /Revoke active elevation now[\s\S]*destructive: true/,
    /server revalidates/i,
    /browser does not infer or combine bases/i,
  );
});

test('Break-glass remains visually and semantically separate with finite assurance-aware post-use context', () => {
  assertSourceContains(
    administrationWorkspace,
    /Emergency · break-glass/,
    /Emergency authority is separate from direct grants and ordinary elevation/,
    /finite, policy-gated, assurance-aware, audited, durably notified/,
    /End emergency authority now[\s\S]*destructive: true/,
    /Complete post-use review/,
    /never retroactively authorizes or extends emergency authority/,
  );
});

test('Current authority stays a consume-unchanged backend-derived child rather than a browser evaluator', () => {
  assertSourceContains(
    administrationWorkspace,
    /CurrentAdministrativeAuthorityPage/,
    /My effective authority/,
  );
  assertSourceExcludes(
    administrationWorkspace,
    /localStorage|sessionStorage|hasPermission|canManage|evaluatePermission|bootstrap superuser|mock business data/i,
  );
});
