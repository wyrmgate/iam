import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSource } from './helpers/source-contract.mjs';

const principals = await readConsoleSource('workspaces/PrincipalsWorkspace.tsx');

test('Principal workspace preserves canonical Identity and Principal semantics', () => {
  assertSourceContains(
    principals,
    /Principal is the technical account, not the governed subject/,
    /may be temporarily uncorrelated/,
    /Provider observation does not become Principal authority/,
    /Credentials remain separate/,
    /operation-time server authorization remains final/,
  );
  assertSourceExcludes(
    principals,
    /provider.*mutation.*register/i,
    /localStorage|sessionStorage/,
  );
});

test('Principal collection is the normal entry point with deliberate registration and support lookup', () => {
  assertSourceContains(
    principals,
    /title=\{<span id="principals-page-title">Principals<\/span>\}/,
    /title="Authoritative Principals"/,
    /<DataTable minWidth=\{980\} ariaLabel="Authoritative Principals">/,
    /Technical account/,
    /ApplicationTarget/,
    /Identity correlation/,
    /Lifecycle/,
    /Updated/,
    /Register Principal/,
    /Open by Principal ID/,
    /title="Open Principal by ID"/,
    /advanced support path/,
    /Load more Principals/,
  );
  assertSourceExcludes(
    principals,
    /<Surface\s+title="Register technical account"/,
    /<Surface title="Open Principal by ID"/,
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

test('Principal detail uses focused object hierarchy and definition-style metadata', () => {
  assertSourceContains(
    principals,
    /Back to Principals/,
    /Principal · technical account/,
    /Principal ID:/,
    /Technical account/,
    /Governed Identity relationship/,
    /DefinitionList/,
    /StateIndicator label=\{principal\.lifecycleState\}/,
    /Open governed Identity/,
    /identityDetailPath\(principal\.identityId!\)/,
    /Authority boundaries remain separate/,
  );
  assertSourceExcludes(
    principals,
    /function DetailDatum/,
    /borderRadius: 1, bgcolor: 'background\.default'/,
  );
});

test('Principal correlation is explicit, one-way, revision-guarded, idempotent, and consequence-confirmed', () => {
  assertSourceContains(
    principals,
    /principalCorrelationPath/,
    /body: \{ identityId: normalizedIdentityId \}/,
    /etag: detail\.etag/,
    /idempotent: true/,
    /Correlate to Identity/,
    /Review Identity correlation/,
    /title="Correlate Principal to governed Identity"/,
    /title="Correlate this Principal\?"/,
    /already correlated Principal cannot be reassigned or cleared/,
    /Correlation does not grant access or mutate provider state/,
    /if \(!detail \|\| detail\.data\.identityId \|\| !detail\.etag\) return/,
  );
});

test('Principal collection preserves stable technical context at narrow widths through shared table overflow', () => {
  assertSourceContains(
    principals,
    /principal\.nativePrincipalKey/,
    /principal\.id/,
    /principal\.applicationTargetId/,
    /principal\.identityId/,
    /principal\.lifecycleState/,
    /principal\.updatedAt/,
    /DataTableRow key=\{principal\.id\} interactive/,
  );
  assertSourceExcludes(
    principals,
    /display: \{ xs: 'none'/,
    /display: \{ xs: 'grid', sm: 'none'/,
  );
});

test('Principal workspace renders explicit accessible operational states and reuses shared UI', () => {
  assertSourceContains(
    principals,
    /state="loading"/,
    /state="empty"/,
    /state = 'forbidden'/,
    /state = 'not-found'/,
    /state = 'conflict'/,
    /state = 'stale'/,
    /ConfirmDialog/,
    /Modal/,
    /DataTable/,
    /StateIndicator/,
    /StatusChip/,
    /TextInput/,
  );
  assertSourceExcludes(
    principals,
    /@mui\//,
    /WyrmgateUiProvider|createTheme|ThemeProvider/,
  );
});
