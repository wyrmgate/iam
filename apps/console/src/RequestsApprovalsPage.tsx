import { type FormEvent, useEffect, useMemo, useState } from 'react';
import { ApiError } from './api';
import {
  createAccessRequest,
  decideApproval,
  getAccessRequest,
  getApproval,
  getRequestItem,
  getRequestsAndApprovals,
  submitAccessRequest,
  type AccessRequest,
  type Approval,
  type ApprovalSort,
  type ApprovalWork,
  type RequestItem,
  type RequestSummary,
  type RequestsAndApprovals,
  type RequestsWindow,
} from './requestsApprovalsApi';
import {
  Box,
  Button,
  ConfirmDialog,
  Page,
  SidePanel,
  StatePanel,
  TextInput,
} from './ui';
import './requests-approvals.css';

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; value: RequestsAndApprovals }
  | { status: 'error'; error: unknown };

type View = 'approvals' | 'requests';

type ApprovalSelection = {
  resource: Approval;
  etag: string;
  item: RequestItem | null;
  request: AccessRequest | null;
};

type RequestSelection = {
  resource: AccessRequest;
  etag: string;
};

const numberFormat = new Intl.NumberFormat();

function queryState() {
  const params = new URLSearchParams(window.location.search);
  const view = params.get('view') === 'requests' ? 'requests' : 'approvals';
  const sort = params.get('sort') === 'newest' ? 'NEWEST' : 'OLDEST';
  return {
    view: view as View,
    sort: sort as ApprovalSort,
    approvalCaseId: params.get('approvalCaseId'),
    requestId: params.get('requestId'),
  };
}

function updateQuery(values: Record<string, string | null>) {
  const params = new URLSearchParams(window.location.search);
  for (const [key, value] of Object.entries(values)) {
    if (value === null) params.delete(key);
    else params.set(key, value);
  }
  const query = params.toString();
  window.history.replaceState({}, '', `${window.location.pathname}${query ? `?${query}` : ''}`);
}

function shortId(value?: string | null) {
  if (!value) return '—';
  return value.length <= 12 ? value : `…${value.slice(-10)}`;
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

function windowLabel(value: RequestsWindow) {
  if (value === '24h') return '24 hours';
  if (value === '30d') return '30 days';
  return '7 days';
}

function statusLabel(value: RequestSummary['presentationStatus']) {
  if (value === 'DRAFT') return 'Draft';
  if (value === 'IN_PROGRESS') return 'In progress';
  if (value === 'COMPLETED_APPLIED') return 'Applied';
  if (value === 'COMPLETED_MIXED') return 'Completed · mixed outcomes';
  return 'Completed · no access applied';
}

function statusTone(value: RequestSummary['presentationStatus']) {
  if (value === 'DRAFT') return 'neutral';
  if (value === 'IN_PROGRESS') return 'info';
  if (value === 'COMPLETED_APPLIED') return 'success';
  if (value === 'COMPLETED_MIXED') return 'warning';
  return 'danger';
}

function itemTone(value: string) {
  if (value === 'APPLIED' || value === 'AUTHORIZED') return 'success';
  if (value === 'DENIED' || value === 'REJECTED' || value === 'EXPIRED' || value === 'CANCELLED') return 'danger';
  if (value === 'EVALUATING' || value === 'PENDING_APPROVAL') return 'warning';
  if (value === 'SUBMITTED') return 'info';
  return 'neutral';
}

function ErrorPanel({ error }: { error: unknown }) {
  let title = 'Unable to load Requests & Approvals';
  let detail = error instanceof Error ? error.message : 'The request could not be completed.';
  let state: 'error' | 'forbidden' | 'stale' | 'conflict' = 'error';
  let correlation: string | null = null;
  if (error instanceof ApiError) {
    correlation = error.correlationId;
    if (error.status === 403) {
      title = 'Not authorized';
      detail = 'The server denied this Governance operation. Server authorization is final.';
      state = 'forbidden';
    } else if (error.status === 412) {
      title = 'State changed';
      detail = 'This resource changed after it was opened. Refresh the current resource before deciding.';
      state = 'stale';
    } else if (error.status === 409) {
      title = 'Governance conflict';
      state = 'conflict';
    } else if (error.status === 503) {
      title = 'Governance evaluation unavailable';
      detail = 'Required Governance data or evaluation is temporarily unavailable. No privilege increase is inferred.';
    }
  }
  return <StatePanel state={state} title={title}>{detail}{correlation ? ` Correlation: ${correlation}` : ''}</StatePanel>;
}

function Badge({ children, tone = 'neutral' }: { children: string; tone?: string }) {
  return <span className={`ra-badge ra-badge-${tone}`}>{children}</span>;
}

function MetricCard({ icon, value, label, qualifier, tone, onClick }: {
  icon: string;
  value: number;
  label: string;
  qualifier: string;
  tone: 'danger' | 'warning' | 'info' | 'success';
  onClick: () => void;
}) {
  return <button type="button" className={`ra-metric ra-metric-${tone}`} onClick={onClick}>
    <span className="ra-metric-icon" aria-hidden="true">{icon}</span>
    <span className="ra-metric-copy"><strong>{numberFormat.format(value)}</strong><b>{label}</b><small>{qualifier}</small></span>
    <span className="ra-arrow" aria-hidden="true">›</span>
  </button>;
}

function ApprovalTable({ items, asOf, onOpen }: { items: ApprovalWork[]; asOf: string; onOpen: (id: string) => void }) {
  if (items.length === 0) {
    return <div className="ra-empty"><strong>No approval work assigned</strong><span>There are no pending current-stage approvals assigned to you.</span></div>;
  }
  return <div className="ra-table-scroll"><table className="ra-table"><caption className="sr-only">Approval work assigned to the current governed actor</caption>
    <thead><tr><th>Age</th><th>Approval</th><th>Subject</th><th>Requested access</th><th>Stage</th><th><span className="sr-only">Action</span></th></tr></thead>
    <tbody>{items.map((item) => <tr key={item.approvalCaseId}>
      <td><Badge tone={relativeAge(item.createdAt, asOf).endsWith('d') ? 'warning' : 'neutral'}>{relativeAge(item.createdAt, asOf)}</Badge></td>
      <td><strong>{shortId(item.approvalCaseId)}</strong><small>{dateTime(item.createdAt)}</small></td>
      <td><strong>{item.subjectKind.replaceAll('_', ' ')}</strong><small>{shortId(item.subjectId)}</small></td>
      <td>{item.targetKind && item.targetId ? <><strong>{item.targetKind}</strong><small>{shortId(item.targetId)}</small></> : <span className="ra-muted">See decision context</span>}</td>
      <td><strong>Stage {item.currentStageOrdinal + 1}</strong><small>Current immutable plan</small></td>
      <td className="ra-action-cell"><Button size="small" variant="contained" onClick={() => onOpen(item.approvalCaseId)}>Review</Button></td>
    </tr>)}</tbody>
  </table></div>;
}

function RequestsTable({ items, onOpen }: { items: RequestSummary[]; onOpen: (id: string) => void }) {
  if (items.length === 0) {
    return <div className="ra-empty"><strong>No self-service requests</strong><span>Create a request to begin governed access evaluation.</span></div>;
  }
  return <div className="ra-table-scroll"><table className="ra-table"><caption className="sr-only">Recent self-service access requests</caption>
    <thead><tr><th>Request</th><th>Target</th><th>Items</th><th>Governance / Access state</th><th>Updated</th><th><span className="sr-only">Action</span></th></tr></thead>
    <tbody>{items.map((item) => <tr key={item.requestId}>
      <td><strong>{shortId(item.requestId)}</strong><small>Created {dateTime(item.createdAt)}</small></td>
      <td>{item.singleTargetKind && item.singleTargetId ? <><strong>{item.singleTargetKind}</strong><small>{shortId(item.singleTargetId)}</small></> : <><strong>{item.itemCount} requested items</strong><small>Open for item detail</small></>}</td>
      <td><strong>{item.itemCount}</strong><small>{item.pendingApprovalItems ? `${item.pendingApprovalItems} pending approval` : item.evaluatingItems ? `${item.evaluatingItems} evaluating` : `${item.appliedItems} applied`}</small></td>
      <td><Badge tone={statusTone(item.presentationStatus)}>{statusLabel(item.presentationStatus)}</Badge></td>
      <td>{dateTime(item.updatedAt)}</td>
      <td className="ra-action-cell"><Button size="small" variant="outlined" onClick={() => onOpen(item.requestId)}>Open</Button></td>
    </tr>)}</tbody>
  </table></div>;
}

function StateDistribution({ data }: { data: RequestsAndApprovals }) {
  const values = [
    ['Draft', data.requestStatusTotals.draft, 'neutral'],
    ['In progress', data.requestStatusTotals.inProgress, 'info'],
    ['All applied', data.requestStatusTotals.completedApplied, 'success'],
    ['Mixed outcomes', data.requestStatusTotals.completedMixed, 'warning'],
    ['No access applied', data.requestStatusTotals.completedNoAccess, 'danger'],
  ] as const;
  const total = Math.max(1, values.reduce((sum, [, value]) => sum + value, 0));
  return <section className="ra-panel ra-status-panel" aria-labelledby="request-state-heading">
    <div className="ra-panel-head"><div><h2 id="request-state-heading">My Request Status</h2><p>Current derived presentation status from authoritative RequestItems.</p></div></div>
    <div className="ra-status-bar" role="img" aria-label={values.map(([label, value]) => `${label}: ${value}`).join(', ')}>
      {values.map(([label, value, tone]) => value > 0 ? <span key={label} className={`ra-status-segment ra-segment-${tone}`} style={{ flex: value / total }} /> : null)}
    </div>
    <div className="ra-status-grid">{values.map(([label, value, tone]) => <div key={label}><i className={`ra-dot ra-dot-${tone}`} /><span><strong>{numberFormat.format(value)}</strong><small>{label}</small></span></div>)}</div>
    <p className="ra-note">This is a read-only presentation projection. `AUTHORIZED` remains Governance authorization; `APPLIED` means Access accepted authoritative intent. Provider fulfillment is separate.</p>
  </section>;
}

function ApprovalPanel({ selection, reason, setReason, busy, error, onClose, onApprove, onReject }: {
  selection: ApprovalSelection | null;
  reason: string;
  setReason: (value: string) => void;
  busy: boolean;
  error: unknown;
  onClose: () => void;
  onApprove: () => void;
  onReject: () => void;
}) {
  const approval = selection?.resource;
  const currentStage = approval?.stages.find((stage) => stage.ordinal === approval.currentStageOrdinal);
  return <SidePanel open={Boolean(selection)} onClose={onClose} busy={busy} title="Approval decision" description="Review immutable Governance evidence before deciding the current stage.">
    {approval ? <div className="ra-side-body">
      {error ? <ErrorPanel error={error} /> : null}
      <div className="ra-detail-hero"><div><small>Approval case</small><strong>{approval.id}</strong></div><Badge tone={approval.state === 'PENDING' ? 'warning' : 'success'}>{approval.state}</Badge></div>
      <div className="ra-detail-grid">
        <div><small>Subject</small><strong>{approval.subjectKind.replaceAll('_', ' ')}</strong><span>{shortId(approval.subjectId)}</span></div>
        <div><small>Current stage</small><strong>{approval.currentStageOrdinal + 1}</strong><span>{currentStage?.decisionMode ?? '—'}</span></div>
        <div><small>Initiator</small><strong>{shortId(approval.initiatorIdentityId)}</strong></div>
        <div><small>Revision</small><strong>{approval.revision}</strong></div>
      </div>
      {selection?.item ? <section className="ra-side-section"><h3>Requested access context</h3>
        <div className="ra-detail-grid"><div><small>Target kind</small><strong>{selection.item.targetKind}</strong></div><div><small>Target ID</small><strong>{shortId(selection.item.roleId ?? selection.item.entitlementId)}</strong></div><div><small>RequestItem state</small><Badge tone={itemTone(selection.item.state)}>{selection.item.state}</Badge></div><div><small>AccessRequest</small><strong>{shortId(selection.item.accessRequestId)}</strong></div></div>
        <p className="ra-note">Approval records Governance decision evidence only. It does not directly mutate Access or prove provider fulfillment.</p>
      </section> : null}
      <section className="ra-side-section"><h3>Immutable plan evidence</h3>
        <div className="ra-stage-list">{approval.stages.map((stage) => <div className={stage.ordinal === approval.currentStageOrdinal ? 'current' : ''} key={stage.id}>
          <span><strong>Stage {stage.ordinal + 1}</strong><small>{stage.decisionMode} · {stage.approvers.length} resolved approver{stage.approvers.length === 1 ? '' : 's'}</small></span>
          <Badge tone={stage.decisions.length ? 'success' : stage.ordinal === approval.currentStageOrdinal ? 'warning' : 'neutral'}>{stage.decisions.length ? `${stage.decisions.length} decided` : stage.ordinal === approval.currentStageOrdinal ? 'Current' : 'Pending'}</Badge>
        </div>)}</div>
      </section>
      {approval.state === 'PENDING' ? <section className="ra-side-section"><h3>Decision</h3>
        <TextInput label="Decision reason (optional)" multiline minRows={3} value={reason} onChange={(event) => setReason(event.target.value)} helperText="Stored as immutable Governance decision evidence. Do not paste secrets or private credential material." />
        <div className="ra-decision-actions"><Button variant="outlined" disabled={busy} onClick={onReject}>Reject current stage</Button><Button variant="contained" disabled={busy} onClick={onApprove}>{busy ? 'Working…' : 'Approve current stage'}</Button></div>
      </section> : <StatePanel title="Decision complete">This ApprovalCase is no longer pending.</StatePanel>}
    </div> : null}
  </SidePanel>;
}

function RequestPanel({ selection, busy, error, onClose, onSubmit }: {
  selection: RequestSelection | null;
  busy: boolean;
  error: unknown;
  onClose: () => void;
  onSubmit: () => void;
}) {
  const request = selection?.resource;
  return <SidePanel open={Boolean(selection)} onClose={onClose} busy={busy} title="Access request" description="Governance request state and authoritative RequestItem progression.">
    {request ? <div className="ra-side-body">
      {error ? <ErrorPanel error={error} /> : null}
      <div className="ra-detail-hero"><div><small>AccessRequest</small><strong>{request.id}</strong></div><Badge tone={request.state === 'DRAFT' ? 'neutral' : 'info'}>{request.state}</Badge></div>
      <div className="ra-detail-grid"><div><small>Created</small><strong>{dateTime(request.createdAt)}</strong></div><div><small>Submitted</small><strong>{dateTime(request.submittedAt)}</strong></div><div><small>Revision</small><strong>{request.revision}</strong></div><div><small>Items</small><strong>{request.items.length}</strong></div></div>
      <section className="ra-side-section"><h3>Request items</h3><div className="ra-item-list">{request.items.map((item) => <div key={item.id}>
        <span><strong>{item.targetKind} · {shortId(item.roleId ?? item.entitlementId)}</strong><small>{item.principalConstraintKind}{item.specificPrincipalId ? ` · Principal ${shortId(item.specificPrincipalId)}` : ''}</small></span>
        <Badge tone={itemTone(item.state)}>{item.state}</Badge>
      </div>)}</div></section>
      <StatePanel title="State boundary">Governance `AUTHORIZED` is not provider success. `APPLIED` means Access accepted the authoritative AccessAssignment; technical fulfillment remains downstream.</StatePanel>
      {request.state === 'DRAFT' ? <div className="ra-decision-actions"><Button variant="contained" disabled={busy} onClick={onSubmit}>{busy ? 'Submitting…' : 'Submit for Governance evaluation'}</Button></div> : null}
    </div> : null}
  </SidePanel>;
}

export function RequestsApprovalsPage() {
  const initial = useMemo(queryState, []);
  const [windowPreset, setWindowPreset] = useState<RequestsWindow>('7d');
  const [sort, setSort] = useState<ApprovalSort>(initial.sort);
  const [view, setView] = useState<View>(initial.view);
  const [load, setLoad] = useState<LoadState>({ status: 'loading' });
  const [refresh, setRefresh] = useState(0);
  const [approvalSelection, setApprovalSelection] = useState<ApprovalSelection | null>(null);
  const [requestSelection, setRequestSelection] = useState<RequestSelection | null>(null);
  const [selectionError, setSelectionError] = useState<unknown>(null);
  const [decisionReason, setDecisionReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [rejectConfirm, setRejectConfirm] = useState(false);
  const [newOpen, setNewOpen] = useState(false);
  const [targetKind, setTargetKind] = useState<'ROLE' | 'ENTITLEMENT'>('ROLE');
  const [targetId, setTargetId] = useState('');
  const [constraint, setConstraint] = useState<'ANY' | 'SPECIFIC'>('ANY');
  const [principalId, setPrincipalId] = useState('');
  const [validFrom, setValidFrom] = useState('');
  const [validUntil, setValidUntil] = useState('');
  const [createError, setCreateError] = useState<unknown>(null);
  const [liveMessage, setLiveMessage] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    setLoad({ status: 'loading' });
    getRequestsAndApprovals(windowPreset, sort, controller.signal)
      .then((value) => {
        setLoad({ status: 'ready', value });
        setLiveMessage('Requests & Approvals refreshed.');
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setLoad({ status: 'error', error });
      });
    return () => controller.abort();
  }, [windowPreset, sort, refresh]);

  useEffect(() => {
    if (load.status !== 'ready') return;
    if (initial.approvalCaseId && !approvalSelection) void openApproval(initial.approvalCaseId);
    if (initial.requestId && !requestSelection) void openRequest(initial.requestId);
    // Initial deep link is consumed once; subsequent selection is driven by explicit actions.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [load.status]);

  function changeView(next: View) {
    setView(next);
    updateQuery({ view: next, approvalCaseId: null, requestId: null });
  }

  async function openApproval(id: string) {
    setSelectionError(null);
    try {
      const approval = await getApproval(id);
      let item: RequestItem | null = null;
      let request: AccessRequest | null = null;
      if (approval.data.subjectKind === 'ACCESS_REQUEST_ITEM') {
        const itemResult = await getRequestItem(approval.data.subjectId);
        item = itemResult.data;
        const requestResult = await getAccessRequest(item.accessRequestId);
        request = requestResult.data;
      }
      setApprovalSelection({ resource: approval.data, etag: approval.etag ?? '', item, request });
      setDecisionReason('');
      setView('approvals');
      updateQuery({ view: 'approvals', approvalCaseId: id, requestId: null });
    } catch (error) {
      setSelectionError(error);
    }
  }

  async function openRequest(id: string) {
    setSelectionError(null);
    try {
      const result = await getAccessRequest(id);
      setRequestSelection({ resource: result.data, etag: result.etag ?? '' });
      setView('requests');
      updateQuery({ view: 'requests', requestId: id, approvalCaseId: null });
    } catch (error) {
      setSelectionError(error);
    }
  }

  async function performDecision(decision: 'approve' | 'reject') {
    if (!approvalSelection?.etag) return;
    setBusy(true);
    setSelectionError(null);
    try {
      const result = await decideApproval(approvalSelection.resource.id, decision, approvalSelection.etag, decisionReason);
      setApprovalSelection((current) => current ? { ...current, resource: result.data, etag: result.etag ?? current.etag } : current);
      setDecisionReason('');
      setRejectConfirm(false);
      setRefresh((value) => value + 1);
      setLiveMessage(`Approval ${decision === 'approve' ? 'approved' : 'rejected'}.`);
    } catch (error) {
      setSelectionError(error);
    } finally {
      setBusy(false);
    }
  }

  async function submitSelectedRequest() {
    if (!requestSelection?.etag) return;
    setBusy(true);
    setSelectionError(null);
    try {
      const result = await submitAccessRequest(requestSelection.resource.id, requestSelection.etag);
      setRequestSelection({ resource: result.data, etag: result.etag ?? requestSelection.etag });
      setRefresh((value) => value + 1);
      setLiveMessage('Access request submitted for Governance evaluation.');
    } catch (error) {
      setSelectionError(error);
    } finally {
      setBusy(false);
    }
  }

  async function createRequest(event: FormEvent) {
    event.preventDefault();
    if (load.status !== 'ready' || !targetId.trim()) return;
    setBusy(true);
    setCreateError(null);
    try {
      const result = await createAccessRequest({
        beneficiaryIdentityId: load.value.actorIdentityId,
        items: [{
          targetKind,
          targetId: targetId.trim(),
          principalConstraintKind: constraint,
          specificPrincipalId: constraint === 'SPECIFIC' ? principalId.trim() || null : null,
          validFrom: validFrom ? new Date(validFrom).toISOString() : null,
          validUntil: validUntil ? new Date(validUntil).toISOString() : null,
        }],
      });
      setNewOpen(false);
      setTargetId('');
      setPrincipalId('');
      setValidFrom('');
      setValidUntil('');
      setRefresh((value) => value + 1);
      await openRequest(result.data.id);
      setLiveMessage('Draft access request created.');
    } catch (error) {
      setCreateError(error);
    } finally {
      setBusy(false);
    }
  }

  const ready = load.status === 'ready' ? load.value : null;

  return <Page width="wide"><div className="requests-approvals">
    <div className="ra-header">
      <div><span className="ra-eyebrow">Governance · My Work</span><h1>Requests &amp; Approvals</h1><p>Request access for yourself and manage approval work assigned to you.</p></div>
      <div className="ra-header-actions">
        <div className="ra-header-context">{ready ? <><span><strong>Tenant:</strong> {shortId(ready.tenantId)}</span><span>Data as of {dateTime(ready.asOf)}</span></> : <span>Loading governed context…</span>}</div>
        <div className="ra-header-controls"><select aria-label="Activity time window" value={windowPreset} onChange={(event) => setWindowPreset(event.target.value as RequestsWindow)}><option value="24h">24 hours</option><option value="7d">7 days</option><option value="30d">30 days</option></select><Button variant="contained" onClick={() => setRefresh((value) => value + 1)}>Refresh</Button></div>
      </div>
    </div>
    <div className="sr-only" aria-live="polite">{liveMessage}</div>

    {load.status === 'loading' ? <div className="ra-skeleton-stack"><div className="ra-skeleton ra-skeleton-metrics" /><div className="ra-skeleton ra-skeleton-large" /></div> : null}
    {load.status === 'error' ? <ErrorPanel error={load.error} /> : null}

    {ready ? <>
      <div className="ra-metric-grid">
        <MetricCard icon="!" value={ready.attention.pendingApprovals} label="My pending approvals" qualifier="Assigned to you" tone="danger" onClick={() => changeView('approvals')} />
        <MetricCard icon="D" value={ready.attention.draftRequests} label="Draft requests" qualifier="Not submitted" tone="info" onClick={() => changeView('requests')} />
        <MetricCard icon="…" value={ready.attention.inFlightRequestItems} label="In-flight request items" qualifier="Evaluation, approval, or Access application" tone="warning" onClick={() => changeView('requests')} />
        <MetricCard icon="✓" value={ready.attention.appliedRequestItems} label="Applied request items" qualifier={`Last ${windowLabel(windowPreset)}`} tone="success" onClick={() => changeView('requests')} />
      </div>

      <section className="ra-panel ra-workspace">
        <div className="ra-workspace-tabs"><div className="ra-tabs" role="tablist" aria-label="Requests and approval work">
          <button type="button" role="tab" aria-selected={view === 'approvals'} className={view === 'approvals' ? 'active' : ''} onClick={() => changeView('approvals')}>My Approval Work <span>{ready.attention.pendingApprovals}</span></button>
          <button type="button" role="tab" aria-selected={view === 'requests'} className={view === 'requests' ? 'active' : ''} onClick={() => changeView('requests')}>My Requests <span>{ready.requests.length}</span></button>
        </div><Button variant="contained" onClick={() => setNewOpen(true)}>New Request</Button></div>

        {view === 'approvals' ? <>
          <div className="ra-panel-head"><div><h2>My Approval Work</h2><p>Pending current-stage decisions assigned to your governed Identity.</p></div><label className="ra-sort">Sort <select value={sort} onChange={(event) => { const next = event.target.value as ApprovalSort; setSort(next); updateQuery({ sort: next === 'NEWEST' ? 'newest' : 'oldest' }); }}><option value="OLDEST">Oldest first</option><option value="NEWEST">Newest first</option></select></label></div>
          <ApprovalTable items={ready.approvals} asOf={ready.asOf} onOpen={(id) => void openApproval(id)} />
        </> : <>
          <div className="ra-panel-head"><div><h2>My Requests</h2><p>Recent self-service AccessRequests for the current governed Identity.</p></div><span className="ra-bounded-label">Latest {ready.requests.length} · bounded</span></div>
          <RequestsTable items={ready.requests} onOpen={(id) => void openRequest(id)} />
        </>}
      </section>

      <div className="ra-context-grid">
        <StateDistribution data={ready} />
        <section className="ra-panel"><div className="ra-panel-head"><div><h2>Request Presets</h2><p>Local form shortcuts; not persisted IAM template resources.</p></div></div>
          <div className="ra-preset-list">
            <button type="button" onClick={() => { setTargetKind('ROLE'); setConstraint('ANY'); setNewOpen(true); }}><span className="ra-preset-icon">R</span><span><strong>Role access</strong><small>Request one governed Role using ANY-principal realization.</small></span><b>›</b></button>
            <button type="button" onClick={() => { setTargetKind('ENTITLEMENT'); setConstraint('ANY'); setNewOpen(true); }}><span className="ra-preset-icon">E</span><span><strong>Entitlement access</strong><small>Request one governed Entitlement directly.</small></span><b>›</b></button>
            <button type="button" onClick={() => { setConstraint('SPECIFIC'); setNewOpen(true); }}><span className="ra-preset-icon">P</span><span><strong>Specific Principal</strong><small>Bind the typed request to one governed Principal ID.</small></span><b>›</b></button>
          </div>
        </section>
      </div>
    </> : null}

    {selectionError && !approvalSelection && !requestSelection ? <ErrorPanel error={selectionError} /> : null}

    <ApprovalPanel selection={approvalSelection} reason={decisionReason} setReason={setDecisionReason} busy={busy} error={selectionError} onClose={() => { setApprovalSelection(null); setSelectionError(null); updateQuery({ approvalCaseId: null }); }} onApprove={() => void performDecision('approve')} onReject={() => setRejectConfirm(true)} />
    <RequestPanel selection={requestSelection} busy={busy} error={selectionError} onClose={() => { setRequestSelection(null); setSelectionError(null); updateQuery({ requestId: null }); }} onSubmit={() => void submitSelectedRequest()} />

    <ConfirmDialog open={rejectConfirm} title="Reject current approval stage?" confirmLabel="Reject current stage" destructive busy={busy} onCancel={() => setRejectConfirm(false)} onConfirm={() => void performDecision('reject')}>Rejection records immutable Governance decision evidence and terminates this approval path. It does not directly mutate Access.</ConfirmDialog>

    <SidePanel open={newOpen} onClose={() => !busy && setNewOpen(false)} busy={busy} title="New access request" description="Create a typed self-service DRAFT request. Submission for evaluation is a separate step.">
      <form className="ra-side-body" onSubmit={(event) => void createRequest(event)}>
        {createError ? <ErrorPanel error={createError} /> : null}
        <StatePanel title="Self-service boundary">The beneficiary is your server-derived governed Identity. This page does not support arbitrary on-behalf requests.</StatePanel>
        <label className="ra-field"><span>Requested target kind</span><select value={targetKind} onChange={(event) => setTargetKind(event.target.value as 'ROLE' | 'ENTITLEMENT')}><option value="ROLE">Role</option><option value="ENTITLEMENT">Entitlement</option></select><small>Choose exactly one typed Catalog target kind.</small></label>
        <TextInput label="Target ID" required value={targetId} onChange={(event) => setTargetId(event.target.value)} helperText="Stable governed Role or Entitlement identifier." />
        <label className="ra-field"><span>Principal constraint</span><select value={constraint} onChange={(event) => setConstraint(event.target.value as 'ANY' | 'SPECIFIC')}><option value="ANY">ANY</option><option value="SPECIFIC">SPECIFIC</option></select><small>SPECIFIC additionally requires one governed Principal ID.</small></label>
        {constraint === 'SPECIFIC' ? <TextInput label="Specific Principal ID" required value={principalId} onChange={(event) => setPrincipalId(event.target.value)} /> : null}
        <div className="ra-form-grid"><TextInput label="Valid from (optional)" type="datetime-local" value={validFrom} onChange={(event) => setValidFrom(event.target.value)} /><TextInput label="Valid until (optional)" type="datetime-local" value={validUntil} onChange={(event) => setValidUntil(event.target.value)} /></div>
        <p className="ra-note">Private credential material, passwords, tokens, and provider secrets do not belong in access-request fields.</p>
        <div className="ra-decision-actions"><Button type="button" variant="outlined" disabled={busy} onClick={() => setNewOpen(false)}>Cancel</Button><Button type="submit" variant="contained" disabled={busy || !targetId.trim() || (constraint === 'SPECIFIC' && !principalId.trim())}>{busy ? 'Creating…' : 'Create draft'}</Button></div>
      </form>
    </SidePanel>
  </div></Page>;
}
