import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const page = await readFile(new URL('../src/TenantOverviewPage.tsx', import.meta.url), 'utf8');
const app = await readFile(new URL('../src/App.tsx', import.meta.url), 'utf8');
const navigation = await readFile(new URL('../src/consoleNavigation.ts', import.meta.url), 'utf8');
const architecture = await readFile(new URL('../../docs/architecture/platform-authority-and-application-tenancy.md', import.meta.url), 'utf8');

test('current Tenant route is an Administration surface rather than a new capability', () => {
  assert.match(navigation, /\{ id: 'tenant', path: '\/tenant', label: 'Current Tenant', group: 'administration' \}/);
  assert.doesNotMatch(navigation, /\{ id: 'tenant', code:/);
  assert.match(app, /route === 'tenant'/);
  assert.match(app, /<TenantOverviewPage authority=/);
});

test('Tenant page derives context and inventory from existing server projections', () => {
  assert.match(page, /CurrentAdministrativeAuthority/);
  assert.match(page, /getIamOperationsOverview\('24h', 'ALL', 'OLDEST'/);
  assert.match(page, /server-resolved Tenant isolation boundary/);
  assert.match(page, /browser does not choose or override authoritative Tenant routing/);
  assert.doesNotMatch(page, /apiRequest<.*tenant/i);
  assert.doesNotMatch(page, /method:\s*'POST'/);
});

test('Tenant management preserves capability ownership and access semantics', () => {
  for (const term of [
    'Identity',
    'Catalog',
    'Access',
    'Governance',
    'Integration',
    'Administration',
    'Audit',
    'AccessAssignment',
    'EffectiveAccess',
    'Role or Entitlement',
    'Organization is tenant-local business structure',
  ]) {
    assert.match(page, new RegExp(term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  }
  assert.match(page, /generic cross-tenant UserTenantMembership/);
  assert.match(page, /explicit tenant binding/);
  assert.match(page, /\/identities/);
  assert.match(page, /\/catalog/);
  assert.match(page, /\/access/);
  assert.match(page, /\/integrations/);
  assert.match(page, /\/administration/);
  assert.match(page, /\/audit/);
});

test('Tenant page follows the enterprise dense-workbench standard', () => {
  assert.match(page, /Page width="wide"/);
  assert.match(page, /<Surface/);
  assert.match(page, /<DataTable/);
  assert.match(page, /<StateIndicator/);
  assert.doesNotMatch(page, /StatusChip/);
  assert.doesNotMatch(page, /gradient/i);
  assert.doesNotMatch(page, /boxShadow/);
  assert.doesNotMatch(page, /Tenant Actions/);
  assert.doesNotMatch(page, /SUSPEND|RETIRED|ACTIVE Tenant/);
});

test('living architecture documents the Console boundary without redefining Tenant authority', () => {
  assert.match(architecture, /## Console tenant context/);
  assert.match(architecture, /not a new Tenant aggregate, lifecycle API or browser-side authority source/);
  assert.match(architecture, /does not model generic `UserTenantMembership` or `UserApplication` authority/);
  assert.match(architecture, /Operation-time server authorization remains final/);
});
