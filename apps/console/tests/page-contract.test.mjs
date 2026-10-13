import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const app = await readFile(new URL('../src/App.tsx', import.meta.url), 'utf8');
const shell = await readFile(new URL('../src/ShellChrome.tsx', import.meta.url), 'utf8');
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

test('job-oriented navigation exposes every operator page', () => {
  const source = `${app}\n${shell}`;
  for (const route of requiredRoutes) {
    assert.match(source, new RegExp(route.replace('/', '\\/')));
  }
});

test('application shell uses a dock, command palette, and server-derived context', () => {
  assert.match(shell, /navigation-dock/);
  assert.match(shell, /command-palette/);
  assert.match(shell, /context-rail/);
  assert.match(shell, /getCurrentAdministrativeAuthority/);
  assert.match(shell, /getSystemInfo/);
  assert.match(shell, /Server authorization remains final/);
  assert.match(shell, /provisioning-job/);
  assert.match(shell, /reconciliation-run/);
  assert.doesNotMatch(shell, /localStorage|sessionStorage/);
});

test('browser client enforces same-origin API routing', () => {
  assert.match(api, /path\.startsWith\('\/api\/'\)/);
  assert.doesNotMatch(api, /VITE_.*BACKEND|BACKEND_ORIGIN/);
  assert.match(api, /credentials:\s*'same-origin'/);
});

test('console never uses browser storage for tokens, secrets, or authority', () => {
  const source = `${app}\n${shell}\n${pages}\n${semanticWorkspace}\n${integrationOperations}\n${authorityPage}\n${api}`;
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
  assert.match(app, /visibleNavigation\(authority\)/);
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
