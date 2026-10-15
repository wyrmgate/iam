import assert from 'node:assert/strict';
import test from 'node:test';
import { assertSourceContains, readConsoleSources } from './helpers/source-contract.mjs';

const { page, geometry, app, main } = await readConsoleSources({
  page: 'workspaces/IdentityCollectionPage.tsx',
  geometry: 'identity-page-standard.css',
  app: 'App.tsx',
  main: 'main.tsx',
});

test('Identity collection implements the approved discovery and dense result composition', () => {
  assertSourceContains(
    page,
    /Identity · governed subjects/,
    /Search display name/,
    /All types/,
    /All lifecycles/,
    /Open by Identity ID/,
    /Clear filters/,
    /Governed subject/,
    /Load more identities/,
  );
});

test('Identity collection uses shared desktop table and semantic mobile record presentations', () => {
  assertSourceContains(
    page,
    /<DataTable ariaLabel="Governed identities"/,
    /display: \{ xs: 'none', sm: 'block' \}/,
    /display: \{ xs: 'grid', sm: 'none' \}/,
    /<StateIndicator label=\{identity\.lifecycleState\}/,
    /identity\.id/,
    /Updated \{formatDateTime\(identity\.updatedAt\)\}/,
  );
  assert.doesNotMatch(page, /identity-golden-table-wrap|identity-golden-mobile-list|identity-golden-mobile-card/);
  assert.doesNotMatch(page, /[⌕▣＋⋮⌄]/);
});

test('Identity collection uses shared wide Page geometry while detail retains its current wide bridge', () => {
  assertSourceContains(page, /<Page width="wide"/);
  assertSourceContains(
    geometry,
    /\.shell-workspace \.identity360-page/,
    /width: min\(96rem, 100%\) !important/,
    /max-width: 100% !important/,
    /margin: 0 auto !important/,
  );
  assertSourceContains(main, /identity-page-standard\.css/);
  assert.doesNotMatch(main, /identity-golden\.css/);
});

test('App uses the enterprise collection only for the collection route and preserves Identity detail', () => {
  assertSourceContains(
    app,
    /IdentityCollectionPage/,
    /route === 'identities' && !identityId/,
    /route === 'identities' && identityId && \(\s*<IdentityWorkspace/,
  );
});
