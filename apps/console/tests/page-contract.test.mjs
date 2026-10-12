import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const app = await readFile(new URL('../src/App.tsx', import.meta.url), 'utf8');
const pages = await readFile(new URL('../src/ControlPlanePages.tsx', import.meta.url), 'utf8');
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
  for (const route of requiredRoutes) {
    assert.match(app, new RegExp(route.replace('/', '\\/')));
  }
});

test('browser client enforces same-origin API routing', () => {
  assert.match(api, /path\.startsWith\('\/api\/'\)/);
  assert.doesNotMatch(api, /VITE_.*BACKEND|BACKEND_ORIGIN/);
  assert.match(api, /credentials:\s*'same-origin'/);
});

test('console never uses browser storage for tokens, secrets, or authority', () => {
  const source = `${app}\n${pages}\n${integrationOperations}\n${authorityPage}\n${api}`;
  assert.doesNotMatch(source, /localStorage|sessionStorage/);
  assert.doesNotMatch(source, /Authorization:\s*['"`]/);
  assert.doesNotMatch(source, /connector-worker\/v1|provisioning-tasks\/.+:claim|lease\/renew/);
});

test('server authorization and accepted public surfaces stay explicit', () => {
  assert.match(pages, /Server authorization is final/);
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
  assert.match(pages, /secret\|password\|token\|private\|raw\|configuration\|referenceKey/i);
  assert.match(pages, /Configured \/ redacted/);
});

test('authoritative mutations retain revision and idempotency semantics', () => {
  assert.match(api, /If-Match/);
  assert.match(api, /Idempotency-Key/);
  assert.match(pages, /requireEtag/);
  assert.match(pages, /idempotent/);
});
