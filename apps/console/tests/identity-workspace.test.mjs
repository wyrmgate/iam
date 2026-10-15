import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSources } from './helpers/source-contract.mjs';

const { identity } = await readConsoleSources({
  identity: 'workspaces/IdentityWorkspace.tsx',
});

test('Identity 360 keeps governed subject authority distinct from Principals, access projection, and provider state', () => {
  assertSourceContains(
    identity,
    /Identity is the governed subject/,
    /Principals are separate technical representations\/accounts/,
    /Provider observations are not Identity authority/,
    /Principal is the technical representation\/account/,
    /Assignments are authoritative business intent\. Effective access is a derived projection/,
    /Technical fulfillment and provider observation remain separate/,
  );
});

test('Identity mutations preserve semantic operations, revision guards, and causal idempotency', () => {
  assertSourceContains(
    identity,
    /transitionIdentity\(identityId, action, identityState\.value\.etag\)/,
    /method: 'PATCH'/,
    /etag: identityState\.value\.etag/,
    /idempotent: true/,
    /body: \{ displayName: displayName\.trim\(\) \}/,
    /:merge/,
    /absorbedRevision: mergeTarget\.value\.revision/,
    /:split/,
    /splitHasSelection/,
    /setConfirmation\(\{ kind: 'lifecycle', action \}\)/,
  );
});

test('canonical attribute disclosure follows classification authorization and resolution semantics', () => {
  assertSourceContains(
    identity,
    /visibility: 'VALUE' \| 'REDACTED'/,
    /values\?: CanonicalValue\[\]/,
    /attribute\.visibility === 'REDACTED'/,
    /Redacted by classification authority/,
    /Authorized values only/,
    /Retained trusted value; current resolution is/,
    /Provider\/source payloads are not shown here/,
  );
  assertSourceExcludes(identity, /valueRedacted|\[authorized value\]/);
});

test('Identity detail is the approved persistent-header six-tab enterprise workbench', () => {
  assertSourceContains(
    identity,
    /<Page width="wide">/,
    /<PageHeader/,
    /Identity · governed subject/,
    /Governed state and actions/,
    /Currently access-eligible/,
    /Currently access-ineligible/,
    /Identity 360 workspace/,
    /label="Overview"/,
    /label="Governed profile"/,
    /label="Principals"/,
    /label="Access"/,
    /label="Governance"/,
    /label="Evidence"/,
    /Identity summary/,
    /Investigation/,
    /Edit metadata/,
    /Advanced Identity correction/,
    /<Surface/,
    /<DataTable/,
    /<StateIndicator/,
  );
  assertSourceExcludes(identity, /title="Lifecycle operations"|title="Display name"|identity360-[a-z-]+/);
});

test('Identity 360 tabs use bounded server-owned subject reads rather than client aggregation', () => {
  assertSourceContains(
    identity,
    /\/principals\?identityId=/,
    /\/access-assignments\?limit=50/,
    /\/effective-access\?identityId=/,
    /\/governance\/subjects\//,
    /\/review-campaigns\?limit=50/,
    /\/exceptions\?limit=50/,
    /\/audit-records\?resourceType=identity&resourceId=/,
    /Load more Principals/,
    /Load more assignments/,
    /Load more effective access/,
    /Load more reviews/,
    /Load more exceptions/,
    /Load more evidence/,
  );
  assertSourceExcludes(
    identity,
    /\.filter\([^)]*identityId/,
    /localStorage|sessionStorage/,
    /Promise\.all\([^)]*\/principals[^)]*\/access/,
  );
});

test('Access and Audit tabs keep semantic dimensions explicit', () => {
  assertSourceContains(
    identity,
    /Authoritative/,
    /Projection/,
    /An ineligible Identity contributes no semantic EffectiveAccess authority/,
    /Direct immutable AuditRecord evidence whose resource is this Identity/,
    /This is not an exhaustive cross-capability activity history/,
    /Related Access, Governance, Integration, or system evidence is not silently joined/,
  );
});

test('Governance tab does not broaden participant-scoped AccessRequest visibility', () => {
  assertSourceContains(
    identity,
    /Access request history is intentionally not shown here/,
    /Request visibility remains participant-scoped/,
    /subject-scoped SoD exceptions/,
  );
  assertSourceExcludes(identity, /\/access-requests\?beneficiaryIdentityId=/);
});

test('advanced correction remains explicit, typed, and ownership preserving', () => {
  assertSourceContains(
    identity,
    /loadMergeTarget/,
    /Review target/,
    /mergeTarget\.value\.revision/,
    /Permanent correction/,
    /Foreign-capability authority never transfers implicitly/,
    /Explicit relationship selection required/,
    /No AccessAssignment or Governance authority is copied/,
    /\/split-candidates/,
    /Current SourceRecord links/,
    /Current Principals/,
    /toggleSplitSelection/,
    /sourceRecordIds: splitSelection\.sourceRecordIds/,
    /principalIds: splitSelection\.principalIds/,
    /first 200 current candidates/,
  );
  assertSourceExcludes(
    identity,
    /Absorbed authoritative revision/,
    /label="Source record IDs"/,
    /label="Principal IDs"/,
    /splitValues\.sourceRecordIds\.split/,
    /splitValues\.principalIds\.split/,
  );
});

test('Identity workspace stays inside shared UI and authenticated API boundaries', () => {
  assertSourceContains(
    identity,
    /from '\.\.\/api'/,
    /from '\.\.\/consoleNavigation'/,
    /from '\.\.\/ui'/,
    /canonical-attributes\?limit=50/,
  );
  assertSourceExcludes(
    identity,
    /Authorization:\s*['"`]/,
    /actorIdentityId\s*:/,
    /@mui\//,
  );
});
