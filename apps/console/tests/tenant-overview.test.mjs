import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const page = await readFile(new URL('../src/TenantOverviewPage.tsx', import.meta.url), 'utf8');
const tenantApi = await readFile(new URL('../src/tenantContextApi.ts', import.meta.url), 'utf8');
const operationsApi = await readFile(new URL('../src/iamOperationsApi.ts', import.meta.url), 'utf8');
const app = await readFile(new URL('../src/App.tsx', import.meta.url), 'utf8');
const navigation = await readFile(new URL('../src/consoleNavigation.ts', import.meta.url), 'utf8');
const tenantManagement = await readFile(new URL('../../../docs/engineering/tenant-management-console.md', import.meta.url), 'utf8');

test('current Tenant route remains Tenant Management without changing route semantics', () => {
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
  assert.doesNotMatch(page, /tenant\.value\.(?:status|code|description|owner)\b/);
});

test('Tenant Management treats the shell as primary navigation and keeps local orientation compact', () => {
  assert.match(page, /visibleConsoleRoutes\(authority\)/);
  assert.match(page, /allowedRouteIds/);
  assert.match(page, /aria-label="Related Tenant workspaces"/);
  for (const label of ['Identities', 'Access', 'Governance', 'Integrations', 'Administration']) {
    assert.match(page, new RegExp(`label: '${label}'`));
  }
  assert.doesNotMatch(page, /tenantSections/);
  assert.doesNotMatch(page, /Tenant management sections/);
  assert.doesNotMatch(page, /<SelectInput/);
  assert.doesNotMatch(page, /Organizations|Settings/);
});

test('Tenant Management uses shared object-header and definition patterns instead of a page-local dashboard header', () => {
  assert.match(page, /<PageHeader/);
  assert.match(page, /eyebrow="Administration · current Tenant"/);
  assert.match(page, /Current Tenant context/);
  assert.match(page, /component="dl"/);
  assert.match(page, /gridTemplateColumns: \{ xs: '1fr', sm: 'minmax\(9rem, 12rem\) minmax\(0, 1fr\)' \}/);
  assert.doesNotMatch(page, /borderRadius: '50%'/);
  assert.doesNotMatch(page, /SummaryTile/);
});

test('Tenant Management keeps governed summary, continuity and attention distinct without synthetic health', () => {
  assert.match(page, /Governed operational summary/);
  assert.match(page, /Administrative continuity/);
  assert.match(page, /Governance and operational attention/);
  assert.match(page, /Tenant Owner continuity/);
  assert.match(page, /Active access assignments/);
  assert.match(page, /Authoritative AccessAssignment intent/);
  assert.match(page, /not a Tenant lifecycle or health score/);
  assert.match(page, /rather than combined into a synthetic score/);
  assert.doesNotMatch(page, /health score/i);
  assert.doesNotMatch(page, /fake trend/i);
});

test('Tenant Management consumes only exact governed distributions and projections', () => {
  assert.match(operationsApi, /tenantDashboard:/);
  assert.match(page, /tenantDashboard\.identityTypes/);
  assert.match(page, /tenantDashboard\.applicationOrigins/);
  assert.match(page, /tenantDashboard\.tenantOwners/);
  assert.match(page, /tenantDashboard\.sources/);
  assert.match(page, /tenantDashboard\.recentActivity/);
  assert.match(page, /<DonutChart/);
  assert.match(page, /ariaLabel=\{`\$\{totalLabel\} distribution`\}/);
  assert.doesNotMatch(page, /active access by application/i);
  assert.doesNotMatch(page, /Acme Corp|ACME|Alice Admin|Workday|Salesforce|GitHub|Jira|Internal Payroll/);
});

test('Tenant Management keeps relational activity and source data in contained tables on narrow screens', () => {
  assert.match(page, /<DataTable ariaLabel="Recent Tenant Audit activity" minWidth=\{620\}>/);
  assert.match(page, /<DataTable ariaLabel="Identity source operational summary" minWidth=\{720\}>/);
  assert.doesNotMatch(page, /display: \{ xs: 'none', md: 'block' \}/);
  assert.doesNotMatch(page, /display: \{ xs: 'grid', md: 'none' \}/);
  assert.match(page, /minHeight: \{ xs: 44, sm: 32 \}/);
});

test('Tenant Management action links remain aligned with projected route visibility', () => {
  assert.match(page, /routePath\('identities', '\/identities'\)/);
  assert.match(page, /routePath\('catalog', '\/catalog'\)/);
  assert.match(page, /routePath\('access', '\/access'\)/);
  assert.match(page, /routePath\('integrations', '\/integrations'\)/);
  assert.match(page, /routePath\('audit', '\/audit'\)/);
  assert.match(tenantManagement, /Operation-time server authorization remains final/);
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
