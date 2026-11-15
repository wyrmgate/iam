import { useRef, useState } from 'react';
import { ApiError, apiRequest, createIdempotencyKey, V1_ROOT, type ApiResult } from '../api';
import { IntegrationOperationsPage } from '../IntegrationOperationsPage';
import { type Spec, Workspace, WorkspaceTabs } from '../SemanticWorkspace';
import { Box, Button, SelectInput, StatePanel, Surface, TextInput, Typography } from '../ui';
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

type ReconcileBinding = { id: string; targetKind: string; lifecycleState: string };
type ScheduledRun = { id: string; state: string; scopeMode: string };

function ReconciliationScheduler() {
  const [bindingId, setBindingId] = useState('');
  const [binding, setBinding] = useState<ApiResult<ReconcileBinding> | null>(null);
  const [objectClass, setObjectClass] = useState('PRINCIPAL');
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [run, setRun] = useState<ScheduledRun | null>(null);
  const retry = useRef<{ fingerprint: string; key: string } | null>(null);

  function showError(error: unknown) {
    setMessage(error instanceof ApiError
      ? error.status === 412 ? 'Binding revision changed. Reload the binding before scheduling.'
        : error.status === 403 ? 'Administrative authorization denied this operation.'
          : `Request failed (${error.status}).${error.correlationId ? ` Correlation: ${error.correlationId}` : ''}`
      : 'Request failed. Retry the same operation to preserve its causal identity.');
  }

  async function loadBinding() {
    setPending(true);
    setMessage(null);
    setBinding(null);
    setRun(null);
    try {
      const result = await apiRequest<ReconcileBinding>(`${V1_ROOT}/connector-bindings/${encodeURIComponent(bindingId.trim())}`);
      if (!result.etag || result.data.lifecycleState !== 'ACTIVE') {
        setMessage('Scheduling requires an active binding with a current revision.');
        return;
      }
      setBinding(result);
      setObjectClass('PRINCIPAL');
    } catch (error) {
      showError(error);
    } finally {
      setPending(false);
    }
  }

  async function schedule() {
    if (!binding?.etag || pending) return;
    setPending(true);
    setMessage(null);
    setRun(null);
    const body = { objectClass, scopeMode: 'FULL', checkpoint: null };
    const fingerprint = JSON.stringify([binding.data.id, binding.etag, body]);
    if (retry.current?.fingerprint !== fingerprint) {
      retry.current = { fingerprint, key: createIdempotencyKey() };
    }
    const key = retry.current.key;
    try {
      const result = await apiRequest<ScheduledRun>(`${V1_ROOT}/connector-bindings/${encodeURIComponent(binding.data.id)}:reconcile`, {
        method: 'POST', etag: binding.etag, idempotencyKey: key, body,
      });
      setRun(result.data);
      retry.current = null;
    } catch (error) {
      showError(error);
    } finally {
      setPending(false);
    }
  }

  return (
    <Surface title="Schedule full reconciliation" description="Load an active binding and schedule durable discovery. Accepted scheduling is not provider completion or verified coverage.">
      <Box sx={{ display: 'grid', gap: 2 }}>
        <TextInput label="Connector binding ID" value={bindingId} disabled={pending}
          onChange={(event) => { setBindingId(event.target.value); setBinding(null); setRun(null); setMessage(null); }} />
        <Button variant="outlined" disabled={pending || !bindingId.trim()} onClick={() => void loadBinding()}>Load binding revision</Button>
        {binding ? <>
          <Typography variant="body2">Loaded binding: {binding.data.id} · {binding.data.targetKind}</Typography>
          <SelectInput label="Object class" value={objectClass} disabled={pending}
            options={binding.data.targetKind === 'SOURCE_SYSTEM' ? ['PRINCIPAL'] : ['PRINCIPAL', 'ENTITLEMENT', 'GRANT']}
            onChange={(event) => setObjectClass(event.target.value)} />
          <Button variant="contained" disabled={pending || !!run} onClick={() => void schedule()}>
            {pending ? 'Working…' : 'Schedule full reconciliation'}
          </Button>
        </> : null}
        {message ? <StatePanel severity="error">{message}</StatePanel> : null}
        {run ? <StatePanel title="Reconciliation accepted">
          Run {run.id} · {run.scopeMode} · {run.state}. Open the Operations workspace to inspect current coverage and progress. Load the binding again to schedule another run.
        </StatePanel> : null}
      </Box>
    </Surface>
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
      <ReconciliationScheduler />
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
