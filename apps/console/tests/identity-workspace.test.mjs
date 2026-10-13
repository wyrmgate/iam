import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSources } from './helpers/source-contract.mjs';

const { identity } = await readConsoleSources({
  identity: 'workspaces/IdentityWorkspace.tsx',
});

test('Identity workspace keeps governed subjects distinct from technical Principals', () => {
  assertSourceContains(
    identity,
    /Identity · governed subjects/,
    /Identity and Principal are deliberately different/,
    /Principals are separate technical representations\/accounts/,
    /provider observations are not Identity authority/,
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
    /:merge/,
    /absorbedRevision: Number\(mergeValues\.absorbedRevision\)/,
    /:split/,
    /splitSelectionPresent/,
    /explicit semantic Identity commands, never generic status edits/,
  );
  assertSourceExcludes(identity, /body:\s*\{[^}]*lifecycleState[^}]*\}\s*\}\);/s);
});

test('canonical attribute disclosure follows the accepted visibility and typed-values contract', () => {
  assertSourceContains(
    identity,
    /visibility: 'VALUE' \| 'REDACTED'/,
    /values\?: CanonicalValue\[\]/,
    /attribute\.visibility === 'REDACTED'/,
    /Redacted by classification authority/,
    /canonicalValueCopy\(attribute\)/,
    /Value disclosure is independently authorized by the server/,
  );
  assertSourceExcludes(identity, /valueRedacted|\[authorized value\]/);
});

test('Identity workspace presents explicit loading, empty, conflict, stale, and correction consequence states', () => {
  assertSourceContains(
    identity,
    /Loading governed Identity/,
    /Loading canonical attributes/,
    /No canonical attributes/,
    /No governed identities/,
    /title: 'Conflict'/,
    /title: 'Resource changed'/,
    /Permanent governed-subject correction/,
    /Explicit relationship selection required/,
    /AccessAssignments, Governance authority and other foreign-capability state do not move implicitly/,
    /A split never copies access or governance authority/,
  );
});

test('Identity workspace stays inside its existing public and shared UI boundaries', () => {
  assertSourceContains(
    identity,
    /from '\.\.\/api'/,
    /from '\.\.\/ui'/,
    /\/identities\//,
    /canonical-attributes\?limit=50/,
  );
  assertSourceExcludes(
    identity,
    /localStorage|sessionStorage/,
    /Authorization:\s*['"`]/,
    /tenantId\s*:/,
    /actorIdentityId\s*:/,
    /SourceSystem administration/,
    /@mui\//,
  );
});
