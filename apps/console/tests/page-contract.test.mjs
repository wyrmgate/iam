import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const app = await readFile(new URL('../src/App.tsx', import.meta.url), 'utf8');
const pages = await readFile(new URL('../src/ControlPlanePages.tsx', import.meta.url), 'utf8');
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
  const source = `${app}\n${pages}\n${authorityPage}\n${api}`;
  assert.doesNotMatch(source, /localStorage|sessionStorage/);
  assert.doesNotMatch(source, /Authorization:\s*['"`]/);
  assert.doesNotMatch(source, /connector-worker\/v1|provisioning-tasks\/.+:claim|lease\/renew/);
});

test('server authorization and remaining API gaps stay explicit', () => {
  assert.match(pages, /Server authorization is final/);
  assert.doesNotMatch(pages, /issue=\{265\}/);
  assert.match(pages, /CurrentAdministrativeAuthorityPage/);
  assert.match(pages, /issue=\{266\}/);
  assert.match(api, /current-administrative-authority/);
  assert.match(app, /visibleNavigation\(authority\)/);
  assert.match(authorityPage, /operation is re-authorized by the server/);
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
