import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSources } from './helpers/source-contract.mjs';

const { app, overview } = await readConsoleSources({
  app: 'App.tsx',
  overview: 'OverviewPage.tsx',
});

test('overview prioritizes current server-derived operator context', () => {
  assertSourceContains(
    overview,
    /Current control-plane context/,
    /label="Runtime"/,
    /label="Tenant"/,
    /label="Governed actor"/,
    /authorityValue\?\.tenantId/,
    /authorityValue\?\.actorIdentityId/,
    /authorityValue\.evaluatedAt/,
    /authorityValue\.authorities\.length/,
  );
});

test('overview makes loading, runtime failure, authority failure, and empty authority explicit', () => {
  assertSourceContains(
    overview,
    /Loading current control-plane context/,
    /Runtime status unavailable/,
    /Administrative authority unavailable/,
    /Authority projection unavailable/,
    /No effective administrative authority sources/,
    /StatePanel/,
    /StatusChip/,
  );
});

test('overview preserves control-plane trust and authorization boundaries', () => {
  assertSourceContains(
    overview,
    /server-derived operating context/i,
    /no-store point-in-time Administration projection/,
    /operation-time server authorization remains final/,
    /browser does not guess current authority/,
    /browser calls only \/api\/\*/,
    /server 403 remains final/,
  );
  assertSourceExcludes(
    overview,
    /localStorage|sessionStorage/,
    /Authorization:\s*['"`]/,
    /VITE_.*BACKEND|BACKEND_ORIGIN/,
    /@mui\//,
  );
});

test('overview consumes existing shared UI primitives and is wired through the application route', () => {
  assertSourceContains(
    overview,
    /from '\.\/ui'/,
    /<Page width="wide">/,
    /<PageHeader/,
    /<Surface/,
    /<StatePanel/,
    /<StatusChip/,
  );
  assertSourceContains(app, /OverviewPage/, /route === 'overview'/);
  assertSourceExcludes(overview, /WyrmgateUiProvider|createTheme|ThemeProvider/);
});
