import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const page = await readFile(new URL('../src/TenantOverviewPage.tsx', import.meta.url), 'utf8');
const tenantApi = await readFile(new URL('../src/tenantContextApi.ts', import.meta.url), 'utf8');
const app = await readFile(new URL('../src/App.tsx', import.meta.url), 'utf8');
const navigation = await readFile(new URL('../src/consoleNavigation.ts', import.meta.url), 'utf8');
const architecture = await readFile(new URL('../../../docs/architecture/platform-authority-and-application-tenancy.md', import.meta.url), 'utf8');

test('current Tenant route remains an Administration surface rather than a new capability', () => {
  assert.match(navigation, /\{ id: 'tenant', path: '\/tenant', label: 'Current Tenant', group: 'administration' \}/);
  assert.match(app, /route === 'tenant'/);
  assert.match(app, /<TenantOverviewPage authority=/);
});

test('Tenant dashboard uses server-derived canonical Tenant metadata and existing operations projection', () => {
  assert.match(page, /getTenantContext/);
  assert.match(page, /getIamOperationsOverview\('24h', 'ALL', 'OLDEST'/);
  assert.match(tenantApi, /\/console\/tenant-context/);
  assert.match(page, /tenant\.value\.displayName/);
  assert.match(page, /tenant\.value\.revision/);
  assert.match(page, /tenant\.value\.createdAt/);
  assert.match(page, /tenant\.value\.updatedAt/);
  assert.doesNotMatch(page, /method:\s*'POST'/);
  assert.doesNotMatch(page, /tenant selector/i);
});

test('Tenant dashboard follows the agreed screenshot composition', () => {
  assert.match(page, /Page width="wide"/);
  for (const label of [
    'Overview', 'Identities', 'Applications', 'Access', 'Organizations', 'Governance',
    'Sources & Integrations', 'Administrators', 'Audit', 'Settings',
    'Tenant Information', 'Tenant Health', 'Recent Activity',
    'Identities by Type', 'Applications by Origin', 'Active Access by Application',
    'Open Governance Items', 'Identity Sources', 'Tenant Actions',
  ]) assert.match(page, new RegExp(label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));

  for (const label of ['Identities', 'Applications', 'Active Access', 'Governance Issues', 'Sources', 'Connectors']) {
    assert.match(page, new RegExp(`label="${label}"`));
  }
  assert.match(page, /startIcon={<LineIcon/);
  assert.match(page, /lg: 'repeat\(6, minmax\(0, 1fr\)\)'/);
  assert.match(page, /lg: '1\.15fr 1fr 1fr'/);
  assert.match(page, /lg: 'repeat\(3, minmax\(0, 1fr\)\)'/);
  assert.match(page, /lg: '1\.35fr 1fr'/);
});

test('unsupported screenshot data remains explicit instead of fabricated', () => {
  assert.match(page, /Breakdown not exposed/);
  assert.match(page, /Source count is not exposed by the current projection/);
  assert.match(page, /Source list not exposed/);
  assert.match(page, /No Tenant mutation actions are exposed by the current Console contract/);
  assert.doesNotMatch(page, /Acme Corp|ACME|Alice Admin|Workday|Salesforce|GitHub|Jira|Internal Payroll/);
  assert.doesNotMatch(page, /\bACTIVE\b/);
  assert.doesNotMatch(page, /Tenant Owner/);
  assert.doesNotMatch(page, /description.*Corporation/i);
});

test('Tenant dashboard preserves IAM semantic distinctions', () => {
  assert.match(page, /AccessAssignment remains authoritative business intent and EffectiveAccess remains a projection/);
  assert.match(page, /not a synthetic health score/);
  assert.match(page, /Recent denied or failed Audit evidence/);
  assert.match(page, /\/identities/);
  assert.match(page, /\/catalog/);
  assert.match(page, /\/access/);
  assert.match(page, /\/integrations/);
  assert.match(page, /\/administration/);
  assert.match(page, /\/audit/);
});

test('Tenant dashboard stays within shared enterprise presentation roles', () => {
  assert.match(page, /<Surface/);
  assert.match(page, /<DataTable/);
  assert.match(page, /<StateIndicator/);
  assert.doesNotMatch(page, /StatusChip/);
  assert.doesNotMatch(page, /gradient/i);
  assert.doesNotMatch(page, /boxShadow/);
  assert.doesNotMatch(page, /#[0-9a-f]{3,8}/i);
});

test('living architecture still documents the Console boundary without redefining Tenant authority', () => {
  assert.match(architecture, /## Console tenant context/);
  assert.match(architecture, /not a new Tenant aggregate, lifecycle API or browser-side authority source/);
  assert.match(architecture, /Operation-time server authorization remains final/);
});
