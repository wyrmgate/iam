import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSources } from './helpers/source-contract.mjs';

const { identity } = await readConsoleSources({
  identity: 'workspaces/IdentityWorkspace.tsx',
});

test('Identity workspace keeps governed subjects distinct from technical Principals', () => {
  assertSourceContains(
    identity,
    /Identity · governed subjects/,
    /Identity is the governed subject/,
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
    /body: \{ displayName \}/,
    /:merge/,
    /absorbedRevision: Number\(mergeValues\.absorbedRevision\)/,
    /:split/,
    /splitSelectionPresent/,
    /Explicit semantic Identity commands/,
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

test('Identity collection uses the golden-page table without inventing unsupported browser search', () => {
  assertSourceContains(
    identity,
    /Search display name/,
    /All types/,
    /All lifecycles/,
    /Open by Identity ID/,
    /Server-backed search coming in the bounded query contract/,
    /Governed subject/,
    /Updated/,
    /Load more identities/,
    /Create governed Identity/,
  );
  assertSourceExcludes(
    identity,
    /identities\.filter\(/,
    /identities\.find\([^)]*displayName/,
    /localStorage|sessionStorage/,
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
    /Authorization:\s*['"`]/,
    /tenantId\s*:/,
    /actorIdentityId\s*:/,
    /SourceSystem administration/,
    /@mui\//,
  );
});
