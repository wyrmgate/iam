import { V1_ROOT } from '../api';
import { type Spec, Workspace } from '../SemanticWorkspace';
import { list } from './workspaceSpecHelpers';

const principalSpec: Spec = {
  eyebrow: 'Identity', title: 'Principals', description: 'Technical account authority owned by Identity; provider observation is not Principal authority.',
  list: list(`${V1_ROOT}/principals`), detail: (id) => `${V1_ROOT}/principals/${encodeURIComponent(id)}`,
  columns: ['nativePrincipalKey', 'applicationTargetId', 'identityId', 'lifecycleState'],
  create: { path: `${V1_ROOT}/principals`, fields: [{ name: 'applicationTargetId', label: 'Application Target ID', required: true }, { name: 'nativePrincipalKey', label: 'Native principal key', required: true }], body: (values) => ({ applicationTargetId: values.applicationTargetId, nativePrincipalKey: values.nativePrincipalKey }) },
  actions: [{ label: 'Correlate to Identity', path: (id) => `${V1_ROOT}/principals/${encodeURIComponent(id)}:correlate`, fields: [{ name: 'identityId', label: 'Identity ID', required: true }], body: (values) => ({ identityId: values.identityId }) }],
};

export function PrincipalsWorkspace() { return <Workspace spec={principalSpec} />; }
