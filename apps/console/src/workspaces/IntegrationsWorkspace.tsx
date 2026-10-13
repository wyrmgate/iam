import { V1_ROOT } from '../api';
import { IntegrationOperationsPage } from '../IntegrationOperationsPage';
import { type Spec, Workspace, WorkspaceTabs } from '../SemanticWorkspace';
import { Box, StatePanel, Surface, Typography } from '../ui';
import { nullable } from './workspaceSpecHelpers';

const connectorSpec: Spec = {
  eyebrow: 'Integration · connector lifecycle',
  title: 'Connector Instances',
  description: 'Integration-owned connector runtime and provider-edge configuration. Connector state controls technical execution; it does not create Catalog or Access authority.',
  detail: (id) => `${V1_ROOT}/connectors/${encodeURIComponent(id)}`,
  create: {
    path: `${V1_ROOT}/connectors`,
    fields: [
      { name: 'connectorType', label: 'Connector type', required: true, help: 'Immutable connector implementation family.' },
      { name: 'runtimeId', label: 'Runtime ID', required: true, help: 'Provider-neutral runtime descriptor selected for this connector.' },
      { name: 'runtimeVersion', label: 'Runtime version', required: true },
      { name: 'configurationVersion', label: 'Configuration version', type: 'number', required: true, help: 'Version of the non-secret provider-edge configuration schema/value set.' },
      {
        name: 'configuration',
        label: 'Non-secret provider configuration JSON',
        type: 'textarea',
        required: true,
        help: 'Configuration is provider-edge metadata only. Never place passwords, tokens, private keys, or other secret values here.',
      },
      {
        name: 'secretReference',
        label: 'Opaque secret reference',
        help: 'Optional write-only reference to approved secret infrastructure. Enter a reference, never the secret value itself.',
      },
    ],
    body: (values) => ({
      connectorType: values.connectorType,
      runtimeId: values.runtimeId,
      runtimeVersion: values.runtimeVersion,
      configurationVersion: Number(values.configurationVersion),
      configuration: JSON.parse(String(values.configuration || '{}')),
      secretReference: nullable(values.secretReference),
    }),
  },
  actions: [
    {
      label: 'Disable connector',
      path: (id) => `${V1_ROOT}/connectors/${encodeURIComponent(id)}:disable`,
      destructive: true,
    },
  ],
};

const bindingSpec: Spec = {
  eyebrow: 'Integration · execution scope',
  title: 'Connector Bindings',
  description: 'A binding attaches one ConnectorInstance to one technical SourceSystem or ApplicationTarget and pins the connector contract used for that scope.',
  detail: (id) => `${V1_ROOT}/connector-bindings/${encodeURIComponent(id)}`,
  actions: [
    {
      label: 'Disable binding',
      path: (id) => `${V1_ROOT}/connector-bindings/${encodeURIComponent(id)}:disable`,
      destructive: true,
    },
  ],
};

const workerSpec: Spec = {
  eyebrow: 'Integration · worker registration',
  title: 'Connector Workers',
  description: 'Administrative registration and allowed execution scope for remote connector workers. Registration is authority to participate in Integration execution, not a browser execution console.',
  detail: (id) => `${V1_ROOT}/connector-workers/${encodeURIComponent(id)}`,
  actions: [
    {
      label: 'Disable worker registration',
      path: (id) => `${V1_ROOT}/connector-workers/${encodeURIComponent(id)}:disable`,
      destructive: true,
    },
  ],
};

function ConnectorWorkspace() {
  return (
    <Box sx={{ display: 'grid', gap: 2 }}>
      <StatePanel state="informational" title="Connector lifecycle is technical authority">
        ConnectorInstance owns runtime selection, configuration version, non-secret provider-edge configuration, secret-reference presence, and lifecycle state. Provider observations produced through a connector remain observations; they do not redefine Catalog Entitlements or AccessAssignments.
      </StatePanel>
      <Surface title="Configuration and secret boundary" description="Keep provider configuration inspectable without moving secret material into the Console.">
        <Box component="ul" sx={{ m: 0, pl: 2.5, display: 'grid', gap: 1 }}>
          <Typography component="li" variant="body2">Provider configuration JSON is non-secret configuration only and remains separate from governance authority.</Typography>
          <Typography component="li" variant="body2">The secret reference is an opaque write-only pointer; read responses expose only whether a secret is configured.</Typography>
          <Typography component="li" variant="body2">Disable is the explicit lifecycle operation. The Console does not offer generic status mutation or provider-native authority editing.</Typography>
        </Box>
      </Surface>
      <Workspace spec={connectorSpec} />
    </Box>
  );
}

function BindingWorkspace() {
  return (
    <Box sx={{ display: 'grid', gap: 2 }}>
      <StatePanel state="informational" title="Binding defines connector scope and contract">
        ConnectorInstance, target kind, and target ID establish the technical scope. Contract identity/version and discovery-completeness capabilities describe what Integration may safely conclude for that binding; they do not make provider state authoritative.
      </StatePanel>
      <Surface title="Completeness is capability-scoped" description="A COMPLETE report is meaningful only when the matching binding capability and run context support it.">
        <Box component="ul" sx={{ m: 0, pl: 2.5, display: 'grid', gap: 1 }}>
          <Typography component="li" variant="body2">Principal, entitlement, and grant discovery completeness are separate capabilities.</Typography>
          <Typography component="li" variant="body2">PARTIAL or UNKNOWN coverage may contribute positive observations but never authorizes destructive absence inference.</Typography>
          <Typography component="li" variant="body2">This workspace opens existing bindings and preserves the accepted explicit disable operation; it does not invent a generic binding status editor.</Typography>
        </Box>
      </Surface>
      <Workspace spec={bindingSpec} />
    </Box>
  );
}

function WorkerWorkspace() {
  return (
    <Box sx={{ display: 'grid', gap: 2 }}>
      <StatePanel state="informational" title="Registration is separate from worker execution">
        Worker registration controls the server-side external subject binding, protocol compatibility, allowed ConnectorBinding scope, and permitted runtime/capability contracts. The authenticated worker still receives only server-approved scope.
      </StatePanel>
      <StatePanel state="warning" title="Internal execution protocol stays private">
        Claiming work, lease renewal, observation batching, completion fencing, task payloads, lease identifiers, and execution checkpoints are internal Integration protocol concerns and are never browser controls.
      </StatePanel>
      <Workspace spec={workerSpec} />
    </Box>
  );
}

function OperationsWorkspace() {
  return (
    <Box sx={{ display: 'grid', gap: 2 }}>
      <StatePanel state="informational" title="Operational / non-authoritative">
        Provisioning jobs, tasks, immutable attempts, and reconciliation runs explain technical fulfillment and reconciliation progress. They are read-only operational views and never become Access intent, desired provider state, or Catalog authority.
      </StatePanel>
      <IntegrationOperationsPage />
    </Box>
  );
}

export function IntegrationsWorkspace() {
  return (
    <WorkspaceTabs
      tabs={[
        { id: 'connector', label: 'Connectors · lifecycle', content: <ConnectorWorkspace /> },
        { id: 'binding', label: 'Bindings · scope', content: <BindingWorkspace /> },
        { id: 'worker', label: 'Workers · registration', content: <WorkerWorkspace /> },
        { id: 'ops', label: 'Operations · read-only', content: <OperationsWorkspace /> },
      ]}
    />
  );
}
