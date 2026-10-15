import test from 'node:test';
import { assertSourceContains, readConsoleSources } from './helpers/source-contract.mjs';

const { page, css, app } = await readConsoleSources({
  page: 'workspaces/IdentityCollectionPage.tsx',
  css: 'identity-golden.css',
  app: 'App.tsx',
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

test('Identity collection has dedicated desktop table and mobile card presentations', () => {
  assertSourceContains(
    page,
    /identity-golden-table-wrap/,
    /identity-golden-mobile-list/,
    /identity-golden-mobile-card/,
  );
  assertSourceContains(
    css,
    /grid-template-columns:196px minmax\(0,1fr\)/,
    /background:linear-gradient\(180deg,#071c36/,
    /identity-golden-mobile-list\{display:grid/,
    /identity-golden-table-wrap\{display:none/,
  );
});

test('App uses the golden collection only for the collection route and preserves Identity detail', () => {
  assertSourceContains(
    app,
    /IdentityCollectionPage/,
    /route === 'identities' && !identityId/,
    /route === 'identities' && identityId && \(\s*<IdentityWorkspace/,
  );
});
