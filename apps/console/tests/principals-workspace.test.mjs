import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSource } from './helpers/source-contract.mjs';

const principals = await readConsoleSource('workspaces/PrincipalsWorkspace.tsx');

test('Principal workspace preserves canonical Identity and Principal semantics', () => {
  assertSourceContains(
    principals,
    /Principal is the technical account, not the governed subject/,
    /may be temporarily uncorrelated/,
    /Provider observation does not become Principal authority/,
    /operation-time server authorization remains final/,
  );
  assertSourceExcludes(
    principals,
    /provider.*mutation.*register/i,
    /localStorage|sessionStorage/,
  );
});

test('Principal registration uses only the accepted uncorrelated public shape', () => {
  assertSourceContains(
    principals,
    /applicationTargetId: normalizedApplicationTargetId/,
    /nativePrincipalKey: normalizedNativePrincipalKey/,
    /idempotent: true/,
    /Registration remains uncorrelated/,
    /does not select or create a governed Identity/,
    /Do not enter passwords, tokens, private keys, or connector secrets/,
  );
  assertSourceExcludes(
    principals,
    /body:\s*\{[^}]*identityId[^}]*nativePrincipalKey/s,
    /body:\s*\{[^}]*lifecycleState[^}]*nativePrincipalKey/s,
    /body:\s*\{[^}]*kind[^}]*nativePrincipalKey/s,
    /body:\s*\{[^}]*(password|secret|token|privateKey)[^}]*\}/is,
  );
});

test('Principal correlation is explicit, one-way, revision-guarded, and idempotent', () => {
  assertSourceContains(
    principals,
    /principalCorrelationPath/,
    /body: \{ identityId: normalizedIdentityId \}/,
    /etag: detail\.etag/,
    /idempotent: true/,
    /already correlated Principal cannot be reassigned or cleared/,
    /if \(!detail \|\| detail\.data\.identityId \|\| !detail\.etag\) return/,
  );
});

test('Principal collection and detail distinguish account, target, correlation, and lifecycle', () => {
  assertSourceContains(
    principals,
    /Native principal key/,
    /ApplicationTarget/,
    /Identity correlation/,
    /Lifecycle/,
    /Correlated/,
    /Uncorrelated/,
    /Authoritative Principals/,
    /Open Principal by ID/,
  );
});

test('Principal workspace renders explicit accessible operational states and reuses shared UI', () => {
  assertSourceContains(
    principals,
    /state="loading"/,
    /state="empty"/,
    /state="forbidden"|state = 'forbidden'/,
    /state = 'not-found'/,
    /state = 'conflict'/,
    /state = 'stale'/,
    /ConfirmDialog/,
    /DataTable/,
    /StatusChip/,
    /TextInput/,
  );
  assertSourceExcludes(
    principals,
    /@mui\//,
    /WyrmgateUiProvider|createTheme|ThemeProvider/,
  );
});
