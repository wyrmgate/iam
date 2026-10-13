import { V1_ROOT } from '../api';
import { CurrentAdministrativeAuthorityPage } from '../CurrentAdministrativeAuthorityPage';
import { type Field, type Spec, type Values, Workspace, WorkspaceTabs } from '../SemanticWorkspace';
import { csv, iso, list, nullable } from './workspaceSpecHelpers';

const scopeFields: Field[] = [
  { name: 'scopeType', label: 'Scope type', type: 'select', options: ['GLOBAL', 'ORGANIZATION', 'APPLICATION', 'APPLICATION_TARGET', 'SOURCE_SYSTEM', 'CONNECTOR_INSTANCE', 'IDENTITY_POPULATION', 'CANONICAL_ATTRIBUTE_CLASSIFICATION', 'SPECIFIC_RESOURCE'], required: true },
  { name: 'scopeResourceType', label: 'Resource type' },
  { name: 'scopeResourceId', label: 'Resource ID' },
  { name: 'scopeKey', label: 'Scope key' },
];

const scope = (values: Values) => ({ type: values.scopeType, resourceType: nullable(values.scopeResourceType), resourceId: nullable(values.scopeResourceId), scopeKey: nullable(values.scopeKey) });

const adminRoleSpec: Spec = {
  eyebrow: 'Administration', title: 'Administrative Roles', description: 'Control-plane permission sets, distinct from Catalog Roles and token claims.',
  list: list(`${V1_ROOT}/administrative-roles`), detail: (id) => `${V1_ROOT}/administrative-roles/${encodeURIComponent(id)}`,
  columns: ['code', 'name', 'revision'],
  create: { path: `${V1_ROOT}/administrative-roles`, fields: [{ name: 'code', label: 'Code', required: true }, { name: 'name', label: 'Name', required: true }, { name: 'permissions', label: 'Permissions', type: 'textarea', required: true }], body: (values) => ({ code: values.code, name: values.name, permissions: csv(values.permissions) }) },
  actions: [
    { label: 'Add permission', path: (id) => `${V1_ROOT}/administrative-roles/${encodeURIComponent(id)}:add-permission`, fields: [{ name: 'permission', label: 'Permission', required: true }], body: (values) => ({ permission: values.permission }) },
    { label: 'Remove permission', path: (id) => `${V1_ROOT}/administrative-roles/${encodeURIComponent(id)}:remove-permission`, destructive: true, fields: [{ name: 'permission', label: 'Permission', required: true }], body: (values) => ({ permission: values.permission }) },
  ],
};

function adminSpec(kind: 'grant' | 'delegation' | 'elevation' | 'break-glass'): Spec {
  if (kind === 'grant') return { eyebrow: 'Administration', title: 'Administrative Grants', description: 'Direct scoped authority.', list: list(`${V1_ROOT}/administrative-grants`), detail: (id) => `${V1_ROOT}/administrative-grants/${encodeURIComponent(id)}`, columns: ['beneficiaryIdentityId', 'roleId', 'state', 'validUntil'], create: { path: `${V1_ROOT}/administrative-grants`, fields: [{ name: 'beneficiaryIdentityId', label: 'Beneficiary Identity ID', required: true }, { name: 'roleId', label: 'Admin Role ID', required: true }, ...scopeFields, { name: 'validUntil', label: 'Valid until', type: 'datetime-local' }, { name: 'grantable', label: 'Grantable', type: 'checkbox' }, { name: 'delegable', label: 'Delegable', type: 'checkbox' }, { name: 'authorityBasisGrantId', label: 'Authority basis Grant ID', required: true }], body: (values) => ({ beneficiaryIdentityId: values.beneficiaryIdentityId, roleId: values.roleId, scope: scope(values), validFrom: null, validUntil: iso(values.validUntil), grantable: Boolean(values.grantable), delegable: Boolean(values.delegable), authorityBasisGrantId: values.authorityBasisGrantId }) }, actions: [{ label: 'Revoke', path: (id) => `${V1_ROOT}/administrative-grants/${encodeURIComponent(id)}:revoke`, destructive: true }] };
  if (kind === 'delegation') return { eyebrow: 'Administration', title: 'Administrative Delegations', description: 'Single-hop delegated authority.', list: list(`${V1_ROOT}/administrative-delegations`), detail: (id) => `${V1_ROOT}/administrative-delegations/${encodeURIComponent(id)}`, columns: ['delegateIdentityId', 'sourceGrantId', 'state', 'validUntil'], actions: [{ label: 'Revoke', path: (id) => `${V1_ROOT}/administrative-delegations/${encodeURIComponent(id)}:revoke`, destructive: true }] };
  if (kind === 'elevation') return { eyebrow: 'Administration', title: 'Administrative Elevations', description: 'Finite JIT authority; Governance approval is evidence, Administration owns authority.', list: list(`${V1_ROOT}/administrative-elevations`), detail: (id) => `${V1_ROOT}/administrative-elevations/${encodeURIComponent(id)}`, columns: ['beneficiaryIdentityId', 'roleId', 'state', 'validUntil'], actions: [{ label: 'Request approval', path: (id) => `${V1_ROOT}/administrative-elevations/${encodeURIComponent(id)}:request-approval` }, { label: 'Apply approval', path: (id) => `${V1_ROOT}/administrative-elevations/${encodeURIComponent(id)}:apply` }, { label: 'Cancel', path: (id) => `${V1_ROOT}/administrative-elevations/${encodeURIComponent(id)}:cancel`, destructive: true }, { label: 'Revoke', path: (id) => `${V1_ROOT}/administrative-elevations/${encodeURIComponent(id)}:revoke`, destructive: true }] };
  return { eyebrow: 'Administration · emergency', title: 'Break-glass Operations', description: 'Emergency authority remains distinct, finite and post-use reviewed.', list: list(`${V1_ROOT}/administrative-break-glass-operations`), detail: (id) => `${V1_ROOT}/administrative-break-glass-operations/${encodeURIComponent(id)}`, columns: ['beneficiaryIdentityId', 'roleId', 'state', 'validUntil'], actions: [{ label: 'Revoke', path: (id) => `${V1_ROOT}/administrative-break-glass-operations/${encodeURIComponent(id)}:revoke`, destructive: true }, { label: 'Complete review', path: (id) => `${V1_ROOT}/administrative-break-glass-operations/${encodeURIComponent(id)}:complete-review`, fields: [{ name: 'outcome', label: 'Outcome', type: 'select', options: ['APPROVED_USE', 'POLICY_CONCERN', 'INCIDENT_FOLLOW_UP_REQUIRED'], required: true }, { name: 'summary', label: 'Summary', type: 'textarea', required: true }], body: (values) => ({ outcome: values.outcome, summary: values.summary }) }] };
}

export function AdministrationWorkspace() {
  return <WorkspaceTabs tabs={[
    { id: 'roles', label: 'Roles', content: <Workspace spec={adminRoleSpec} /> },
    { id: 'grants', label: 'Grants', content: <Workspace spec={adminSpec('grant')} /> },
    { id: 'delegations', label: 'Delegations', content: <Workspace spec={adminSpec('delegation')} /> },
    { id: 'elevations', label: 'Elevations', content: <Workspace spec={adminSpec('elevation')} /> },
    { id: 'breakglass', label: 'Break-glass', content: <Workspace spec={adminSpec('break-glass')} /> },
    { id: 'authority', label: 'My authority', content: <CurrentAdministrativeAuthorityPage /> },
  ]} />;
}
