import assert from 'node:assert/strict';
import { access } from 'node:fs/promises';
import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSources } from './helpers/source-contract.mjs';

const sources = await readConsoleSources({
  app: 'App.tsx',
  requests: 'RequestsApprovalsPage.tsx',
  identity: 'workspaces/IdentityWorkspace.tsx',
  principals: 'workspaces/PrincipalsWorkspace.tsx',
  catalog: 'workspaces/CatalogWorkspace.tsx',
  accessBoundary: 'workspaces/AccessWorkspace.tsx',
  access: 'workspaces/AccessGoldenWorkspace.tsx',
  accessApi: 'accessApi.ts',
  governance: 'workspaces/GovernanceWorkspace.tsx',
  credential: 'workspaces/CredentialsWorkspace.tsx',
  integration: 'workspaces/IntegrationsWorkspace.tsx',
  administration: 'workspaces/AdministrationWorkspace.tsx',
  audit: 'workspaces/AuditWorkspace.tsx',
  helpers: 'workspaces/workspaceSpecHelpers.ts',
});

test('App is runtime and route composition rather than a capability implementation hotspot', () => {
  assertSourceContains(sources.app, /\.\/RequestsApprovalsPage/, /\.\/workspaces\/IdentityWorkspace/, /\.\/workspaces\/PrincipalsWorkspace/, /\.\/workspaces\/CatalogWorkspace/, /\.\/workspaces\/AccessWorkspace/, /\.\/workspaces\/GovernanceWorkspace/, /\.\/workspaces\/CredentialsWorkspace/, /\.\/workspaces\/IntegrationsWorkspace/, /\.\/workspaces\/AdministrationWorkspace/, /\.\/workspaces\/AuditWorkspace/, /matchConsoleRoute\(pathname\)/, /getCurrentAdministrativeAuthority/, /getSystemInfo/);
  assertSourceExcludes(sources.app, /ControlPlanePages/, /V1_ROOT/, /apiRequest/, /getIdentity/, /listIdentities/, /transitionIdentity/);
});

test('legacy multi-capability ControlPlanePages hotspot is removed', async () => {
  await assert.rejects(access(new URL('../src/ControlPlanePages.tsx', import.meta.url)));
});

test('each capability ownership surface has a focused implementation file', () => {
  assertSourceContains(sources.requests, /export function RequestsApprovalsPage/, /My Approval Work/, /My Requests/, /New Request/);
  assertSourceContains(sources.identity, /export function IdentityWorkspace/, /\/identities/);
  assertSourceContains(sources.principals, /export function PrincipalsWorkspace/, /\/principals/, /Technical account authority owned by Identity/);
  assertSourceContains(sources.catalog, /export function CatalogWorkspace/, /\/applications/, /\/roles/);
  assertSourceContains(sources.accessBoundary, /AccessGoldenWorkspace/);
  assertSourceContains(sources.access, /export function AccessWorkspace/, /Authoritative assignments/, /Effective access/, /Lifecycle access policy/);
  assertSourceContains(sources.accessApi, /\/access-assignments/, /\/effective-access-views/, /\/lifecycle-access-policy/);
  assertSourceContains(sources.governance, /export function ReviewsWorkspace/, /export function PoliciesWorkspace/, /\/governance\//);
  assertSourceExcludes(sources.governance, /export function RequestsWorkspace/);
  assertSourceContains(sources.credential, /export function CredentialsWorkspace/, /\/credentials/);
  assertSourceContains(sources.integration, /export function IntegrationsWorkspace/, /\/connectors/, /IntegrationOperationsPage/);
  assertSourceContains(sources.administration, /export function AdministrationWorkspace/, /\/administrative-/, /CurrentAdministrativeAuthorityPage/);
  assertSourceContains(sources.audit, /export function AuditWorkspace/, /\/audit-records/, /\/evidence-snapshots/);
});

test('shared workspace helpers remain presentation-neutral and do not redefine capability contracts', () => {
  assertSourceContains(sources.helpers, /withCursor/, /nullable/, /iso/, /csv/, /list/);
  assertSourceExcludes(sources.helpers, /SemanticWorkspace/, /\.\.\/ui/, /administrative-|governance\/|access-assignments|credentials|connectors|audit-records|identities|principals|applications/);
});
