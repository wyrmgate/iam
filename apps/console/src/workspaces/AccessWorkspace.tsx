import { V1_ROOT } from '../api';
import { type Spec, Workspace, WorkspaceTabs } from '../SemanticWorkspace';
import { iso, list, nullable } from './workspaceSpecHelpers';

const assignmentSpec: Spec = {
  eyebrow: 'Access', title: 'Access Assignments', description: 'Authoritative business access intent.',
  list: list(`${V1_ROOT}/access-assignments`), detail: (id) => `${V1_ROOT}/access-assignments/${encodeURIComponent(id)}`,
  columns: ['identityId', 'targetKind', 'lifecycleState', 'principalConstraintKind'],
  create: {
    path: `${V1_ROOT}/access-assignments`,
    fields: [
      { name: 'identityId', label: 'Identity ID', required: true },
      { name: 'targetKind', label: 'Target kind', type: 'select', options: ['ROLE', 'ENTITLEMENT'], required: true },
      { name: 'roleId', label: 'Role ID' }, { name: 'entitlementId', label: 'Entitlement ID' },
      { name: 'principalConstraintKind', label: 'Principal constraint', type: 'select', options: ['ANY', 'SPECIFIC'], required: true },
      { name: 'specificPrincipalId', label: 'Specific Principal ID' },
      { name: 'validFrom', label: 'Valid from', type: 'datetime-local' }, { name: 'validUntil', label: 'Valid until', type: 'datetime-local' },
    ],
    body: (values) => ({ identityId: values.identityId, targetKind: values.targetKind, roleId: nullable(values.roleId), entitlementId: nullable(values.entitlementId), principalConstraintKind: values.principalConstraintKind, specificPrincipalId: nullable(values.specificPrincipalId), validFrom: iso(values.validFrom), validUntil: iso(values.validUntil) }),
  },
  actions: ['suspend', 'resume', 'cancel', 'revoke'].map((name) => ({ label: name[0].toUpperCase() + name.slice(1), path: (id) => `${V1_ROOT}/access-assignments/${encodeURIComponent(id)}/${name}`, destructive: name === 'cancel' || name === 'revoke' })),
};

const effectiveSpec: Spec = {
  eyebrow: 'Access · projection', title: 'Effective Access', description: 'Read-only entitlement-level projection, never editable business intent.',
  list: list(`${V1_ROOT}/effective-access`), detail: (id) => `${V1_ROOT}/effective-access/${encodeURIComponent(id)}`,
  columns: ['identityId', 'entitlementId', 'principalConstraintKey', 'supportCount'],
};

export function AccessWorkspace() {
  return <WorkspaceTabs tabs={[
    { id: 'intent', label: 'Assignments', content: <Workspace spec={assignmentSpec} /> },
    { id: 'effective', label: 'Effective access', content: <Workspace spec={effectiveSpec} /> },
    { id: 'policy', label: 'Lifecycle policy', content: <Workspace spec={{ eyebrow: 'Access · policy', title: 'Lifecycle Access Policy', description: 'Typed policy read/activation is available at the accepted semantic endpoint; generic expression editing is intentionally not provided.', detail: () => `${V1_ROOT}/lifecycle-access-policy` }} /> },
  ]} />;
}
