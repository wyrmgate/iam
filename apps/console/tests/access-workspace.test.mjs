import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSource } from './helpers/source-contract.mjs';

const accessWorkspace = await readConsoleSource('workspaces/AccessGoldenWorkspace.tsx');
const accessBoundary = await readConsoleSource('workspaces/AccessWorkspace.tsx');
const enterprisePresentation = await readConsoleSource('enterprise-console.css');
const cssVariables = await readConsoleSource('ui/cssVariables.ts');
const accessApi = await readConsoleSource('accessApi.ts');
const api = await readConsoleSource('api.ts');

test('Access golden page is Assignments-first and keeps authority, projection, and expert policy distinct', () => {
  assertSourceContains(accessWorkspace, /Assignments/, /AUTHORITATIVE · ACCESS/, /Effective access/, /DERIVED · READ ONLY/, /Lifecycle access policy/, /AUTHORITATIVE POLICY · EXPERT/);
});

test('Access assignment detail separates lifecycle, temporal validity, provenance, and principal constraint', () => {
  assertSourceContains(accessWorkspace, /Authoritative intent/, /Temporal validity/, /Principal constraint/, /Manual grant/, /Request item/, /Lifecycle policy rule/);
});

test('Access lifecycle operations remain explicit, consequence-aware, revision guarded, and causally idempotent', () => {
  assertSourceContains(accessWorkspace, /Suspend access/, /Resume access/, /Cancel scheduled access/, /Revoke access/, /provider failure will not reactivate this assignment/, /another assignment still supports it/, /createIdempotencyKey/);
  assertSourceContains(accessApi, /mutateAssignment/, /idempotencyKey/);
  assertSourceContains(api, /If-Match/, /Idempotency-Key/);
  assertSourceExcludes(accessWorkspace, /Change status/);
});

test('manual grant uses bounded selectors and does not expose lifecycle or provenance mutation', () => {
  assertSourceContains(accessWorkspace, /Search governed identities/, /TargetPicker/, /searchAccessTargets/, /Specific Principal/, /Manual grant/, /Lifecycle state is not operator-selectable/, /Provider realization happens asynchronously/);
});

test('EffectiveAccess is explanation-only, human-readable, and routes changes through supporting authoritative assignments', () => {
  assertSourceContains(accessWorkspace, /Why is this entitlement effective/, /DERIVED · READ ONLY/, /identityLabel/, /entitlementLabel/, /Current supports/, /Role-derived access/, /Direct entitlement assignment/, /Open supporting assignment/, /Deterministic path reference/, /Change supporting intent, never this projection/);
  assertSourceExcludes(accessWorkspace, /Revoke EffectiveAccess|Edit EffectiveAccess|Suspend EffectiveAccess/);
});

test('Lifecycle policy has a closed typed immutable-successor editor', () => {
  assertSourceContains(accessWorkspace, /Closed typed rule model/, /arbitrary JSON/, /generic AND\/OR\/NOT/, /Lifecycle policy successor/, /PolicyRuleEditor/, /CONTAINS_ANY/, /CONTAINS_ALL/, /2–20 distinct homogeneous values/, /stable IDs/, /tenant-wide retroactive sweep/, /Governance evaluation/);
  assertSourceExcludes(accessWorkspace, /expressionJson|jsonPath|free-form expression/);
});

test('Access workspace does not invent provider fulfillment state or provider mutation surfaces', () => {
  assertSourceContains(accessWorkspace, /Technical realization is separate/, /Provisioning tasks, provider attempts and provider observations are Integration-owned/);
  assertSourceExcludes(accessWorkspace, /\/provisioning-|\/connector-|ObservedGrant|DesiredGrantState|DesiredPrincipalState/);
});

test('Access tabs satisfy keyboard navigation and destructive actions use shared restrained state presentation', () => {
  assertSourceContains(accessWorkspace, /role="tablist"/, /role="tab"/);
  assertSourceContains(accessBoundary, /ArrowRight/, /ArrowLeft/, /Home/, /End/, /preventDefault/, /focus\(\)/, /click\(\)/);
  assertSourceContains(enterprisePresentation, /Typed state remains caller-owned/, /\.access-state::before/, /\.access-state-revoked::before/, /\.access-state-suspended::before/);
  assertSourceExcludes(accessBoundary, /access-enterprise-alignment\.css/);
});

test('Access mobile controls retain the enterprise 44px touch-target baseline', () => {
  assertSourceContains(cssVariables, /'--wg-mobile-target': px\(tokens\.density\.mobileTargetHeight\)/);
  assertSourceContains(enterprisePresentation, /@media \(max-width: 760px\)/, /\.access-detail-close/, /width: var\(--wg-mobile-target\)/, /height: var\(--wg-mobile-target\)/);
});
