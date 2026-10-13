import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSource } from './helpers/source-contract.mjs';

const accessWorkspace = await readConsoleSource('workspaces/AccessWorkspace.tsx');

test('Access workspace separates authoritative assignment intent from read-only EffectiveAccess projection', () => {
  assertSourceContains(
    accessWorkspace,
    /Access · authoritative intent/,
    /Authoritative business intent/,
    /Access · derived projection/,
    /Derived, read-only projection/,
    /no edit or lifecycle operation is available here/,
  );
});

test('Access assignment presentation keeps target, principal constraint, validity, and explicit lifecycle semantics visible', () => {
  assertSourceContains(
    accessWorkspace,
    /targetKind/,
    /roleId/,
    /entitlementId/,
    /principalConstraintKind/,
    /specificPrincipalId/,
    /validFrom/,
    /validUntil/,
    /Suspend current access/,
    /Resume suspended access/,
    /Cancel scheduled access/,
    /Revoke current access/,
  );
});

test('Access lifecycle reduction operations are consequence-aware and keep server concurrency semantics delegated', () => {
  assertSourceContains(
    accessWorkspace,
    /Suspend current access[\s\S]*destructive: true/,
    /Cancel scheduled access[\s\S]*destructive: true/,
    /Revoke current access[\s\S]*destructive: true/,
    /Server authorization, revision checks, and idempotency remain final/,
  );
});

test('Lifecycle policy is presented as the accepted closed typed model rather than a generic expression editor', () => {
  assertSourceContains(
    accessWorkspace,
    /Closed typed predicate model/,
    /ALWAYS/,
    /SINGLE equality/,
    /MULTI CONTAINS/,
    /CONTAINS_ANY \/ CONTAINS_ALL/,
    /DECIMAL, DATE, and DATETIME/,
    /immutable successor/,
  );
  assertSourceExcludes(
    accessWorkspace,
    /expressionJson|jsonPath|generic expression editor|provider state editor|desired provider state/,
  );
});

test('Access workspace remains within accepted public routes and does not introduce provider mutation surfaces', () => {
  assertSourceContains(
    accessWorkspace,
    /\/access-assignments/,
    /\/effective-access/,
    /\/lifecycle-access-policy/,
  );
  assertSourceExcludes(
    accessWorkspace,
    /\/provisioning-|\/connector-|ObservedGrant|DesiredGrantState|DesiredPrincipalState/,
  );
});
