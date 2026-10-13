import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSources } from './helpers/source-contract.mjs';

const { identity } = await readConsoleSources({
  identity: 'workspaces/IdentityWorkspace.tsx',
});

test('Identity workspace keeps governed subjects distinct from technical Principals', () => {
  assertSourceContains(
    identity,
    /Identity is authoritative governed-subject state/,
    /Principals are separate technical representations\/accounts/,
    /provider observations are not Identity authority/,
    /Identity is the governed subject/,
    /Principal IDs \(technical accounts\)/,
  );
});

test('Identity mutations preserve semantic operations, revision guards, and causal idempotency', () => {
  assertSourceContains(
    identity,
    /transitionIdentity\(identityId, action, state\.value\.etag\)/,
    /method: 'PATCH'/,
    /etag: state\.value\.etag/,
    /idempotent: true/,
    /body: \{ displayName \}/,
    /:merge/,
    /absorbedRevision: mergeTarget\.value\.revision/,
    /:split/,
    /splitSelectionPresent/,
    /setConfirmation\(\{ kind: 'lifecycle', action \}\)/,
  );
});

test('canonical attribute disclosure follows the accepted visibility and typed-values contract', () => {
  assertSourceContains(
    identity,
    /visibility: 'VALUE' \| 'REDACTED'/,
    /values\?: CanonicalValue\[\]/,
    /attribute\.visibility === 'REDACTED'/,
    /Redacted by classification authority/,
    /canonicalValueCopy\(attribute\)/,
    /Value disclosure remains independently authorized/,
    /Retained trusted value · current resolution is/,
  );
  assertSourceExcludes(identity, /valueRedacted|\[authorized value\]/);
});

test('Identity detail is a persistent-header tabbed workbench instead of stacked admin cards', () => {
  assertSourceContains(
    identity,
    /Identity · governed subject/,
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
    /Advanced correction/,
  );
  assertSourceExcludes(
    identity,
    /<Surface/,
    /title="Lifecycle operations"/,
    /title="Display name"/,
    /title="Merge administrative correction"/,
    /title="Split administrative correction"/,
  );
});

test('Identity collection uses the dense golden-page workbench without inventing browser search', () => {
  assertSourceContains(
    identity,
    /identity-workbench/,
    /Governed identities/,
    /Open by Identity ID/,
    /Browse the server-ordered collection/,
    /Search and type\/lifecycle filtering remain server-contract work/,
    /identity-mobile-list/,
    /Load more/,
    /Create governed Identity/,
    /Refresh/,
  );
  assertSourceExcludes(
    identity,
    /identities\.filter\(/,
    /identities\.find\([^)]*displayName/,
    /localStorage|sessionStorage/,
  );
});

test('advanced correction is progressive and does not ask the operator to type absorbed revision', () => {
  assertSourceContains(
    identity,
    /loadMergeTarget/,
    /Review target/,
    /mergeTarget\.value\.revision/,
    /Permanent governed-subject correction/,
    /AccessAssignments and Governance authority do not transfer/,
    /Explicit relationship selection required/,
    /A split never copies access or governance authority/,
    /bounded split-candidates query/,
  );
  assertSourceExcludes(identity, /Absorbed authoritative revision/);
});

test('Identity workspace stays inside its existing public and shared UI boundaries', () => {
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
    /tenantId\s*:/,
    /actorIdentityId\s*:/,
    /SourceSystem administration/,
    /@mui\//,
  );
});
