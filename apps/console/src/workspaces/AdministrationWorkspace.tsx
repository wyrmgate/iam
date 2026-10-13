import { V1_ROOT } from '../api';
import { CurrentAdministrativeAuthorityPage } from '../CurrentAdministrativeAuthorityPage';
import { type Field, type Spec, type Values, Workspace, WorkspaceTabs } from '../SemanticWorkspace';
import { csv, iso, list, nullable } from './workspaceSpecHelpers';

const scopeFields: Field[] = [
  {
    name: 'scopeType',
    label: 'Typed scope',
    type: 'select',
    options: ['GLOBAL', 'ORGANIZATION', 'APPLICATION', 'APPLICATION_TARGET', 'SOURCE_SYSTEM', 'CONNECTOR_INSTANCE', 'IDENTITY_POPULATION', 'CANONICAL_ATTRIBUTE_CLASSIFICATION', 'SPECIFIC_RESOURCE'],
    required: true,
    help: 'Scope is evaluated by the server. Selecting a modeled scope type does not create browser-side hierarchy or containment semantics.',
  },
  { name: 'scopeResourceType', label: 'Scoped resource type', help: 'Use only when required by the selected typed scope.' },
  { name: 'scopeResourceId', label: 'Scoped resource ID', help: 'Stable resource identifier for an exact scoped authority boundary.' },
  { name: 'scopeKey', label: 'Scope key', help: 'Exact governed key where the accepted scope contract requires one.' },
];

const scope = (values: Values) => ({ type: values.scopeType, resourceType: nullable(values.scopeResourceType), resourceId: nullable(values.scopeResourceId), scopeKey: nullable(values.scopeKey) });

const adminRoleSpec: Spec = {
  eyebrow: 'Administration · authority definition',
  title: 'Administrative Roles',
  description: 'Control-plane permission bundles. AdministrativeRole is distinct from Catalog Role, AccessAssignment, and OAuth/OIDC roles, scopes, or claims.',
  list: list(`${V1_ROOT}/administrative-roles`),
  detail: (id) => `${V1_ROOT}/administrative-roles/${encodeURIComponent(id)}`,
  columns: ['code', 'name', 'revision'],
  create: {
    path: `${V1_ROOT}/administrative-roles`,
    fields: [
      { name: 'code', label: 'Code', required: true },
      { name: 'name', label: 'Name', required: true },
      { name: 'permissions', label: 'Semantic permissions', type: 'textarea', required: true, help: 'Permission membership defines control-plane capability only. It does not grant business access.' },
    ],
    body: (values) => ({ code: values.code, name: values.name, permissions: csv(values.permissions) }),
  },
  actions: [
    {
      label: 'Add permission to role',
      path: (id) => `${V1_ROOT}/administrative-roles/${encodeURIComponent(id)}:add-permission`,
      fields: [{ name: 'permission', label: 'Permission', required: true, help: 'Adding authority is subject to server-side prospective grantability and self-escalation checks.' }],
      body: (values) => ({ permission: values.permission }),
    },
    {
      label: 'Remove permission from role',
      path: (id) => `${V1_ROOT}/administrative-roles/${encodeURIComponent(id)}:remove-permission`,
      destructive: true,
      fields: [{ name: 'permission', label: 'Permission', required: true, help: 'Removal reduces authority for grants and dependent authority that reference this role.' }],
      body: (values) => ({ permission: values.permission }),
    },
  ],
};

function adminSpec(kind: 'grant' | 'delegation' | 'elevation' | 'break-glass'): Spec {
  if (kind === 'grant') {
    return {
      eyebrow: 'Administration · direct authority',
      title: 'Administrative Grants',
      description: 'Direct governed-actor authority with typed scope, validity, and explicit grantable/delegable rights. AdministrativeGrant is not AccessAssignment.',
      list: list(`${V1_ROOT}/administrative-grants`),
      detail: (id) => `${V1_ROOT}/administrative-grants/${encodeURIComponent(id)}`,
      columns: ['beneficiaryIdentityId', 'roleId', 'state', 'validFrom', 'validUntil'],
      create: {
        path: `${V1_ROOT}/administrative-grants`,
        warning: 'Creating direct administrative authority can increase control-plane privilege. The server revalidates the complete resulting role, typed scope, validity, propagated grantable/delegable rights, and the single authority-basis grant at operation time.',
        fields: [
          { name: 'beneficiaryIdentityId', label: 'Beneficiary governed Identity ID', required: true },
          { name: 'roleId', label: 'Administrative Role ID', required: true },
          ...scopeFields,
          { name: 'validUntil', label: 'Valid until', type: 'datetime-local', help: 'Authority expires semantically at this time; browser/session lifetime cannot extend it.' },
          { name: 'grantable', label: 'Grantable authority', type: 'checkbox', help: 'Allows this direct grant to serve as a bounded basis for privilege-increasing direct grant or role changes when the server proves containment.' },
          { name: 'delegable', label: 'Delegable authority', type: 'checkbox', help: 'Allows finite single-hop delegation from this direct source when the server proves containment.' },
          { name: 'authorityBasisGrantId', label: 'Authority-basis direct Grant ID', required: true, help: 'One current effective grantable direct grant must contain the complete resulting authority. The browser does not infer or combine bases.' },
        ],
        body: (values) => ({ beneficiaryIdentityId: values.beneficiaryIdentityId, roleId: values.roleId, scope: scope(values), validFrom: null, validUntil: iso(values.validUntil), grantable: Boolean(values.grantable), delegable: Boolean(values.delegable), authorityBasisGrantId: values.authorityBasisGrantId }),
      },
      actions: [{ label: 'Revoke direct grant now', path: (id) => `${V1_ROOT}/administrative-grants/${encodeURIComponent(id)}:revoke`, destructive: true }],
    };
  }

  if (kind === 'delegation') {
    return {
      eyebrow: 'Administration · delegated authority',
      title: 'Administrative Delegations',
      description: 'Finite single-hop authority derived from one current direct delegable source. Source revocation, expiry, narrowing, or loss of delegability removes dependent authority at operation time.',
      list: list(`${V1_ROOT}/administrative-delegations`),
      detail: (id) => `${V1_ROOT}/administrative-delegations/${encodeURIComponent(id)}`,
      columns: ['delegateIdentityId', 'sourceGrantId', 'roleId', 'state', 'validUntil'],
      actions: [{ label: 'Revoke delegation now', path: (id) => `${V1_ROOT}/administrative-delegations/${encodeURIComponent(id)}:revoke`, destructive: true }],
    };
  }

  if (kind === 'elevation') {
    return {
      eyebrow: 'Administration · temporary elevation',
      title: 'Administrative Elevations',
      description: 'Finite JIT authority. Governance ApprovalCase is immutable evidence only; approval never becomes Administration authority until apply revalidates the current basis, role, scope, validity, actor eligibility, and approval fingerprint.',
      list: list(`${V1_ROOT}/administrative-elevations`),
      detail: (id) => `${V1_ROOT}/administrative-elevations/${encodeURIComponent(id)}`,
      columns: ['beneficiaryIdentityId', 'initiatorIdentityId', 'roleId', 'state', 'validUntil'],
      actions: [
        { label: 'Request Governance approval evidence', path: (id) => `${V1_ROOT}/administrative-elevations/${encodeURIComponent(id)}:request-approval` },
        { label: 'Apply approved elevation after revalidation', path: (id) => `${V1_ROOT}/administrative-elevations/${encodeURIComponent(id)}:apply` },
        { label: 'Cancel pending elevation', path: (id) => `${V1_ROOT}/administrative-elevations/${encodeURIComponent(id)}:cancel`, destructive: true },
        { label: 'Revoke active elevation now', path: (id) => `${V1_ROOT}/administrative-elevations/${encodeURIComponent(id)}:revoke`, destructive: true },
      ],
    };
  }

  return {
    eyebrow: 'Administration · emergency authority only',
    title: 'Break-glass Operations',
    description: 'Emergency authority is separate from direct grants and ordinary elevation. It is finite, policy-gated, assurance-aware, audited, durably notified, and subject to post-use review after authority ends.',
    list: list(`${V1_ROOT}/administrative-break-glass-operations`),
    detail: (id) => `${V1_ROOT}/administrative-break-glass-operations/${encodeURIComponent(id)}`,
    columns: ['beneficiaryIdentityId', 'roleId', 'state', 'incidentReference', 'validUntil'],
    actions: [
      { label: 'End emergency authority now', path: (id) => `${V1_ROOT}/administrative-break-glass-operations/${encodeURIComponent(id)}:revoke`, destructive: true },
      {
        label: 'Complete post-use review',
        path: (id) => `${V1_ROOT}/administrative-break-glass-operations/${encodeURIComponent(id)}:complete-review`,
        fields: [
          { name: 'outcome', label: 'Review outcome', type: 'select', options: ['APPROVED_USE', 'POLICY_CONCERN', 'INCIDENT_FOLLOW_UP_REQUIRED'], required: true, help: 'Review is immutable evidence after authority ends; it never retroactively authorizes or extends emergency authority.' },
          { name: 'summary', label: 'Review summary', type: 'textarea', required: true, help: 'Record the bounded post-use assessment required by the accepted Administration contract.' },
        ],
        body: (values) => ({ outcome: values.outcome, summary: values.summary }),
      },
    ],
  };
}

export function AdministrationWorkspace() {
  return <WorkspaceTabs tabs={[
    { id: 'roles', label: 'Admin roles', content: <Workspace spec={adminRoleSpec} /> },
    { id: 'grants', label: 'Direct grants', content: <Workspace spec={adminSpec('grant')} /> },
    { id: 'delegations', label: 'Delegations', content: <Workspace spec={adminSpec('delegation')} /> },
    { id: 'elevations', label: 'Elevations', content: <Workspace spec={adminSpec('elevation')} /> },
    { id: 'breakglass', label: 'Emergency · break-glass', content: <Workspace spec={adminSpec('break-glass')} /> },
    { id: 'authority', label: 'My effective authority', content: <CurrentAdministrativeAuthorityPage /> },
  ]} />;
}
