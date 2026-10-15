import assert from 'node:assert/strict';
import test from 'node:test';
import { assertSourceContains, readConsoleSources } from './helpers/source-contract.mjs';

const { collection, detail, app, main } = await readConsoleSources({
  collection: 'workspaces/IdentityCollectionPage.tsx',
  detail: 'workspaces/IdentityWorkspace.tsx',
  app: 'App.tsx',
  main: 'main.tsx',
});

test('Identity collection implements the approved discovery and dense result composition', () => {
  assertSourceContains(
    collection,
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
    collection,
    /<DataTable ariaLabel="Governed identities"/,
    /display: \{ xs: 'none', sm: 'block' \}/,
    /display: \{ xs: 'grid', sm: 'none' \}/,
    /<StateIndicator label=\{identity\.lifecycleState\}/,
    /identity\.id/,
    /Updated \{formatDateTime\(identity\.updatedAt\)\}/,
  );
  assert.doesNotMatch(collection, /identity-golden-table-wrap|identity-golden-mobile-list|identity-golden-mobile-card/);
  assert.doesNotMatch(collection, /[⌕▣＋⋮⌄]/);
});

test('Identity 360 uses the shared Object 360 enterprise composition', () => {
  assertSourceContains(
    detail,
    /<Page width="wide">/,
    /<PageHeader/,
    /Governed state and actions/,
    /<StateIndicator label=\{identity\.lifecycleState\}/,
    /<DataTable ariaLabel="Governed canonical attributes"/,
    /<DataTable ariaLabel="Identity Principals"/,
    /<DataTable ariaLabel="Identity access assignments"/,
    /<DataTable ariaLabel="Identity effective access"/,
    /<DataTable ariaLabel="Identity access review campaigns"/,
    /<DataTable ariaLabel="Direct Identity AuditRecord evidence"/,
  );
  assert.doesNotMatch(detail, /identity-detail-golden\.css|identity360-[a-z-]+/);
  assert.doesNotMatch(detail, /[←✎⚙⌄⋮✓›ⓘ⚠]/);
});

test('Identity detail preserves the six semantic views and capability boundaries', () => {
  assertSourceContains(
    detail,
    /value="overview" label="Overview"/,
    /value="profile" label="Governed profile"/,
    /value="principals" label="Principals"/,
    /value="access" label="Access"/,
    /value="governance" label="Governance"/,
    /value="evidence" label="Evidence"/,
    /Assignments are authoritative business intent\. Effective access is a derived projection\./,
    /Access request history is intentionally not shown here\./,
    /This is not an exhaustive cross-capability activity history\./,
  );
});

test('Identity routes share enterprise page geometry without page-specific bridge CSS', () => {
  assertSourceContains(collection, /<Page width="wide"/);
  assertSourceContains(detail, /<Page width="wide">/);
  assert.doesNotMatch(main, /identity-page-standard\.css|identity-detail-golden\.css|identity-golden\.css/);
});

test('App preserves the dedicated Identity collection and detail route split', () => {
  assertSourceContains(
    app,
    /IdentityCollectionPage/,
    /route === 'identities' && !identityId/,
    /route === 'identities' && identityId && \(\s*<IdentityWorkspace/,
  );
});
