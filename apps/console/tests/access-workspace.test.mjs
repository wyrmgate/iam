import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSource } from './helpers/source-contract.mjs';

const accessWorkspace = await readConsoleSource('workspaces/AccessWorkspace.tsx');
const accessApi = await readConsoleSource('access/accessApi.ts');
const accessTypes = await readConsoleSource('access/accessTypes.ts');

test('Access golden page separates authoritative assignment intent from read-only EffectiveAccess projection', () => {
  assertSourceContains(
    accessWorkspace,
    /Assignments · Authoritative/,
    /Authoritative assignments/,
    /Effective access · Read only/,
    /Derived entitlement-level Access projection/,
    /change business intent through a supporting AccessAssignment/,
  );
  assertSourceExcludes(
    accessWorkspace,
    /Edit EffectiveAccess|Revoke EffectiveAccess|Suspend EffectiveAccess/,
  );
});

test('Access assignment presentation keeps target, principal constraint, temporal validity, provenance and lifecycle semantics visible', () => {
  assertSourceContains(
    accessWorkspace,
    /targetKind/,
    /principalConstraintKind/,
    /validFrom/,
    /validUntil/,
    /provenanceKind/,
    /Suspend access/,
    /Resume access/,
    /Cancel scheduled access/,
    /Revoke access/,
  );
  assertSourceContains(
    accessTypes,
    /'SCHEDULED' \| 'ACTIVE' \| 'SUSPENDED' \| 'REVOKED' \| 'EXPIRED' \| 'CANCELLED'/,
    /'CURRENT' \| 'FUTURE' \| 'ENDED'/,
    /'MANUAL' \| 'REQUEST_ITEM' \| 'LIFECYCLE_POLICY_RULE'/,
  );
});

test('Access lifecycle operations preserve authoritative concurrency and causal retry semantics', () => {
  assertSourceContains(
    accessWorkspace,
    /Resource changed/,
    /Refresh and review the latest authoritative state/,
    /newCausalKey\(\)/,
    /mutateAssignment\(selected, confirm\.action, etag, confirm\.key\)/,
    /Provider fulfillment is separate from Access authority/,
  );
  assertSourceContains(
    accessApi,
    /etag,/,
    /idempotencyKey,/,
    /\/access-assignments\/\$\{encodeURIComponent\(assignmentId\)\}\/\$\{action\}/,
  );
});

test('Lifecycle policy is presented as the accepted closed typed model rather than a generic expression editor', () => {
  assertSourceContains(
    accessWorkspace,
    /Closed typed policy language/,
    /ALWAYS/,
    /CANONICAL_STRING_EQUALS/,
    /CANONICAL_STRING_CONTAINS/,
    /CANONICAL_STRING_CONTAINS_ANY/,
    /CANONICAL_STRING_CONTAINS_ALL/,
    /immutable successor/,
    /does not perform an immediate tenant-wide retroactive sweep/,
  );
  assertSourceExcludes(
    accessWorkspace,
    /expressionJson|jsonPath|generic expression editor|provider state editor/,
  );
});

test('Access golden page uses bounded Console read composition while mutations stay on accepted Access routes', () => {
  assertSourceContains(
    accessApi,
    /\/api\/v1/,
    /\/console\/access/,
    /\/access-assignments/,
    /\/lifecycle-access-policy/,
    /\/effective\/\$\{encodeURIComponent\(effectiveAccessId\)\}\/supports/,
  );
  assertSourceExcludes(
    accessWorkspace,
    /\/provisioning-|\/connector-|ObservedGrant|DesiredGrantState|DesiredPrincipalState/,
  );
});
