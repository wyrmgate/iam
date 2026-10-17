import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const api = await readFile(new URL('../src/api.ts', import.meta.url), 'utf8');
const platformApi = await readFile(new URL('../src/platformTenantsApi.ts', import.meta.url), 'utf8');
const page = await readFile(new URL('../src/PlatformTenantsPage.tsx', import.meta.url), 'utf8');
const detail = await readFile(new URL('../src/PlatformTenantDetailPage.tsx', import.meta.url), 'utf8');
const navigation = await readFile(new URL('../src/consoleNavigation.ts', import.meta.url), 'utf8');
const app = await readFile(new URL('../src/App.tsx', import.meta.url), 'utf8');
const architecture = await readFile(new URL('../../../docs/architecture/platform-authority-and-application-tenancy.md', import.meta.url), 'utf8');
const tenantManagement = await readFile(new URL('../../../docs/engineering/tenant-management-console.md', import.meta.url), 'utf8');

test('Platform Tenant collection and detail remain distinct from current-Tenant management authority', () => {
  assert.match(navigation, /id: 'platform-tenants', path: '\/platform\/tenants', label: 'Tenants', group: 'platform', platformOwnerOnly: true/);
  assert.match(navigation, /platformTenantMatch/);
  assert.match(navigation, /params: \{ tenantId \}/);
  assert.match(navigation, /platformTenantDetailPath/);
  assert.match(navigation, /id: 'tenant', path: '\/tenant', label: 'Tenant Management', group: 'administration'/);
  assert.match(app, /<PlatformTenantsPage onSelect=/);
  assert.match(app, /<PlatformTenantDetailPage tenantId=/);
});

test('Platform Tenant navigation consumes a server-derived viable Platform Owner hint', () => {
  assert.match(api, /viablePlatformOwner: boolean/);
  assert.match(navigation, /if \(route\.platformOwnerOnly\) return authority\.viablePlatformOwner/);
  assert.doesNotMatch(navigation, /GLOBAL.*platform/i);
  assert.doesNotMatch(page, /super admin/i);
  assert.doesNotMatch(detail, /super admin/i);
});

test('Platform Tenant collection and detail use protected server endpoints', () => {
  assert.match(platformApi, /\/console\/platform\/tenants/);
  assert.match(platformApi, /getPlatformTenant/);
  assert.match(platformApi, /encodeURIComponent\(tenantId\)/);
  assert.match(page, /listPlatformTenants/);
  assert.match(page, /onSelect\(tenant\.id\)/);
  assert.match(detail, /getPlatformTenant\(tenantId/);
  assert.match(detail, /current viable Platform Owner/);
});

test('Platform Tenant detail uses Tenant Management presentation but only canonical metadata', () => {
  for (const field of ['displayName', 'id', 'revision', 'createdAt', 'updatedAt']) {
    assert.match(detail, new RegExp(`state\\.tenant\\.${field}`));
  }
  assert.match(detail, /Tenant Management presentation in deployment inspection mode/);
  assert.match(detail, /does not change your current Tenant context/);
  assert.match(detail, /tenant-scoped administrative authority/);
  assert.match(detail, /Tenant-owned Identity, Principal, Access, Governance, Credential, Integration and Audit workspaces are intentionally not exposed/);
  assert.match(detail, /excludes Tenant lifecycle\/status, code, description, subscription, domains, tenant-owned IAM data and mutation controls/);
  assert.doesNotMatch(detail, /tenant\.status|tenant\.code|tenant\.description|tenant\.owner/);
});

test('Platform Tenant detail is responsive without introducing a second authority model', () => {
  assert.match(detail, /display: \{ xs: 'none', sm: 'flex' \}/);
  assert.match(detail, /display: \{ xs: 'flex', sm: 'none' \}/);
  assert.match(detail, /Back to Tenant inventory/);
  assert.match(detail, /Platform inspection/);
});

test('Platform Tenant detail preserves authorization and not-found states', () => {
  assert.match(detail, /error\.status === 403/);
  assert.match(detail, /error\.status === 404/);
  assert.match(detail, /Tenant not found/);
});

test('living contracts keep deployment inspection separate from tenant context switching', () => {
  assert.match(architecture, /\/platform\/tenants/);
  assert.match(architecture, /viable `PLATFORM_OWNER`/);
  assert.match(architecture, /does not change the current Tenant context/);
  assert.match(architecture, /browser.*not.*authoritative/i);
  assert.match(tenantManagement, /does not switch authenticated Tenant context/);
  assert.match(tenantManagement, /does not grant tenant-scoped Administration authority/);
});
