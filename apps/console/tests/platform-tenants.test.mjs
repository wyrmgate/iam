import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const api = await readFile(new URL('../src/api.ts', import.meta.url), 'utf8');
const platformApi = await readFile(new URL('../src/platformTenantsApi.ts', import.meta.url), 'utf8');
const page = await readFile(new URL('../src/PlatformTenantsPage.tsx', import.meta.url), 'utf8');
const navigation = await readFile(new URL('../src/consoleNavigation.ts', import.meta.url), 'utf8');
const app = await readFile(new URL('../src/App.tsx', import.meta.url), 'utf8');
const architecture = await readFile(new URL('../../../docs/architecture/platform-authority-and-application-tenancy.md', import.meta.url), 'utf8');

test('Platform Tenant route is distinct from current-Tenant administration', () => {
  assert.match(navigation, /id: 'platform-tenants', path: '\/platform\/tenants', label: 'Tenants', group: 'platform', platformOwnerOnly: true/);
  assert.match(navigation, /id: 'tenant', path: '\/tenant', label: 'Current Tenant', group: 'administration'/);
  assert.match(app, /route === 'platform-tenants'/);
  assert.match(app, /<PlatformTenantsPage/);
});

test('Platform Tenant navigation consumes a server-derived viable Platform Owner hint', () => {
  assert.match(api, /viablePlatformOwner: boolean/);
  assert.match(navigation, /if \(route\.platformOwnerOnly\) return authority\.viablePlatformOwner/);
  assert.doesNotMatch(navigation, /GLOBAL.*platform/i);
  assert.doesNotMatch(page, /super admin/i);
});

test('Platform Tenant inventory uses the protected bounded server endpoint', () => {
  assert.match(platformApi, /\/console\/platform\/tenants/);
  assert.match(platformApi, /withCursor/);
  assert.match(page, /listPlatformTenants/);
  assert.match(page, /Load more/);
  assert.match(page, /Platform Owner authority is required|current viable Platform Owner/);
});

test('Platform Tenant inventory renders only canonical Tenant metadata', () => {
  for (const field of ['displayName', 'id', 'revision', 'createdAt', 'updatedAt']) {
    assert.match(page, new RegExp(`tenant\\.${field}`));
  }
  assert.doesNotMatch(page, /tenant\.status|tenant\.code|tenant\.description|tenant\.owner/);
  assert.match(page, /Tenant lifecycle\/status, code and description are not part of the current canonical Tenant contract/);
});

test('living architecture keeps deployment inventory separate from tenant context switching', () => {
  assert.match(architecture, /\/platform\/tenants/);
  assert.match(architecture, /viable `PLATFORM_OWNER`/);
  assert.match(architecture, /does not change the current Tenant context/);
  assert.match(architecture, /browser.*not.*authoritative/i);
});
