import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSources } from './helpers/source-contract.mjs';

const { app, overview, api, styles } = await readConsoleSources({
  app: 'App.tsx',
  overview: 'OverviewPage.tsx',
  api: 'iamOperationsApi.ts',
  styles: 'iam-operations.css',
});

test('overview implements the IAM Operations golden-page hierarchy', () => {
  assertSourceContains(
    overview,
    /IAM Operations/,
    /What needs my attention right now, and is IAM operating correctly\?/,
    /Pending approvals/,
    /Pending reviews/,
    /Final provisioning failures/,
    /Failed reconciliations/,
    /My Work/,
    /Provisioning Health/,
    /Reconciliation Health/,
    /My Governance Activity/,
    /Security & Administration Evidence/,
    /Environment Snapshot/,
  );
});

test('overview keeps governance, fulfillment, reconciliation, and audit semantics distinct', () => {
  assertSourceContains(
    overview,
    /Assigned Governance decisions/,
    /Immutable provider execution attempts; not Governance authorization/,
    /Run outcome and effective completeness remain separate/,
    /Actor-scoped Governance evidence only/,
    /not a tenant risk score/,
    /Audit evidence signals, not current authorization or incident state/,
    /unavailable data is never shown as zero/,
  );
  assertSourceExcludes(
    overview,
    /localStorage|sessionStorage/,
    /Authorization:\s*['"`]/,
    /VITE_.*BACKEND|BACKEND_ORIGIN/,
    /@mui\//,
  );
});

test('overview uses a bounded first-party read model rather than cursor-page aggregation', () => {
  assertSourceContains(
    api,
    /\/console\/iam-operations/,
    /window, workType, workSort/,
    /OverviewStatus/,
    /NOT_AUTHORIZED/,
    /UNAVAILABLE/,
  );
  assertSourceContains(
    overview,
    /getIamOperationsOverview/,
    /AbortController/,
    /No counts are inferred from browser state/,
  );
  assertSourceExcludes(overview, /while\s*\([^)]*cursor|nextCursor/);
});

test('overview is responsive and accessible while remaining wired to the root route', () => {
  assertSourceContains(
    overview,
    /from '\.\/ui'/,
    /<Page width="wide">/,
    /aria-live="polite"/,
    /View data table/,
    /<caption className="sr-only">/,
    /prefers-reduced-motion|iam-chart-table/,
  );
  assertSourceContains(
    styles,
    /@media\s*\(\s*max-width:\s*760px\s*\)/,
    /@media\s*\(\s*prefers-reduced-motion:\s*reduce\s*\)/,
    /iam-attention-grid/,
  );
  assertSourceContains(app, /OverviewPage/, /route === 'overview'/);
  assertSourceExcludes(overview, /WyrmgateUiProvider|createTheme|ThemeProvider/);
});
