import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const page = await readFile(new URL('../src/TenantOverviewPage.tsx', import.meta.url), 'utf8');
const tenantApi = await readFile(new URL('../src/tenantContextApi.ts', import.meta.url), 'utf8');
const operationsApi = await readFile(new URL('../src/iamOperationsApi.ts', import.meta.url), 'utf8');
const app = await readFile(new URL('../src/App.tsx', import.meta.url), 'utf8');
const navigation = await readFile(new URL('../src/consoleNavigation.ts', import.meta.url), 'utf8');
const tenantManagement = await readFile(new URL('../../../docs/engineering/tenant-management-console.md', import.meta.url), 'utf8');

test('current Tenant route is presented as Tenant Management without changing route semantics', () => {
  assert.match(navigation, /\{ id: 'tenant', path: '\/tenant', label: 'Tenant Management', group: 'administration' \}/);
  assert.match(app, /route === 'tenant'/);
  assert.match(app, /<TenantOverviewPage authority=/);
  assert.doesNotMatch(navigation, /label: 'Current Tenant'/);
});

test('Tenant Management uses server-derived canonical Tenant metadata and existing operations projection', () => {
  assert.match(page, /getTenantContext/);
  assert.match(page, /getIamOperationsOverview\('24h', 'ALL', 'OLDEST'/);
  assert.match(tenantApi, /\/console\/tenant-context/);
  for (const field of ['displayName', 'id', 'revision', 'createdAt', 'updatedAt']) {
    assert.match(page, new RegExp(`tenant\\.value\\.${field}`));
  }
  assert.doesNotMatch(page, /method:\s*'POST'/);
  assert.doesNotMatch(page, /tenant selector/i);
});

test('Tenant Management navigation is authority-aware and contains only implemented destinations', () => {
  assert.match(page, /visibleConsoleRoutes\(authority\)/);
  assert.match(page, /allowedRouteIds/);
  assert.match(page, /availableSections/);
  for (const label of ['Overview', 'Identities', 'Principals', 'Applications', 'Access', 'Governance', 'Credentials', 'Sources & Integrations', 'Administration', 'Audit']) {
    assert.match(page, new RegExp(`label: '${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}'`));
  }
  assert.doesNotMatch(page, /Organizations/);
  assert.doesNotMatch(page, /Settings/);
  assert.doesNotMatch(page, /disabledReason/);
});

test('Tenant Management has distinct mobile and desktop navigation and content treatments', () => {
  assert.match(page, /display: \{ xs: 'block', sm: 'none' \}/);
  assert.match(page, /<SelectInput/);
  assert.match(page, /label="Tenant area"/);
  assert.match(page, /display: \{ xs: 'none', sm: 'flex' \}/);
  assert.match(page, /aria-label="Tenant management sections"/);
  assert.match(page, /display: \{ xs: 'grid', md: 'none' \}/);
  assert.match(page, /display: \{ xs: 'none', md: 'block' \}/);
  assert.match(page, /minWidth=\{600\}/);
  assert.match(page, /minWidth=\{720\}/);
});

test('Tenant Management consumes governed read projections instead of fabricating Tenant semantics', () => {
  assert.match(operationsApi, /tenantDashboard:/);
  assert.match(page, /tenantDashboard\.identityTypes/);
  assert.match(page, /tenantDashboard\.applicationOrigins/);
  assert.match(page, /tenantDashboard\.tenantOwners/);
  assert.match(page, /tenantDashboard\.sources/);
  assert.match(page, /tenantDashboard\.recentActivity/);
  assert.match(page, /Tenant Owner continuity/);
  assert.match(page, /not a synthetic Tenant health score/);
  assert.match(page, /does not expose an exact bounded per-application Access aggregation/);
  assert.doesNotMatch(page, /Acme Corp|ACME|Alice Admin|Workday|Salesforce|GitHub|Jira|Internal Payroll/);
  assert.doesNotMatch(page, /tenant\.status|tenant\.code|tenant\.description|tenant\.owner/);
});

test('Tenant Management renders exact governed distributions with shared charts', () => {
  assert.match(page, /<DonutChart/);
  assert.match(page, /ariaLabel=\{`\$\{totalLabel\} distribution`\}/);
  assert.match(page, /tenantDashboard\.identityTypes/);
  assert.match(page, /tenantDashboard\.applicationOrigins/);
});

test('Tenant Management keeps action links aligned with projected route visibility', () => {
  assert.match(page, /routePath\('identities', '\/identities'\)/);
  assert.match(page, /routePath\('catalog', '\/catalog'\)/);
  assert.match(page, /routePath\('access', '\/access'\)/);
  assert.match(page, /routePath\('integrations', '\/integrations'\)/);
  assert.match(page, /routePath\('audit', '\/audit'\)/);
  assert.match(page, /operation-time server authorization/i);
});

test('Tenant Management stays within shared enterprise presentation roles', () => {
  assert.match(page, /<Surface/);
  assert.match(page, /<DataTable/);
  assert.match(page, /<StateIndicator/);
  assert.match(page, /<StatePanel/);
  assert.doesNotMatch(page, /gradient/i);
  assert.doesNotMatch(page, /boxShadow/);
  assert.doesNotMatch(page, /#[0-9a-f]{3,8}/i);
});

test('living Tenant Management contract preserves current context and Platform inspection boundaries', () => {
  assert.match(tenantManagement, /\/tenant/);
  assert.match(tenantManagement, /server-resolved current Tenant/);
  assert.match(tenantManagement, /\/platform\/tenants\/:tenantId/);
  assert.match(tenantManagement, /does not switch authenticated Tenant context/);
  assert.match(tenantManagement, /Operation-time server authorization remains final/);
});
