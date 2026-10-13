import assert from 'node:assert/strict';
import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSources } from './helpers/source-contract.mjs';

const {
  app,
  shell,
  navigation,
  shellFoundation,
  ui,
  uiPage,
  pages,
  semanticWorkspace,
  integrationOperations,
  authorityPage,
  api,
} = await readConsoleSources({
  app: 'App.tsx',
  shell: 'ShellChrome.tsx',
  navigation: 'consoleNavigation.ts',
  shellFoundation: 'shell-foundation.css',
  ui: 'ui/index.tsx',
  uiPage: 'ui/page.tsx',
  pages: 'ControlPlanePages.tsx',
  semanticWorkspace: 'SemanticWorkspace.tsx',
  integrationOperations: 'IntegrationOperationsPage.tsx',
  authorityPage: 'CurrentAdministrativeAuthorityPage.tsx',
  api: 'api.ts',
});

const requiredRoutes = [
  '/identities',
  '/principals',
  '/catalog',
  '/access',
  '/requests',
  '/reviews',
  '/policies',
  '/credentials',
  '/integrations',
  '/administration',
  '/audit',
];

test('shared navigation registry exposes every operator page', () => {
  for (const route of requiredRoutes) {
    assert.match(navigation, new RegExp(route.replace('/', '\\/')));
  }
  assertSourceContains(shell, /consoleNavigation/);
});

test('application shell uses labeled collapsible navigation, command palette, and server-derived context', () => {
  assertSourceContains(
    shell,
    /navigation-dock/,
    /dock-label/,
    /navigationCollapsed/,
    /mobileNavigationOpen/,
    /command-palette/,
    /context-rail/,
    /getCurrentAdministrativeAuthority/,
    /getSystemInfo/,
    /Server authorization remains final/,
  );
  assertSourceContains(navigation, /provisioning-job/, /reconciliation-run/);
  assertSourceExcludes(shell, /localStorage|sessionStorage/);
});

test('assigned governance work remains discoverable without generic administrative eligibility', () => {
  assertSourceContains(
    navigation,
    /path: '\/requests'[\s\S]*visibleWithoutAdministrativeEligibility: true/,
    /path: '\/reviews'[\s\S]*visibleWithoutAdministrativeEligibility: true/,
    /path: '\/policies'[\s\S]*resourceTypes:/,
  );
});

test('mobile navigation exposes all destinations instead of hiding later groups', () => {
  assertSourceContains(
    shellFoundation,
    /navigation-dock\.mobile-open/,
    /mobile-nav-scrim/,
    /\.dock-primary \.dock-item:nth-of-type\(n\+6\)[\s\S]*display:\s*grid/,
  );
});

test('page foundation exposes deliberate width modes and a reusable toolbar through the stable ui barrel', () => {
  assertSourceContains(
    uiPage,
    /type PageWidth = 'narrow' \| 'standard' \| 'wide'/,
    /data-page-width=\{width\}/,
    /export function PageToolbar/,
    /minWidth:\s*0/,
  );
  assertSourceContains(ui, /export \{ Page, PageHeader, PageToolbar, Surface \} from '\.\/page'/);
});

test('browser client enforces same-origin API routing', () => {
  assertSourceContains(api, /path\.startsWith\('\/api\/'\)/, /credentials:\s*'same-origin'/);
  assertSourceExcludes(api, /VITE_.*BACKEND|BACKEND_ORIGIN/);
});

test('console never uses browser storage for tokens, secrets, or authority', () => {
  const source = `${app}\n${shell}\n${navigation}\n${pages}\n${semanticWorkspace}\n${integrationOperations}\n${authorityPage}\n${api}`;
  assertSourceExcludes(
    source,
    /localStorage|sessionStorage/,
    /Authorization:\s*['"`]/,
    /connector-worker\/v1|provisioning-tasks\/.+:claim|lease\/renew/,
  );
});

test('server authorization and accepted public surfaces stay explicit', () => {
  assertSourceContains(semanticWorkspace, /Server authorization is final/);
  assertSourceExcludes(pages, /issue=\{265\}/, /issue=\{266\}/);
  assertSourceContains(pages, /CurrentAdministrativeAuthorityPage/, /IntegrationOperationsPage/);
  assertSourceContains(api, /current-administrative-authority/);
  assertSourceContains(navigation, /visibleConsoleRoutes/);
  assertSourceContains(authorityPage, /operation is re-authorized by the server/);
});

test('integration operations consume only public read APIs and preserve authority separation', () => {
  assertSourceContains(
    integrationOperations,
    /provisioning-jobs/,
    /provisioning-tasks/,
    /provisioning-attempts/,
    /reconciliation-runs/,
    /Operational \/ non-authoritative/,
    /AccessAssignment.*authoritative/,
  );
  assertSourceExcludes(integrationOperations, /:claim|lease\/renew|:complete|connector-worker\/v1/);
});

test('secret-shaped detail fields are redacted', () => {
  assertSourceContains(
    semanticWorkspace,
    /secret\|password\|token\|private\|raw\|configuration\|referenceKey/i,
    /Configured \/ redacted/,
  );
});

test('authoritative mutations retain revision and idempotency semantics', () => {
  assertSourceContains(api, /If-Match/, /Idempotency-Key/);
  assertSourceContains(semanticWorkspace, /requireEtag/, /idempotent/);
});
