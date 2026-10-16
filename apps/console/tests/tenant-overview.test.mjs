import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const page = await readFile(new URL('../src/TenantOverviewPage.tsx', import.meta.url), 'utf8');
const app = await readFile(new URL('../src/App.tsx', import.meta.url), 'utf8');
const navigation = await readFile(new URL('../src/consoleNavigation.ts', import.meta.url), 'utf8');
const architecture = await readFile(new URL('../../../docs/architecture/platform-authority-and-application-tenancy.md', import.meta.url), 'utf8');

test('current Tenant route remains an Administration surface rather than a new capability', () => {
  assert.match(navigation, /\{ id: 'tenant', path: '\/tenant', label: 'Current Tenant', group: 'administration' \}/);
  assert.doesNotMatch(navigation, /\{ id: 'tenant', code:/);
  assert.match(app, /route === 'tenant'/);
  assert.match(app, /<TenantOverviewPage authority=/);
});

test('Tenant Overview derives all context from existing server projections', () => {
  assert.match(page, /CurrentAdministrativeAuthority/);
  assert.match(page, /getIamOperationsOverview\('24h', 'ALL', 'OLDEST'/);
  assert.match(page, /server-resolved Tenant isolation boundary/);
  assert.doesNotMatch(page, /apiRequest<.*tenant/i);
  assert.doesNotMatch(page, /method:\s*'POST'/);
  assert.doesNotMatch(page, /tenant selector/i);
});

test('Tenant Overview follows the agreed dashboard composition', () => {
  assert.match(page, /Page width="standard"/);
  assert.match(page, />Tenant Overview</);
  assert.match(page, /title="Tenant details"/);
  assert.match(page, /title="IAM estate"/);
  assert.match(page, /title="Requires attention"/);
  assert.match(page, /title="Integration posture"/);
  assert.match(page, /title="Administration"/);
  assert.match(page, /title="Recent evidence"/);
  assert.match(page, /gridTemplateColumns: \{ xs: '1fr', lg: 'minmax\(0, 2fr\) minmax\(320px, 1fr\)' \}/);
});

test('Tenant Overview preserves IAM semantic distinctions without making them the primary UI', () => {
  assert.match(page, /Authoritative business access intent; EffectiveAccess remains a separate projection/);
  assert.match(page, /Tenant Owner and other grants remain Administration-owned authority/);
  assert.match(page, /Operational fulfillment and provider-observation posture; not Tenant business state/);
  assert.match(page, /evidence, not mutable governance state/);
  assert.match(page, /\/identities/);
  assert.match(page, /\/catalog/);
  assert.match(page, /\/access/);
  assert.match(page, /\/integrations/);
  assert.match(page, /\/administration/);
  assert.match(page, /\/audit/);
});

test('Tenant Overview uses enterprise workbench primitives and avoids decorative dashboard patterns', () => {
  assert.match(page, /<Surface/);
  assert.match(page, /<DataTable/);
  assert.match(page, /<StateIndicator/);
  assert.doesNotMatch(page, /StatusChip/);
  assert.doesNotMatch(page, /gradient/i);
  assert.doesNotMatch(page, /boxShadow/);
  assert.doesNotMatch(page, /donut|pie chart|radial/i);
  assert.doesNotMatch(page, /Tenant boundary semantics/);
  assert.doesNotMatch(page, /Manage this Tenant/);
  assert.doesNotMatch(page, /Owning capability/);
  assert.doesNotMatch(page, /SUSPEND|RETIRED|ACTIVE Tenant/);
});

test('living architecture still documents the Console boundary without redefining Tenant authority', () => {
  assert.match(architecture, /## Console tenant context/);
  assert.match(architecture, /not a new Tenant aggregate, lifecycle API or browser-side authority source/);
  assert.match(architecture, /does not model generic `UserTenantMembership` or `UserApplication` authority/);
  assert.match(architecture, /Operation-time server authorization remains final/);
});
