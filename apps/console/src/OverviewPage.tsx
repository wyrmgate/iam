import { useEffect, useMemo, useRef, useState } from 'react';
import type { CurrentAdministrativeAuthority, SystemInfo } from './api';
import {
  getIamOperationsOverview,
  type IamOperationsOverview,
  type OverviewMetric,
  type OverviewStatus,
  type OverviewWindow,
  type OverviewWorkSort,
  type OverviewWorkType,
} from './iamOperationsApi';
import { navigateConsole } from './consoleNavigation';
import { Box, Button, Page, StatePanel } from './ui';
import './iam-operations.css';

type LoadState<T> =
  | { status: 'loading' }
  | { status: 'ready'; value: T }
  | { status: 'error'; error: unknown };

type OverviewPageProps = {
  system: LoadState<SystemInfo>;
  authority: LoadState<CurrentAdministrativeAuthority>;
};

type OverviewLoadState =
  | { status: 'loading' }
  | { status: 'ready'; value: IamOperationsOverview }
  | { status: 'error'; error: unknown };

const numberFormat = new Intl.NumberFormat();
const compactFormat = new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 });

function shortId(value?: string | null) {
  if (!value) return '—';
  return value.length <= 10 ? value : `…${value.slice(-8)}`;
}

function dateTime(value?: string | null) {
  if (!value) return '—';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString([], {
    year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  });
}

function relativeAge(value: string, asOf: string) {
  const from = new Date(value).getTime();
  const to = new Date(asOf).getTime();
  if (!Number.isFinite(from) || !Number.isFinite(to)) return dateTime(value);
  const minutes = Math.max(0, Math.floor((to - from) / 60_000));
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h`;
  return `${Math.floor(hours / 24)}d`;
}

function windowLabel(value: OverviewWindow) {
  if (value === '24h') return '24 hours';
  if (value === '30d') return '30 days';
  return '7 days';
}

function statusText(status: OverviewStatus) {
  if (status === 'NOT_AUTHORIZED') return 'Not authorized';
  if (status === 'UNAVAILABLE') return 'Unavailable';
  if (status === 'UNSUPPORTED') return 'Not supported';
  return 'Available';
}

function MetricValue({ metric }: { metric: OverviewMetric }) {
  if (metric.status !== 'AVAILABLE' || metric.value === null) {
    return <span className="iam-metric-unavailable">{statusText(metric.status)}</span>;
  }
  return <>{numberFormat.format(metric.value)}</>;
}

function AttentionCard({ tone, icon, label, metric, qualifier, onOpen }: {
  tone: 'danger' | 'warning' | 'info'; icon: string; label: string; metric: OverviewMetric; qualifier: string; onOpen?: () => void;
}) {
  const content = <>
    <span className={`iam-attention-icon iam-tone-${tone}`} aria-hidden="true">{icon}</span>
    <span className="iam-attention-copy">
      <strong className="iam-attention-value"><MetricValue metric={metric} /></strong>
      <span className="iam-attention-label">{label}</span>
      <span className="iam-attention-qualifier">{qualifier}</span>
    </span>
    {onOpen ? <span className="iam-attention-arrow" aria-hidden="true">›</span> : null}
  </>;
  if (!onOpen || metric.status !== 'AVAILABLE') return <div className={`iam-attention-card iam-attention-${tone}`}>{content}</div>;
  return <button className={`iam-attention-card iam-attention-${tone}`} type="button" onClick={onOpen}>{content}</button>;
}

function StatusBadge({ value, kind = 'neutral' }: { value: string; kind?: 'success' | 'danger' | 'warning' | 'info' | 'neutral' }) {
  return <span className={`iam-badge iam-badge-${kind}`}>{value}</span>;
}

function SectionUnavailable({ status, noun }: { status: OverviewStatus; noun: string }) {
  return <div className="iam-section-state"><strong>{statusText(status)}</strong><span>{status === 'NOT_AUTHORIZED'
    ? `Your current authority does not include this ${noun}.` : `Wyrmgate could not evaluate this ${noun} right now.`}</span></div>;
}

function SummaryStat({ label, value, tone = 'neutral' }: { label: string; value: number | string; tone?: string }) {
  return <div className="iam-summary-stat"><span className={`iam-summary-dot iam-dot-${tone}`} aria-hidden="true" />
    <span><strong>{typeof value === 'number' ? numberFormat.format(value) : value}</strong><small>{label}</small></span></div>;
}

function ProvisioningChart({ data }: { data: IamOperationsOverview['provisioning'] }) {
  const max = useMemo(() => Math.max(1, ...data.trend.map((bucket) => bucket.succeeded + bucket.failedRetryable + bucket.failedFinal)), [data.trend]);
  if (data.status !== 'AVAILABLE' || !data.summary) return <SectionUnavailable status={data.status} noun="provisioning health projection" />;
  const allZero = data.trend.every((bucket) => bucket.succeeded + bucket.failedRetryable + bucket.failedFinal === 0);
  return <div className="iam-health-body">
    <div className="iam-chart-column">
      <div className="iam-chart-legend" aria-hidden="true"><span><i className="iam-legend-success" />Succeeded</span>
        <span><i className="iam-legend-warning" />Retryable failure</span><span><i className="iam-legend-danger" />Final failure</span></div>
      {allZero ? <div className="iam-empty-chart">No provisioning attempts completed in this period.</div> : <>
        <div className="iam-bar-chart" role="img" aria-label={`Provisioning attempt outcomes: ${data.summary.succeededAttempts} succeeded, ${data.summary.retryableFailedAttempts} retryable failures, ${data.summary.finalFailedAttempts} final failures.`}>
          {data.trend.map((bucket) => {
            const total = bucket.succeeded + bucket.failedRetryable + bucket.failedFinal;
            const height = `${Math.max(8, (total / max) * 100)}%`;
            return <div className="iam-bar-slot" key={bucket.bucketStart}><div className="iam-bar-stack" style={{ height }} title={`${dateTime(bucket.bucketStart)} · ${total} attempts`}>
              <span className="iam-bar-success" style={{ flex: bucket.succeeded }} /><span className="iam-bar-warning" style={{ flex: bucket.failedRetryable }} /><span className="iam-bar-danger" style={{ flex: bucket.failedFinal }} />
            </div><small>{new Date(bucket.bucketStart).toLocaleDateString([], { month: 'short', day: 'numeric' })}</small></div>;
          })}
        </div>
        <details className="iam-chart-table"><summary>View data table</summary><table><thead><tr><th>Bucket</th><th>Succeeded</th><th>Retryable</th><th>Final</th></tr></thead><tbody>
          {data.trend.map((bucket) => <tr key={bucket.bucketStart}><td>{dateTime(bucket.bucketStart)}</td><td>{bucket.succeeded}</td><td>{bucket.failedRetryable}</td><td>{bucket.failedFinal}</td></tr>)}
        </tbody></table></details>
      </>}
    </div>
    <div className="iam-health-summary"><span className="iam-summary-kicker">Totals</span>
      <SummaryStat label="Succeeded" value={data.summary.succeededAttempts} tone="success" />
      <SummaryStat label="Retryable failures" value={data.summary.retryableFailedAttempts} tone="warning" />
      <SummaryStat label="Final failures" value={data.summary.finalFailedAttempts} tone="danger" />
      <SummaryStat label="Running now" value={data.summary.runningTasksNow} tone="info" />
    </div>
  </div>;
}

function WorkTable({ overview, onType, onSort }: { overview: IamOperationsOverview; onType: (value: OverviewWorkType) => void; onSort: (value: OverviewWorkSort) => void }) {
  const work = overview.myWork;
  if (work.status !== 'AVAILABLE') return <SectionUnavailable status={work.status} noun="assigned governance work" />;
  return <><div className="iam-section-toolbar"><div className="iam-segmented" aria-label="Work type">
    {(['ALL', 'APPROVAL', 'REVIEW'] as OverviewWorkType[]).map((value) => <button type="button" key={value} className={work.appliedType === value ? 'active' : ''} onClick={() => onType(value)}>
      {value === 'ALL' ? `All (${work.totalCount ?? 0})` : value === 'APPROVAL' ? `Approvals (${work.approvalCount ?? 0})` : `Reviews (${work.reviewCount ?? 0})`}
    </button>)}
  </div><label className="iam-inline-select"><span>Sort</span><select value={work.appliedSort} onChange={(event) => onSort(event.target.value as OverviewWorkSort)}>
    <option value="OLDEST">Oldest first</option><option value="NEWEST">Newest first</option></select></label></div>
  {work.items.length === 0 ? <div className="iam-empty-state"><strong>No assigned governance work</strong><span>There are no pending approvals or reviews assigned to you.</span></div> :
    <div className="iam-table-scroll"><table className="iam-table"><caption className="sr-only">Assigned approvals and reviews</caption><thead><tr><th>Type</th><th>Item</th><th>Subject / resource</th><th>Created</th><th>Age</th><th>State</th><th><span className="sr-only">Action</span></th></tr></thead><tbody>
      {work.items.map((item) => { const isApproval = item.kind === 'APPROVAL'; return <tr key={`${item.kind}-${item.id}`}>
        <td><StatusBadge value={isApproval ? 'Approval' : 'Review'} kind={isApproval ? 'danger' : 'warning'} /></td>
        <td><strong>{isApproval ? 'APR' : 'REV'}-{shortId(item.id).replace('…', '')}</strong><small>{isApproval ? 'Governance approval' : 'Access review'}</small></td>
        <td><strong>{item.subjectKind.replaceAll('_', ' ')}</strong><small>{shortId(item.subjectId)}</small></td>
        <td><time dateTime={item.createdAt}>{dateTime(item.createdAt)}</time></td><td>{relativeAge(item.createdAt, overview.asOf)}</td>
        <td><StatusBadge value={isApproval ? `Stage ${item.stageOrdinal + 1}` : 'Pending'} kind="warning" /></td>
        <td><Button size="small" variant="contained" onClick={() => navigateConsole(isApproval ? `/requests?view=approvals&approvalCaseId=${encodeURIComponent(item.id)}` : `/reviews?reviewItemId=${encodeURIComponent(item.id)}`)}>{isApproval ? 'Review' : 'Open'}</Button></td>
      </tr>; })}
    </tbody></table></div>}
  </>;
}

function ReconciliationPanel({ data, window }: { data: IamOperationsOverview['reconciliation']; window: OverviewWindow }) {
  if (data.status !== 'AVAILABLE' || !data.summary) return <SectionUnavailable status={data.status} noun="reconciliation health projection" />;
  return <div className="iam-reconciliation"><div className="iam-recon-summary">
    <SummaryStat label="Running now" value={data.summary.runningNow} tone="info" /><SummaryStat label={`Failed · ${windowLabel(window)}`} value={data.summary.failedInWindow} tone="danger" />
    <SummaryStat label={`Complete + succeeded · ${windowLabel(window)}`} value={data.summary.completeSucceededInWindow} tone="success" />
    <div className="iam-last-complete"><small>Last effectively COMPLETE</small><strong>{data.summary.lastCompleteAt ? dateTime(data.summary.lastCompleteAt) : 'No completed run'}</strong></div>
  </div><div className="iam-recon-list"><span className="iam-summary-kicker">Recent runs</span>
    {data.recentRuns.length === 0 ? <div className="iam-empty-inline">No reconciliation runs in this period.</div> : data.recentRuns.map((run) => <button type="button" className="iam-recon-row" key={run.id} onClick={() => navigateConsole(`/integrations?operations=reconciliation&runId=${encodeURIComponent(run.id)}`)}>
      <span><strong>{shortId(run.connectorBindingId)}</strong><small>{run.completedAt ? dateTime(run.completedAt) : `Started ${dateTime(run.startedAt)}`}</small></span>
      <span className="iam-recon-state"><StatusBadge value={run.state} kind={run.state === 'SUCCEEDED' ? 'success' : run.state === 'FAILED' ? 'danger' : run.state === 'RUNNING' ? 'info' : 'neutral'} />
        <StatusBadge value={run.effectiveCompleteness} kind={run.effectiveCompleteness === 'COMPLETE' ? 'success' : run.effectiveCompleteness === 'PARTIAL' ? 'warning' : 'neutral'} /></span>
    </button>)}
  </div></div>;
}

function GovernanceActivity({ data, window }: { data: IamOperationsOverview['myGovernanceActivity']; window: OverviewWindow }) {
  if (data.status !== 'AVAILABLE') return <SectionUnavailable status={data.status} noun="governance decision activity" />;
  const items = [['Approvals', data.approvals ?? 0, 'success'], ['Rejections', data.rejections ?? 0, 'danger'], ['Reviews kept', data.keeps ?? 0, 'info'], ['Reviews revoked', data.revokes ?? 0, 'warning']] as const;
  const total = items.reduce((sum, item) => sum + item[1], 0);
  return <div className="iam-activity"><div className="iam-activity-total"><span>My decisions · {windowLabel(window)}</span><strong>{numberFormat.format(total)}</strong></div>
    <div className="iam-activity-grid">{items.map(([label, value, tone]) => <div className="iam-activity-card" key={label}><span className={`iam-summary-dot iam-dot-${tone}`} /><strong>{numberFormat.format(value)}</strong><small>{label}</small></div>)}</div>
    <p className="iam-context-note">Actor-scoped Governance evidence only. These counters are not a tenant risk score.</p></div>;
}

function SecurityEvidence({ data, window }: { data: IamOperationsOverview['securityEvidence']; window: OverviewWindow }) {
  if (data.status !== 'AVAILABLE') return <SectionUnavailable status={data.status} noun="Audit evidence summary" />;
  return <><div className="iam-signal-strip"><div><span>Denied · {windowLabel(window)}</span><strong>{numberFormat.format(data.deniedCount ?? 0)}</strong></div><div><span>Failure · {windowLabel(window)}</span><strong>{numberFormat.format(data.failureCount ?? 0)}</strong></div></div>
    {data.recent.length === 0 ? <div className="iam-empty-state"><strong>No denied or failed Audit records</strong><span>No matching evidence occurred in this period.</span></div> :
      <div className="iam-table-scroll"><table className="iam-table iam-evidence-table"><caption className="sr-only">Recent denied and failed Audit evidence</caption><thead><tr><th>Time</th><th>Outcome</th><th>Action</th><th>Resource</th></tr></thead><tbody>
        {data.recent.map((item) => <tr key={item.id}><td>{dateTime(item.occurredAt)}</td><td><StatusBadge value={item.outcome} kind="danger" /></td><td>{item.actionType}</td><td><strong>{item.resourceType}</strong><small>{shortId(item.resourceId)}</small></td></tr>)}
      </tbody></table></div>}
  </>;
}

function EnvironmentMetric({ label, metric, icon, detail }: { label: string; metric: OverviewMetric; icon: string; detail: string }) {
  return <div className="iam-environment-card"><span className="iam-environment-icon" aria-hidden="true">{icon}</span><span>
    <strong>{metric.status === 'AVAILABLE' && metric.value !== null ? compactFormat.format(metric.value) : statusText(metric.status)}</strong><small>{label}</small><em>{detail}</em>
  </span></div>;
}

export function OverviewPage({ system, authority }: OverviewPageProps) {
  const [window, setWindow] = useState<OverviewWindow>('7d');
  const [workType, setWorkType] = useState<OverviewWorkType>('ALL');
  const [workSort, setWorkSort] = useState<OverviewWorkSort>('OLDEST');
  const [refreshKey, setRefreshKey] = useState(0);
  const [state, setState] = useState<OverviewLoadState>({ status: 'loading' });
  const [announcement, setAnnouncement] = useState('');
  const refreshRef = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setState((current) => current.status === 'ready' ? current : { status: 'loading' });
    getIamOperationsOverview(window, workType, workSort, controller.signal).then((value) => {
      setState({ status: 'ready', value }); if (refreshKey > 0) setAnnouncement(`IAM Operations refreshed at ${dateTime(value.asOf)}.`);
    }).catch((error: unknown) => {
      if (error instanceof DOMException && error.name === 'AbortError') return;
      setState({ status: 'error', error }); setAnnouncement('IAM Operations could not be refreshed.');
    });
    return () => controller.abort();
  }, [window, workType, workSort, refreshKey]);

  const overview = state.status === 'ready' ? state.value : null;
  const tenant = overview?.tenantId ?? (authority.status === 'ready' ? authority.value.tenantId : 'Resolving tenant…');
  const runtimeStatus = system.status === 'ready' ? system.value.status : null;
  function refresh() { setAnnouncement(''); setRefreshKey((value) => value + 1); requestAnimationFrame(() => refreshRef.current?.focus()); }

  return <Page width="wide"><div className="iam-operations">
    <header className="iam-page-header"><div><span className="iam-eyebrow">Operator home</span><h1>IAM Operations</h1><p>What needs my attention right now, and is IAM operating correctly?</p></div>
      <div className="iam-header-actions"><div className="iam-header-context"><span><strong>Tenant:</strong> {tenant}</span><span><strong>Data as of</strong> {overview ? dateTime(overview.asOf) : 'Loading…'}</span>
        {overview?.partial ? <StatusBadge value="Partial data" kind="warning" /> : null}{runtimeStatus ? <StatusBadge value={runtimeStatus} kind="success" /> : null}</div>
        <div className="iam-header-controls"><label><span className="sr-only">Time window</span><select value={window} onChange={(event) => setWindow(event.target.value as OverviewWindow)}><option value="24h">24 hours</option><option value="7d">7 days</option><option value="30d">30 days</option></select></label>
          <Button ref={refreshRef} variant="contained" onClick={refresh} disabled={state.status === 'loading'}>Refresh ↻</Button></div></div>
    </header><div className="sr-only" aria-live="polite">{announcement}</div>

    {state.status === 'loading' && !overview ? <div className="iam-skeleton-stack" aria-label="Loading IAM Operations"><div className="iam-skeleton iam-skeleton-cards" /><div className="iam-skeleton iam-skeleton-large" /><div className="iam-skeleton iam-skeleton-large" /></div> : null}
    {state.status === 'error' && !overview ? <StatePanel severity="error" title="IAM Operations unavailable">The bounded overview projection could not be loaded. No counts are inferred from browser state.<Box sx={{ mt: 2 }}><Button variant="outlined" onClick={refresh}>Try again</Button></Box></StatePanel> : null}

    {overview ? <>
      {overview.partial ? <div className="iam-partial-banner" role="status"><strong>Some sections are unavailable.</strong><span>Authorized, successfully evaluated sections remain usable; unavailable data is never shown as zero.</span></div> : null}
      <section aria-labelledby="attention-heading"><h2 className="sr-only" id="attention-heading">Attention</h2><div className="iam-attention-grid">
        <AttentionCard tone="danger" icon="✓" label="Pending approvals" metric={overview.attention.pendingApprovals} qualifier="Assigned to you · Current" onOpen={() => navigateConsole('/requests?view=approvals&status=pending&sort=oldest')} />
        <AttentionCard tone="warning" icon="◷" label="Pending reviews" metric={overview.attention.pendingReviews} qualifier="Assigned to you · Current" onOpen={() => navigateConsole('/reviews?status=pending&sort=oldest')} />
        <AttentionCard tone="danger" icon="!" label="Final provisioning failures" metric={overview.attention.finalProvisioningFailures} qualifier={`Last ${windowLabel(window)}`} onOpen={() => navigateConsole(`/integrations?operations=attempts&outcome=FAILED_FINAL&from=${encodeURIComponent(overview.window.from)}&until=${encodeURIComponent(overview.window.until)}`)} />
        <AttentionCard tone="warning" icon="▲" label="Failed reconciliations" metric={overview.attention.failedReconciliations} qualifier={`Last ${windowLabel(window)}`} onOpen={() => navigateConsole(`/integrations?operations=reconciliation&state=FAILED&from=${encodeURIComponent(overview.window.from)}&until=${encodeURIComponent(overview.window.until)}`)} />
      </div></section>

      <section className="iam-panel iam-work-panel" aria-labelledby="work-heading"><div className="iam-panel-header"><div><h2 id="work-heading">My Work</h2><p>Assigned Governance decisions, ordered for action.</p></div><Button variant="text" onClick={() => navigateConsole('/requests')}>View all →</Button></div>
        <WorkTable overview={overview} onType={setWorkType} onSort={setWorkSort} /></section>

      <section aria-labelledby="health-heading"><div className="iam-section-heading"><div><span className="iam-eyebrow">Operational health</span><h2 id="health-heading">Technical realization</h2></div><span>Window: {windowLabel(window)}</span></div>
        <div className="iam-health-grid"><article className="iam-panel"><div className="iam-panel-header"><div><h3>Provisioning Health</h3><p>Immutable provider execution attempts; not Governance authorization.</p></div><Button variant="text" onClick={() => navigateConsole('/integrations?operations=attempts')}>View provisioning →</Button></div><ProvisioningChart data={overview.provisioning} /></article>
          <article className="iam-panel"><div className="iam-panel-header"><div><h3>Reconciliation Health</h3><p>Run outcome and effective completeness remain separate.</p></div><Button variant="text" onClick={() => navigateConsole('/integrations?operations=reconciliation')}>View reconciliation →</Button></div><ReconciliationPanel data={overview.reconciliation} window={window} /></article></div>
      </section>

      <section aria-labelledby="activity-heading"><div className="iam-section-heading"><div><span className="iam-eyebrow">Activity & evidence</span><h2 id="activity-heading">Governance and security context</h2></div></div>
        <div className="iam-activity-grid-layout"><article className="iam-panel"><div className="iam-panel-header"><div><h3>My Governance Activity</h3><p>Your immutable approval and review decisions.</p></div></div><GovernanceActivity data={overview.myGovernanceActivity} window={window} /></article>
          <article className="iam-panel"><div className="iam-panel-header"><div><h3>Security & Administration Evidence</h3><p>Audit evidence signals, not current authorization or incident state.</p></div><Button variant="text" onClick={() => navigateConsole('/audit')}>View audit →</Button></div><SecurityEvidence data={overview.securityEvidence} window={window} /></article></div>
      </section>

      <section className="iam-panel" aria-labelledby="environment-heading"><div className="iam-panel-header"><div><h2 id="environment-heading">Environment Snapshot</h2><p>Current authorized inventory counts. Each metric is evaluated independently.</p></div></div>
        <div className="iam-environment-grid"><EnvironmentMetric label="Identities" metric={overview.environment.activeIdentities} icon="♟" detail="Active" /><EnvironmentMetric label="Uncorrelated principals" metric={overview.environment.uncorrelatedPrincipals} icon="⌁" detail="Needs attention" />
          <EnvironmentMetric label="Access assignments" metric={overview.environment.activeAccessAssignments} icon="◉" detail="Active" /><EnvironmentMetric label="Applications" metric={overview.environment.applications} icon="▦" detail="Active" /><EnvironmentMetric label="Connectors" metric={overview.environment.activeConnectors} icon="◆" detail="Active" /></div>
      </section>
    </> : null}
  </div></Page>;
}
