import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const app = await readFile(new URL('../src/App.tsx', import.meta.url), 'utf8');
const shell = await readFile(new URL('../src/ShellChrome.tsx', import.meta.url), 'utf8');
const navigation = await readFile(new URL('../src/consoleNavigation.ts', import.meta.url), 'utf8');
const shellFoundation = await readFile(new URL('../src/shell-foundation.css', import.meta.url), 'utf8');
const ui = await readFile(new URL('../src/ui/index.tsx', import.meta.url), 'utf8');
const uiPage = await readFile(new URL('../src/ui/page.tsx', import.meta.url), 'utf8');
const pages = await readFile(new URL('../src/ControlPlanePages.tsx', import.meta.url), 'utf8');
const semanticWorkspace = await readFile(new URL('../src/SemanticWorkspace.tsx', import.meta.url), 'utf8');
const integrationOperations = await readFile(new URL('../src/IntegrationOperationsPage.tsx', import.meta.url), 'utf8');
const authorityPage = await readFile(new URL('../src/CurrentAdministrativeAuthorityPage.tsx', import.meta.url), 'utf8');
const api = await readFile(new URL('../src/api.ts', import.meta.url), 'utf8');

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
  assert.match(shell, /consoleNavigation/);
});

test('application shell uses labeled collapsible navigation, command palette, and server-derived context', () => {
  assert.match(shell, /navigation-dock/);
  assert.match(shell, /dock-label/);
  assert.match(shell, /navigationCollapsed/);
  assert.match(shell, /mobileNavigationOpen/);
  assert.match(shell, /command-palette/);
  assert.match(shell, /context-rail/);
  assert.match(shell, /getCurrentAdministrativeAuthority/);
  assert.match(shell, /getSystemInfo/);
  assert.match(shell, /Server authorization remains final/);
  assert.match(navigation, /provisioning-job/);
  assert.match(navigation, /reconciliation-run/);
  assert.doesNotMatch(shell, /localStorage|sessionStorage/);
});

test('assigned governance work remains discoverable without generic administrative eligibility', () => {
  assert.match(navigation, /path: '\/requests'[\s\S]*visibleWithoutAdministrativeEligibility: true/);
  assert.match(navigation, /path: '\/reviews'[\s\S]*visibleWithoutAdministrativeEligibility: true/);
  assert.match(navigation, /path: '\/policies'[\s\S]*resourceTypes:/);
});

test('mobile navigation exposes all destinations instead of hiding later groups', () => {
  assert.match(shellFoundation, /navigation-dock\.mobile-open/);
  assert.match(shellFoundation, /mobile-nav-scrim/);
  assert.match(shellFoundation, /\.dock-primary \.dock-item:nth-of-type\(n\+6\)[\s\S]*display:\s*grid/);
});

test('page foundation exposes deliberate width modes and a reusable toolbar through the stable ui barrel', () => {
  assert.match(uiPage, /type PageWidth = 'narrow' \| 'standard' \| 'wide'/);
  assert.match(uiPage, /data-page-width=\{width\}/);
  assert.match(uiPage, /export function PageToolbar/);
  assert.match(uiPage, /minWidth:\s*0/);
  assert.match(ui, /export \{ Page, PageHeader, PageToolbar, Surface \} from '\.\/page'/);
});

test('browser client enforces same-origin API routing', () => {
  assert.match(api, /path\.startsWith\('\/api\/'\)/);
  assert.doesNotMatch(api, /VITE_.*BACKEND|BACKEND_ORIGIN/);
  assert.match(api, /credentials:\s*'same-origin'/);
});

test('console never uses browser storage for tokens, secrets, or authority', () => {
  const source = `${app}\n${shell}\n${navigation}\n${pages}\n${semanticWorkspace}\n${integrationOperations}\n${authorityPage}\n${api}`;
  assert.doesNotMatch(source, /localStorage|sessionStorage/);
  assert.doesNotMatch(source, /Authorization:\s*['"`]/);
  assert.doesNotMatch(source, /connector-worker\/v1|provisioning-tasks\/.+:claim|lease\/renew/);
});

test('server authorization and accepted public surfaces stay explicit', () => {
  assert.match(semanticWorkspace, /Server authorization is final/);
  assert.doesNotMatch(pages, /issue=\{265\}/);
  assert.doesNotMatch(pages, /issue=\{266\}/);
  assert.match(pages, /CurrentAdministrativeAuthorityPage/);
  assert.match(pages, /IntegrationOperationsPage/);
  assert.match(api, /current-administrative-authority/);
  assert.match(navigation, /visibleConsoleRoutes/);
  assert.match(authorityPage, /operation is re-authorized by the server/);
});

test('integration operations consume only public read APIs and preserve authority separation', () => {
  assert.match(integrationOperations, /provisioning-jobs/);
  assert.match(integrationOperations, /provisioning-tasks/);
  assert.match(integrationOperations, /provisioning-attempts/);
  assert.match(integrationOperations, /reconciliation-runs/);
  assert.match(integrationOperations, /Operational \/ non-authoritative/);
  assert.match(integrationOperations, /AccessAssignment.*authoritative/);
  assert.doesNotMatch(integrationOperations, /:claim|lease\/renew|:complete|connector-worker\/v1/);
});

test('secret-shaped detail fields are redacted', () => {
  assert.match(semanticWorkspace, /secret\|password\|token\|private\|raw\|configuration\|referenceKey/i);
  assert.match(semanticWorkspace, /Configured \/ redacted/);
});

test('authoritative mutations retain revision and idempotency semantics', () => {
  assert.match(api, /If-Match/);
  assert.match(api, /Idempotency-Key/);
  assert.match(semanticWorkspace, /requireEtag/);
  assert.match(semanticWorkspace, /idempotent/);
});
