import { useEffect, useState } from 'react';
import { ApiError, apiRequest, V1_ROOT, withCursor, type ApiResult } from './api';

type Resource = Record<string, unknown>;
type Page = { items: Resource[]; nextCursor?: string | null };
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
    columns: ['state', 'operationType', 'subjectKind', 'attemptCount', 'failureCode'],
    list: (cursor) => withCursor(`${V1_ROOT}/provisioning-tasks`, cursor),
    detail: (id) => `${V1_ROOT}/provisioning-tasks/${encodeURIComponent(id)}`,
  },
  {
    id: 'runs',
    label: 'Reconciliation runs',
    title: 'Reconciliation Runs',
    description: 'Provider reconciliation process/coverage status. Observation remains non-authoritative until explicitly mapped through accepted IAM semantics.',
    columns: ['state', 'scopeObjectClass', 'reportedCoverage', 'effectiveCompleteness', 'failureCode'],
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
  return <div className="state-card state-error" role="alert"><strong>{title}</strong><span>{detail}</span></div>;
}

function valueText(value: unknown) {
  if (value === null || value === undefined || value === '') return '—';
  return typeof value === 'object' ? '[structured]' : String(value);
}

function OperationalWorkspace({ spec }: { spec: Spec }) {
  const [pages, setPages] = useState<Page[]>([]);
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
    apiRequest<Page>(spec.list(null), { signal: controller.signal })
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
      const { data } = await apiRequest<Page>(spec.list(next));
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

  return <section className="page-stack">
    <header className="page-header"><p className="eyebrow">Integration · operational status</p><h1>{spec.title}</h1><p>{spec.description}</p></header>
    {error !== null ? <ErrorState error={error} /> : null}
    {loading && items.length === 0 ? <div className="state-card">Loading…</div> : null}
    {!loading && items.length === 0 && error === null ? <div className="state-card">No operational records found.</div> : null}
    {items.length > 0 ? <div className="table-wrap"><table><thead><tr>{spec.columns.map((column) => <th key={column}>{column}</th>)}<th>Open</th></tr></thead><tbody>{items.map((item, index) => <tr key={String(item.id ?? index)}>{spec.columns.map((column) => <td key={column}>{valueText(item[column])}</td>)}<td>{typeof item.id === 'string' ? <button className="text-button" onClick={() => void lookup(item.id as string)}>View</button> : null}</td></tr>)}</tbody></table></div> : null}
    {next ? <button className="button secondary" disabled={loading} onClick={() => void loadMore()}>Load more</button> : null}
    <div className="card lookup-card"><label><span>Resource ID</span><input value={id} onChange={(event) => setId(event.target.value)} /></label><button className="button secondary" disabled={!id} onClick={() => void lookup()}>Lookup</button></div>
    {detail ? <section className="card detail-panel"><dl className="resource-details">{Object.entries(detail.data).map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{valueText(value)}</dd></div>)}</dl></section> : null}
  </section>;
}

function ProvisioningAttempts() {
  const [taskId, setTaskId] = useState('');
  const [activeTaskId, setActiveTaskId] = useState('');
  if (!activeTaskId) {
    return <section className="page-stack"><header className="page-header"><p className="eyebrow">Integration · operational status</p><h1>Provisioning Attempts</h1><p>Immutable normalized attempt outcomes for one provisioning task. Worker leases, provider-native object IDs and raw metadata are intentionally absent.</p></header><div className="card lookup-card"><label><span>Provisioning task ID</span><input value={taskId} onChange={(event) => setTaskId(event.target.value)} /></label><button className="button" disabled={!taskId} onClick={() => setActiveTaskId(taskId)}>Open attempts</button></div></section>;
  }
  const spec: Spec = {
    id: `attempts:${activeTaskId}`,
    label: 'Attempts',
    title: 'Provisioning Attempts',
    description: `Immutable normalized attempts for task ${activeTaskId}. Worker execution internals remain private.`,
    columns: ['attemptNumber', 'outcome', 'failureCategory', 'startedAt', 'completedAt'],
    list: (cursor) => withCursor(`${V1_ROOT}/provisioning-tasks/${encodeURIComponent(activeTaskId)}/attempts`, cursor),
    detail: (id) => `${V1_ROOT}/provisioning-attempts/${encodeURIComponent(id)}`,
  };
  return <><button className="back-link" onClick={() => setActiveTaskId('')}>← Choose another task</button><OperationalWorkspace spec={spec} /></>;
}

export function IntegrationOperationsPage() {
  const [active, setActive] = useState('jobs');
  const current = specs.find((spec) => spec.id === active);
  return <>
    <div className="state-card"><strong>Operational / non-authoritative</strong><span>These views report Integration process and provider-observation status only. AccessAssignment and other owning-capability state remain authoritative.</span></div>
    <div className="tabs">
      {specs.map((spec) => <button key={spec.id} className={active === spec.id ? 'active' : ''} onClick={() => setActive(spec.id)}>{spec.label}</button>)}
      <button className={active === 'attempts' ? 'active' : ''} onClick={() => setActive('attempts')}>Attempts</button>
    </div>
    {active === 'attempts' ? <ProvisioningAttempts /> : current ? <OperationalWorkspace spec={current} /> : null}
  </>;
}
