import { V1_ROOT } from '../api';
import { IntegrationOperationsPage } from '../IntegrationOperationsPage';
import { type Spec, Workspace, WorkspaceTabs } from '../SemanticWorkspace';
import { nullable } from './workspaceSpecHelpers';

export function IntegrationsWorkspace() {
  const connector: Spec = { eyebrow: 'Integration', title: 'Connector Instances', description: 'Integration-owned connector lifecycle/configuration. Provider observation is not Catalog/Access authority.', detail: (id) => `${V1_ROOT}/connectors/${encodeURIComponent(id)}`, create: { path: `${V1_ROOT}/connectors`, fields: [{ name: 'connectorType', label: 'Connector type', required: true }, { name: 'runtimeId', label: 'Runtime ID', required: true }, { name: 'runtimeVersion', label: 'Runtime version', required: true }, { name: 'configurationVersion', label: 'Configuration version', type: 'number', required: true }, { name: 'configuration', label: 'Provider configuration JSON', type: 'textarea', required: true, help: 'Do not place secrets here.' }, { name: 'secretReference', label: 'Opaque secret reference' }], body: (values) => ({ connectorType: values.connectorType, runtimeId: values.runtimeId, runtimeVersion: values.runtimeVersion, configurationVersion: Number(values.configurationVersion), configuration: JSON.parse(String(values.configuration || '{}')), secretReference: nullable(values.secretReference) }) }, actions: [{ label: 'Disable', path: (id) => `${V1_ROOT}/connectors/${encodeURIComponent(id)}:disable`, destructive: true }] };
  return <WorkspaceTabs tabs={[
    { id: 'connector', label: 'Connectors', content: <Workspace spec={connector} /> },
    { id: 'binding', label: 'Bindings', content: <Workspace spec={{ eyebrow: 'Integration', title: 'Connector Bindings', description: 'Public binding administration lookup. The current checked-in contract does not define a request schema for create/update, so the browser does not invent one.', detail: (id) => `${V1_ROOT}/connector-bindings/${encodeURIComponent(id)}`, actions: [{ label: 'Disable', path: (id) => `${V1_ROOT}/connector-bindings/${encodeURIComponent(id)}:disable`, destructive: true }] }} /> },
    { id: 'worker', label: 'Workers', content: <Workspace spec={{ eyebrow: 'Integration', title: 'Connector Workers', description: 'Public registration lookup. Internal worker execution protocol is never exposed to the browser.', detail: (id) => `${V1_ROOT}/connector-workers/${encodeURIComponent(id)}`, actions: [{ label: 'Disable', path: (id) => `${V1_ROOT}/connector-workers/${encodeURIComponent(id)}:disable`, destructive: true }] }} /> },
    { id: 'ops', label: 'Operations', content: <IntegrationOperationsPage /> },
  ]} />;
}
