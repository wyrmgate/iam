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

test('Platform Tenant detail aligns with shared object-detail vocabulary and canonical metadata only', () => {
  for (const field of ['displayName', 'id', 'revision', 'createdAt', 'updatedAt']) {
    assert.match(detail, new RegExp(`state\\.tenant\\.${field}`));
  }
  assert.match(detail, /<PageHeader/);
  assert.match(detail, /eyebrow="Platform · Tenant inspection"/);
  assert.match(detail, /Platform inspection · read-only/);
  assert.match(detail, /Deployment inspection boundary/);
  assert.match(detail, /does not change your current Tenant context/);
  assert.match(detail, /grant tenant-scoped administrative authority/);
  assert.match(detail, /Tenant-owned capability workspaces are intentionally not linked/);
  assert.match(detail, /Tenant lifecycle\/status, code, description, subscription, domains, tenant-owned IAM data and mutation controls/);
  assert.doesNotMatch(detail, /tenant\.status|tenant\.code|tenant\.description|tenant\.owner/);
});

test('Platform Tenant detail uses shared responsive header/action behavior instead of duplicate mobile chrome', () => {
  assert.match(detail, /<Page width="standard"/);
  assert.match(detail, /<Button variant="outlined" onClick=\{onBack\}>Back to Tenants<\/Button>/);
  assert.doesNotMatch(detail, /display: \{ xs: 'none', sm: 'flex' \}/);
  assert.doesNotMatch(detail, /display: \{ xs: 'flex', sm: 'none' \}/);
  assert.doesNotMatch(detail, /borderRadius: '50%'/);
  assert.doesNotMatch(detail, /Back to Tenant inventory/);
});

test('Platform Tenant detail preserves authorization and not-found states', () => {
  assert.match(detail, /error\.status === 403/);
  assert.match(detail, /error\.status === 404/);
  assert.match(detail, /Tenant not found/);
  assert.match(detail, /state="informational"/);
  assert.match(detail, /state="loading"/);
  assert.match(detail, /state="error"/);
});

test('living contracts keep deployment inspection separate from tenant context switching', () => {
  assert.match(architecture, /\/platform\/tenants/);
  assert.match(architecture, /viable `PLATFORM_OWNER`/);
  assert.match(architecture, /does not change the current Tenant context/);
  assert.match(architecture, /browser.*not.*authoritative/i);
  assert.match(tenantManagement, /does not switch authenticated Tenant context/);
  assert.match(tenantManagement, /does not grant tenant-scoped Administration authority/);
});
