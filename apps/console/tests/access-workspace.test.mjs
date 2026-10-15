import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSource } from './helpers/source-contract.mjs';

const accessWorkspace = await readConsoleSource('workspaces/AccessWorkspace.tsx');
const accessApi = await readConsoleSource('accessApi.ts');
const api = await readConsoleSource('api.ts');

test('Access golden page is Assignments-first and keeps authority, projection, and expert policy distinct', () => {
  assertSourceContains(
    accessWorkspace,
    /Assignments/,
    /Authoritative/,
    /Effective access/,
    /Read only/,
    /Lifecycle policy/,
    /Expert/,
    /AUTHORITATIVE · ACCESS/,
    /DERIVED · READ ONLY/,
  );
});

test('Access assignment detail separates lifecycle, temporal validity, provenance, and principal constraint', () => {
  assertSourceContains(
    accessWorkspace,
    /Authoritative intent/,
    /Temporal validity/,
    /Principal constraint/,
    /Origin/,
    /Lifecycle/,
    /validFrom/,
    /validUntil/,
    /Manual grant/,
    /Request item/,
    /Lifecycle policy rule/,
  );
});

test('Access lifecycle operations remain explicit, consequence-aware, revision guarded, and causally idempotent', () => {
  assertSourceContains(
    accessWorkspace,
    /Suspend access/,
    /Resume access/,
    /Cancel scheduled access/,
    /Revoke access/,
    /provider failure will not reactivate this assignment/,
    /another assignment still supports it/,
    /createIdempotencyKey/,
    /result\.etag/,
  );
  assertSourceContains(
    accessApi,
    /idempotencyKey/,
    /\/access-assignments\/\$\{encodeURIComponent\(id\)\}\/\$\{action\}/,
  );
  assertSourceContains(api, /If-Match/, /Idempotency-Key/);
  assertSourceExcludes(accessWorkspace, /Change status/);
});

test('manual grant uses bounded selectors and does not expose lifecycle or provenance mutation', () => {
  assertSourceContains(
    accessWorkspace,
    /Search governed identities/,
    /kind === 'ROLE' \? 'roles' : 'entitlements'/,
    /Specific Principal/,
    /Origin/,
    /Manual grant/,
    /Lifecycle state is not operator-selectable/,
    /Provider realization happens asynchronously/,
  );
  assertSourceExcludes(accessWorkspace, /provenanceKind[^\n]*select|lifecycleState[^\n]*select/);
});

test('EffectiveAccess is explanation-only and routes changes through supporting authoritative assignments', () => {
  assertSourceContains(
    accessWorkspace,
    /Why is this entitlement effective/,
    /Read-only projection/,
    /Current supports/,
    /Role-derived access/,
    /Direct entitlement assignment/,
    /Open supporting assignment/,
    /Deterministic path reference/,
  );
  assertSourceExcludes(accessWorkspace, /Revoke EffectiveAccess|Edit EffectiveAccess|Suspend EffectiveAccess/);
});

test('Lifecycle policy remains a closed typed immutable-successor surface', () => {
  assertSourceContains(
    accessWorkspace,
    /Closed typed rule model/,
    /arbitrary JSON/,
    /generic AND\/OR\/NOT/,
    /immutable successor/,
    /does not perform a tenant-wide retroactive sweep/,
    /server-side Governance evaluation/,
  );
  assertSourceExcludes(accessWorkspace, /expressionJson|jsonPath|provider state editor|desired provider state/);
});

test('Access workspace does not invent provider fulfillment state or provider mutation surfaces', () => {
  assertSourceContains(
    accessWorkspace,
    /Technical realization is separate/,
    /Provisioning tasks, provider attempts and provider observations are Integration-owned/,
  );
  assertSourceExcludes(
    accessWorkspace,
    /\/provisioning-|\/connector-|ObservedGrant|DesiredGrantState|DesiredPrincipalState|Converged/,
  );
});
