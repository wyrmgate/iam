import { useEffect, useRef, useState } from 'react';
import { ApiError, apiRequest, createIdempotencyKey, V1_ROOT, withCursor, type ApiResult } from './api';
import {
  Button,
  Box,
  CheckboxInput,
  DataTable,
  Page,
  PageHeader,
  StatePanel,
  Surface,
  Tab,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tabs,
  TextInput,
  Typography,
} from './ui';

type Resource = Record<string, unknown>;
type PageResult = { items: Resource[]; nextCursor?: string | null };
type Spec = {
  id: string;
  label: string;
  title: string;
  description: string;
  columns: string[];
  list: (cursor?: string | null) => string;
  detail: (id: string) => string;
};

const specs: Spec[] = [
  {
    id: 'jobs',
    label: 'Provisioning jobs',
    title: 'Provisioning Jobs',
    description: 'Integration-owned fulfillment process status. This is operational state, not Access authoritative intent or desired provider state.',
    columns: ['state', 'connectorBindingId', 'createdAt', 'updatedAt', 'correlationId'],
    list: (cursor) => withCursor(`${V1_ROOT}/provisioning-jobs`, cursor),
    detail: (id) => `${V1_ROOT}/provisioning-jobs/${encodeURIComponent(id)}`,
  },
  {
    id: 'tasks',
    label: 'Provisioning tasks',
    title: 'Provisioning Tasks',
    description: 'Bounded technical fulfillment tasks. Provider execution payloads, idempotency keys and worker lease state are never exposed here.',
    columns: ['state', 'operationType', 'subjectKind', 'attemptCount', 'failureCode', 'recoveryDisposition'],
    list: (cursor) => withCursor(`${V1_ROOT}/provisioning-tasks`, cursor),
    detail: (id) => `${V1_ROOT}/provisioning-tasks/${encodeURIComponent(id)}`,
  },
  {
    id: 'runs',
    label: 'Reconciliation runs',
    title: 'Reconciliation Runs',
    description: 'Provider reconciliation process/coverage status. Observation remains non-authoritative until explicitly mapped through accepted IAM semantics.',
    columns: ['state', 'scopeObjectClass', 'scopeMode', 'reportedCoverage', 'effectiveCompleteness', 'failureCode'],
    list: (cursor) => withCursor(`${V1_ROOT}/reconciliation-runs`, cursor),
    detail: (id) => `${V1_ROOT}/reconciliation-runs/${encodeURIComponent(id)}`,
  },
];

function ErrorState({ error }: { error: unknown }) {
  let title = 'Request failed';
  let detail = error instanceof Error ? error.message : 'Unable to complete request.';
  if (error instanceof ApiError && error.status === 403) {
    title = 'Not authorized';
    detail = 'Administrative Authorization denied this operational read. Server authorization is final.';
  }
  return <StatePanel severity="error" title={title}>{detail}</StatePanel>;
}

function valueText(value: unknown) {
  if (value === null || value === undefined || value === '') return '—';
  return typeof value === 'object' ? '[structured]' : String(value);
}

type RecoveryDecision = { id: string; disposition: string; observation: string; correctiveJobId?: string | null };

function VerifiedRecovery({ task, refresh }: { task: ApiResult<Resource>; refresh: () => Promise<void> }) {
  const [runId, setRunId] = useState('');
  const [principalId, setPrincipalId] = useState('');
  const [quiesced, setQuiesced] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [decision, setDecision] = useState<RecoveryDecision | null>(null);
  const causal = useRef<{ fingerprint: string; key: string } | null>(null);
  const allowed = ['provider_effect_unknown_after_lease_expiry', 'provider_create_effect_unknown', 'late_provider_success_requires_recovery', 'retry_budget_exhausted'];
  if (task.data.state !== 'BLOCKED' || !allowed.includes(String(task.data.failureCode)) || task.data.recoveryDecisionId) return null;

  async function recover() {
    if (!task.etag || !confirmed || !runId.trim() || !quiesced || pending) return;
    setPending(true);
    setError(null);
    try {
      const time = new Date(quiesced);
      if (Number.isNaN(time.getTime())) throw new Error('Enter a valid execution quiescence time.');
      const body = { reconciliationRunId: runId.trim(), principalId: principalId.trim() || null, executionQuiescedAt: time.toISOString() };
      const fingerprint = JSON.stringify([task.data.id, task.etag, body]);
      if (causal.current?.fingerprint !== fingerprint) causal.current = { fingerprint, key: createIdempotencyKey() };
      const result = await apiRequest<RecoveryDecision>(`${V1_ROOT}/provisioning-tasks/${encodeURIComponent(String(task.data.id))}:recover`, {
        method: 'POST', etag: task.etag, idempotencyKey: causal.current.key, body,
      });
      setDecision(result.data);
      await refresh();
    } catch (cause) {
      setError(cause);
    } finally {
      setPending(false);
    }
  }

  return (
    <Surface title="Verify and recover blocked provisioning" description="Never retry an uncertain mutation blindly. Stop prior external execution, then complete a new full reconciliation of the exact binding.">
      <Box sx={{ display: 'grid', gap: 2 }}>
        <TextInput label="Verified reconciliation run ID" value={runId} disabled={pending} onChange={(event) => setRunId(event.target.value)} />
        <TextInput label="Governed Principal ID (required for uncertain create)" value={principalId} disabled={pending} onChange={(event) => setPrincipalId(event.target.value)} />
        <TextInput label="Execution quiesced at (local time)" type="datetime-local" value={quiesced} disabled={pending}
          slotProps={{ inputLabel: { shrink: true } }} onChange={(event) => setQuiesced(event.target.value)} />
        <CheckboxInput label="I verified prior external execution stopped before this full scan" checked={confirmed} disabled={pending} onChange={setConfirmed} />
        <Button variant="contained" disabled={pending || !!decision || !task.etag || !confirmed || !runId.trim() || !quiesced} onClick={() => void recover()}>
          {pending ? 'Verifying…' : 'Record verified recovery'}
        </Button>
        {error !== null ? <ErrorState error={error} /> : null}
        {decision ? <StatePanel title="Recovery decision recorded">
          {decision.disposition} · {decision.observation}. Original task stays blocked. {decision.correctiveJobId ? `New corrective job: ${decision.correctiveJobId}; provider completion is not implied.` : 'No corrective provider work is required for the verified observation.'}
        </StatePanel> : null}
      </Box>
    </Surface>
  );
}

function OperationalWorkspace({ spec }: { spec: Spec }) {
  const [pages, setPages] = useState<PageResult[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [id, setId] = useState('');
  const [detail, setDetail] = useState<ApiResult<Resource> | null>(null);
  const items = pages.flatMap((page) => page.items);
  const next = pages.at(-1)?.nextCursor ?? null;

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    setPages([]);
    setDetail(null);
    apiRequest<PageResult>(spec.list(null), { signal: controller.signal })
      .then(({ data }) => setPages([data]))
      .catch((cause) => {
        if (!(cause instanceof DOMException && cause.name === 'AbortError')) setError(cause);
      })
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, [spec]);

  async function loadMore() {
    if (!next) return;
    setLoading(true);
    try {
      const { data } = await apiRequest<PageResult>(spec.list(next));
      setPages((current) => [...current, data]);
    } catch (cause) {
      setError(cause);
    } finally {
      setLoading(false);
    }
  }

  async function lookup(target = id) {
    if (!target) return;
    setError(null);
    try {
      setDetail(await apiRequest<Resource>(spec.detail(target)));
      setId(target);
    } catch (cause) {
      setError(cause);
      setDetail(null);
    }
  }

  return (
    <Page>
      <PageHeader eyebrow="Integration · operational status" title={spec.title} description={spec.description} />

      {error !== null ? <ErrorState error={error} /> : null}
      {loading && items.length === 0 ? <StatePanel>Loading operational records…</StatePanel> : null}
      {!loading && items.length === 0 && error === null ? <StatePanel>No operational records found.</StatePanel> : null}

      {items.length > 0 ? (
        <Surface title="Operational records" description="Provider execution details remain data-minimized at this public Console boundary.">
          <DataTable minWidth={900}>
            <TableHead>
              <TableRow>
                {spec.columns.map((column) => <TableCell key={column}>{column}</TableCell>)}
                <TableCell align="right">Open</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {items.map((item, index) => (
                <TableRow key={String(item.id ?? index)} hover>
                  {spec.columns.map((column) => <TableCell key={column}>{valueText(item[column])}</TableCell>)}
                  <TableCell align="right">
                    {typeof item.id === 'string' ? (
                      <Button size="small" variant="text" onClick={() => void lookup(item.id as string)}>View</Button>
                    ) : null}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </DataTable>
        </Surface>
      ) : null}

      {next ? (
        <Button variant="outlined" disabled={loading} onClick={() => void loadMore()} sx={{ alignSelf: 'flex-start' }}>
          {loading ? 'Loading…' : 'Load more'}
        </Button>
      ) : null}

      <Surface title="Open resource" description="Lookup uses the accepted public Integration read contract for the selected resource type.">
        <div className="lookup-card">
          <TextInput label="Resource ID" value={id} onChange={(event) => setId(event.target.value)} />
          <Button variant="outlined" disabled={!id} onClick={() => void lookup()}>Lookup</Button>
        </div>
      </Surface>

      {detail && spec.id === 'tasks' ? <VerifiedRecovery key={String(detail.data.id)} task={detail} refresh={() => lookup(String(detail.data.id))} /> : null}
      {detail ? (
        <Surface title="Resource detail">
          <dl className="resource-details">
            {Object.entries(detail.data).map(([key, value]) => (
              <div key={key}><dt>{key}</dt><dd>{valueText(value)}</dd></div>
            ))}
          </dl>
        </Surface>
      ) : null}
    </Page>
  );
}

function ProvisioningAttempts() {
  const [taskId, setTaskId] = useState('');
  const [activeTaskId, setActiveTaskId] = useState('');

  if (!activeTaskId) {
    return (
      <Page>
        <PageHeader
          eyebrow="Integration · operational status"
          title="Provisioning Attempts"
          description="Immutable normalized attempt outcomes for one provisioning task. Worker leases, provider-native object IDs and raw metadata are intentionally absent."
        />
        <Surface title="Choose provisioning task">
          <div className="lookup-card">
            <TextInput label="Provisioning task ID" value={taskId} onChange={(event) => setTaskId(event.target.value)} />
            <Button variant="contained" disabled={!taskId} onClick={() => setActiveTaskId(taskId)}>Open attempts</Button>
          </div>
        </Surface>
      </Page>
    );
  }

  const spec: Spec = {
    id: `attempts:${activeTaskId}`,
    label: 'Attempts',
    title: 'Provisioning Attempts',
    description: `Immutable normalized attempts for task ${activeTaskId}. Recovery status is separate from immutable attempt evidence; PLANNED is not provider completion and MANUAL requires evidence-based investigation. Worker execution internals remain private.`,
    columns: ['attemptNumber', 'outcome', 'effectRecoveryState', 'effectRecoveryReason', 'failureCategory', 'startedAt', 'completedAt'],
    list: (cursor) => withCursor(`${V1_ROOT}/provisioning-tasks/${encodeURIComponent(activeTaskId)}/attempts`, cursor),
    detail: (id) => `${V1_ROOT}/provisioning-attempts/${encodeURIComponent(id)}`,
  };

  return (
    <>
      <Button variant="text" onClick={() => setActiveTaskId('')} sx={{ mb: 1 }}>← Choose another task</Button>
      <OperationalWorkspace spec={spec} />
    </>
  );
}

export function IntegrationOperationsPage() {
  const [active, setActive] = useState('jobs');
  const current = specs.find((spec) => spec.id === active);

  return (
    <>
      <StatePanel title="Operational / non-authoritative">
        These views report Integration process and provider-observation status only. AccessAssignment and other owning-capability state remain authoritative.
      </StatePanel>
      <Tabs value={active} onChange={(_event, value: string) => setActive(value)} aria-label="Integration operational views">
        {specs.map((spec) => <Tab key={spec.id} value={spec.id} label={spec.label} />)}
        <Tab value="attempts" label="Attempts" />
      </Tabs>
      <Typography component="div">
        {active === 'attempts' ? <ProvisioningAttempts /> : current ? <OperationalWorkspace spec={current} /> : null}
      </Typography>
    </>
  );
}
